package com.dwp.services.platform.apihistory;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.filter.ApiHistoryServletFilter;
import com.dwp.observability.api.HttpApiHistoryPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiHistoryIngestSecurityFilter extends OncePerRequestFilter {

    private final String ingestToken;
    private final ObjectMapper objectMapper;

    public ApiHistoryIngestSecurityFilter(
            @Value("${dwp.observability.api-history.ingest-token:}") String ingestToken,
            ObjectMapper objectMapper) {
        this.ingestToken = ingestToken == null ? "" : ingestToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(ApiHistoryServletFilter.COLLECTOR_PATH);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(HttpApiHistoryPublisher.INGEST_TOKEN_HEADER);
        if (ingestToken.isBlank()) {
            write(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "API history ingest identity is not configured.");
            return;
        }
        if (!constantTimeEquals(ingestToken, supplied)) {
            write(response, ErrorCode.UNAUTHORIZED, "Trusted observability identity is required.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void write(HttpServletResponse response, ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode, message));
    }
}
