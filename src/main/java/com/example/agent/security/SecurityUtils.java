package com.example.agent.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具：从 SecurityContext 获取当前登录用户信息。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 获取当前登录用户的 userId。
     * JwtAuthenticationFilter 把 userId 存在 Authentication 的 details 里。
     * @return 当前用户 userId，未登录返回 null
     */
    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object details = auth.getDetails();
        if (details instanceof java.util.Map<?, ?> map) {
            Object userId = map.get("userId");
            if (userId instanceof Number n) return n.longValue();
            if (userId instanceof String s) return Long.parseLong(s);
        }
        return null;
    }

    /**
     * 获取当前登录用户名。
     */
    public static String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getName();
    }
}
