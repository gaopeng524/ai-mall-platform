package com.aimall.common.trace;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Web 请求入口 TraceId 拦截过滤器：
 * 1. 优先提取请求头中的 X-Trace-Id（实现网关与跨微服务链路延续）；
 * 2. 若无则自动生成紧凑 TraceId 并注入 MDC 与响应头回传客户端；
 * 3. 请求结束在 finally 中强制执行 MDC.clear()，彻底杜绝 Tomcat 线程池污染与内存泄漏。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        if (servletRequest instanceof HttpServletRequest request && servletResponse instanceof HttpServletResponse response) {
            String incomingTraceId = request.getHeader(TraceConstant.TRACE_ID_HEADER);
            String traceId = TraceContext.initOrGetTraceId(incomingTraceId);
            // 将 TraceId 回填至 Response Header，便于前端/调用方排错
            response.setHeader(TraceConstant.TRACE_ID_HEADER, traceId);
        }

        try {
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            TraceContext.clear();
        }
    }
}
