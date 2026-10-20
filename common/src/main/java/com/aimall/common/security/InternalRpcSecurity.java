package com.aimall.common.security;

import com.aimall.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 微服务内部 Feign RPC 通信安全校验
 */
public class InternalRpcSecurity {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    public static final String INTERNAL_TOKEN_VALUE = "aimall-internal-rpc-secret-token-2026";

    /**
     * 判断当前请求是否由合法内部 Feign RPC 客户端发出
     */
    public static boolean isInternalRpc(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String header = request.getHeader(INTERNAL_TOKEN_HEADER);
        return StringUtils.hasText(header) && INTERNAL_TOKEN_VALUE.equals(header.trim());
    }

    /**
     * 强制校验内部 Feign RPC 调用凭证，若非法直接拒绝
     */
    public static void requireInternalRpc(HttpServletRequest request) {
        if (!isInternalRpc(request)) {
            throw new BusinessException(403, "非法访问：仅允许微服务内部 RPC 调用");
        }
    }
}
