package com.dwp.services.platform.provisioning;

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
public class ProviderProvisioningSecurityFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-DWP-Provisioning-Token";

    private final String expectedToken;
    private final ObjectMapper objectMapper;

    public ProviderProvisioningSecurityFilter(
            @Value("${dwp.provider.provisioning-token:}") String expectedToken,
            ObjectMapper objectMapper) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/provider/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String actual = request.getHeader(TOKEN_HEADER);
        if (expectedToken.isBlank() || actual == null || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                    response.getOutputStream(),
                    ApiResponse.error(ErrorCode.UNAUTHORIZED, "Provider provisioning identity is required."));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
