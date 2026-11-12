package com.aimall.ai.context;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 生产级 Redis 熔断防雪崩与全链路本地降级管理器
 * 
 * 核心设计目标：
 * 1. 快速失败防挂起：当 Redis 宕机或连接被拒绝（Connection Refused）时，彻底消灭 Lettuce 默认 60s 阻塞超时，杜绝 AI 处于无休止“思考中”；
 * 2. 状态机自愈熔断器（Circuit Breaker）：
 *    - CLOSED（正常状态）：全量请求正常读写 Redis 分布式存储；
 *    - OPEN（熔断状态）：连续失败 2 次后触发熔断，在 5 秒静默窗口期内 0ms 快速失败，100% 切换至本地内存，不向网络发任何握手包；
 *    - HALF-OPEN（半开探活）：静默期过后，允许单次请求探活，若 Redis 恢复连通则平滑切回 CLOSED，实现零重启秒级自愈；
 * 3. 六大核心资产全量本地无缝平替（Caffeine + ConcurrentHashMap）：
 *    - 接口限流器 (RateLimiter)
 *    - 会话所有者与沙箱隔离 (SessionOwner)
 *    - 会话并发互斥锁 (SessionLock)
 *    - 会话长文本历史记忆 (SessionMemory)
 *    - 视觉感知特征缓存 (VisualCache)
 *    - 导购问句语义缓存 (QueryCache)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisFallbackManager {

    private final StringRedisTemplate stringRedisTemplate;

    /** 熔断状态标志：true = 正常健康；false = 熔断降级中 */
    private final AtomicBoolean redisAvailable = new AtomicBoolean(true);
    /** 连续失败计数器 */
    private final AtomicInteger failureCount = new AtomicInteger(0);
    /** 上次发生异常的时间戳 */
    private final AtomicLong lastFailureTimestamp = new AtomicLong(0);
    /** 半开状态并发探测保护 */
    private final AtomicBoolean probing = new AtomicBoolean(false);

    /** 连续失败阈值：连续 2 次网络或连接异常即进入熔断 */
    private static final int FAILURE_THRESHOLD = 2;
    /** 熔断静默窗口期：5 秒。在此期间所有请求 0ms 快速降级，不发任何网络包 */
    private static final long PROBE_INTERVAL_MS = 5000;

    // =========================================================================
    // 本地内存平替容器（Caffeine + ConcurrentHashMap，具备自动过期与防泄漏保护）
    // =========================================================================

    /** 本地限流计数器：Key -> count (TTL: 1 分钟) */
    private final Cache<String, AtomicInteger> localRateLimitCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    /** 本地会话所有者映射：SessionId -> UserId (TTL: 30 分钟) */
    private final Cache<String, String> localSessionOwnerCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    /** 本地会话并发互斥锁：SessionId -> TraceId */
    private final Map<String, String> localSessionLocks = new ConcurrentHashMap<>();

    /** 本地会话记忆 JSON 缓存：SessionId -> Json (TTL: 30 分钟) */
    private final Cache<String, String> localMemoryJsonCache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    /** 本地视觉解构缓存：ImageHash -> Json (TTL: 24 小时) */
    private final Cache<String, String> localVisualCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(24))
            .build();

    /** 本地订单待确认草稿缓存（Human-in-the-Loop）：confirmToken -> Json (TTL: 10 分钟) */
    private final Cache<String, String> localDraftCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    // =========================================================================
    // 状态机与探活核心逻辑
    // =========================================================================

    /**
     * 判断当前 Redis 是否可用（带半开状态定时探活）
     */
    public boolean isAvailable() {
        if (redisAvailable.get()) {
            return true;
        }
        long now = System.currentTimeMillis();
        // 5 秒静默期过后，允许单次请求尝试探活（Half-Open 半开状态）
        if (now - lastFailureTimestamp.get() > PROBE_INTERVAL_MS) {
            if (probing.compareAndSet(false, true)) {
                try {
                    if (stringRedisTemplate != null && stringRedisTemplate.getConnectionFactory() != null) {
                        try (var conn = stringRedisTemplate.getConnectionFactory().getConnection()) {
                            String ping = conn.ping();
                            if ("PONG".equalsIgnoreCase(ping)) {
                                markSuccess();
                                return true;
                            }
                        }
                    }
                } catch (Exception e) {
                    markFailure(e);
                } finally {
                    probing.set(false);
                }
            }
        }
        return false;
    }

    private void markSuccess() {
        failureCount.set(0);
        if (!redisAvailable.get()) {
            redisAvailable.set(true);
            log.info("=================================================================");
            log.info("[Redis 故障自愈] Redis 网络已恢复通畅，自动切回 Redis 分布式集群模式！");
            log.info("=================================================================");
        }
    }

    private void markFailure(Exception e) {
        lastFailureTimestamp.set(System.currentTimeMillis());
        int count = failureCount.incrementAndGet();
        if (count >= FAILURE_THRESHOLD && redisAvailable.get()) {
            redisAvailable.set(false);
            log.warn("=================================================================");
            log.warn("[Redis 熔断保护生效] 检测到 Redis 发生连接异常 (原因: {})！", e.getMessage());
            log.warn("[自动降级] 已立即进入本地内存降级模式（限流、会话锁、会话记忆与缓存全部切换为本地 Caffeine/ConcurrentHashMap，0ms 快速响应）！");
            log.warn("=================================================================");
        } else if (redisAvailable.get()) {
            log.debug("[Redis 连接微抖动] 偶发失败 count={}, err={}", count, e.getMessage());
        }
    }

    /**
     * 通用执行器：优先执行 Redis 逻辑，失败或熔断时 0ms 无缝执行本地降级逻辑
     */
    public <T> T execute(Supplier<T> redisAction, Supplier<T> fallbackAction) {
        if (!isAvailable()) {
            return fallbackAction != null ? fallbackAction.get() : null;
        }
        try {
            T result = redisAction.get();
            markSuccess();
            return result;
        } catch (Exception e) {
            markFailure(e);
            return fallbackAction != null ? fallbackAction.get() : null;
        }
    }

    public void execute(Runnable redisAction, Runnable fallbackAction) {
        if (!isAvailable()) {
            if (fallbackAction != null) fallbackAction.run();
            return;
        }
        try {
            redisAction.run();
            markSuccess();
        } catch (Exception e) {
            markFailure(e);
            if (fallbackAction != null) fallbackAction.run();
        }
    }

    // =========================================================================
    // 六大功能业务门面封装
    // =========================================================================

    /**
     * 1. 接口滑动窗口限流：Redis 正常时用原子自增，异常时用本地 Caffeine
     */
    public boolean checkRateLimit(String identifier, int maxRequestsPerMinute) {
        String key = "ai:ratelimit:" + identifier;
        return Boolean.TRUE.equals(execute(
                () -> {
                    Long count = stringRedisTemplate.opsForValue().increment(key);
                    if (count != null && count == 1) {
                        stringRedisTemplate.expire(key, Duration.ofMinutes(1));
                    }
                    return count != null && count <= maxRequestsPerMinute;
                },
                () -> {
                    AtomicInteger counter = localRateLimitCache.get(key, k -> new AtomicInteger(0));
                    return counter != null && counter.incrementAndGet() <= maxRequestsPerMinute;
                }
        ));
    }

    /**
     * 2. 会话所有者读取
     */
    public String getSessionOwner(String sessionId) {
        String key = "ai:chat:session:owner:" + sessionId;
        return execute(
                () -> stringRedisTemplate.opsForValue().get(key),
                () -> localSessionOwnerCache.getIfPresent(sessionId)
        );
    }

    /**
     * 2. 会话所有者首次绑定
     */
    public void setSessionOwnerIfAbsent(String sessionId, String userId, Duration ttl) {
        String key = "ai:chat:session:owner:" + sessionId;
        execute(
                () -> stringRedisTemplate.opsForValue().setIfAbsent(key, userId, ttl),
                () -> localSessionOwnerCache.asMap().putIfAbsent(sessionId, userId)
        );
    }

    /**
     * 3. 同一会话并发互斥锁获取（防并发双击导致大模型乱答）
     */
    public boolean acquireSessionLock(String sessionId, String traceId, Duration ttl) {
        String key = "ai:chat:session:active:" + sessionId;
        return Boolean.TRUE.equals(execute(
                () -> {
                    Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, traceId, ttl);
                    return Boolean.TRUE.equals(ok);
                },
                () -> {
                    String existing = localSessionLocks.putIfAbsent(sessionId, traceId);
                    return existing == null;
                }
        ));
    }

    /**
     * 3. 同一会话并发互斥锁安全释放
     */
    public void releaseSessionLock(String sessionId, String traceId) {
        String key = "ai:chat:session:active:" + sessionId;
        execute(
                () -> {
                    String holder = stringRedisTemplate.opsForValue().get(key);
                    if (traceId != null && traceId.equals(holder)) {
                        stringRedisTemplate.delete(key);
                    }
                },
                () -> {
                    if (traceId != null) {
                        localSessionLocks.remove(sessionId, traceId);
                    } else {
                        localSessionLocks.remove(sessionId);
                    }
                }
        );
    }

    /**
     * 4. 读取会话历史长文本 JSON
     */
    public String getSessionMemoryJson(String sessionId) {
        String key = "ai:chat:memory:" + sessionId;
        return execute(
                () -> stringRedisTemplate.opsForValue().get(key),
                () -> localMemoryJsonCache.getIfPresent(sessionId)
        );
    }

    /**
     * 4. 保存会话历史长文本 JSON（同时同步到本地内存）
     */
    public void saveSessionMemoryJson(String sessionId, String json, Duration ttl) {
        // 双写：无论 Redis 是否健康，始终在本地内存留一份快照
        localMemoryJsonCache.put(sessionId, json);
        String key = "ai:chat:memory:" + sessionId;
        execute(
                () -> stringRedisTemplate.opsForValue().set(key, json, ttl),
                () -> {}
        );
    }

    /**
     * 4. 清除会话（属主与历史）
     */
    public void clearSession(String sessionId) {
        localSessionOwnerCache.invalidate(sessionId);
        localMemoryJsonCache.invalidate(sessionId);
        localSessionLocks.remove(sessionId);
        execute(
                () -> {
                    stringRedisTemplate.delete("ai:chat:session:owner:" + sessionId);
                    stringRedisTemplate.delete("ai:chat:memory:" + sessionId);
                },
                () -> {}
        );
    }

    /**
     * 5. 视觉解构感知特征缓存读取
     */
    public String getVisualCache(String imageHash) {
        String key = "ai:cache:visual:" + imageHash;
        return execute(
                () -> stringRedisTemplate.opsForValue().get(key),
                () -> localVisualCache.getIfPresent(imageHash)
        );
    }

    /**
     * 5. 视觉解构感知特征缓存写入
     */
    public void putVisualCache(String imageHash, String jsonPayload, Duration ttl) {
        localVisualCache.put(imageHash, jsonPayload);
        String key = "ai:cache:visual:" + imageHash;
        execute(
                () -> stringRedisTemplate.opsForValue().set(key, jsonPayload, ttl),
                () -> {}
        );
    }

    /**
     * 6. 语义问句缓存读取
     */
    public String getQueryCache(String queryHash) {
        String key = "ai:cache:query:" + queryHash;
        return execute(
                () -> stringRedisTemplate.opsForValue().get(key),
                () -> null // 降级模式直接穿透
        );
    }

    /**
     * 6. 语义问句缓存写入
     */
    public void putQueryCache(String queryHash, String jsonPayload, Duration ttl) {
        String key = "ai:cache:query:" + queryHash;
        execute(
                () -> stringRedisTemplate.opsForValue().set(key, jsonPayload, ttl),
                () -> {}
        );
    }

    /**
     * 6. 语义问句缓存清除
     */
    public void deleteQueryCache(String queryHash) {
        String key = "ai:cache:query:" + queryHash;
        execute(
                () -> stringRedisTemplate.delete(key),
                () -> {}
        );
    }

    /**
     * 7. 待确认订单草稿存储（Human-in-the-Loop）
     */
    public void putOrderDraft(String confirmToken, String jsonPayload, Duration ttl) {
        String key = "ai:order:draft:" + confirmToken;
        localDraftCache.put(confirmToken, jsonPayload);
        execute(
                () -> stringRedisTemplate.opsForValue().set(key, jsonPayload, ttl),
                () -> {}
        );
    }

    public String getOrderDraft(String confirmToken) {
        String key = "ai:order:draft:" + confirmToken;
        return execute(
                () -> {
                    String val = stringRedisTemplate.opsForValue().get(key);
                    return (val != null && !val.isBlank()) ? val : localDraftCache.getIfPresent(confirmToken);
                },
                () -> localDraftCache.getIfPresent(confirmToken)
        );
    }

    public void deleteOrderDraft(String confirmToken) {
        String key = "ai:order:draft:" + confirmToken;
        localDraftCache.invalidate(confirmToken);
        execute(
                () -> stringRedisTemplate.delete(key),
                () -> {}
        );
    }

    /**
     * 确认单提交互斥锁：同一个 confirmToken 同一时刻只允许一个请求真正去下单（防并发双击）。
     * 拿不到锁的请求等待持有者把草稿标记为 USED 后直接返回同一订单。
     */
    public boolean tryLockOrderConfirm(String confirmToken, String owner, Duration ttl) {
        String key = "ai:order:confirm:lock:" + confirmToken;
        return Boolean.TRUE.equals(execute(
                () -> Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, owner, ttl)),
                () -> localSessionLocks.putIfAbsent("confirm:" + confirmToken, owner) == null
        ));
    }

    public void unlockOrderConfirm(String confirmToken, String owner) {
        String key = "ai:order:confirm:lock:" + confirmToken;
        execute(
                () -> {
                    String holder = stringRedisTemplate.opsForValue().get(key);
                    if (owner != null && owner.equals(holder)) {
                        stringRedisTemplate.delete(key);
                    }
                },
                () -> localSessionLocks.remove("confirm:" + confirmToken, owner)
        );
    }
}
