package com.aimall.ai.service;

import com.aimall.ai.context.RedisFallbackManager;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问句语义缓存（两级）
 *
 * <pre>
 * L1 精确缓存：normalize(问句) 的 MD5 → Redis（Redis 宕机时自动降级 Caffeine），不消耗 Embedding；
 * L2 语义缓存：问句 Embedding 存入 pgvector 表 ai_semantic_cache，
 *              新问句按余弦相似度检索最近邻，相似度 ≥ 阈值 且通过安全校验才算命中，命中后回填 L1。
 * </pre>
 *
 * 阈值选取（text-embedding-v3 实测）：
 * 同义问句（“推荐跑鞋” vs “推荐一双跑鞋”）相似度 0.92~0.98；
 * 不同意图（“小米14” vs “小米13”、“iPhone 15” vs “iPhone 15 Pro”、“降噪” vs “不降噪”）为 0.77~0.91。
 * 因此默认阈值 0.92，并叠加两条规则兜底：
 * 1. 型号/数字/英文 token 必须完全一致（防止 14 命中 13、15 命中 15 Pro、1000 命中 2000）；
 * 2. 否定词出现情况必须一致（防止“不降噪”命中“降噪”）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private static final String TABLE = "ai_semantic_cache";
    private static final Pattern KEY_TOKEN = Pattern.compile("[a-z]+|[0-9]+");
    /** 正反疑问句式（如“好不好/值不值/行不行/有没有/是不是”），属于询问而非否定，检测否定词前先剔除 */
    private static final Pattern A_NOT_A_QUESTION = Pattern.compile("([\\u4e00-\\u9fa5])不\\1|能不能|可不可以|好不|行不|值不|有没有|有没|是不是|对不对");
    private static final Pattern NEGATION = Pattern.compile("不|没|别|非|无需|勿|无");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[\\s?？!！。.,，~～]+$");

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final RedisFallbackManager redisFallbackManager;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Value("${ai.semantic-cache.enabled:true}")
    private boolean enabled;

    @Value("${ai.semantic-cache.similarity-threshold:0.92}")
    private double similarityThreshold;

    @Value("${ai.semantic-cache.ttl:6h}")
    private Duration ttl;

    /** pgvector 表是否就绪；建表失败时只使用 L1 精确缓存 */
    private volatile boolean tableReady = false;

    private final AtomicInteger writeCounter = new AtomicInteger();

    /**
     * 命中结果
     *
     * @param payload      缓存的回答 JSON（answer + products）
     * @param matchedQuery 命中的原始问句
     * @param similarity   余弦相似度（精确命中为 1.0）
     * @param level        exact / semantic
     * @param cacheId      命中条目的 ID（用于脏数据驱逐）
     */
    public record Hit(String payload, String matchedQuery, double similarity, String level, String cacheId) {
    }

    /**
     * 查询结果：未命中时携带本次已计算的 Embedding，写缓存时复用，避免重复调用 Embedding 接口
     */
    public record Lookup(Hit hit, float[] embedding) {
        public boolean isHit() {
            return hit != null;
        }

        static Lookup miss(float[] embedding) {
            return new Lookup(null, embedding);
        }
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[AI 语义缓存] 已关闭 (ai.semantic-cache.enabled=false)，仅使用 L1 精确缓存");
            return;
        }
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector;");
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS ai_semantic_cache (
                        id         varchar(64) PRIMARY KEY,
                        query      text        NOT NULL,
                        embedding  vector(1024) NOT NULL,
                        payload    text        NOT NULL,
                        created_at timestamptz NOT NULL DEFAULT now()
                    );
                    """);
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS ai_semantic_cache_embedding_idx
                    ON ai_semantic_cache USING hnsw (embedding vector_cosine_ops);
                    """);
            tableReady = true;
            log.info("[AI 语义缓存] pgvector 表 {} 就绪，相似度阈值={}，TTL={}", TABLE, similarityThreshold, ttl);
        } catch (Exception e) {
            log.warn("[AI 语义缓存] 初始化 pgvector 表失败，降级为仅 L1 精确缓存: {}", e.getMessage());
        }
    }

    /**
     * 查询缓存：先 L1 精确匹配，再 L2 语义匹配
     */
    public Lookup lookup(String query) {
        String normalized = normalize(query);
        String exactId = hash(normalized);

        // L1 精确缓存
        try {
            String payload = redisFallbackManager.getQueryCache(exactId);
            if (payload != null && !payload.isBlank()) {
                countHit("exact");
                return new Lookup(new Hit(payload, query, 1.0, "exact", exactId), null);
            }
        } catch (Exception e) {
            log.debug("[AI 语义缓存] L1 读取略过: {}", e.getMessage());
        }

        if (!enabled || !tableReady) {
            return Lookup.miss(null);
        }

        // L2 语义缓存
        float[] embedding;
        try {
            embedding = embeddingModel.embed(normalized);
        } catch (Exception e) {
            log.warn("[AI 语义缓存] 问句向量化失败，跳过 L2: {}", e.getMessage());
            return Lookup.miss(null);
        }

        try {
            String vector = toPgVector(embedding);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, query, payload, 1 - (embedding <=> ?::vector) AS similarity "
                            + "FROM ai_semantic_cache WHERE created_at > ? "
                            + "ORDER BY embedding <=> ?::vector LIMIT 1",
                    vector, Timestamp.from(Instant.now().minus(ttl)), vector);
            if (rows.isEmpty()) {
                return Lookup.miss(embedding);
            }

            Map<String, Object> row = rows.get(0);
            String matchedQuery = String.valueOf(row.get("query"));
            double similarity = ((Number) row.get("similarity")).doubleValue();

            if (similarity < similarityThreshold) {
                log.debug("[AI 语义缓存] 最近邻相似度不足: {} < {}, query='{}', nearest='{}'",
                        String.format("%.4f", similarity), similarityThreshold, normalized, matchedQuery);
                return Lookup.miss(embedding);
            }
            if (!isSafeSemanticMatch(normalized, matchedQuery)) {
                log.info("[AI 语义缓存] 相似度 {} 达标但未通过型号/否定词校验，拒绝命中: query='{}', nearest='{}'",
                        String.format("%.4f", similarity), normalized, matchedQuery);
                return Lookup.miss(embedding);
            }

            String payload = String.valueOf(row.get("payload"));
            // 回填 L1，下次同样的问法直接精确命中，不再调用 Embedding
            redisFallbackManager.putQueryCache(exactId, payload, ttl);
            countHit("semantic");
            log.info("[AI 语义缓存] L2 语义命中: similarity={}, query='{}', matched='{}'",
                    String.format("%.4f", similarity), normalized, matchedQuery);
            return new Lookup(new Hit(payload, matchedQuery, similarity, "semantic", String.valueOf(row.get("id"))), embedding);
        } catch (Exception e) {
            log.warn("[AI 语义缓存] L2 检索失败，按未命中处理: {}", e.getMessage());
            return Lookup.miss(embedding);
        }
    }

    /**
     * 写入缓存（L1 + L2）
     *
     * @param embedding 查询阶段已计算好的向量，可为 null（为 null 时重新计算）
     */
    public void put(String query, String payload, float[] embedding) {
        String normalized = normalize(query);
        String id = hash(normalized);
        redisFallbackManager.putQueryCache(id, payload, ttl);

        if (!enabled || !tableReady) {
            return;
        }
        try {
            float[] vec = (embedding != null) ? embedding : embeddingModel.embed(normalized);
            jdbcTemplate.update(
                    "INSERT INTO ai_semantic_cache (id, query, embedding, payload, created_at) "
                            + "VALUES (?, ?, ?::vector, ?, now()) "
                            + "ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding, "
                            + "payload = EXCLUDED.payload, created_at = now()",
                    id, normalized, toPgVector(vec), payload);
            if (writeCounter.incrementAndGet() % 50 == 0) {
                purgeExpired();
            }
        } catch (Exception e) {
            log.warn("[AI 语义缓存] L2 写入失败（L1 已写入）: {}", e.getMessage());
        }
    }

    /**
     * 驱逐脏缓存：同时删除当前问句与命中条目
     */
    public void evict(String query, Hit hit) {
        String id = hash(normalize(query));
        redisFallbackManager.deleteQueryCache(id);
        if (hit != null && hit.cacheId() != null) {
            redisFallbackManager.deleteQueryCache(hit.cacheId());
        }
        if (!enabled || !tableReady) {
            return;
        }
        try {
            jdbcTemplate.update("DELETE FROM ai_semantic_cache WHERE id = ? OR id = ?",
                    id, hit != null ? hit.cacheId() : id);
        } catch (Exception e) {
            log.debug("[AI 语义缓存] L2 驱逐略过: {}", e.getMessage());
        }
    }

    private void purgeExpired() {
        try {
            int removed = jdbcTemplate.update("DELETE FROM ai_semantic_cache WHERE created_at < ?",
                    Timestamp.from(Instant.now().minus(ttl)));
            if (removed > 0) {
                log.info("[AI 语义缓存] 清理过期条目 {} 条", removed);
            }
        } catch (Exception e) {
            log.debug("[AI 语义缓存] 清理过期条目略过: {}", e.getMessage());
        }
    }

    private void countHit(String level) {
        meterRegistryProvider.ifAvailable(mr -> mr.counter("ai.cache.hits", "type", "query", "level", level).increment());
    }

    // ==================== 纯函数（便于单元测试） ====================

    /**
     * 问句归一化：去首尾空白、转小写、合并空白、去掉句末标点
     */
    static String normalize(String query) {
        if (query == null) {
            return "";
        }
        String s = query.trim().toLowerCase().replaceAll("\\s+", " ");
        return TRAILING_PUNCT.matcher(s).replaceAll("");
    }

    /**
     * 语义命中安全校验：型号/数字/英文 token 完全一致，且否定词出现情况一致
     */
    static boolean isSafeSemanticMatch(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        return keyTokens(na).equals(keyTokens(nb))
                && hasNegation(na) == hasNegation(nb);
    }

    static boolean hasNegation(String s) {
        String stripped = A_NOT_A_QUESTION.matcher(s == null ? "" : s).replaceAll("");
        return NEGATION.matcher(stripped).find();
    }

    /**
     * 提取英文单词与数字串，如 “iPhone15 Pro” → {iphone, 15, pro}
     */
    static Set<String> keyTokens(String s) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher m = KEY_TOKEN.matcher(s == null ? "" : s.toLowerCase());
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    private static String hash(String normalized) {
        return DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private static String toPgVector(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 10).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
