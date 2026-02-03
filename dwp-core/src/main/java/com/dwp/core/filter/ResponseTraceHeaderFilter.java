package com.dwp.core.filter;

import com.dwp.core.constant.HeaderConstants;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 응답 헤더에 X-Trace-Id, X-Gateway-Request-Id 추가.
 * FE 요청: DevErrorPanel 등에서 trace 정보 표시용.
 * 요청 헤더에서 추출하여 응답 헤더로 전파.
 */
public class ResponseTraceHeaderFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            String traceId = httpRequest.getHeader(HeaderConstants.X_TRACE_ID);
            String gatewayRequestId = httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID);
            if (traceId != null && !traceId.isBlank()) {
                httpResponse.setHeader(HeaderConstants.X_TRACE_ID, traceId);
            }
            if (gatewayRequestId != null && !gatewayRequestId.isBlank()) {
                httpResponse.setHeader(HeaderConstants.X_GATEWAY_REQUEST_ID, gatewayRequestId);
            }
        }
        chain.doFilter(request, response);
    }
}
