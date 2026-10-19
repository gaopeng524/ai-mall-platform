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

    public static String getUserRole() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.role() : null;
    }

    /**
     * 判断当前上下文用户是否具备管理员权限（兼容 ADMIN 与 ROLE_ADMIN）
     */
    public static boolean isAdmin() {
        return isAdmin(getUserRole());
    }

    /**
     * 判断指定角色是否具备管理员权限（兼容 ADMIN 与 ROLE_ADMIN）
     */
    public static boolean isAdmin(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String clean = role.trim().toUpperCase();
        return "ADMIN".equals(clean) || "ROLE_ADMIN".equals(clean);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public record UserInfo(Long userId, String username, String role) {
    }
}
