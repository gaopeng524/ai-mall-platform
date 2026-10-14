package com.aimall.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关全局 TraceId 过滤器（分布式调用链发源地）：
 * 1. 处于最高优先级（Order = -300），先于鉴权与业务路由执行；
 * 2. 请求入口生成/继承 TraceId，写入下游请求头 X-Trace-Id 并回填响应头；
 * 3. 挂载日志 MDC，并在响应完成后执行清理。
 */
@Slf4j
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        final String finalTraceId = traceId;

        // 向下游微服务传递 X-Trace-Id
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(TRACE_ID_HEADER, finalTraceId)
                .build();

        // 响应头添加 X-Trace-Id 回传客户端
        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, finalTraceId);

        // 设置当前线程 MDC
        MDC.put(TRACE_ID_MDC_KEY, finalTraceId);

        log.info("[网关路由入口] 收到请求: {} {}, 分配 TraceId: {}", request.getMethod(), request.getURI().getPath(), finalTraceId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFinally(signalType -> MDC.remove(TRACE_ID_MDC_KEY));
    }

    @Override
    public int getOrder() {
        return -300; // 最优先执行，确保后续所有 Filter 均能感知 TraceId
    }
}
