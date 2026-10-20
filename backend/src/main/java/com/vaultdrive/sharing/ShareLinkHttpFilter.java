package com.vaultdrive.sharing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ShareLinkHttpFilter extends OncePerRequestFilter {
    public static final String TOKEN_HEADER = "X-VaultDrive-Share-Token";
    private final ShareLinkAdmission admission;

    public ShareLinkHttpFilter(ShareLinkAdmission admission) { this.admission = admission; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean publicLink = path.startsWith("/api/v1/public/share-links");
        if (publicLink || path.matches("/api/v1/files/[^/]+/share-links(?:/[^/]+)?")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("X-Content-Type-Options", "nosniff");
        }
        if (!publicLink) { chain.doFilter(request, response); return; }
        if (!admission.takeRequestToken()) { error(response, 429); return; }
        try (var permit = admission.request()) {
            if (permit == null) { error(response, 429); return; }
            // Reject query strings entirely; capabilities are accepted only through the dedicated header.
            if (!request.getMethod().equals("GET") || request.getQueryString() != null
                    || !path.matches("/api/v1/public/share-links/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?:/download)?")
                    || Collections.list(request.getHeaders(TOKEN_HEADER)).size() != 1) {
                error(response, 404); return;
            }
            chain.doFilter(request, response);
        }
    }

    static void error(HttpServletResponse response, int status) throws IOException {
        if (status == 429) response.setHeader("Retry-After", "1");
        com.vaultdrive.common.exception.ApiError.write(response, status, status == 404
                ? "Share link not found" : "Public share link capacity exceeded");
    }
}
