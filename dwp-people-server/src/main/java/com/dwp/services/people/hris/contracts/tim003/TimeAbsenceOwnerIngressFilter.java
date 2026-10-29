package com.dwp.services.people.hris.contracts.tim003;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Default-off exact workload ingress for SVC-PEP-HRM-007. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 14)
final class TimeAbsenceOwnerIngressFilter extends OncePerRequestFilter {

    private static final String ENABLED =
            "dwp.hris.tim003.employment-absence-owner.enabled";
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "cookie", "origin", "referer", "forwarded", "x-forwarded-for",
            "x-forwarded-host", "x-forwarded-proto", "x-forwarded-client-cert",
            "x-tenant-id", "x-tenant-override", "x-purpose-code",
            "x-projection-id", "x-dwp-user-id", "x-dwp-tenant-id");

    private final boolean enabled;
    private final TimeAbsenceOwnerPorts.WorkloadProofVerifier proofs;
    private final TimeAbsenceOwnerPorts.IngressDenialAudit denialAudit;

    @Autowired
    TimeAbsenceOwnerIngressFilter(
            Environment environment,
            TimeAbsenceOwnerPorts.WorkloadProofVerifier proofs,
            TimeAbsenceOwnerPorts.IngressDenialAudit denialAudit) {
        this(environment.getProperty(ENABLED, Boolean.class, false), proofs, denialAudit);
    }

    TimeAbsenceOwnerIngressFilter(
            boolean enabled,
            TimeAbsenceOwnerPorts.WorkloadProofVerifier proofs) {
        this(enabled, proofs, evidence -> false);
    }

    TimeAbsenceOwnerIngressFilter(
            boolean enabled,
            TimeAbsenceOwnerPorts.WorkloadProofVerifier proofs,
            TimeAbsenceOwnerPorts.IngressDenialAudit denialAudit) {
        this.enabled = enabled;
        this.proofs = proofs;
        this.denialAudit = denialAudit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !TimeAbsenceOwnerRouteContract.isPlanePath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        TimeAbsenceOwnerRequestContext.clear(request);
        if (!enabled) {
            deny(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    TimeAbsenceOwnerContract.IngressDenialReason.DEFAULT_OFF);
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            response.setHeader("Allow", "GET");
            deny(request, response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    TimeAbsenceOwnerContract.IngressDenialReason.METHOD_NOT_ALLOWED);
            return;
        }
        if (!TimeAbsenceOwnerRouteContract.isCanonicalRelationshipPath(
                request.getRequestURI())) {
            deny(request, response, HttpServletResponse.SC_BAD_REQUEST,
                    TimeAbsenceOwnerContract.IngressDenialReason.PATH_INVALID);
            return;
        }
        if (hasForbiddenHeader(request)
                || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            deny(request, response, HttpServletResponse.SC_BAD_REQUEST,
                    TimeAbsenceOwnerContract.IngressDenialReason.HEADER_OR_BODY_REJECTED);
            return;
        }
        TimeAbsenceOwnerRouteContract.Route route =
                TimeAbsenceOwnerRouteContract.route(request);
        if (route == null) {
            deny(request, response, HttpServletResponse.SC_BAD_REQUEST,
                    TimeAbsenceOwnerContract.IngressDenialReason.QUERY_INVALID);
            return;
        }
        TimeAbsenceOwnerContract.VerifiedRequest verified;
        try {
            verified = proofs.verify(
                    request, route.request(), route.path(), route.queryFingerprint());
            if (!verified.request().equals(route.request())
                    || !verified.path().equals(route.path())
                    || !verified.queryFingerprint().equals(route.queryFingerprint())
                    || !TimeAbsenceOwnerRouteContract.exactCorrelationHeader(
                            request, verified.correlationId())) {
                throw TimeAbsenceOwnerContract.Failure.invalidProof();
            }
        } catch (TimeAbsenceOwnerContract.Failure failure) {
            deny(request, response, failure.status(),
                    TimeAbsenceOwnerContract.IngressDenialReason.PROOF_REJECTED);
            return;
        } catch (RuntimeException exception) {
            deny(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    TimeAbsenceOwnerContract.IngressDenialReason.PROOF_REJECTED);
            return;
        }
        TimeAbsenceOwnerRequestContext.set(request, verified);
        try {
            chain.doFilter(request, response);
        } finally {
            TimeAbsenceOwnerRequestContext.clear(request);
        }
    }

    private static boolean hasForbiddenHeader(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return true;
        for (String name : Collections.list(names)) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_HEADERS.contains(normalized)
                    || normalized.startsWith("x-tenant-")
                    || normalized.startsWith("sec-fetch-")
                    || normalized.startsWith("sec-ch-ua")) {
                return true;
            }
        }
        return false;
    }

    private static void writeEmpty(HttpServletResponse response, int status) {
        response.resetBuffer();
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setContentLength(0);
    }

    private void deny(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            TimeAbsenceOwnerContract.IngressDenialReason reason) {
        try {
            denialAudit.append(TimeAbsenceOwnerContract.IngressDenialEvidence.of(
                    reason, status, presentedCorrelationId(request)));
        } catch (RuntimeException ignored) {
            // The request remains denied even when the not-yet-admitted audit backing is absent.
        }
        writeEmpty(response, status);
    }

    private static UUID presentedCorrelationId(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("X-Correlation-ID"));
        return values.size() == 1
                ? TimeAbsenceOwnerRouteContract.canonicalUuid(values.getFirst()) : null;
    }
}
