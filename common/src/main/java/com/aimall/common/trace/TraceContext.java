package com.aimall.common.trace;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * MDC 链路上下文管理工具类（轻量级 ThreadLocal 包装）
 */
public class TraceContext {

    /**
     * 获取当前线程的 TraceId
     */
    public static String getTraceId() {
        return MDC.get(TraceConstant.TRACE_ID_MDC_KEY);
    }

    /**
     * 设置当前线程的 TraceId
     */
    public static void setTraceId(String traceId) {
        if (StringUtils.hasText(traceId)) {
            MDC.put(TraceConstant.TRACE_ID_MDC_KEY, traceId);
        }
    }

    /**
     * 优先继承现有 TraceId，若不存在则生成新的 16 位紧凑 TraceId
     */
    public static String initOrGetTraceId(String candidateTraceId) {
        String traceId = StringUtils.hasText(candidateTraceId) ? candidateTraceId : generateTraceId();
        setTraceId(traceId);
        return traceId;
    }

    /**
     * 生成紧凑唯一的 16 位 16 进制 TraceId
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 清理当前线程 MDC（杜绝线程池污染与内存泄漏）
     */
    public static void clear() {
        MDC.remove(TraceConstant.TRACE_ID_MDC_KEY);
    }
}
