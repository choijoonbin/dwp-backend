package com.dwp.services.provider.rollout;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

/** Fail-closed transport boundary for the local synthetic rollout feed. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnProperty(
        name = "dwp.provider.synthetic-product-surface-bootstrap.enabled",
        havingValue = "true")
public class LocalSyntheticProductSurfaceBootstrapFilter extends OncePerRequestFilter {
    static final String PATH = "/internal/synthetic/v1/product-surface/bootstrap";
    static final String TOKEN_HEADER = "X-DWP-Synthetic-Rollout-Token";
    public static final String AUTHORIZED_REQUEST_ATTRIBUTE =
            LocalSyntheticProductSurfaceBootstrapFilter.class.getName() + ".authorized";

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    public LocalSyntheticProductSurfaceBootstrapFilter(
            @Value("${dwp.environment:}") String environment,
            @Value("${dwp.provider.synthetic-product-surface-bootstrap.token:}") String token,
            ObjectMapper objectMapper) {
        requireLocalConfiguration(environment, token);
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
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
            FilterChain chain) throws ServletException, IOException {
        var tokenHeaders = Collections.list(request.getHeaders(TOKEN_HEADER));
        String actual = tokenHeaders.size() == 1 ? tokenHeaders.getFirst() : null;
        if (!"POST".equals(request.getMethod())
                || !PATH.equals(request.getRequestURI())
                || !loopback(request.getRemoteAddr())
                || actual == null
                || !actual.equals(actual.trim())
                || !MessageDigest.isEqual(
                        expectedToken, actual.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                    response.getOutputStream(),
                    ApiResponse.error(
                            ErrorCode.UNAUTHORIZED,
                            "Local synthetic rollout identity is required."));
            return;
        }
        request.setAttribute(AUTHORIZED_REQUEST_ATTRIBUTE, Boolean.TRUE);
        try {
            chain.doFilter(request, response);
        } finally {
            request.removeAttribute(AUTHORIZED_REQUEST_ATTRIBUTE);
        }
    }

    static void requireLocalConfiguration(String environment, String token) {
        if (!"local".equals(environment)) {
            throw new IllegalStateException(
                    "Synthetic rollout bootstrap is available only in the local environment.");
        }
        if (token == null || token.length() < 32 || !token.equals(token.trim())) {
            throw new IllegalStateException(
                    "Synthetic rollout bootstrap requires an unpadded token of at least 32 characters.");
        }
    }

    static boolean loopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException error) {
            return false;
        }
    }
}
