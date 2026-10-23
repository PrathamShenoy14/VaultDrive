package com.vaultdrive.operations;

import com.vaultdrive.common.exception.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed in the authenticated security chain before bearer database validation. */
public class AdmissionFilter extends OncePerRequestFilter {
    private final RequestAdmission admission;
    public AdmissionFilter(RequestAdmission admission) { this.admission = admission; }
    static RequestAdmission.Lane lane(String path) {
        if (!path.startsWith("/api/v1/") || path.startsWith("/api/v1/health")) return null;
        if (path.startsWith("/api/v1/auth/")) return RequestAdmission.Lane.AUTHENTICATION;
        if (path.startsWith("/api/v1/upload-sessions")) return RequestAdmission.Lane.MULTIPART;
        if (path.matches("/api/v1/files/[^/]+/download")) return RequestAdmission.Lane.DOWNLOADS;
        return RequestAdmission.Lane.METADATA;
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var lane = lane(req.getRequestURI().substring(req.getContextPath().length()));
        if (lane == null) { chain.doFilter(req, res); return; }
        var permit = admission.acquire(lane);
        if (permit == null) {
            res.setHeader("Retry-After", "1"); res.setHeader("Cache-Control", "no-store");
            ApiError.write(res, 429, "Request capacity exceeded"); return;
        }
        var lease = new AdmissionLease(permit);
        req.setAttribute(AdmissionLease.ATTRIBUTE, lease);
        try { chain.doFilter(req, res); }
        finally {
            if (!req.isAsyncStarted()) lease.finish();
            else {
                try {
                    req.getAsyncContext().addListener(new AsyncListener() {
                        public void onComplete(AsyncEvent event) { lease.finish(); }
                        public void onTimeout(AsyncEvent event) { lease.finish(); }
                        public void onError(AsyncEvent event) { lease.finish(); }
                        public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                    });
                } catch (IllegalStateException alreadyCompleted) { lease.finish(); }
            }
        }
    }
}
