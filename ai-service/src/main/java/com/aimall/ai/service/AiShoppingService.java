package com.aimall.ai.service;

import com.aimall.ai.context.AiToolExecutionContext;
import com.aimall.common.trace.TraceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.Media;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AI 智能导购核心服务（双 Agent 协同：视觉解构专家 + 选品风控与交易专家）
 * 
 * 核心架构亮点：
 * 1. 统一基座多模态（qwen3.7-flash）：
 *    - Agent 1（视觉解构专家）：输入商品图片，提取结构化标签与搜索关键词，发布 visual_analysis 实时步骤；
 *    - Agent 2（选品风控与交易专家）：挂载 MallTools 9 项微服务能力，根据解构特征搜库、比价、排查无货并生成平替推荐卡片；
 * 2. 实时结构化 SSE 事件流：推送 visual_analysis、tool_step、products 与平滑 chunk 文本；
 * 3. Redis + 内存双层会话记忆：滑动窗口裁剪与自动过期；
 * 4. 防越权与全链路追踪：AiToolExecutionContext 绑定网关透传鉴权 userId 与 TraceId。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiShoppingService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String REDIS_MEMORY_PREFIX = "ai:chat:memory:";
    private static final Duration MEMORY_TTL = Duration.ofMinutes(30);
    private static final int MAX_HISTORY_MESSAGES = 20;

    /** 本地会话历史兜底容器（当 Redis 不可用时平滑降级） */
    private final Map<String, List<Message>> fallbackMemoryMap = new ConcurrentHashMap<>();

    private static final String VISUAL_AGENT_SYSTEM_PROMPT = """
            你是一个顶级的电商多模态视觉解构专家，擅长从用户拍摄或上传的商品实物图片中提取关键属性。
            你的任务：仔细分析图片中的核心主体，提取用于商城数据库比对与精准检索的结构化信息。
            
            输出规范：
            必须直接输出标准的 JSON 字符串（严禁包含任何 Markdown 格式块如 ```json，不要输出任何多余问候或闲聊）：
            {
              "category": "商品品类（如：运动鞋/手机/耳机/数码配件/服饰/生活电器/箱包配饰）",
              "style": "外观版型与设计风格",
              "color": "主要颜色与特征配色",
              "brand": "若有明确LOGO或高辨识度品牌请写明，无法确定写未知",
              "features": "核心材质、科技或外观特征（如：气垫跑鞋/Gasket机械键盘/头戴降噪/全铝CNC/大朵鹅绒等）",
              "searchQuery": "推荐用于商城在售数据库检索的核心关键词（简短精准，1至2个词，如：Nike Pegasus / 跑步鞋 / 降噪耳机 / 机械键盘 / 羽绒服 / 冲锋衣 / 咖啡机 / 双肩包 / 苹果 / 小米 / 华为）",
              "summary": "一句话精炼描述此商品的视觉特征与核心亮点"
            }
            """;

    private static final String SYSTEM_PROMPT = """
            你是一个专业的智能导购与电商交易专家，名叫"小购"，服务于 AI 电商平台。
            
            你的核心能力与工具规范：
            1. 搜索与推荐：当用户咨询商品、找平替或提供视觉解构特征时，必须优先调用 `searchProducts` 获取商城真实在售商品。
            2. 详情与对比：当用户对比多款商品或询问具体参数时，调用 `getProductDetail` 或 `searchProducts`。
            3. 购物车管理：
               - 用户说“加入购物车”、“先放购物车”时，调用 `addToCart`；
               - 用户想查看购物车时，调用 `getCartList`；
               - 用户明确要求“结算购物车”时，调用 `checkoutCart`。
            4. 订单全生命周期操作：
               - 用户明确确认要直接购买某商品时，调用 `createOrder`；
               - 用户查询历史订单时，调用 `getUserOrders`；
               - 用户查询某笔具体订单号（如 ORD...）的状态时，调用 `getOrderDetail`；
               - 用户要求取消某笔未支付订单时，调用 `cancelOrder`。
            
            行为准则：
            - 回答专业、亲切、客观，多维度对比正品与性价比『平替款』的价格、材质与核心体验差异。
            - 严禁凭空捏造商品 ID、价格或订单号，所有商品必须通过微服务工具获取。
            - 当前已认证用户 ID 为 %d（所有工具已由系统底层自动绑定该身份）。
            """;

    /**
     * 重载流式对话接口（纯文本对话）
     */
    public Flux<String> chat(String sessionId, Long userId, String message) {
        return chat(sessionId, userId, message, null);
    }

    /**
     * 流式导购对话主入口（支持图片多模态拍立淘与纯文本对话）
     */
    public Flux<String> chat(String sessionId, Long userId, String message, String imageData) {
        final Long effectiveUserId = (userId != null && userId > 0) ? userId : 2L;
        final String traceId = TraceContext.initOrGetTraceId(TraceContext.getTraceId());
        final String userPrompt = (message != null && !message.isBlank()) ? message.trim() : "帮我识别图中的商品，并在商城中推荐同款或高性价比平替";

        log.info("[AI Agent 对话] sessionId={}, userId={}, traceId={}, hasImage={}, query={}",
                sessionId, effectiveUserId, traceId, (imageData != null && !imageData.isBlank()), userPrompt);

        return Flux.create(sink -> {
            AiToolExecutionContext.bind(sessionId, effectiveUserId, traceId, sink::next);
            TraceContext.setTraceId(traceId);

            try {
                // 1. 判断是否携带图片数据，启动双 Agent 流水线
                Media media = parseMedia(imageData);
                String actualAgentPrompt = userPrompt;

                if (media != null) {
                    // ==========================================
                    // 阶段一：Agent 1 视觉解构专家 (Visual Analyst)
                    // ==========================================
                    AiToolExecutionContext.emitToolStep(
                            "visual_analysis",
                            "通义千问 qwen3.7-flash 正在解构图像视觉特征",
                            "多模态空间感知与商品属性抽取中...",
                            "running"
                    );

                    String visualRawOutput = chatClient.prompt()
                            .system(VISUAL_AGENT_SYSTEM_PROMPT)
                            .user(u -> u.text(userPrompt).media(media))
                            .call()
                            .content();

                    log.info("[AI Agent 1 视觉解构原始输出] {}", visualRawOutput);
                    Map<String, Object> visualAttr = parseVisualJson(visualRawOutput);

                    String category = String.valueOf(visualAttr.getOrDefault("category", "精选商品"));
                    String summary = String.valueOf(visualAttr.getOrDefault("summary", "优质热销单品"));
                    String color = String.valueOf(visualAttr.getOrDefault("color", "多色可选"));
                    String brand = String.valueOf(visualAttr.getOrDefault("brand", "优质品牌"));
                    String searchQuery = String.valueOf(visualAttr.getOrDefault("searchQuery", category));

                    AiToolExecutionContext.emitToolStep(
                            "visual_analysis",
                            "视觉解构完成: " + summary,
                            "品类: " + category + " | 配色: " + color + " | 提取搜索词: " + searchQuery,
                            "success"
                    );

                    // ==========================================
                    // 阶段二：组装 Agent 2 拍立淘找平替任务 Prompt
                    // ==========================================
                    actualAgentPrompt = String.format("""
                            【视觉拍立淘·搜同款与平替任务】
                            用户上传了商品实物图片，视觉解构专家已完成前序分析：
                            - 识别品类：%s
                            - 外观与核心卖点：%s
                            - 配色与风格：%s
                            - 识别品牌：%s
                            - 推荐数据库检索词：%s
                            - 用户诉求：%s
                            
                            【专家执行指引】：
                            1. 立即调用 `searchProducts` 工具，优先使用检索词（如 "%s"）搜索商城在售库存商品；
                            2. 找到商品后，请从价格梯队、材质科技、适用场景对比原图商品与在售商品，重点推荐高性价比的『对标平替款』；
                            3. 给出客观购买建议，严禁编造非在售商品！
                            """,
                            category, summary, color, brand, searchQuery, userPrompt, searchQuery);
                }

                // 2. 加载会话历史（优先从 Redis 读取）
                List<Message> history = loadSessionHistory(sessionId);
                history.add(new UserMessage(media != null ? "[拍立淘识别] " + userPrompt : userPrompt));

                // 3. 辅助 RAG：检索向量库背景知识
                String ragContext = retrieveContext(actualAgentPrompt);

                // 4. 组装 Agent 2 System Prompt
                String systemPrompt = SYSTEM_PROMPT.formatted(effectiveUserId)
                        + (ragContext.isEmpty() ? "" : "\n\n【商品知识库参考】\n" + ragContext);

                // 5. 调用 Agent 2 (选品风控与交易专家) 执行 Function Calling
                String response = chatClient.prompt()
                        .system(systemPrompt)
                        .user(actualAgentPrompt)
                        .functions(
                                "searchProducts",
                                "getProductDetail",
                                "createOrder",
                                "getUserOrders",
                                "getOrderDetail",
                                "cancelOrder",
                                "addToCart",
                                "getCartList",
                                "checkoutCart"
                        )
                        .call()
                        .content();

                if (response == null || response.isBlank()) {
                    response = "已为您检索到相关商品，详情请查看上方的商品推荐卡片～";
                }

                // 6. 保存会话历史至 Redis
                history.add(new AssistantMessage(response));
                saveSessionHistory(sessionId, history);

                // 7. 将回复包装为结构化 chunk 事件平滑推流
                int chunkSize = 6;
                for (int i = 0; i < response.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, response.length());
                    String piece = response.substring(i, end);
                    sink.next(AiToolExecutionContext.formatChunkEvent(piece));
                    try {
                        Thread.sleep(12);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                sink.complete();
            } catch (Exception e) {
                log.error("[AI Agent 对话异常] {}", e.getMessage(), e);
                String errText = "【AI 导购遇到问题】" + (e.getMessage() != null ? e.getMessage() : "服务繁忙，请稍后再试");
                sink.next(AiToolExecutionContext.formatChunkEvent(errText));
                sink.complete();
            } finally {
                AiToolExecutionContext.clear(sessionId);
                TraceContext.clear();
            }
        });
    }

    /**
     * 解析多模态图片媒体（支持 Base64 与网络 URL）
     */
    private Media parseMedia(String imageData) {
        if (imageData == null || imageData.isBlank()) {
            return null;
        }
        try {
            String trimmed = imageData.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return Media.builder()
                        .mimeType(MimeTypeUtils.IMAGE_JPEG)
                        .data(new URL(trimmed))
                        .build();
            }

            // 处理 Base64 数据
            String base64Content = trimmed;
            MimeType mimeType = MimeTypeUtils.IMAGE_JPEG;
            if (base64Content.contains(",")) {
                String meta = base64Content.substring(0, base64Content.indexOf(","));
                base64Content = base64Content.substring(base64Content.indexOf(",") + 1);
                if (meta.contains("image/png")) {
                    mimeType = MimeTypeUtils.IMAGE_PNG;
                } else if (meta.contains("image/webp")) {
                    mimeType = MimeType.valueOf("image/webp");
                }
            }
            byte[] bytes = Base64.getDecoder().decode(base64Content.replaceAll("\\s", ""));
            return Media.builder()
                    .mimeType(mimeType)
                    .data(new ByteArrayResource(bytes))
                    .build();
        } catch (Exception e) {
            log.warn("[AI Multimodal] 图片媒体解析异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析视觉解构专家输出的 JSON
     */
    private Map<String, Object> parseVisualJson(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return Collections.emptyMap();
        }
        String clean = rawOutput.trim();
        if (clean.startsWith("```json")) {
            clean = clean.substring(7);
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3);
        }
        if (clean.endsWith("```")) {
            clean = clean.substring(0, clean.length() - 3);
        }
        clean = clean.trim();
        try {
            return objectMapper.readValue(clean, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[AI Visual Agent] JSON 解析非标准，使用兜底抽取: {}", e.getMessage());
            return Map.of(
                    "category", "热销优选",
                    "searchQuery", "运动鞋",
                    "summary", clean.length() > 60 ? clean.substring(0, 60) + "..." : clean
            );
        }
    }

    /**
     * RAG 语义检索：从 pgvector 向量库召回商品知识
     */
    private String retrieveContext(String query) {
        try {
            var docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(3)
                            .similarityThreshold(0.7)
                            .build());
            if (docs == null || docs.isEmpty()) {
                return "";
            }
            AiToolExecutionContext.emitToolStep(
                    "vector_search",
                    "已从 pgvector 向量库召回 " + docs.size() + " 条商品知识片段",
                    "余弦相似度阈值 ≥ 0.70",
                    "success"
            );
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < docs.size(); i++) {
                sb.append(i + 1).append(". ").append(docs.get(i).getContent()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("向量库检索跳过: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 从 Redis 加载会话历史（若 Redis 不可达则降级读取本地内存）
     */
    private List<Message> loadSessionHistory(String sessionId) {
        String redisKey = REDIS_MEMORY_PREFIX + sessionId;
        try {
            String json = stringRedisTemplate.opsForValue().get(redisKey);
            if (json != null && !json.isBlank()) {
                List<Map<String, String>> rawList = objectMapper.readValue(
                        json, new TypeReference<List<Map<String, String>>>() {});
                List<Message> messages = new ArrayList<>();
                for (Map<String, String> item : rawList) {
                    String role = item.getOrDefault("role", "user");
                    String content = item.getOrDefault("content", "");
                    if ("assistant".equalsIgnoreCase(role)) {
                        messages.add(new AssistantMessage(content));
                    } else {
                        messages.add(new UserMessage(content));
                    }
                }
                return messages;
            }
        } catch (Exception e) {
            log.debug("[AI Memory] Redis 读取降级为本地内存: {}", e.getMessage());
        }
        return new ArrayList<>(fallbackMemoryMap.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()));
    }

    /**
     * 保存会话历史至 Redis（保留最近 20 条消息，30 分钟滑动过期）
     */
    private void saveSessionHistory(String sessionId, List<Message> history) {
        if (history.size() > MAX_HISTORY_MESSAGES) {
            history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY_MESSAGES, history.size()));
        }
        fallbackMemoryMap.put(sessionId, new CopyOnWriteArrayList<>(history));

        String redisKey = REDIS_MEMORY_PREFIX + sessionId;
        try {
            List<Map<String, String>> serializableList = new ArrayList<>(history.size());
            for (Message msg : history) {
                String role = (msg instanceof AssistantMessage) ? "assistant" : "user";
                serializableList.add(Map.of("role", role, "content", msg.getContent()));
            }
            stringRedisTemplate.opsForValue().set(
                    redisKey,
                    objectMapper.writeValueAsString(serializableList),
                    MEMORY_TTL
            );
        } catch (Exception e) {
            log.debug("[AI Memory] Redis 写入跳过，已保存至本地兜底缓存: {}", e.getMessage());
        }
    }

    /**
     * 清空会话记忆
     */
    public void clearSession(String sessionId) {
        fallbackMemoryMap.remove(sessionId);
        try {
            stringRedisTemplate.delete(REDIS_MEMORY_PREFIX + sessionId);
        } catch (Exception ignored) {
        }
        log.info("[AI Agent] 会话已清除: {}", sessionId);
    }
}
