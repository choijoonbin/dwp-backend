package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs before the platform gateway filter and admits only an exact, provider-verified callback.
 * It also caps the servlet stream before MVC can allocate an unbounded request body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class MailWebhookSecurityFilter extends OncePerRequestFilter {

    private static final Pattern PATH = Pattern.compile(
            "^/internal/v1/mail/webhooks/([A-Za-z0-9_]{2,40})$");
    private static final String VERIFIED_ATTRIBUTE =
            MailWebhookSecurityFilter.class.getName() + ".verified";
    private static final String RECEIPT_ATTRIBUTE =
            MailWebhookSecurityFilter.class.getName() + ".receipt";
    private static final String VALIDATION_TOKEN_ATTRIBUTE =
            MailWebhookSecurityFilter.class.getName() + ".validation-token";
    private static final int MAXIMUM_VALIDATION_TOKEN_BYTES = 2_048;
    private static final int MAXIMUM_VALIDATION_QUERY_BYTES = 4_096;

    private final MailWebhookIngressUseCase ingress;
    private final ObjectMapper objectMapper;
    private final int maximumBodyBytes;

    MailWebhookSecurityFilter(
            MailWebhookIngressUseCase ingress,
            ObjectMapper objectMapper,
            @Value("${dwp.platform.mail.webhooks.maximum-body-bytes:1048576}")
            int maximumBodyBytes) {
        if (maximumBodyBytes <= 0) {
            throw new IllegalArgumentException("maximumBodyBytes must be positive");
        }
        this.ingress = ingress;
        this.objectMapper = objectMapper;
        this.maximumBodyBytes = maximumBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !PATH.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Matcher matcher = PATH.matcher(request.getRequestURI());
        if (!matcher.matches() || !"POST".equals(request.getMethod())) {
            write(response, HttpServletResponse.SC_NOT_FOUND,
                    ErrorCode.NOT_FOUND, "MAIL_WEBHOOK_ROUTE_INVALID");
            return;
        }
        if (request.getParameterMap().containsKey("validationToken")) {
            admitGraphValidation(request, response, chain, matcher.group(1));
            return;
        }
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maximumBodyBytes) {
            write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    ErrorCode.INVALID_INPUT_VALUE, "WEBHOOK_PAYLOAD_LIMIT_EXCEEDED");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maximumBodyBytes + 1);
        if (body.length == 0 || body.length > maximumBodyBytes) {
            write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    ErrorCode.INVALID_INPUT_VALUE, "WEBHOOK_PAYLOAD_LIMIT_EXCEEDED");
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request, body);
        MailWebhookVerificationPort.Verification verification;
        try {
            verification = ingress.verify(
                    matcher.group(1), headers(request), body);
        } catch (ResponseStatusException rejected) {
            write(response, rejected.getStatusCode().value(),
                    rejected.getStatusCode().value() == 503
                            ? ErrorCode.EXTERNAL_SERVICE_ERROR : ErrorCode.UNAUTHORIZED,
                    safeReason(rejected));
            return;
        }
        cached.setAttribute(VERIFIED_ATTRIBUTE, Boolean.TRUE);
        cached.setAttribute(RECEIPT_ATTRIBUTE, verification);
        chain.doFilter(cached, response);
    }

    private void admitGraphValidation(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain,
            String rawProvider) throws IOException, ServletException {
        String query = request.getQueryString();
        String[] values = request.getParameterValues("validationToken");
        String token = values == null || values.length != 1 ? null : values[0];
        boolean providerMatches = "MICROSOFT_GRAPH".equalsIgnoreCase(rawProvider);
        boolean oneParameter = request.getParameterMap().size() == 1;
        boolean queryBounded = query != null
                && query.getBytes(StandardCharsets.UTF_8).length
                <= MAXIMUM_VALIDATION_QUERY_BYTES;
        if (!providerMatches || !oneParameter || !queryBounded
                || !safeValidationToken(token)) {
            write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ErrorCode.INVALID_INPUT_VALUE,
                    "WEBHOOK_VALIDATION_TOKEN_INVALID");
            return;
        }
        if (request.getContentLengthLong() > 0
                || request.getInputStream().readNBytes(1).length != 0) {
            write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ErrorCode.INVALID_INPUT_VALUE,
                    "WEBHOOK_VALIDATION_BODY_NOT_EMPTY");
            return;
        }
        request.setAttribute(VERIFIED_ATTRIBUTE, Boolean.TRUE);
        request.setAttribute(VALIDATION_TOKEN_ATTRIBUTE, token);
        chain.doFilter(request, response);
    }

    public static boolean verified(HttpServletRequest request) {
        return request != null
                && "POST".equals(request.getMethod())
                && PATH.matcher(request.getRequestURI()).matches()
                && Boolean.TRUE.equals(request.getAttribute(VERIFIED_ATTRIBUTE))
                && (request.getAttribute(RECEIPT_ATTRIBUTE)
                        instanceof MailWebhookVerificationPort.Verification
                    || request.getAttribute(VALIDATION_TOKEN_ATTRIBUTE) instanceof String);
    }

    static MailWebhookVerificationPort.Verification verification(
            HttpServletRequest request) {
        if (!verified(request)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "WEBHOOK_VERIFICATION_REQUIRED");
        }
        return (MailWebhookVerificationPort.Verification)
                request.getAttribute(RECEIPT_ATTRIBUTE);
    }

    static String validationToken(HttpServletRequest request) {
        if (!verified(request)) return null;
        Object token = request.getAttribute(VALIDATION_TOKEN_ATTRIBUTE);
        return token instanceof String value ? value : null;
    }

    private static boolean safeValidationToken(String token) {
        if (token == null || token.isBlank()) return false;
        if (token.getBytes(StandardCharsets.UTF_8).length
                > MAXIMUM_VALIDATION_TOKEN_BYTES) return false;
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (Character.isISOControl(value) || Character.isSurrogate(value)) return false;
        }
        return true;
    }

    private Map<String, List<String>> headers(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, List.copyOf(Collections.list(request.getHeaders(name))));
        }
        return Map.copyOf(headers);
    }

    private String safeReason(ResponseStatusException rejected) {
        String reason = rejected.getReason();
        return reason == null || !reason.matches("[A-Z0-9_.-]{1,120}")
                ? "WEBHOOK_VERIFICATION_FAILED" : reason;
    }

    private void write(
            HttpServletResponse response,
            int status,
            ErrorCode code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // The bounded body is already buffered and immediately readable.
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] value, int offset, int length) {
                    return input.read(value, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
