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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Default-off exact workload ingress for SVC-PEP-HRM-007. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 14)
final class TimeAbsenceOwnerIngressFilter extends OncePerRequestFilter {

    private static final String ENABLED =
            "dwp.hris.tim003.employment-absence-owner.enabled";
    private static final Set<String> QUERY_FIELDS = Set.of(
            "minimumRelationshipRevision", "effectiveOn");
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
        return !isPlanePath(request.getRequestURI());
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
        if (!isCanonicalRelationshipPath(request.getRequestURI())) {
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
        Route route = route(request);
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
                    || !exactCorrelationHeader(request, verified.correlationId())) {
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

    static boolean matchesVerifiedRequest(
            HttpServletRequest request,
            TimeAbsenceOwnerContract.VerifiedRequest verified) {
        Route current = route(request);
        return current != null
                && "GET".equals(request.getMethod())
                && verified.method().equals(request.getMethod())
                && verified.path().equals(current.path())
                && verified.queryFingerprint().equals(current.queryFingerprint())
                && verified.request().equals(current.request())
                && exactCorrelationHeader(request, verified.correlationId());
    }

    static Route route(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(TimeAbsenceOwnerContract.PATH_PREFIX)) {
            return null;
        }
        String rawId = path.substring(TimeAbsenceOwnerContract.PATH_PREFIX.length());
        UUID relationshipPublicId = canonicalUuid(rawId);
        if (relationshipPublicId == null) return null;

        Map<String, String[]> parameters = request.getParameterMap();
        if (!parameters.keySet().equals(QUERY_FIELDS)) return null;
        String minimumRaw = single(parameters, "minimumRelationshipRevision");
        String effectiveOnRaw = single(parameters, "effectiveOn");
        if (minimumRaw == null || effectiveOnRaw == null) return null;
        try {
            long minimum = Long.parseLong(minimumRaw);
            LocalDate effectiveOn = LocalDate.parse(effectiveOnRaw);
            if (minimum < 0 || !Long.toString(minimum).equals(minimumRaw)
                    || !effectiveOn.toString().equals(effectiveOnRaw)) {
                return null;
            }
            String first = "minimumRelationshipRevision=" + minimumRaw
                    + "&effectiveOn=" + effectiveOnRaw;
            String second = "effectiveOn=" + effectiveOnRaw
                    + "&minimumRelationshipRevision=" + minimumRaw;
            String rawQuery = request.getQueryString();
            if (!first.equals(rawQuery) && !second.equals(rawQuery)) return null;
            TimeAbsenceOwnerContract.Request ownerRequest =
                    new TimeAbsenceOwnerContract.Request(
                            relationshipPublicId, minimum, effectiveOn);
            return new Route(path, ownerRequest, fingerprint(path, ownerRequest));
        } catch (NumberFormatException | DateTimeParseException exception) {
            return null;
        }
    }

    private static boolean isPlanePath(String path) {
        String root = TimeAbsenceOwnerContract.PATH_PREFIX.substring(
                0, TimeAbsenceOwnerContract.PATH_PREFIX.length() - 1);
        return path != null && (path.equals(root) || path.startsWith(root + "/"));
    }

    private static boolean isCanonicalRelationshipPath(String path) {
        if (path == null || !path.startsWith(TimeAbsenceOwnerContract.PATH_PREFIX)) {
            return false;
        }
        return canonicalUuid(path.substring(
                TimeAbsenceOwnerContract.PATH_PREFIX.length())) != null;
    }

    private static String single(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);
        if (values == null || values.length != 1) return null;
        String value = values[0];
        return value == null || value.isBlank() || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                ? null : value;
    }

    private static UUID canonicalUuid(String raw) {
        try {
            UUID value = UUID.fromString(raw);
            return value.toString().equals(raw) ? value : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String fingerprint(
            String path,
            TimeAbsenceOwnerContract.Request request) {
        String canonical = "GET\n" + path
                + "\nminimumRelationshipRevision="
                + request.minimumRelationshipRevision()
                + "\neffectiveOn=" + request.effectiveOn();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean exactCorrelationHeader(
            HttpServletRequest request,
            UUID expected) {
        List<String> values = Collections.list(request.getHeaders("X-Correlation-ID"));
        return values.size() == 1 && expected.toString().equals(values.getFirst());
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
        return values.size() == 1 ? canonicalUuid(values.getFirst()) : null;
    }

    record Route(
            String path,
            TimeAbsenceOwnerContract.Request request,
            String queryFingerprint) {
    }
}
