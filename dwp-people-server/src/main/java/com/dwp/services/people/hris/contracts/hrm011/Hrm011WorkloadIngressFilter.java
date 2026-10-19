package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.people.hris.contracts.hrm011.Hrm011WorkloadTrustProfile.Failure;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Exact isolated workload boundary for SVC-PEP-HRM-003-R2 and SVC-PEP-HRM-005. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
final class Hrm011WorkloadIngressFilter extends OncePerRequestFilter {

    static final String COMPENSATION_PATH =
            "/internal/hris-people/v1/compensation-basis-snapshots";
    static final String PAYROLL_FACTS_PATH =
            "/internal/hris-people/v1/payroll-worker-fact-snapshots";
    static final String COMPENSATION_BINDING = "SVC-PEP-HRM-003-R2";
    static final String PAYROLL_FACTS_BINDING = "SVC-PEP-HRM-005";

    private static final Set<String> QUERY_FIELDS = Set.of(
            "purposeCode", "asOf", "projection", "limit", "cursor");
    private static final Set<String> REQUIRED_QUERY_FIELDS = Set.of(
            "purposeCode", "asOf", "projection", "limit");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "cookie", "origin", "referer", "forwarded", "x-tenant-id",
            "x-tenant-override", "x-purpose-code", "x-projection-id",
            "x-forwarded-user", "x-forwarded-email", "x-forwarded-client-cert");

    private final Hrm011WorkloadTrustProfile trust;
    private final Hrm011WorkloadAdmissionControl admission;
    private final ObjectMapper mapper;

    Hrm011WorkloadIngressFilter(
            Hrm011WorkloadTrustProfile trust,
            Hrm011WorkloadAdmissionControl admission,
            ObjectMapper mapper) {
        this.trust = trust;
        this.admission = admission;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isPlanePath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Hrm011WorkloadRequestContext.clear(request);
        if (!trust.enabled()) {
            write(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "HRM011_WORKLOAD_SERVICE_UNAVAILABLE");
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            response.setHeader("Allow", "GET");
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getOutputStream(), ApiResponse.error(
                    ErrorCode.INVALID_INPUT_VALUE, "HRM011_WORKLOAD_METHOD_NOT_ALLOWED"));
            return;
        }
        Route route = route(request);
        if (route == null) {
            write(response, ErrorCode.NOT_FOUND, "HRM011_WORKLOAD_ROUTE_OR_QUERY_INVALID");
            return;
        }
        if (hasForbiddenHeader(request)
                || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            write(response, ErrorCode.FORBIDDEN,
                    "BROWSER_OR_CALLER_AUTHORITY_HEADERS_FORBIDDEN");
            return;
        }
        try {
            var verified = trust.authenticate(
                    request, route.request(), route.bindingId(), route.path(),
                    route.queryFingerprint());
            var decision = admission.admit(verified.trusted().tenantId());
            if (!decision.allowed()) {
                response.setHeader("Retry-After",
                        Integer.toString(decision.retryAfterSeconds()));
                write(response, ErrorCode.SERVICE_RATE_LIMITED, decision.reason());
                return;
            }
            var lease = decision.lease();
            boolean releaseDeferred = false;
            try {
                Hrm011WorkloadRequestContext.set(request, verified);
                chain.doFilter(request, response);
            } finally {
                Hrm011WorkloadRequestContext.clear(request);
                if (request.isAsyncStarted()) {
                    releaseDeferred = releaseOnAsyncCompletion(request, lease);
                }
                if (!releaseDeferred) lease.close();
            }
        } catch (Failure failure) {
            ErrorCode code = switch (failure.kind()) {
                case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
                case DENIED -> ErrorCode.FORBIDDEN;
                case UNAVAILABLE -> ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;
            };
            write(response, code, failure.code());
        }
    }

    static boolean matchesVerifiedRequest(
            HttpServletRequest request,
            Hrm011WorkloadRequestContext.VerifiedRequest verified) {
        if (!verified.method().equals(request.getMethod())
                || !verified.path().equals(request.getRequestURI())) {
            return false;
        }
        Route current = route(request);
        return current != null
                && verified.bindingId().equals(current.bindingId())
                && verified.queryFingerprint().equals(current.queryFingerprint())
                && verified.snapshotRequest().equals(current.request())
                && exactCorrelationHeader(request, verified);
    }

    private static boolean exactCorrelationHeader(
            HttpServletRequest request,
            Hrm011WorkloadRequestContext.VerifiedRequest verified) {
        java.util.List<String> values = Collections.list(
                request.getHeaders("X-Correlation-ID"));
        return values.size() == 1
                && verified.correlationId().toString().equals(values.getFirst());
    }

    private static boolean isPlanePath(String path) {
        return path != null && (path.equals(COMPENSATION_PATH)
                || path.startsWith(COMPENSATION_PATH + "/")
                || path.equals(PAYROLL_FACTS_PATH)
                || path.startsWith(PAYROLL_FACTS_PATH + "/"));
    }

    private static Route route(HttpServletRequest request) {
        String path = request.getRequestURI();
        String bindingId;
        String expectedPurpose;
        Set<String> projections;
        if (COMPENSATION_PATH.equals(path)) {
            bindingId = COMPENSATION_BINDING;
            expectedPurpose = Hrm011WorkloadIngress.SVC003_PURPOSE;
            projections = Set.of(Hrm011WorkloadIngress.COMPENSATION);
        } else if (PAYROLL_FACTS_PATH.equals(path)) {
            bindingId = PAYROLL_FACTS_BINDING;
            expectedPurpose = Hrm011WorkloadIngress.SVC005_PURPOSE;
            projections = Hrm011WorkloadIngress.PAYROLL_PROJECTIONS;
        } else {
            return null;
        }
        Map<String, String[]> parameters = request.getParameterMap();
        if (!QUERY_FIELDS.containsAll(parameters.keySet())
                || !parameters.keySet().containsAll(REQUIRED_QUERY_FIELDS)) {
            return null;
        }
        for (String required : REQUIRED_QUERY_FIELDS) {
            if (single(parameters, required) == null) return null;
        }
        String purpose = single(parameters, "purposeCode");
        String rawAsOf = single(parameters, "asOf");
        String projection = single(parameters, "projection");
        String rawLimit = single(parameters, "limit");
        String cursor = null;
        if (parameters.containsKey("cursor")) {
            cursor = single(parameters, "cursor");
            if (cursor == null || cursor.length() > 4096) return null;
        }
        if (!expectedPurpose.equals(purpose) || !projections.contains(projection)) return null;
        try {
            Instant asOf = Instant.parse(rawAsOf);
            int limit = Integer.parseInt(rawLimit);
            if (!asOf.toString().equals(rawAsOf) || limit < 1 || limit > 500
                    || !Integer.toString(limit).equals(rawLimit)) {
                return null;
            }
            var snapshotRequest = new Hrm011WorkloadIngress.SnapshotRequest(
                    purpose, asOf, projection, limit, cursor);
            return new Route(path, bindingId, snapshotRequest,
                    fingerprint(path, snapshotRequest));
        } catch (DateTimeParseException | NumberFormatException exception) {
            return null;
        }
    }

    private static String single(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);
        if (values == null || values.length != 1) return null;
        String value = values[0];
        return value == null || value.isBlank() || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                ? null : value;
    }

    private static String fingerprint(
            String path, Hrm011WorkloadIngress.SnapshotRequest request) {
        String canonical = "GET\n" + path
                + "\npurposeCode=" + request.purposeCode()
                + "\nasOf=" + request.asOf()
                + "\nprojection=" + request.projection()
                + "\nlimit=" + request.limit()
                + "\ncursor=" + (request.cursor() == null ? "<null>" : request.cursor());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static boolean hasForbiddenHeader(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return true;
        for (String name : Collections.list(names)) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_HEADERS.contains(normalized)
                    || normalized.startsWith("x-dwp-")
                    || normalized.startsWith("x-tenant-")
                    || normalized.startsWith("x-forwarded-")
                    || normalized.startsWith("sec-fetch-")
                    || normalized.startsWith("sec-ch-ua")) {
                return true;
            }
        }
        return false;
    }

    private void write(
            HttpServletResponse response,
            ErrorCode code,
            String reason) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        if (code == ErrorCode.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"dwp-people-hrm011\"");
        }
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, reason));
    }

    private static boolean releaseOnAsyncCompletion(
            HttpServletRequest request,
            Hrm011WorkloadAdmissionControl.Lease lease) {
        try {
            request.getAsyncContext().addListener(new AsyncLeaseReleaseListener(request, lease));
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static final class AsyncLeaseReleaseListener implements AsyncListener {
        private final HttpServletRequest request;
        private final Hrm011WorkloadAdmissionControl.Lease lease;

        private AsyncLeaseReleaseListener(
                HttpServletRequest request,
                Hrm011WorkloadAdmissionControl.Lease lease) {
            this.request = request;
            this.lease = lease;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            clearAndRelease();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            clearAndRelease();
        }

        @Override
        public void onError(AsyncEvent event) {
            clearAndRelease();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }

        private void clearAndRelease() {
            Hrm011WorkloadRequestContext.clear(request);
            lease.close();
        }
    }

    private record Route(
            String path,
            String bindingId,
            Hrm011WorkloadIngress.SnapshotRequest request,
            String queryFingerprint) {
    }
}
