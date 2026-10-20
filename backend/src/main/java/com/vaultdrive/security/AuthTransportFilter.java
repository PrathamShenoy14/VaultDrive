package com.vaultdrive.security;

import com.vaultdrive.auth.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;

/** Cookie credentials are confined to endpoints with an explicit CSRF contract. */
public class AuthTransportFilter extends OncePerRequestFilter {
    public static final String CSRF_HEADER = "X-VaultDrive-CSRF";
    private static final Set<String> COOKIE_ENDPOINTS = Set.of(
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path.startsWith("/api/v1/auth/") || path.equals("/api/v1/users/me")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        if ("POST".equals(request.getMethod()) && COOKIE_ENDPOINTS.contains(path)) {
            if (request.getQueryString() != null) {
                com.vaultdrive.common.exception.ApiError.write(response, 400, "Invalid request");
                return;
            }
            String header = path.endsWith("/login") ? "X-VaultDrive-Client" : CSRF_HEADER;
            var values = Collections.list(request.getHeaders(header));
            boolean valid = values.size() == 1 && (path.endsWith("/login")
                    ? "1".equals(values.getFirst()) : SessionService.validSecret(values.getFirst()));
            if (!valid) {
                com.vaultdrive.common.exception.ApiError.write(response, 403, "Permission denied");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
