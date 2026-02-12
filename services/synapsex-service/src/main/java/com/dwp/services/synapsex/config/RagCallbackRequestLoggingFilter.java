package com.dwp.services.synapsex.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Aura RAG 콜백 요청 추적용 로그.
 * POST /synapse/rag/chunks, /synapse/rag/status 요청이 SynapseX에 도달했을 때 즉시 로그하여
 * 400/502 원인 추적 시 "Gateway에서 막힘" vs "SynapseX 진입 후 실패" 구분 가능하게 함.
 */
@Slf4j
@Component
@Order(-300)
public class RagCallbackRequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("POST".equalsIgnoreCase(request.getMethod())
                && (path != null && (path.contains("/synapse/rag/chunks") || path.contains("/synapse/rag/status")))) {
            int contentLength = request.getContentLength();
            log.info("RAG callback request received: method=POST path={} contentLength={}",
                    path, contentLength >= 0 ? contentLength : "unknown");
        }
        filterChain.doFilter(request, response);
    }
}
