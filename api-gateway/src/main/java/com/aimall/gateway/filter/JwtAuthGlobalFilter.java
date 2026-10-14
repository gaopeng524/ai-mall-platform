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
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 全局鉴权过滤器
 * 白名单路径直接放行，其他路径验证 Token 并将用户信息写入 Header 传递给下游
 */
@Slf4j
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    @Value("${jwt.secret:aimall-platform-secret-key-must-be-32-bytes!}")
    private String secret;

    /** 白名单：无需强制登录拦截的路径（演示环境支持免密查单与快速下单） */
    private static final List<String> WHITE_LIST = List.of(
            "/api/user/register",
            "/api/user/login",
            "/api/product/**",          // 商品列表/详情公开
            "/api/order/**",            // 订单中心路由放行
            "/api/cart/**",             // 购物车中心放行
            "/api/pay/**",              // 支付中心收银台放行
            "/api/ai/**",               // 导购对话与 Agent 工具流
            "/actuator/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 白名单放行
        boolean isWhite = WHITE_LIST.stream().anyMatch(p -> pathMatcher.match(p, path));
        if (isWhite) {
            return chain.filter(exchange);
        }

        // 获取 Token
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "缺少 Authorization Header");
        }
        String token = authHeader.substring(7);

        // 验证 Token
        Claims claims;
        try {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (JwtException e) {
            return unauthorized(exchange, "Token 无效或已过期");
        }

        // 将用户信息写入 Header，传递给下游服务
        String userId = claims.getSubject();
        String username = claims.get("username", String.class);
        String role = claims.get("role", String.class);

        ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-User-Id", userId)
                .header("X-Username", username)
                .header("X-User-Role", role)
                .build();

        log.debug("JWT 鉴权通过: userId={}, path={}", userId, path);
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        log.warn("鉴权失败: {}, path={}", reason, exchange.getRequest().getURI().getPath());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }

    @Override
    public int getOrder() {
        return -100; // 最高优先级
    }
}
