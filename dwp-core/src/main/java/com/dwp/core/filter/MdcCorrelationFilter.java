package com.dwp.core.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * MDC Correlation ID Filter (C27)
 * <p>
 * 목적: Gateway에서 전파된 Correlation ID를 MDC에 저장
 * - 모든 로그에 correlationId 자동 포함
 * - 장애 추적 용이
 * </p>
 */
public class MdcCorrelationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(MdcCorrelationFilter.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_TENANT_ID = "tenantId";
    private static final String MDC_USER_ID = "userId";
    private static final String MDC_AGENT_ID = "agentId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            try {
                // Correlation ID
                String correlationId = httpRequest.getHeader(CORRELATION_ID_HEADER);
                if (correlationId != null) {
                    MDC.put(MDC_CORRELATION_ID, correlationId);
                }

                // Tenant/User/Agent ID (추가 컨텍스트)
                String tenantId = httpRequest.getHeader("X-Tenant-ID");
                if (tenantId != null) {
                    MDC.put(MDC_TENANT_ID, tenantId);
                }

                String userId = httpRequest.getHeader("X-User-ID");
                if (userId != null) {
                    MDC.put(MDC_USER_ID, userId);
                }

                String agentId = httpRequest.getHeader("X-Agent-ID");
                if (agentId != null) {
                    MDC.put(MDC_AGENT_ID, agentId);
                }

                chain.doFilter(request, response);
            } finally {
                // MDC 정리 (메모리 누수 방지)
                MDC.remove(MDC_CORRELATION_ID);
                MDC.remove(MDC_TENANT_ID);
                MDC.remove(MDC_USER_ID);
                MDC.remove(MDC_AGENT_ID);
            }
        } else {
            chain.doFilter(request, response);
        }
    }
}
