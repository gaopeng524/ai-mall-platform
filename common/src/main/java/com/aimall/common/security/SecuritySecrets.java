package com.aimall.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 各服务共享的安全密钥：启动时校验，缺失或不合格直接启动失败。
 *
 * <ul>
 *   <li><b>JWT_SECRET</b>（或配置项 jwt.secret）：必填，至少 32 字节。仓库是公开的，所以不再提供默认值——
 *       用默认值部署意味着任何人都能伪造登录凭证；</li>
 *   <li><b>INTERNAL_RPC_TOKEN</b>（或 internal.rpc.token）：可选，服务间 Feign 调用凭证。
 *       不配置时由 JWT_SECRET 派生（HMAC-SHA256），所以只配一个 JWT_SECRET 即可，各服务算出的值一致。</li>
 * </ul>
 */
@Component
public class SecuritySecrets {

    /** 曾经写在配置文件里的默认密钥，已随仓库公开，禁止继续使用 */
    static final String LEGACY_PUBLIC_DEFAULT = "aimall-platform-secret-key-must-be-32-bytes!";
    static final int MIN_SECRET_BYTES = 32;

    private final String jwtSecret;

    public SecuritySecrets(@Value("${jwt.secret:${JWT_SECRET:}}") String jwtSecret,
                           @Value("${internal.rpc.token:${INTERNAL_RPC_TOKEN:}}") String internalRpcToken) {
        this.jwtSecret = requireStrongSecret(jwtSecret);
        InternalRpcSecurity.configure(StringUtils.hasText(internalRpcToken)
                ? internalRpcToken.trim()
                : deriveInternalToken(this.jwtSecret));
    }

    public SecretKey jwtKey() {
        return io.jsonwebtoken.security.Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public static String requireStrongSecret(String secret) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("未配置 JWT_SECRET：请设置环境变量 JWT_SECRET（至少 32 个字符的随机串，"
                    + "所有服务使用同一个值）。可用 `openssl rand -base64 48` 生成，参见 .env.example。");
        }
        String trimmed = secret.trim();
        if (LEGACY_PUBLIC_DEFAULT.equals(trimmed)) {
            throw new IllegalStateException("JWT_SECRET 不能使用仓库里公开过的默认值，请换成自己生成的随机串。");
        }
        if (trimmed.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET 太短：至少需要 " + MIN_SECRET_BYTES + " 字节。");
        }
        return trimmed;
    }

    static String deriveInternalToken(String jwtSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal("aimall-internal-rpc-v1".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("派生内部调用凭证失败", e);
        }
    }
}
