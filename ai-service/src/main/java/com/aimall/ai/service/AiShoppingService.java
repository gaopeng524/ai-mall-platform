package com.aimall.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AI 智能导购核心服务
 * 
 * 核心设计亮点：
 * 1. AI Agent Function Calling：自动装配 MallTools，大模型自主调用查商品、扣库存、下单 RPC
 * 2. 多轮上下文记忆：滑动窗口维护会话历史，支持连续追问
 * 3. 辅助 RAG 检索：从 pgvector 向量库召回商品知识，增强回答
 * 4. 响应式 WebFlux SSE：异步流式逐字打字机推流
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiShoppingService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    /** 会话历史记忆 Map（生产环境可映射至 Redis） */
    private final Map<String, List<Message>> memoryMap = new ConcurrentHashMap<>();

    private static final String SYSTEM_PROMPT = """
            你是一个专业的智能导购助手，名叫"小购"，服务于 AI 电商平台。
            
            你的能力：
            1. 根据用户需求搜索并推荐合适的商品
            2. 详细介绍商品参数、特点和使用场景
            3. 比较不同商品的优缺点
            4. 在用户确认购买意向后帮助创建订单 (调用订单工具)
            5. 查询用户的历史订单
            6. 帮助用户将商品添加至购物车、查看购物车商品列表、或一键合并结算购物车
            
            行为规范：
            - 回答简洁、专业、友好
            - 主动提问了解用户需求（预算、用途等）
            - 不要编造商品信息，所有商品数据通过工具获取
            - 用户确认购买前，先询问确认，再调用下单工具
            - 用户说“加入购物车”或“先放购物车”时，主动调用购物车添加工具
            - 若知识库中有相关商品文档，优先参考知识库内容
            
            当前用户ID：%d
            """;

    /**
     * 流式导购对话主入口
     */
    public Flux<String> chat(String sessionId, Long userId, String message) {
        // 1. 获取该会话历史（最多保留最近 10 条交互）
        List<Message> history = memoryMap.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        history.add(new UserMessage(message));

        // 2. 辅助 RAG：检索向量库背景知识
        String ragContext = retrieveContext(message);

        // 3. 组装 System Prompt
        String systemPrompt = SYSTEM_PROMPT.formatted(userId)
                + (ragContext.isEmpty() ? "" : "\n\n【商品知识库参考】\n" + ragContext);

        log.info("[AI Agent 对话] sessionId={}, userId={}, query={}", sessionId, userId, message);

        return Flux.create(sink -> {
            try {
                // 调用 ChatClient 执行 Agent 思考与 Function Calling
                String response = chatClient.prompt()
                        .system(systemPrompt)
                        .messages(history)
                        .functions("searchProducts", "getProductDetail", "createOrder", "getUserOrders", "addToCart", "getCartList", "checkoutCart")
                        .call()
                        .content();

                if (response == null || response.isBlank()) {
                    response = "抱歉，我未能生成有效回答，请换个方式提问～";
                }

                // 对话结束后将 AI 回复加入历史记忆
                history.add(new AssistantMessage(response));
                if (history.size() > 20) {
                    history.subList(0, history.size() - 20).clear();
                }

                // 平滑切分流式输出至前端 SSE
                int chunkSize = 4;
                for (int i = 0; i < response.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, response.length());
                    sink.next(response.substring(i, end));
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                sink.complete();
            } catch (Exception e) {
                log.error("[AI Agent 对话异常] {}", e.getMessage(), e);
                sink.next("【AI 导购遇到问题】" + (e.getMessage() != null ? e.getMessage() : "服务繁忙，请稍后再试"));
                sink.complete();
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
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < docs.size(); i++) {
                sb.append(i + 1).append(". ").append(docs.get(i).getContent()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("向量库检索跳过: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 清空会话记忆
     */
    public void clearSession(String sessionId) {
        memoryMap.remove(sessionId);
        log.info("[AI Agent] 会话已清除: {}", sessionId);
    }
}
