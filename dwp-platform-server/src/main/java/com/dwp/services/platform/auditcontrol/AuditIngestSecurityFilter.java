package com.dwp.services.platform.auditcontrol;

import com.dwp.audit.HttpAuditEventPublisher;
import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
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
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AuditIngestSecurityFilter extends OncePerRequestFilter {

    public static final String COLLECTOR_PATH = "/internal/audit/events";

    private final String ingestToken;
    private final ObjectMapper objectMapper;

    public AuditIngestSecurityFilter(
            @Value("${dwp.audit.ingest-token:}") String ingestToken,
            ObjectMapper objectMapper) {
        this.ingestToken = ingestToken == null ? "" : ingestToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(COLLECTOR_PATH);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HttpAuditEventPublisher.INGEST_TOKEN_HEADER);
        if (ingestToken.isBlank()) {
            write(response, ErrorCode.EXTERNAL_SERVICE_ERROR, "Audit ingest identity is not configured.");
            return;
        }
        if (supplied == null || !MessageDigest.isEqual(
                ingestToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            write(response, ErrorCode.UNAUTHORIZED, "Trusted audit source identity is required.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
