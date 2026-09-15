package com.dwp.services.platform.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Terminates the purpose-bound Provider Widget Registry transport credential and
 * exposes only a canonical existing admin route to the normal Platform guards.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 9)
public class ProviderWidgetRegistryBffIngressFilter extends OncePerRequestFilter {

    static final String INTERNAL_PREFIX = "/internal/provider-bff/v1/widget-registry";
    static final String TOKEN_HEADER = "X-DWP-Widget-Registry-Token";
    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT");

    private final String expectedToken;
    private final String platformServiceToken;
    private final ObjectMapper objectMapper;

    public ProviderWidgetRegistryBffIngressFilter(
            @Value("${dwp.platform.widget-registry-provider-token:}") String expectedToken,
            @Value("${dwp.platform.service-token:}") String platformServiceToken,
            ObjectMapper objectMapper) {
        this.expectedToken = value(expectedToken);
        this.platformServiceToken = value(platformServiceToken);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null
                || !(path.equals(INTERNAL_PREFIX) || path.startsWith(INTERNAL_PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String publicPath = publicPath(request.getRequestURI());
        if (publicPath == null || ambiguous(request.getRequestURI())
                || !METHODS.contains(request.getMethod())
                || !supported(request.getMethod(), publicPath)) {
            writeError(response, ErrorCode.INVALID_INPUT_VALUE,
                    "A canonical Provider Widget Registry request is required.");
            return;
        }
        if (expectedToken.isBlank() || platformServiceToken.isBlank()) {
            writeError(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Provider Widget Registry transport identity is not configured.");
            return;
        }
        List<String> providedTokens = values(request, TOKEN_HEADER);
        if (providedTokens.size() != 1
                || !constantTimeEquals(expectedToken, providedTokens.getFirst())
                || !values(request, SERVICE_TOKEN_HEADER).isEmpty()) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Provider Widget Registry transport identity is required.");
            return;
        }

        filterChain.doFilter(
                new TrustedWidgetRegistryRequest(request, publicPath, platformServiceToken),
                response);
    }

    private void writeError(
            HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }

    private static String publicPath(String path) {
        String suffix = path.substring(INTERNAL_PREFIX.length());
        if (suffix.equals("/definitions")) return "/v1/admin/widget-definitions";
        if (suffix.startsWith("/definitions/")) {
            return "/v1/admin/widget-definitions/" + suffix.substring("/definitions/".length());
        }
        if (suffix.startsWith("/definition-versions/")) {
            return "/v1/admin/widget-definition-versions/"
                    + suffix.substring("/definition-versions/".length());
        }
        if (suffix.equals("/runtime-controls")) return "/v1/admin/widget-runtime-controls";
        if (suffix.startsWith("/runtime-controls/")) {
            return "/v1/admin/widget-runtime-controls/"
                    + suffix.substring("/runtime-controls/".length());
        }
        if (suffix.startsWith("/registry/")) {
            return "/v1/admin/widget-registry/" + suffix.substring("/registry/".length());
        }
        return null;
    }

    private static boolean supported(String method, String path) {
        if (path.equals("/v1/admin/widget-definitions")) {
            return method.equals("GET") || method.equals("POST");
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+$")) {
            return method.equals("GET");
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/versions$")) {
            return method.equals("GET") || method.equals("POST");
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/retirement-impact$")) {
            return method.equals("GET");
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/retire$")) {
            return method.equals("POST");
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/channels/[^/]+$")) {
            return method.equals("GET");
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/channels/[^/]+/impact$")) {
            return method.equals("GET");
        }
        if (path.matches(
                "^/v1/admin/widget-definitions/[^/]+/channels/[^/]+/(promote|rollback)$")) {
            return method.equals("POST");
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+$")) {
            return method.equals("GET") || method.equals("PUT");
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+/impact$")) {
            return method.equals("GET");
        }
        if (path.matches(
                "^/v1/admin/widget-definition-versions/[^/]+/(validate|submit|rework|decision)$")) {
            return method.equals("POST");
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+/evidence$")) {
            return method.equals("GET") || method.equals("POST");
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+/evidence/[^/]+$")) {
            return method.equals("GET");
        }
        if (path.matches(
                "^/v1/admin/widget-definition-versions/[^/]+/"
                        + "(publish|deprecate|block|quarantine|revoke)$")) {
            return method.equals("POST");
        }
        if (path.equals("/v1/admin/widget-runtime-controls")) {
            return method.equals("GET");
        }
        if (path.equals("/v1/admin/widget-runtime-controls/disable")) {
            return method.equals("POST");
        }
        if (path.matches(
                "^/v1/admin/widget-runtime-controls/[^/]+/(enable-approvals|enable)$")) {
            return method.equals("POST");
        }
        return method.equals("GET")
                && (path.equals("/v1/admin/widget-registry/readiness")
                || path.equals("/v1/admin/widget-registry/events"));
    }

    private static boolean ambiguous(String path) {
        return path.indexOf('%') >= 0
                || path.indexOf(';') >= 0
                || path.indexOf('\\') >= 0
                || path.contains("//")
                || path.contains("/./")
                || path.endsWith("/.")
                || path.contains("/../")
                || path.endsWith("/..");
    }

    private static List<String> values(HttpServletRequest request, String header) {
        Enumeration<String> values = request.getHeaders(header);
        return values == null ? List.of() : Collections.list(values);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class TrustedWidgetRegistryRequest extends HttpServletRequestWrapper {
        private final String publicPath;
        private final String platformServiceToken;

        private TrustedWidgetRegistryRequest(
                HttpServletRequest request, String publicPath, String platformServiceToken) {
            super(request);
            this.publicPath = publicPath;
            this.platformServiceToken = platformServiceToken;
        }

        @Override
        public String getRequestURI() {
            return publicPath;
        }

        @Override
        public String getServletPath() {
            return publicPath;
        }

        @Override
        public String getHeader(String name) {
            if (SERVICE_TOKEN_HEADER.equalsIgnoreCase(name)) return platformServiceToken;
            if (TOKEN_HEADER.equalsIgnoreCase(name)) return null;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (SERVICE_TOKEN_HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(platformServiceToken));
            }
            if (TOKEN_HEADER.equalsIgnoreCase(name)) return Collections.emptyEnumeration();
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            Enumeration<String> source = super.getHeaderNames();
            if (source != null) {
                while (source.hasMoreElements()) {
                    String name = source.nextElement();
                    if (!TOKEN_HEADER.equalsIgnoreCase(name)
                            && !SERVICE_TOKEN_HEADER.equalsIgnoreCase(name)) {
                        names.add(name);
                    }
                }
            }
            names.add(SERVICE_TOKEN_HEADER);
            return Collections.enumeration(names);
        }
    }
}
