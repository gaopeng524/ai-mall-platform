package com.aimall.ai.context;

import com.aimall.common.context.UserContext;
import com.aimall.common.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AI 工具执行上下文管理器
 * 
 * 职责：
 * 1. 维护当前 AI 会话的身份（userId）、全链路追踪 ID（traceId）与 SSE 流式事件推送通道（eventConsumer）；
 * 2. 在大模型 Function Calling 多线程/异步回调时，将身份信息和追踪 ID 注入当前执行线程（UserContext / TraceContext）；
 * 3. 向前端推送结构化事件流（工具调用步骤 tool_step、推荐商品卡片 products、平滑文本打字 chunk）。
 */
@Slf4j
public class AiToolExecutionContext {

    public static final String AI_EVENT_PREFIX = "__AI_EVENT__:";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Data
    @Builder
    public static class SessionContext {
        private String sessionId;
        private Long userId;
        private String traceId;
        private Consumer<String> eventConsumer;
        private volatile List<Map<String, Object>> lastEmittedProducts;
        @Builder.Default
        private final java.util.concurrent.atomic.AtomicInteger toolExecutionCount = new java.util.concurrent.atomic.AtomicInteger(0);
        @Builder.Default
        private final java.util.concurrent.atomic.AtomicInteger productSearchCount = new java.util.concurrent.atomic.AtomicInteger();
        @Builder.Default
        private final java.util.concurrent.atomic.AtomicInteger consecutiveNotFoundCount = new java.util.concurrent.atomic.AtomicInteger(0);
        /** 单次提问（单轮 Turn）内商品服务是否已触发 Sentinel 429 限流（该标记随本轮回答结束 clear() 自动销毁，不影响下一句提问） */
        @Builder.Default
        private volatile boolean productServiceRateLimited = false;
        /** 记录当前会话实际执行过的工具名称列表（按顺序记录，供自动化评测与日志追踪） */
        @Builder.Default
        private final List<String> executedTools = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    /** 线程本地上下文绑定（支持父子线程继承） */
    private static final ThreadLocal<SessionContext> THREAD_CONTEXT = new InheritableThreadLocal<>();

    /** 会话全局注册表（针对显式 sessionId 检索提供映射） */
    private static final Map<String, SessionContext> SESSION_REGISTRY = new ConcurrentHashMap<>();

    private static final java.util.concurrent.ExecutorService TOOL_EXECUTOR = java.util.concurrent.Executors.newCachedThreadPool(
            new org.springframework.scheduling.concurrent.CustomizableThreadFactory("ai-tool-worker-")
    );

    private static String getRegistryKey(String sessionId, Long userId) {
        String uidStr = (userId != null && userId > 0) ? String.valueOf(userId) : "guest";
        return uidStr + ":" + (sessionId != null ? sessionId : "default");
    }

    /**
     * 绑定当前 SSE 会话上下文
     */
    public static void bind(String sessionId, Long userId, String traceId, Consumer<String> eventConsumer) {
        SessionContext ctx = SessionContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .traceId(traceId)
                .eventConsumer(eventConsumer)
                .build();
        THREAD_CONTEXT.set(ctx);
        if (sessionId != null) {
            SESSION_REGISTRY.put(getRegistryKey(sessionId, userId), ctx);
            SESSION_REGISTRY.put(sessionId, ctx);
        }
        if (userId != null && userId > 0) {
            UserContext.set(new UserContext.UserInfo(userId, null, null));
        } else {
            UserContext.clear();
        }
        if (traceId != null) {
            TraceContext.setTraceId(traceId);
        }
    }

    /**
     * 清除会话上下文
     */
    public static void clear(String sessionId) {
        clear(sessionId, null);
    }

    public static void clear(String sessionId, Long userId) {
        THREAD_CONTEXT.remove();
        if (sessionId != null) {
            SESSION_REGISTRY.remove(getRegistryKey(sessionId, userId));
            SESSION_REGISTRY.remove(sessionId);
        }
        UserContext.clear();
        TraceContext.clear();
    }

    @FunctionalInterface
    public interface ToolAction<T> {
        T execute() throws Exception;
    }

    /**
     * 获取当前活动上下文（严格基于当前线程绑定的 ThreadLocal）
     */
    public static SessionContext getActiveContext() {
        return THREAD_CONTEXT.get();
    }

    /**
     * 根据显式 sessionId 获取全局注册的会话上下文
     */
    public static SessionContext getContextBySessionId(String sessionId) {
        if (sessionId != null) {
            return SESSION_REGISTRY.get(sessionId);
        }
        return null;
    }

    /**
     * 工具执行作用域隔离包装器（重载：兼容无工具名传入）：
     */
    public static <T> T executeInToolContext(org.springframework.ai.chat.model.ToolContext toolContext, ToolAction<T> action) {
        return executeInToolContext("unknown_tool", toolContext, null, action);
    }

    /**
     * 工具执行作用域隔离包装器（完整版：支持工具名称、入参、出参全量审计日志）：
     * 1. 显式从 Spring AI ToolContext 提取 sessionId，严格从 SESSION_REGISTRY 命中对应会话；
     * 2. 在执行线程中安全绑定 SessionContext、UserContext 与 TraceContext；
     * 3. 结构化打印工具调用【入参】、【出参】、【耗时】与【执行状态】；
     * 4. 在 finally 块中强制恢复或清除线程上下文，彻底消灭 Reactor Netty / 线程池复用导致的身份串流与内存泄漏！
     */
    public static <T> T executeInToolContext(String toolName, org.springframework.ai.chat.model.ToolContext toolContext, Object toolRequest, ToolAction<T> action) {
        String sessionId = null;
        Long explicitUserId = null;
        String explicitTraceId = null;

        if (toolContext != null && toolContext.getContext() != null) {
            Object sid = toolContext.getContext().get("sessionId");
            if (sid != null) {
                sessionId = sid.toString();
            }
            Object uid = toolContext.getContext().get("userId");
            if (uid instanceof Number n) {
                explicitUserId = n.longValue();
            } else if (uid != null) {
                try {
                    explicitUserId = Long.parseLong(uid.toString());
                } catch (Exception ignored) {
                }
            }
            Object tid = toolContext.getContext().get("traceId");
            if (tid != null) {
                explicitTraceId = tid.toString();
            }
        }

        SessionContext targetCtx = null;
        if (sessionId != null) {
            if (explicitUserId != null && explicitUserId > 0) {
                targetCtx = SESSION_REGISTRY.get(getRegistryKey(sessionId, explicitUserId));
            }
            if (targetCtx == null) {
                targetCtx = SESSION_REGISTRY.get(sessionId);
            }
        }
        if (targetCtx == null) {
            targetCtx = THREAD_CONTEXT.get();
        }

        SessionContext prevCtx = THREAD_CONTEXT.get();
        UserContext.UserInfo prevUser = UserContext.get();
        String prevTrace = TraceContext.getTraceId();

        Long effectiveUserId = explicitUserId;
        if (effectiveUserId == null && targetCtx != null) {
            effectiveUserId = targetCtx.getUserId();
        }

        String effectiveTrace = explicitTraceId;
        if (effectiveTrace == null && targetCtx != null) {
            effectiveTrace = targetCtx.getTraceId();
        }

        log.info(">>>>>>>>>>>>>>>>>>>> [AI 工具调用·执行开始] >>>>>>>>>>>>>>>>>>>>");
        log.info("[工具名称] {}", toolName);
        log.info("[输入参数] {}", toolRequest != null ? toolRequest : "{}");
        log.info("[调用会话] sessionId={}, userId={}, traceId={}", sessionId, effectiveUserId, effectiveTrace);
        log.info("-------------------------------------------------------------");

        // 核心风控熔断：杜绝大模型陷入死循环（如由于猜错ID，连续不断调用 getProductDetail(54), (55), (56)...）
        if (targetCtx != null) {
            targetCtx.getExecutedTools().add(toolName);
            if (targetCtx.getEventConsumer() != null) {
                try {
                    Map<String, Object> toolPayload = new java.util.LinkedHashMap<>();
                    toolPayload.put("event", "tool_call");
                    toolPayload.put("toolName", toolName);
                    toolPayload.put("args", toolRequest != null ? OBJECT_MAPPER.valueToTree(toolRequest) : Map.of());
                    targetCtx.getEventConsumer().accept(AI_EVENT_PREFIX + OBJECT_MAPPER.writeValueAsString(toolPayload));
                } catch (Exception ignored) {}
            }
            int execCount = targetCtx.getToolExecutionCount().incrementAndGet();
            int missCount = targetCtx.getConsecutiveNotFoundCount().get();
            if (execCount > 5 || missCount >= 3) {
                log.warn("[AI 工具熔断保护] 会话 {} 工具调用触发熔断: toolName={}, execCount={}, missCount={}",
                        sessionId, toolName, execCount, missCount);
                AiToolExecutionContext.emitToolStep(
                        "tool_circuit_breaker",
                        "系统安全熔断：工具调用已达保护上限",
                        "已自动终止循环调用，正在为您呈现最佳导购回复",
                        "failed"
                );
                String cbMsg = "【系统安全熔断】单轮对话工具调用已达安全上限（检测到连续多次检索不存在的编号或反复调用工具）。"
                        + "严禁继续调用任何工具！请立即基于已有知识直接给用户友好的文字回复，或建议用户直接提供商品名称进行搜索！";
                if (targetCtx.getEventConsumer() != null) {
                    try {
                        Map<String, Object> resPayload = Map.of(
                                "event", "tool_result",
                                "toolName", toolName,
                                "result", cbMsg,
                                "costMs", 0L
                        );
                        targetCtx.getEventConsumer().accept(AI_EVENT_PREFIX + OBJECT_MAPPER.writeValueAsString(resPayload));
                    } catch (Exception ignored) {}
                }
                return (T) cbMsg;
            }
        }

        long start = System.currentTimeMillis();
        try {
            // 核心解决：Spring AI 在 Netty reactor-http-nio 线程中执行工具时，Spring Cloud LoadBalancer
            // 会调用 Mono.block() 选择微服务实例，触发 "block() is not supported in thread reactor-http-nio" 异常。
            // 将工具调用调度到专属工作线程池中执行，彻底解耦 Netty 事件循环，杜绝阻塞检查拦截！
            final SessionContext finalTargetCtx = targetCtx;
            final Long finalEffectiveUserId = effectiveUserId;
            final String finalEffectiveTrace = effectiveTrace;

            T result = TOOL_EXECUTOR.submit(() -> {
                if (finalTargetCtx != null) {
                    THREAD_CONTEXT.set(finalTargetCtx);
                }
                if (finalEffectiveUserId != null && finalEffectiveUserId > 0) {
                    UserContext.set(new UserContext.UserInfo(finalEffectiveUserId, null, null));
                } else {
                    UserContext.clear();
                }
                if (finalEffectiveTrace != null) {
                    TraceContext.setTraceId(finalEffectiveTrace);
                }
                try {
                    return action.execute();
                } finally {
                    THREAD_CONTEXT.remove();
                    UserContext.clear();
                    TraceContext.clear();
                }
            }).get();

            // 统计连续未命中/失败次数，用于快速触发熔断
            if (targetCtx != null && result instanceof String strResult) {
                if (strResult.contains("未找到") || strResult.contains("不存在") || strResult.contains("失败") || strResult.contains("拦截")) {
                    targetCtx.getConsecutiveNotFoundCount().incrementAndGet();
                } else {
                    targetCtx.getConsecutiveNotFoundCount().set(0);
                }
            }

            long cost = System.currentTimeMillis() - start;
            if (targetCtx != null && targetCtx.getEventConsumer() != null) {
                try {
                    Map<String, Object> resPayload = new java.util.LinkedHashMap<>();
                    resPayload.put("event", "tool_result");
                    resPayload.put("toolName", toolName);
                    resPayload.put("args", toolRequest != null ? OBJECT_MAPPER.valueToTree(toolRequest) : Map.of());
                    resPayload.put("result", result != null ? String.valueOf(result) : "");
                    resPayload.put("costMs", cost);
                    targetCtx.getEventConsumer().accept(AI_EVENT_PREFIX + OBJECT_MAPPER.writeValueAsString(resPayload));
                } catch (Exception ignored) {}
            }
            log.info("<<<<<<<<<<<<<<<<<<<< [AI 工具调用·执行完成] <<<<<<<<<<<<<<<<<<<<");
            log.info("[工具名称] {}, [耗时] {} ms", toolName, cost);
            log.info("[输出结果]\n{}", result);
            log.info("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
            return result;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("<<<<<<<<<<<<<<<<<<<< [AI 工具调用·执行失败] <<<<<<<<<<<<<<<<<<<<");
            log.error("[工具名称] {}, [耗时] {} ms, [错误信息] {}", toolName, cost, e.getMessage(), e);
            log.error("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        } finally {
            // 严格在 finally 清理/复原线程上下文，杜绝 Netty 线程复用导致的身份串流
            if (prevCtx != null) {
                THREAD_CONTEXT.set(prevCtx);
            } else {
                THREAD_CONTEXT.remove();
            }
            if (prevUser != null) {
                UserContext.set(prevUser);
            } else {
                UserContext.clear();
            }
            if (prevTrace != null) {
                TraceContext.setTraceId(prevTrace);
            } else {
                TraceContext.clear();
            }
        }
    }

    /**
     * 获取当前工具调用线程的安全认证 UserId（未登录用户返回 null，严禁静默兜底至演示账号 2L）
     */
    public static Long getCurrentUserId() {
        SessionContext ctx = THREAD_CONTEXT.get();
        if (ctx != null && ctx.getUserId() != null && ctx.getUserId() > 0) {
            return ctx.getUserId();
        }
        Long uid = UserContext.getUserId();
        if (uid != null && uid > 0) {
            return uid;
        }
        return null;
    }

    /**
     * 格式化 SSE 心跳保持帧（结构化事件，避免直接发送 ": ping" 污染打字机回答）
     */
    public static String formatPingEvent() {
        return AI_EVENT_PREFIX + "{\"event\":\"ping\"}";
    }

    /**
     * 格式化纯文本 chunk 事件（保留完整换行与格式）
     */
    public static String formatChunkEvent(String text) {
        try {
            Map<String, Object> payload = Map.of(
                    "event", "chunk",
                    "text", text != null ? text : ""
            );
            return AI_EVENT_PREFIX + OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return text != null ? text : "";
        }
    }

    /**
     * 向前端实时推送工具执行步骤卡片 (tool_step)
     */
    public static void emitToolStep(String type, String title, String detail, String status) {
        SessionContext ctx = getActiveContext();
        if (ctx == null || ctx.getEventConsumer() == null) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "event", "tool_step",
                    "type", type != null ? type : "product_query",
                    "title", title != null ? title : "",
                    "detail", detail != null ? detail : "",
                    "status", status != null ? status : "running"
            );
            String eventLine = AI_EVENT_PREFIX + OBJECT_MAPPER.writeValueAsString(payload);
            ctx.getEventConsumer().accept(eventLine);
        } catch (Exception e) {
            log.warn("[AI Event] emitToolStep error: {}", e.getMessage());
        }
    }

    /**
     * 向前端实时推送内嵌商品卡片列表 (products)
     */
    public static void emitProducts(List<Map<String, Object>> products) {
        SessionContext ctx = getActiveContext();
        if (ctx == null || ctx.getEventConsumer() == null || products == null || products.isEmpty()) {
            return;
        }
        ctx.setLastEmittedProducts(new ArrayList<>(products));
        try {
            Map<String, Object> payload = Map.of(
                    "event", "products",
                    "products", products
            );
            String eventLine = AI_EVENT_PREFIX + OBJECT_MAPPER.writeValueAsString(payload);
            ctx.getEventConsumer().accept(eventLine);
        } catch (Exception e) {
            log.warn("[AI Event] emitProducts error: {}", e.getMessage());
        }
    }

    /**
     * 获取当前会话最近推送的商品列表（用于语义缓存快照联动）
     */
    public static List<Map<String, Object>> getLastEmittedProducts() {
        SessionContext ctx = getActiveContext();
        return ctx != null ? ctx.getLastEmittedProducts() : null;
    }

    /**
     * 获取当前会话已经执行过的工具名称列表（供自动化评测与链路断言）
     */
    public static List<String> getExecutedTools() {
        SessionContext ctx = getActiveContext();
        return ctx != null ? new ArrayList<>(ctx.getExecutedTools()) : Collections.emptyList();
    }

    /** 限制单轮对话最多发起两次实际商品查询，防止大模型无效循环重试 */
    public static boolean tryProductSearch() {
        SessionContext ctx = getActiveContext();
        return ctx == null || ctx.getProductSearchCount().incrementAndGet() <= 2;
    }

    /**
     * 向前端实时推送待确认订单草稿卡片（Human-in-the-Loop）
     */
    public static void emitOrderConfirm(Map<String, Object> draftMap) {
        SessionContext ctx = getActiveContext();
        if (ctx == null || ctx.getEventConsumer() == null || draftMap == null || draftMap.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> payload = new java.util.HashMap<>(draftMap);
            payload.put("event", "order_confirm");
            String eventLine = AI_EVENT_PREFIX + OBJECT_MAPPER.writeValueAsString(payload);
            ctx.getEventConsumer().accept(eventLine);
        } catch (Exception e) {
            log.warn("[AI Event] emitOrderConfirm error: {}", e.getMessage());
        }
    }

    /**
     * 向前端推送本轮对话的 Token 消耗与成本统计（生产级可观测性）
     */
    public static void emitTokenUsage(int promptTokens, int completionTokens, int totalTokens, String model, double costYuan, boolean cached) {
        SessionContext ctx = getActiveContext();
        if (ctx == null || ctx.getEventConsumer() == null) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "event", "token_usage",
                    "promptTokens", promptTokens,
                    "completionTokens", completionTokens,
                    "totalTokens", totalTokens,
                    "model", model != null ? model : "qwen3.7-flash",
                    "costYuan", String.format("%.4f", costYuan),
                    "cached", cached
            );
            String eventLine = AI_EVENT_PREFIX + OBJECT_MAPPER.writeValueAsString(payload);
            ctx.getEventConsumer().accept(eventLine);
        } catch (Exception e) {
            log.warn("[AI Event] emitTokenUsage error: {}", e.getMessage());
        }
    }

    /**
     * 标记当前这轮回答（单次请求生命周期内）商品服务已触发 Sentinel 限流
     */
    public static void markProductServiceRateLimited() {
        SessionContext ctx = getActiveContext();
        if (ctx != null) {
            ctx.setProductServiceRateLimited(true);
        }
    }

    /**
     * 判断当前这轮回答内商品服务是否已处于限流状态
     */
    public static boolean isProductServiceRateLimited() {
        SessionContext ctx = getActiveContext();
        return ctx != null && ctx.isProductServiceRateLimited();
    }
}
