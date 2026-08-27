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
import java.util.ArrayDeque;
import java.util.Deque;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ProviderProvisioningSecurityFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-DWP-Provisioning-Token";
    private static final String WIDGET_REGISTRY_PREFIX = "/internal/provider/v1/widget-registry";

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
        String path = request.getRequestURI();
        if (isAmbiguous(path) && couldResolveToInternalProvider(path)) return false;
        boolean widgetRegistryPath = path.equals(WIDGET_REGISTRY_PREFIX)
                || path.startsWith(WIDGET_REGISTRY_PREFIX + "/");
        return widgetRegistryPath || !path.startsWith("/internal/provider/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (isAmbiguous(request.getRequestURI())) {
            writeError(
                    response,
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A canonical internal Provider path is required.");
            return;
        }
        String actual = request.getHeader(TOKEN_HEADER);
        if (expectedToken.isBlank() || actual == null || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            writeError(
                    response,
                    ErrorCode.UNAUTHORIZED,
                    "Provider provisioning identity is required.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode, message));
    }

    private static boolean isAmbiguous(String path) {
        return path.indexOf('%') >= 0
                || path.indexOf(';') >= 0
                || path.indexOf('\\') >= 0
                || path.contains("//")
                || path.contains("/./")
                || path.endsWith("/.")
                || path.contains("/../")
                || path.endsWith("/..");
    }

    private static boolean couldResolveToInternalProvider(String rawPath) {
        if (rawPath.equals("/internal/provider") || rawPath.startsWith("/internal/provider/")) return true;
        String decoded = decodeAsciiPercentEscapes(rawPath).replace('\\', '/');
        Deque<String> segments = new ArrayDeque<>();
        for (String rawSegment : decoded.split("/", -1)) {
            String segment = rawSegment;
            int matrixStart = segment.indexOf(';');
            if (matrixStart >= 0) segment = segment.substring(0, matrixStart);
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }
        String normalized = "/" + String.join("/", segments);
        return normalized.equals("/internal/provider") || normalized.startsWith("/internal/provider/");
    }

    private static String decodeAsciiPercentEscapes(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '%' || index + 2 >= value.length()) {
                decoded.append(current);
                continue;
            }
            int high = Character.digit(value.charAt(index + 1), 16);
            int low = Character.digit(value.charAt(index + 2), 16);
            int octet = high < 0 || low < 0 ? -1 : (high << 4) | low;
            if (octet < 0 || octet > 0x7f) {
                decoded.append(current);
                continue;
            }
            decoded.append((char) octet);
            index += 2;
        }
        return decoded.toString();
    }
}
