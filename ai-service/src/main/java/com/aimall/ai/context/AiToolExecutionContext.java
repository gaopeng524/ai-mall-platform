package com.aimall.ai.context;

import com.aimall.common.context.UserContext;
import com.aimall.common.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
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
    }

    /** 线程本地上下文绑定（支持父子线程继承） */
    private static final ThreadLocal<SessionContext> THREAD_CONTEXT = new InheritableThreadLocal<>();

    /** 会话全局注册表（针对显式 sessionId 检索提供映射） */
    private static final Map<String, SessionContext> SESSION_REGISTRY = new ConcurrentHashMap<>();

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
     * 工具执行作用域隔离包装器：
     * 1. 显式从 Spring AI ToolContext 提取 sessionId，严格从 SESSION_REGISTRY 命中对应会话；
     * 2. 在执行线程中安全绑定 SessionContext、UserContext 与 TraceContext；
     * 3. 在 finally 块中强制恢复或清除线程上下文，彻底消灭 Reactor Netty / 线程池复用导致的身份串流与内存泄漏！
     */
    public static <T> T executeInToolContext(org.springframework.ai.chat.model.ToolContext toolContext, ToolAction<T> action) {
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

        try {
            Long effectiveUserId = explicitUserId;
            if (effectiveUserId == null && targetCtx != null) {
                effectiveUserId = targetCtx.getUserId();
            }

            if (targetCtx != null) {
                THREAD_CONTEXT.set(targetCtx);
            }

            if (effectiveUserId != null && effectiveUserId > 0) {
                UserContext.set(new UserContext.UserInfo(effectiveUserId, null, null));
            } else {
                UserContext.clear();
            }

            String effectiveTrace = explicitTraceId;
            if (effectiveTrace == null && targetCtx != null) {
                effectiveTrace = targetCtx.getTraceId();
            }
            if (effectiveTrace != null) {
                TraceContext.setTraceId(effectiveTrace);
            }
            return action.execute();
        } catch (Exception e) {
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
}
