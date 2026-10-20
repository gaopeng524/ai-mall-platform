package com.aimall.common.trace;

import com.aimall.common.context.UserContext;
import com.aimall.common.security.InternalRpcSecurity;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenFeign 跨服务 RPC 调用全链路透传拦截器：
 * 1. 自动从当前线程 MDC 提取 TraceId 注入 X-Trace-Id Header；
 * 2. 自动从当前线程 UserContext 提取用户信息注入 X-User-Id, X-Username, X-User-Role；
 * 3. 自动注入 X-Internal-Token 内部服务调用凭证，杜绝外部伪造与越权；
 * 实现跨微服务零侵入的全链路追踪与身份传递。
 */
@Component
@ConditionalOnClass(RequestInterceptor.class)
public class FeignTraceInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 0. 注入微服务内部 Feign RPC 专有凭证
        template.header(InternalRpcSecurity.INTERNAL_TOKEN_HEADER, InternalRpcSecurity.INTERNAL_TOKEN_VALUE);

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
