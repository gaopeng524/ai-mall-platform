package com.aimall.gateway.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("网关鉴权过滤器：系统的安全边界")
class JwtAuthGlobalFilterTest {

    private static final String SECRET = "unit-test-jwt-secret-0123456789-abcdefghijklmnop";

    private JwtAuthGlobalFilter filter;
    /** 被放行到下游的请求（没有放行则为 null） */
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @BeforeEach
    void setUp() {
        filter = new JwtAuthGlobalFilter();
        ReflectionTestUtils.setField(filter, "secret", SECRET);
        filter.validateSecret();
        forwarded.set(null);
    }

    private static String token(String secret, long ttlMs) {
        return Jwts.builder().subject("42").claim("username", "alice").claim("role", "USER")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        filter.filter(exchange, chain).block();
        return exchange;
    }

    private HttpHeaders forwardedHeaders() {
        assertNotNull(forwarded.get(), "请求应当被放行到下游");
        return forwarded.get().getRequest().getHeaders();
    }

    @Test
    @DisplayName("密钥未配置 / 使用公开过的默认值 / 太短：启动失败")
    void secretMustBeConfigured() {
        for (String bad : new String[]{"", "   ", "aimall-platform-secret-key-must-be-32-bytes!", "short"}) {
            JwtAuthGlobalFilter f = new JwtAuthGlobalFilter();
            ReflectionTestUtils.setField(f, "secret", bad);
            assertThrows(IllegalStateException.class, f::validateSecret, "应拒绝: '" + bad + "'");
        }
    }

    @Test
    @DisplayName("外部请求不能经网关调用内部接口（带着合法登录凭证也不行）")
    void internalEndpointsAreBlocked() {
        for (String path : new String[]{"/api/product/batch-deduct-stock", "/api/product/sync-deduct-stock",
                "/api/product/rollback-stock", "/api/order/pay-callback", "/api/user/internal/1", "/order-service/order/create"}) {
            forwarded.set(null);
            MockServerWebExchange ex = run(MockServerHttpRequest.post(path)
                    .header("Authorization", "Bearer " + token(SECRET, 60_000)));
            assertEquals(HttpStatus.FORBIDDEN, ex.getResponse().getStatusCode(), path);
            assertNull(forwarded.get(), path + " 不应被转发");
        }
    }

    @Test
    @DisplayName("匿名访问白名单路径：放行，但剥离伪造的身份头和内部调用凭证")
    void anonymousWhitelistRequestIsSanitized() {
        run(MockServerHttpRequest.get("/api/product/list")
                .header("X-User-Id", "1").header("X-User-Role", "ADMIN").header("X-Internal-Token", "forged"));
        HttpHeaders h = forwardedHeaders();
        assertNull(h.getFirst("X-User-Id"));
        assertNull(h.getFirst("X-User-Role"));
        assertNull(h.getFirst("X-Internal-Token"));
    }

    @Test
    @DisplayName("匿名访问非白名单路径 → 401")
    void anonymousProtectedRequestIsRejected() {
        MockServerWebExchange ex = run(MockServerHttpRequest.get("/api/order/list").header("X-User-Id", "1"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
        assertNull(forwarded.get());
    }

    @Test
    @DisplayName("合法凭证：身份以 JWT 为准，覆盖客户端自带的身份头，并剥离内部调用凭证")
    void validTokenInjectsIdentityFromJwt() {
        run(MockServerHttpRequest.get("/api/order/list")
                .header("Authorization", "Bearer " + token(SECRET, 60_000))
                .header("X-User-Id", "1").header("X-User-Role", "ADMIN").header("X-Internal-Token", "forged"));
        HttpHeaders h = forwardedHeaders();
        assertEquals("42", h.getFirst("X-User-Id"));
        assertEquals("alice", h.getFirst("X-Username"));
        assertEquals("USER", h.getFirst("X-User-Role"));
        assertEquals(1, h.get("X-User-Id").size());
        assertNull(h.getFirst("X-Internal-Token"));
    }

    @Test
    @DisplayName("用其它密钥（包括公开过的默认密钥）签发的凭证、过期凭证 → 401")
    void forgedOrExpiredTokenIsRejected() {
        String[] bad = {
                token("aimall-platform-secret-key-must-be-32-bytes!", 60_000),
                token("another-secret-another-secret-another-secret-123", 60_000),
                token(SECRET, -1_000)
        };
        for (String t : bad) {
            forwarded.set(null);
            MockServerWebExchange ex = run(MockServerHttpRequest.get("/api/order/list").header("Authorization", "Bearer " + t));
            assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
            assertNull(forwarded.get());
        }
    }
}
