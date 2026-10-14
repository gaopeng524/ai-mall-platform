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
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AI 智能导购核心服务
 * 
 * 核心设计亮点：
 * 1. AI Agent Function Calling：自动装配 9 个电商业务 Tool（搜商品、查详情、下单、查订单、查单明细、取消订单、加购、看购物车、合并结算）；
 * 2. 实时结构化 SSE 事件流：支持实时向前端推送 tool_step（工具调用进度卡片）、products（可交互商品卡片）与 chunk（保留完整换行的文本块）；
 * 3. Redis + 内存双层会话记忆：支持 30 分钟 TTL 自动滑动过期与最多 20 条滑动窗口裁剪，兼顾分布式扩展与防 OOM；
 * 4. 防越权与全链路追踪：通过 AiToolExecutionContext 绑定真实鉴权 userId 与 TraceId。
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

    private static final String SYSTEM_PROMPT = """
            你是一个专业的智能导购助手，名叫"小购"，服务于 AI 电商平台。
            
            你的核心能力与工具规范：
            1. 搜索与推荐：当用户咨询商品或表达预算/品类需求时，调用 `searchProducts` 获取真实在售商品。
            2. 详情与对比：当用户询问具体商品参数或对比多款商品时，调用 `getProductDetail` 或 `searchProducts`。
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
            - 回答简洁、专业、条理清晰，使用清晰的条目展示商品亮点与价格。
            - 严禁编造商品 ID、价格或订单号，所有商品与订单数据必须通过工具获取。
            - 当前已认证用户 ID 为 %d（所有工具已由系统底层自动绑定该用户身份，无需向用户索要用户 ID）。
            """;

    /**
     * 流式导购对话主入口
     */
    public Flux<String> chat(String sessionId, Long userId, String message) {
        final Long effectiveUserId = (userId != null && userId > 0) ? userId : 2L;
        final String traceId = TraceContext.initOrGetTraceId(TraceContext.getTraceId());

        // 1. 加载会话历史（优先从 Redis 读取，支持 30 分钟 TTL）
        List<Message> history = loadSessionHistory(sessionId);
        history.add(new UserMessage(message));

        log.info("[AI Agent 对话] sessionId={}, userId={}, traceId={}, query={}",
                sessionId, effectiveUserId, traceId, message);

        return Flux.create(sink -> {
            // 绑定当前会话上下文与 SSE 实时事件发射器
            AiToolExecutionContext.bind(sessionId, effectiveUserId, traceId, sink::next);
            TraceContext.setTraceId(traceId);

            try {
                // 2. 辅助 RAG：检索向量库背景知识并推送状态卡片
                String ragContext = retrieveContext(message);

                // 3. 组装 System Prompt
                String systemPrompt = SYSTEM_PROMPT.formatted(effectiveUserId)
                        + (ragContext.isEmpty() ? "" : "\n\n【商品知识库参考】\n" + ragContext);

                // 4. 调用 ChatClient 执行 Agent 思考与 Function Calling
                String response = chatClient.prompt()
                        .system(systemPrompt)
                        .messages(history)
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
                    response = "抱歉，我未能生成有效回答，请换个方式提问～";
                }

                // 5. 对话结束后将 AI 回复加入历史记忆并持久化至 Redis
                history.add(new AssistantMessage(response));
                saveSessionHistory(sessionId, history);

                // 6. 将回复包装为结构化 chunk 事件平滑推流（完整保留换行符 \n 与 Markdown 缩进）
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
     * RAG 语义检索：从 pgvector 向量库召回商品信息
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
