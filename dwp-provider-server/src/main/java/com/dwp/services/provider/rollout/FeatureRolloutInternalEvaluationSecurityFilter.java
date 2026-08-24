package com.dwp.services.provider.rollout;

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
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FeatureRolloutInternalEvaluationSecurityFilter extends OncePerRequestFilter {

    public static final String PATH = "/internal/provider/v1/feature-rollouts/evaluate";
    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    static final String GATEWAY_IDENTITY = "dwp-gateway";

    private final String serviceToken;
    private final ObjectMapper objectMapper;

    public FeatureRolloutInternalEvaluationSecurityFilter(
            @Value("${dwp.provider.service-token:}") String serviceToken,
            ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())) {
            error(response, ErrorCode.INVALID_INPUT_VALUE, "POST is required");
            return;
        }
        if (serviceToken.isBlank()) {
            error(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Rollout evaluation service identity is not configured");
            return;
        }
        if (!constantTimeEquals(serviceToken, request.getHeader(SERVICE_TOKEN_HEADER))
                || !GATEWAY_IDENTITY.equals(request.getHeader(SERVICE_IDENTITY_HEADER))) {
            error(response, ErrorCode.UNAUTHORIZED,
                    "A trusted gateway service identity is required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void error(
            HttpServletResponse response,
            ErrorCode code,
            String message) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
