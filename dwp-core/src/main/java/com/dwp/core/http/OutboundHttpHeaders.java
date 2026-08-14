package com.dwp.core.http;

import com.dwp.core.constant.HeaderConstants;
import com.dwp.observability.api.ApiHistoryAttributes;
import com.dwp.observability.api.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/**
 * Propagates request-scoped observability context on trusted outbound service calls.
 */
public final class OutboundHttpHeaders {

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-f]{16}$");

    private OutboundHttpHeaders() {
    }

    public static void propagateObservability(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes servletRequestAttributes) {
            propagateObservability(servletRequestAttributes.getRequest(), headers);
        }
    }

    public static void propagateObservability(HttpServletRequest request, HttpHeaders headers) {
        String correlationId = header(request, HeaderConstants.X_CORRELATION_ID);
        if (correlationId != null) {
            headers.set(HeaderConstants.X_CORRELATION_ID, correlationId);
        }

        String traceParent = traceParent(request);
        if (traceParent != null) {
            headers.set(HeaderConstants.TRACE_PARENT, TraceContext.childOf(traceParent).traceParent());
        }

        String traceState = header(request, HeaderConstants.TRACE_STATE);
        if (traceState != null) {
            headers.set(HeaderConstants.TRACE_STATE, traceState);
        }
    }

    private static String traceParent(HttpServletRequest request) {
        String supplied = header(request, HeaderConstants.TRACE_PARENT);
        if (supplied != null) return supplied;

        Object traceId = request.getAttribute(ApiHistoryAttributes.TRACE_ID);
        Object spanId = request.getAttribute(ApiHistoryAttributes.SPAN_ID);
        if (traceId instanceof String trace
                && spanId instanceof String span
                && TRACE_ID.matcher(trace).matches()
                && SPAN_ID.matcher(span).matches()) {
            return "00-" + trace + "-" + span + "-01";
        }
        return null;
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
