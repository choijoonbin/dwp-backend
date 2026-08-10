package com.dwp.core.filter;

import com.dwp.core.constant.HeaderConstants;
import com.dwp.observability.api.ApiHistoryAttributes;
import com.dwp.observability.api.TraceContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

/** Adds request identifiers to the logging context for the duration of a request. */
public class MdcCorrelationFilter implements Filter {

    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_TENANT_ID = "tenantId";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_SPAN_ID = "spanId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {
            try {
                String correlationId = correlationId(httpRequest);
                TraceContext traceContext = TraceContext.childOf(
                        httpRequest.getHeader(HeaderConstants.TRACE_PARENT));

                httpRequest.setAttribute(ApiHistoryAttributes.CORRELATION_ID, correlationId);
                httpRequest.setAttribute(ApiHistoryAttributes.TRACE_ID, traceContext.traceId());
                httpRequest.setAttribute(ApiHistoryAttributes.SPAN_ID, traceContext.spanId());
                httpRequest.setAttribute(
                        ApiHistoryAttributes.PARENT_SPAN_ID, traceContext.parentSpanId());
                httpResponse.setHeader(HeaderConstants.X_CORRELATION_ID, correlationId);

                MDC.put(MDC_CORRELATION_ID, correlationId);
                MDC.put(MDC_TRACE_ID, traceContext.traceId());
                MDC.put(MDC_SPAN_ID, traceContext.spanId());

                String tenantId = firstNonBlank(
                        httpRequest.getHeader(HeaderConstants.X_DWP_TENANT_ID),
                        httpRequest.getHeader(HeaderConstants.X_TENANT_ID));
                if (tenantId != null) {
                    MDC.put(MDC_TENANT_ID, tenantId);
                }

                chain.doFilter(request, response);
            } finally {
                MDC.remove(MDC_CORRELATION_ID);
                MDC.remove(MDC_TENANT_ID);
                MDC.remove(MDC_TRACE_ID);
                MDC.remove(MDC_SPAN_ID);
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    private static String correlationId(HttpServletRequest request) {
        String supplied = request.getHeader(HeaderConstants.X_CORRELATION_ID);
        if (supplied == null || supplied.isBlank() || supplied.length() > 128) {
            return UUID.randomUUID().toString();
        }
        return supplied.replaceAll("[^A-Za-z0-9._:-]", "_");
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
