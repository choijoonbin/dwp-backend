package com.dwp.services.people.integration;

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
import java.util.List;

/** Fail-closed transport boundary for the disposable People workforce feed. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnProperty(
        name = "dwp.hris.people-workforce.synthetic-bootstrap.enabled",
        havingValue = "true")
final class LocalSyntheticPeopleWorkforceBootstrapFilter extends OncePerRequestFilter {

    static final String PATH = LocalSyntheticPeopleWorkforceBootstrapPaths.PATH;
    static final String TOKEN_HEADER = "X-DWP-Synthetic-People-Bootstrap-Token";

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    LocalSyntheticPeopleWorkforceBootstrapFilter(
            @Value("${DWP_ENVIRONMENT:}") String environment,
            @Value("${dwp.hris.people-workforce.synthetic-bootstrap.token:}") String token,
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
        String token = exactHeader(request, TOKEN_HEADER);
        if (!"POST".equals(request.getMethod())
                || !loopback(request.getRemoteAddr())
                || token == null
                || !MessageDigest.isEqual(
                        expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                    response.getOutputStream(),
                    ApiResponse.error(
                            ErrorCode.UNAUTHORIZED,
                            "Local synthetic People bootstrap identity is required."));
            return;
        }
        request.setAttribute(
                LocalSyntheticPeopleWorkforceBootstrapPaths.AUTHORIZED_REQUEST_ATTRIBUTE,
                Boolean.TRUE);
        try {
            chain.doFilter(request, response);
        } finally {
            request.removeAttribute(
                    LocalSyntheticPeopleWorkforceBootstrapPaths.AUTHORIZED_REQUEST_ATTRIBUTE);
        }
    }

    static void requireLocalConfiguration(String environment, String token) {
        if (!"local".equals(environment)) {
            throw new IllegalStateException(
                    "Synthetic People bootstrap is available only in the local environment.");
        }
        if (token == null || token.length() < 32 || !token.equals(token.trim())) {
            throw new IllegalStateException(
                    "Synthetic People bootstrap requires an unpadded token of at least 32 characters.");
        }
    }

    static boolean loopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException error) {
            return false;
        }
    }

    private static String exactHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.getFirst() == null) return null;
        String value = values.getFirst();
        return value.isBlank() || !value.equals(value.trim()) ? null : value;
    }
}
