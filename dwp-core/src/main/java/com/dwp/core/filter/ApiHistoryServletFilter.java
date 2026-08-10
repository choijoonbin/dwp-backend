package com.dwp.core.filter;

import com.dwp.core.constant.HeaderConstants;
import com.dwp.observability.api.ApiHistoryAttributes;
import com.dwp.observability.api.ApiHistoryEvent;
import com.dwp.observability.api.ApiHistoryPrivacyHasher;
import com.dwp.observability.api.ApiHistoryPublisher;
import com.dwp.observability.api.ApiHistorySanitizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Captures one privacy-minimized history event for every inbound servlet request. */
public final class ApiHistoryServletFilter extends OncePerRequestFilter {

    public static final String COLLECTOR_PATH = "/internal/observability/api-history";
    private static final String POLICY_VERSION = "dwp-api-history-v1";

    private final ApiHistoryPublisher publisher;
    private final ApiHistoryPrivacyHasher privacyHasher;
    private final String serviceName;
    private final String serviceVersion;
    private final String serviceInstance;
    private final String environment;

    public ApiHistoryServletFilter(
            ApiHistoryPublisher publisher,
            String privacyHashSecret,
            String serviceName,
            String serviceVersion,
            String serviceInstance,
            String environment) {
        this.publisher = publisher;
        this.privacyHasher = new ApiHistoryPrivacyHasher(privacyHashSecret);
        this.serviceName = ApiHistorySanitizer.truncate(serviceName, 120);
        this.serviceVersion = ApiHistorySanitizer.truncate(serviceVersion, 60);
        this.serviceInstance = ApiHistorySanitizer.truncate(serviceInstance, 160);
        this.environment = ApiHistorySanitizer.truncate(environment, 40);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(COLLECTOR_PATH)
                || request.getRequestURI().startsWith("/internal/audit/events");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Instant occurredAt = Instant.now();
        long started = System.nanoTime();
        CountingResponseWrapper countingResponse = new CountingResponseWrapper(response);
        Throwable failure = null;
        try {
            filterChain.doFilter(request, countingResponse);
        } catch (IOException | ServletException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            publisher.publish(event(
                    request,
                    countingResponse,
                    occurredAt,
                    Instant.now(),
                    durationMs,
                    failure));
        }
    }

    private ApiHistoryEvent event(
            HttpServletRequest request,
            CountingResponseWrapper response,
            Instant occurredAt,
            Instant completedAt,
            long durationMs,
            Throwable failure) {
        int status = failure != null && response.getStatus() < 400
                ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                : response.getStatus();
        String userAgent = request.getHeader("User-Agent");
        String trustedTenant = request.getHeader(HeaderConstants.X_DWP_TENANT_ID);
        String tenantValue = attribute(request, ApiHistoryAttributes.TENANT_ID);
        Long tenantId = positiveLong(firstNonBlank(
                tenantValue,
                firstNonBlank(trustedTenant, request.getHeader(HeaderConstants.X_TENANT_ID))));
        String actorId = firstNonBlank(
                attribute(request, ApiHistoryAttributes.ACTOR_ID),
                request.getHeader(HeaderConstants.X_DWP_USER_ID));
        String actorType = firstNonBlank(
                attribute(request, ApiHistoryAttributes.ACTOR_TYPE),
                actorId == null ? "ANONYMOUS" : "USER");
        String routeTemplate = ApiHistorySanitizer.normalizeRouteTemplate(
                request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
                request.getRequestURI());
        return new ApiHistoryEvent(
                UUID.randomUUID(),
                occurredAt,
                completedAt,
                tenantId,
                ApiHistorySanitizer.truncate(actorType, 20),
                ApiHistorySanitizer.truncate(actorId, 160),
                authType(request),
                serviceName,
                serviceVersion,
                serviceInstance,
                environment,
                "SERVICE",
                null,
                ApiHistorySanitizer.truncate(request.getMethod(), 12),
                routeTemplate,
                ApiHistorySanitizer.normalizePath(request.getRequestURI()),
                ApiHistorySanitizer.truncate(request.getScheme(), 12),
                ApiHistorySanitizer.truncate(request.getProtocol(), 20),
                status,
                outcome(status, failure),
                durationMs,
                nonNegative(request.getContentLengthLong()),
                response.bodyBytes(),
                attribute(request, ApiHistoryAttributes.CORRELATION_ID),
                attribute(request, ApiHistoryAttributes.TRACE_ID),
                attribute(request, ApiHistoryAttributes.SPAN_ID),
                attribute(request, ApiHistoryAttributes.PARENT_SPAN_ID),
                privacyHasher.hash(request.getRemoteAddr()),
                ApiHistorySanitizer.userAgentFamily(userAgent),
                privacyHasher.hash(userAgent),
                failure == null
                        ? null
                        : ApiHistorySanitizer.truncate(failure.getClass().getSimpleName(), 80),
                POLICY_VERSION);
    }

    private static String authType(HttpServletRequest request) {
        String explicit = attribute(request, ApiHistoryAttributes.AUTH_TYPE);
        if (explicit != null) return ApiHistorySanitizer.truncate(explicit, 20);
        if (request.getHeader(HeaderConstants.X_DWP_SERVICE_TOKEN) != null) return "SERVICE";
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return "BEARER";
        }
        if (request.getCookies() != null && request.getCookies().length > 0) return "SESSION";
        return "NONE";
    }

    private static String outcome(int status, Throwable failure) {
        if (failure != null || status >= 500) return "SERVER_ERROR";
        if (status >= 400) return "CLIENT_ERROR";
        if (status >= 300) return "REDIRECTION";
        return "SUCCESS";
    }

    private static String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private static Long nonNegative(long value) {
        return value < 0 ? null : value;
    }

    private static final class CountingResponseWrapper extends HttpServletResponseWrapper {

        private CountingServletOutputStream outputStream;
        private PrintWriter writer;
        private long bodyBytes;

        private CountingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) throw new IllegalStateException("getWriter() has already been called.");
            if (outputStream == null) {
                outputStream = new CountingServletOutputStream(super.getOutputStream(), this);
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (outputStream != null) {
                throw new IllegalStateException("getOutputStream() has already been called.");
            }
            if (writer == null) {
                writer = new PrintWriter(new CountingWriter(super.getWriter(), this), true);
            }
            return writer;
        }

        private void addBytes(long count) {
            bodyBytes += Math.max(0, count);
        }

        private long bodyBytes() {
            return bodyBytes;
        }
    }

    private static final class CountingServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final CountingResponseWrapper owner;

        private CountingServletOutputStream(
                ServletOutputStream delegate,
                CountingResponseWrapper owner) {
            this.delegate = delegate;
            this.owner = owner;
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            owner.addBytes(1);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            delegate.write(value, offset, length);
            owner.addBytes(length);
        }
    }

    private static final class CountingWriter extends Writer {

        private final Writer delegate;
        private final CountingResponseWrapper owner;

        private CountingWriter(Writer delegate, CountingResponseWrapper owner) {
            this.delegate = delegate;
            this.owner = owner;
        }

        @Override
        public void write(char[] value, int offset, int length) throws IOException {
            delegate.write(value, offset, length);
            owner.addBytes(new String(value, offset, length).getBytes(StandardCharsets.UTF_8).length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
