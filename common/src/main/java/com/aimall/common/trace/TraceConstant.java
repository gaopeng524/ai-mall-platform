package com.aimall.common.trace;

/**
 * 分布式链路追踪全局常量规范
 */
public interface TraceConstant {

    /** HTTP 请求头 TraceId 标识 */
    String TRACE_ID_HEADER = "X-Trace-Id";

    /** Logback / SLF4J MDC 绑定的键名 */
    String TRACE_ID_MDC_KEY = "traceId";

    /** RocketMQ 消息自定义属性中的 TraceId 键名 */
    String TRACE_ID_MQ_PROP = "TRACE_ID";
}
