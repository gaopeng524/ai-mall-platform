package com.aimall.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("安全密钥：必须显式配置，内部调用凭证不再写死")
class SecuritySecretsTest {

    private static final String SECRET = "unit-test-jwt-secret-0123456789-abcdefghijklmnop";

    @AfterEach
    void reset() {
        InternalRpcSecurity.configure(null);
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (token != null) {
            req.addHeader(InternalRpcSecurity.INTERNAL_TOKEN_HEADER, token);
        }
        return req;
    }

    @Test
    @DisplayName("未配置、使用公开过的默认值、太短：启动失败")
    void rejectsMissingLegacyOrWeakSecret() {
        assertThrows(IllegalStateException.class, () -> new SecuritySecrets("", ""));
        assertThrows(IllegalStateException.class, () -> new SecuritySecrets(null, ""));
        assertThrows(IllegalStateException.class, () -> new SecuritySecrets("   ", ""));
        assertThrows(IllegalStateException.class,
                () -> new SecuritySecrets(SecuritySecrets.LEGACY_PUBLIC_DEFAULT, ""));
        assertThrows(IllegalStateException.class, () -> new SecuritySecrets("too-short", ""));
    }

    @Test
    @DisplayName("只配置 JWT_SECRET：内部调用凭证由它派生，各服务算出的值一致")
    void derivesInternalTokenFromJwtSecret() {
        new SecuritySecrets(SECRET, "");
        String token = InternalRpcSecurity.currentToken();
        assertNotNull(token);
        assertNotEquals(SECRET, token, "派生值不能等于 JWT 密钥本身");
        assertEquals(token, SecuritySecrets.deriveInternalToken(SECRET), "同一密钥派生结果稳定");
        assertNotEquals(token, SecuritySecrets.deriveInternalToken(SECRET + "x"));

        assertTrue(InternalRpcSecurity.isInternalRpc(requestWithToken(token)));
        assertFalse(InternalRpcSecurity.isInternalRpc(requestWithToken("aimall-internal-rpc-secret-token-2026")),
                "旧的源码常量不再有效");
        assertFalse(InternalRpcSecurity.isInternalRpc(requestWithToken(null)));
    }

    @Test
    @DisplayName("显式配置 INTERNAL_RPC_TOKEN 时优先使用")
    void explicitInternalTokenWins() {
        new SecuritySecrets(SECRET, "my-internal-token");
        assertTrue(InternalRpcSecurity.isInternalRpc(requestWithToken("my-internal-token")));
        assertFalse(InternalRpcSecurity.isInternalRpc(requestWithToken(SecuritySecrets.deriveInternalToken(SECRET))));
    }

    @Test
    @DisplayName("凭证尚未注入时，任何请求都不算内部调用")
    void nothingIsInternalBeforeConfiguration() {
        assertNull(InternalRpcSecurity.currentToken());
        assertFalse(InternalRpcSecurity.isInternalRpc(requestWithToken("anything")));
        assertThrows(com.aimall.common.exception.BusinessException.class,
                () -> InternalRpcSecurity.requireInternalRpc(requestWithToken("anything")));
    }

    private final org.springframework.boot.test.context.runner.ApplicationContextRunner contextRunner =
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withBean(SecuritySecrets.class).withBean(JwtUtil.class);

    @Test
    @DisplayName("Spring 容器：没有 JWT_SECRET 启动失败，错误信息指明怎么配置")
    void contextFailsWithoutSecret() {
        // 显式置空：开发机和 CI 上通常设置了 JWT_SECRET 环境变量，不能让它影响这个用例
        contextRunner.withPropertyValues("jwt.secret=", "JWT_SECRET=").run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ctx.getStartupFailure());
            assertInstanceOf(IllegalStateException.class, root);
            assertTrue(root.getMessage().contains("未配置 JWT_SECRET"), root.getMessage());
        });
    }

    @Test
    @DisplayName("Spring 容器：通过环境变量名 JWT_SECRET 或配置项 jwt.secret 都能启动并签发凭证")
    void contextStartsWithSecret() {
        for (String property : new String[]{"JWT_SECRET=" + SECRET, "jwt.secret=" + SECRET}) {
            contextRunner.withPropertyValues(property).run(ctx -> {
                assertNull(ctx.getStartupFailure());
                String token = ctx.getBean(JwtUtil.class).generateToken(7L, "alice", "USER");
                assertEquals(3, token.split("\\.").length);
                assertNotNull(InternalRpcSecurity.currentToken());
            });
        }
    }
}
