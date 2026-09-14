package com.dwp.services.approval.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** Capture, but never rewrite, the exact bytes consumed by the existing JSON message converter. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ApprovalWorkflowQuorumBodyFilter extends OncePerRequestFilter {
    public static final int MAX_BODY = 262_144;
    static final String OVERFLOW = ApprovalWorkflowQuorumBodyFilter.class.getName() + ".overflow";
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !(request.getRequestURI().matches("/v1/tasks/[^/]+/decisions")
                || request.getRequestURI().matches("/v1/requests/[^/]+/information-response"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new ContentCachingRequestWrapper(request, MAX_BODY) {
            @Override protected void handleContentOverflow(int limit) {
                // Preserve the original converter/legacy path; typed admission must reject this truncated cache.
                request.setAttribute(OVERFLOW, Boolean.TRUE);
            }
        }, response);
    }
}
