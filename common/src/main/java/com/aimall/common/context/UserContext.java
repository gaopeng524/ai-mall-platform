package com.aimall.common.context;

/**
 * 用户上下文（ThreadLocal，在网关解析 JWT 后传入 Header，各服务从 Header 解析写入）
 */
public class UserContext {

    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    public static void set(UserInfo userInfo) {
        CONTEXT.set(userInfo);
    }

    public static UserInfo get() {
        return CONTEXT.get();
    }

    public static Long getUserId() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.userId() : null;
    }

    /**
     * 获取当前登录用户 ID，若未登录或未传递则返回指定的兜底默认值 (如演示用户 ID 2L)
     */
    public static Long getUserIdOrDefault(Long defaultUserId) {
        Long uid = getUserId();
        return uid != null ? uid : defaultUserId;
    }

    public static String getUsername() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.username() : null;
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public record UserInfo(Long userId, String username, String role) {
    }
}
