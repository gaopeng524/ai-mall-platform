package com.aimall.ai.context;

import com.aimall.common.context.UserContext;
import com.aimall.common.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

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
    }

    /** 线程本地上下文绑定（支持父子线程继承） */
    private static final ThreadLocal<SessionContext> THREAD_CONTEXT = new InheritableThreadLocal<>();

    /** 会话全局注册表（针对跨线程调度但未继承 ThreadLocal 的场景提供基于最近会话的兜底） */
    private static final Map<String, SessionContext> SESSION_REGISTRY = new ConcurrentHashMap<>();
    private static volatile SessionContext LATEST_SESSION = null;

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
            SESSION_REGISTRY.put(sessionId, ctx);
        }
        LATEST_SESSION = ctx;
        if (userId != null) {
            UserContext.set(new UserContext.UserInfo(userId, null, null));
        }
        if (traceId != null) {
            TraceContext.setTraceId(traceId);
        }
    }

    /**
     * 清除会话上下文
     */
    public static void clear(String sessionId) {
        THREAD_CONTEXT.remove();
        if (sessionId != null) {
            SESSION_REGISTRY.remove(sessionId);
        }
        if (LATEST_SESSION != null && sessionId != null && sessionId.equals(LATEST_SESSION.getSessionId())) {
            LATEST_SESSION = null;
        }
        UserContext.clear();
        TraceContext.clear();
    }

    /**
     * 获取当前活动上下文
     */
    public static SessionContext getActiveContext() {
        SessionContext ctx = THREAD_CONTEXT.get();
        if (ctx != null) {
            return ctx;
        }
        return LATEST_SESSION;
    }

    /**
     * 准备工具执行线程环境并解析有效 UserId
     */
    public static Long prepareToolThreadAndResolveUserId(Long fallbackUserId) {
        SessionContext ctx = getActiveContext();
        Long uid = null;
        if (ctx != null) {
            uid = ctx.getUserId();
            if (ctx.getTraceId() != null) {
                TraceContext.setTraceId(ctx.getTraceId());
            }
        }
        if (uid == null || uid <= 0) {
            uid = UserContext.getUserId();
        }
        if (uid == null || uid <= 0) {
            uid = fallbackUserId != null && fallbackUserId > 0 ? fallbackUserId : 2L;
        }
        UserContext.set(new UserContext.UserInfo(uid, null, null));
        return uid;
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
}
