package com.example.agent.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器:每个请求解析 Authorization 头,合法则写入 SecurityContext
 * 放在 UsernamePasswordAuthenticationFilter 之前
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length());
        try {
            String username = tokenProvider.getUsername(token);
            String role = tokenProvider.getRole(token);
            Long userId = tokenProvider.getUserId(token);
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                // token 合法,构造认证对象写入上下文
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);
                // 把 userId 存到 details，SecurityUtils.getCurrentUserId() 从这里取
                java.util.Map<String, Object> details = new java.util.HashMap<>();
                details.put("userId", userId);
                details.putAll(java.util.Map.of(
                        "remoteAddress", request.getRemoteAddr(),
                        "sessionId", request.getSession(false) != null ? request.getSession(false).getId() : ""));
                auth.setDetails(details);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } catch (JwtException e) {
            // token 非法/过期:不设置认证,后续由 AuthenticationEntryPoint 兜底返回 401
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
