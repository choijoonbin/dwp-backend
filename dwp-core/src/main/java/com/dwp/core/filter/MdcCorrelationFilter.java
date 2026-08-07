package com.dwp.core.filter;

import com.dwp.core.constant.HeaderConstants;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import java.io.IOException;

/** Adds request identifiers to the logging context for the duration of a request. */
public class MdcCorrelationFilter implements Filter {

    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_TENANT_ID = "tenantId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            try {
                String correlationId = httpRequest.getHeader(HeaderConstants.X_CORRELATION_ID);
                if (correlationId != null) {
                    MDC.put(MDC_CORRELATION_ID, correlationId);
                }

                String tenantId = httpRequest.getHeader(HeaderConstants.X_TENANT_ID);
                if (tenantId != null) {
                    MDC.put(MDC_TENANT_ID, tenantId);
                }

                chain.doFilter(request, response);
            } finally {
                MDC.remove(MDC_CORRELATION_ID);
                MDC.remove(MDC_TENANT_ID);
            }
        } else {
            chain.doFilter(request, response);
        }
    }
}
