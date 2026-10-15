package com.dwp.services.auth.config;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.auth.dto.HrisRecipientAuthorityDtos;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/** Dedicated fail-closed boundary for the one HRIS notification recipient lookup. */
@Configuration
public class HrisRecipientAuthoritySecurityConfig {

    public static final String PATH =
            "/internal/auth/v1/hris-recipient-authority/resolve";
    public static final String PATH_ROOT =
            "/internal/auth/v1/hris-recipient-authority";
    public static final String TOKEN_HEADER =
            "X-DWP-Hris-Recipient-Authority-Token";
    public static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    public static final String PEOPLE_SERVICE_IDENTITY = "dwp-people-server";

    @Bean
    @Order(-13)
    SecurityFilterChain hrisRecipientAuthoritySecurityFilterChain(
            HttpSecurity http,
            @Value("${dwp.auth.hris-recipient-authority-token:}") String token,
            ObjectMapper objectMapper) throws Exception {
        http
                .securityMatcher(request -> candidate(request.getRequestURI())
                        || request.getHeader(TOKEN_HEADER) != null)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                        new RecipientAuthorityFilter(token, objectMapper),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }

    static boolean candidate(String path) {
        return PATH_ROOT.equals(path) || path.startsWith(PATH_ROOT + "/");
    }

    static final class RecipientAuthorityFilter extends OncePerRequestFilter {

        private static final int MAX_BODY_BYTES = 4096;
        private static final Set<String> ALLOWED_DWP_HEADERS = Set.of(
                TOKEN_HEADER.toLowerCase(Locale.ROOT),
                SERVICE_IDENTITY_HEADER.toLowerCase(Locale.ROOT));

        private final String expectedToken;
        private final ObjectMapper responseMapper;
        private final ObjectMapper strictRequestMapper;

        RecipientAuthorityFilter(String expectedToken, ObjectMapper objectMapper) {
            this.expectedToken = expectedToken == null ? "" : expectedToken;
            this.responseMapper = objectMapper;
            this.strictRequestMapper = JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(
                            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .disable(
                            DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                            DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build();
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            if (!trustedCredential(request)) {
                error(response, ErrorCode.UNAUTHORIZED,
                        "Dedicated HRIS recipient authority credentials are required.");
                return;
            }
            if (!"POST".equals(request.getMethod())
                    || !PATH.equals(request.getRequestURI())
                    || request.getQueryString() != null
                    || !json(request.getContentType())) {
                error(response, ErrorCode.FORBIDDEN,
                        "The HRIS recipient authority request is outside its boundary.");
                return;
            }

            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length == 0 || body.length > MAX_BODY_BYTES || !strict(body)) {
                error(response, ErrorCode.INVALID_FORMAT,
                        "The request body format is invalid.");
                return;
            }
            filterChain.doFilter(new BodyRequest(request, body), response);
        }

        private boolean trustedCredential(HttpServletRequest request) {
            String actualToken = exactHeader(request, TOKEN_HEADER);
            String identity = exactHeader(request, SERVICE_IDENTITY_HEADER);
            boolean borrowed = Collections.list(request.getHeaderNames()).stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .anyMatch(name -> "authorization".equals(name)
                            || name.startsWith("x-dwp-")
                            && !ALLOWED_DWP_HEADERS.contains(name));
            return trimStable(expectedToken)
                    && expectedToken.length() <= 2048
                    && trimStable(actualToken)
                    && actualToken.length() <= 2048
                    && MessageDigest.isEqual(
                            expectedToken.getBytes(StandardCharsets.UTF_8),
                            actualToken.getBytes(StandardCharsets.UTF_8))
                    && PEOPLE_SERVICE_IDENTITY.equals(identity)
                    && !borrowed
                    && (request.getCookies() == null || request.getCookies().length == 0);
        }

        private boolean strict(byte[] body) {
            try {
                HrisRecipientAuthorityDtos.ResolveRequest request = strictRequestMapper
                        .readValue(body, HrisRecipientAuthorityDtos.ResolveRequest.class);
                return HrisRecipientAuthorityDtos.valid(request);
            } catch (IOException | RuntimeException exception) {
                return false;
            }
        }

        private static String exactHeader(HttpServletRequest request, String name) {
            var values = Collections.list(request.getHeaders(name));
            return values.size() == 1 ? values.getFirst() : null;
        }

        private static boolean trimStable(String value) {
            return value != null && !value.isBlank() && value.equals(value.strip());
        }

        private static boolean json(String type) {
            if (type == null) {
                return false;
            }
            try {
                MediaType media = MediaType.parseMediaType(type);
                return MediaType.APPLICATION_JSON.getType().equals(media.getType())
                        && MediaType.APPLICATION_JSON.getSubtype().equals(media.getSubtype())
                        && (media.getCharset() == null
                        || StandardCharsets.UTF_8.equals(media.getCharset()));
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        private void error(
                HttpServletResponse response,
                ErrorCode code,
                String message) throws IOException {
            response.setStatus(code.getHttpStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            responseMapper.writeValue(
                    response.getOutputStream(), ApiResponse.error(code, message));
        }
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException(
                            "Synchronous recipient authority body only.");
                }
            };
        }
    }
}
