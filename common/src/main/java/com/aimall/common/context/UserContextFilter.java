package com.aimall.common.context;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * 用户上下文全局过滤器：
 * 1. 自动从网关转发的请求头 (X-User-Id, X-Username, X-User-Role) 中提取用户信息，
 *    写入当前线程 UserContext (ThreadLocal) 中；
 * 2. 请求完成后在 finally 中强制执行 UserContext.clear()，彻底杜绝 Tomcat 线程池污染与内存泄漏。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UserContextFilter implements Filter {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USERNAME_HEADER = "X-Username";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        if (servletRequest instanceof HttpServletRequest request) {
            String userIdStr = request.getHeader(USER_ID_HEADER);
            String username = request.getHeader(USERNAME_HEADER);
            String role = request.getHeader(USER_ROLE_HEADER);

            if (StringUtils.hasText(userIdStr)) {
                try {
                    Long userId = Long.valueOf(userIdStr);
                    UserContext.set(new UserContext.UserInfo(userId, username, role));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        try {
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            UserContext.clear();
        }
    }
}
