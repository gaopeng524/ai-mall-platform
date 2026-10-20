package com.aimall.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 全局鉴权过滤器
 * 1. 若请求携带合法的 Authorization Bearer Token，无论是否在白名单中，均解析并向下游透传 X-User-Id / X-Username / X-User-Role；
 * 2. 若未携带 Token 或 Token 过期，白名单路径放行（支持演示环境免密体验），非白名单路径返回 401。
 */
@Slf4j
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    @Value("${jwt.secret:aimall-platform-secret-key-must-be-32-bytes!}")
    private String secret;

    /** 严禁外部网关直接调用的内部微服务 RPC 端点与 serviceId 路径（仅允许内部网络 Feign 互通） */
    private static final List<String> INTERNAL_BLOCKED_PATHS = List.of(
            "/**/product/pre-deduct-stock",
            "/**/product/sync-deduct-stock",
            "/**/product/rollback-pre-stock",
            "/**/product/deduct-stock",
            "/**/product/rollback-stock",
            "/**/order/pay-callback",
            "/*-service/**"
    );

    /** 白名单：仅公开无需登录拦截的路径（商品浏览、注册登录、公开导购对话） */
    private static final List<String> WHITE_LIST = List.of(
            "/api/user/register",
            "/api/user/login",
            "/api/product/search",
            "/api/product/list",
            "/api/product/*",           // 商品详情公开浏览
            "/api/ai/chat/stream",      // AI 智能导购流式对话（支持游客咨询体验）
            "/actuator/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 0. 安全拦截：严禁外部客户端通过网关直接调用微服务内部 RPC 接口
        boolean isInternalBlocked = INTERNAL_BLOCKED_PATHS.stream().anyMatch(p -> pathMatcher.match(p, path));
        if (isInternalBlocked) {
            log.warn("[网关安全防御拦截] 外部请求试图直接调用微服务内部 RPC 端点: path={}, ip={}",
                    path, request.getRemoteAddress());
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }

        boolean isWhite = WHITE_LIST.stream().anyMatch(p -> pathMatcher.match(p, path));

        // 1. 优先尝试解析 Authorization Header（确保已登录用户在访问白名单路由时也能正确透传真实身份）
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (StringUtils.hasText(token)) {
                try {
                    SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
                    Claims claims = Jwts.parser().verifyWith(key).build()
                            .parseSignedClaims(token).getPayload();

                    String userId = claims.getSubject();
                    String username = claims.get("username", String.class);
                    String role = claims.get("role", String.class);

                    // 剥离客户端伪造的 X-User-* 及内部 RPC 凭据头，强制注入经由 JWT 验签合法的用户身份
                    ServerHttpRequest mutatedRequest = request.mutate()
                            .headers(headers -> {
                                headers.remove("X-User-Id");
                                headers.remove("X-Username");
                                headers.remove("X-User-Role");
                                headers.remove("X-Internal-Token");
                            })
                            .header("X-User-Id", userId != null ? userId : "")
                            .header("X-Username", username != null ? username : "")
                            .header("X-User-Role", role != null ? role : "")
                            .build();

                    log.debug("JWT 鉴权通过并透传身份: userId={}, username={}, path={}", userId, username, path);
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                } catch (JwtException e) {
                    if (!isWhite) {
                        return unauthorized(exchange, "Token 无效或已过期");
                    }
                    log.debug("白名单路径携带了无效或过期 Token，降级为匿名放行: path={}, reason={}", path, e.getMessage());
                }
            }
        }

        // 2. 未携带有效 Token 时：
        // 白名单路径必须彻底剥离客户端请求中可能伪造的 X-User-* 头，杜绝匿名冒充！
        if (isWhite) {
            ServerHttpRequest sanitizedRequest = request.mutate()
                    .headers(headers -> {
                        headers.remove("X-User-Id");
                        headers.remove("X-Username");
                        headers.remove("X-User-Role");
                        headers.remove("X-Internal-Token");
                    })
                    .build();
            return chain.filter(exchange.mutate().request(sanitizedRequest).build());
        }

        return unauthorized(exchange, "缺少 Authorization Header 或 Token 无效");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        log.warn("鉴权失败: {}, path={}", reason, exchange.getRequest().getURI().getPath());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }

    @Override
    public int getOrder() {
        return -100; // 高优先级，位于 TraceIdGlobalFilter (-300) 之后
    }
}
