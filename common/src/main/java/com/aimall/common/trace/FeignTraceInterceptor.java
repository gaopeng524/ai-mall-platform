package com.aimall.common.trace;

import com.aimall.common.context.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenFeign 跨服务 RPC 调用全链路透传拦截器：
 * 1. 自动从当前线程 MDC 提取 TraceId 注入 X-Trace-Id Header；
 * 2. 自动从当前线程 UserContext 提取用户信息注入 X-User-Id, X-Username, X-User-Role；
 * 实现跨微服务零侵入的全链路追踪与身份传递。
 */
@Component
@ConditionalOnClass(RequestInterceptor.class)
public class FeignTraceInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 1. 全链路透传 TraceId
        String traceId = TraceContext.getTraceId();
        if (StringUtils.hasText(traceId)) {
            template.header(TraceConstant.TRACE_ID_HEADER, traceId);
        }

        // 2. 全链路透传用户上下文
        UserContext.UserInfo userInfo = UserContext.get();
        if (userInfo != null) {
            if (userInfo.userId() != null) {
                template.header("X-User-Id", String.valueOf(userInfo.userId()));
            }
            if (StringUtils.hasText(userInfo.username())) {
                template.header("X-Username", userInfo.username());
            }
            if (StringUtils.hasText(userInfo.role())) {
                template.header("X-User-Role", userInfo.role());
            }
        }
    }
}
