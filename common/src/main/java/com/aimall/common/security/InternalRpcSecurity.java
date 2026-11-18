package com.aimall.common.security;

import com.aimall.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 微服务内部 Feign RPC 通信安全校验
 */
public class InternalRpcSecurity {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /**
     * 内部调用凭证。不再是写死在源码里的常量：服务启动时由 {@link SecuritySecrets} 注入
     * （来自 INTERNAL_RPC_TOKEN，或由 JWT_SECRET 派生）。未注入时任何请求都不被视为内部调用。
     */
    private static volatile String internalToken;

    static void configure(String token) {
        internalToken = token;
    }

    /** 供 Feign 拦截器给出站请求带上凭证；尚未配置时返回 null */
    public static String currentToken() {
        return internalToken;
    }

    /**
     * 判断当前请求是否由合法内部 Feign RPC 客户端发出
     */
    public static boolean isInternalRpc(HttpServletRequest request) {
        String expected = internalToken;
        if (request == null || expected == null) {
            return false;
        }
        String header = request.getHeader(INTERNAL_TOKEN_HEADER);
        return StringUtils.hasText(header) && java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                header.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
