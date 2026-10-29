package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.people.hr.performance.compensationplanning.ApprovedCompensationSnapshotTrustProfile.Failure;
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
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/** Exact, isolated workload boundary for SVC-PEP-PER-001. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
final class ApprovedCompensationSnapshotIngressFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX =
            ApprovedCompensationSnapshotIngressContract.PATH_PREFIX;
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "cookie", "origin", "referer", "x-dwp-user-id",
            "x-dwp-person-public-id", "x-dwp-display-name-b64",
            "x-dwp-roles", "x-dwp-permissions", "x-dwp-service-token",
            "x-dwp-tenant-id", "x-tenant-id", "x-tenant-override",
            "x-purpose-code", "x-projection-id",
            "x-forwarded-user", "x-forwarded-email", "x-forwarded-client-cert");

    private final ApprovedCompensationSnapshotTrustProfile trust;
    private final ApprovedCompensationSnapshotAdmissionControl admission;
    private final ObjectMapper mapper;

    ApprovedCompensationSnapshotIngressFilter(
            ApprovedCompensationSnapshotTrustProfile trust,
            ApprovedCompensationSnapshotAdmissionControl admission,
            ObjectMapper mapper) {
        this.trust = trust;
        this.admission = admission;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApprovedCompensationSnapshotIngressContract.isPlanePath(
                request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        ApprovedCompensationSnapshotRequestContext.clear(request);
        if (!trust.enabled()) {
            write(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "SVC_PEP_PER_001_UNAVAILABLE");
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            response.setHeader("Allow", "GET");
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getOutputStream(), ApiResponse.error(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "SVC_PEP_PER_001_METHOD_NOT_ALLOWED"));
            return;
        }
        ApprovedCompensationSnapshotIngressContract.Route route =
                ApprovedCompensationSnapshotIngressContract.route(request);
        if (route == null) {
            write(response, ErrorCode.NOT_FOUND, "SVC_PEP_PER_001_ROUTE_NOT_FOUND");
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
                    request, route.snapshotId(), route.snapshotRevision(),
                    route.payloadDigest());
            var decision = admission.admit(verified.tenantId());
            if (!decision.allowed()) {
                response.setHeader("Retry-After",
                        Integer.toString(decision.retryAfterSeconds()));
                write(response, ErrorCode.SERVICE_RATE_LIMITED, decision.reason());
                return;
            }
            var lease = decision.lease();
            boolean releaseDeferred = false;
            try {
                ApprovedCompensationSnapshotRequestContext.set(request, verified);
                chain.doFilter(request, response);
            } finally {
                ApprovedCompensationSnapshotRequestContext.clear(request);
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

    private static boolean hasForbiddenHeader(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return true;
        for (String name : Collections.list(names)) {
            String normalized = name.toLowerCase(java.util.Locale.ROOT);
            if (FORBIDDEN_HEADERS.contains(normalized)
                    || normalized.startsWith("x-dwp-")
                    || normalized.startsWith("sec-fetch-")
                    || normalized.startsWith("sec-ch-ua")) {
                return true;
            }
            List<String> values = Collections.list(request.getHeaders(name));
            if ((values.size() != 1 && !"authorization".equals(normalized))
                    || values.isEmpty()
                    || values.stream().anyMatch(value -> value == null
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
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
            response.setHeader("WWW-Authenticate",
                    "Bearer realm=\"dwp-people-approved-compensation-snapshot\"");
        }
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, reason));
    }

    private static boolean releaseOnAsyncCompletion(
            HttpServletRequest request,
            ApprovedCompensationSnapshotAdmissionControl.Lease lease) {
        try {
            request.getAsyncContext().addListener(new AsyncLeaseReleaseListener(lease));
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static final class AsyncLeaseReleaseListener implements AsyncListener {
        private final ApprovedCompensationSnapshotAdmissionControl.Lease lease;

        private AsyncLeaseReleaseListener(
                ApprovedCompensationSnapshotAdmissionControl.Lease lease) {
            this.lease = lease;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            lease.close();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            lease.close();
        }

        @Override
        public void onError(AsyncEvent event) {
            lease.close();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
