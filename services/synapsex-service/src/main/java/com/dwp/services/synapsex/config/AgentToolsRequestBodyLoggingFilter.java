package com.dwp.services.synapsex.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * POST /synapse/agent-tools/actions/simulate, /propose 수신 본문 로깅.
 * 검증 오류(400) 원인 파악용 — 실제 수신한 JSON 키/값 확인.
 */
@Slf4j
@Component
@Order(-50)
public class AgentToolsRequestBodyLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_LOG = 1_000;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            return true;
        }
        String path = request.getRequestURI();
        return !path.endsWith("/synapse/agent-tools/actions/simulate")
                && !path.endsWith("/synapse/agent-tools/actions/propose");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            byte[] buf = wrapped.getContentAsByteArray();
            if (buf != null && buf.length > 0) {
                String body = new String(buf, StandardCharsets.UTF_8);
                if (body.length() > MAX_BODY_LOG) {
                    body = body.substring(0, MAX_BODY_LOG) + "...(truncated)";
                }
                log.info("Agent-tools request body received: path={} status={} length={} body={}",
                        request.getRequestURI(), response.getStatus(), buf.length, body);
            } else {
                log.warn("Agent-tools request body empty or not cached: path={} status={} contentLength={}",
                        request.getRequestURI(), response.getStatus(), request.getContentLength());
            }
        }
    }
}
