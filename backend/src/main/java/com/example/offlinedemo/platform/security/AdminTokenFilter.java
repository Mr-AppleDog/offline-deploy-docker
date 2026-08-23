package com.example.offlinedemo.platform.security;

import com.example.offlinedemo.platform.config.PlatformProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminTokenFilter extends OncePerRequestFilter {
    private final String adminToken;

    public AdminTokenFilter(PlatformProperties properties) {
        this.adminToken = properties.getAdminToken() == null ? "" : properties.getAdminToken();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return adminToken.isBlank() || !request.getRequestURI().startsWith("/api/platform/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String provided = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (!MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write("{\"ok\":false,\"message\":\"需要有效的管理令牌\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
