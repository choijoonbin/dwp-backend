package com.dwp.services.people.hris.contracts.tim003;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exact SVC-PEP-HRM-007 route contract shared by ingress verification and
 * request-context consumption.
 */
final class TimeAbsenceOwnerRouteContract {

    private static final Set<String> QUERY_FIELDS = Set.of(
            "minimumRelationshipRevision", "effectiveOn");

    private TimeAbsenceOwnerRouteContract() {
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

    static boolean isPlanePath(String path) {
        String root = TimeAbsenceOwnerContract.PATH_PREFIX.substring(
                0, TimeAbsenceOwnerContract.PATH_PREFIX.length() - 1);
        return path != null && (path.equals(root) || path.startsWith(root + "/"));
    }

    static boolean isCanonicalRelationshipPath(String path) {
        if (path == null || !path.startsWith(TimeAbsenceOwnerContract.PATH_PREFIX)) {
            return false;
        }
        return canonicalUuid(path.substring(
                TimeAbsenceOwnerContract.PATH_PREFIX.length())) != null;
    }

    static boolean exactCorrelationHeader(HttpServletRequest request, UUID expected) {
        List<String> values = Collections.list(request.getHeaders("X-Correlation-ID"));
        return values.size() == 1 && expected.toString().equals(values.getFirst());
    }

    static UUID canonicalUuid(String raw) {
        try {
            UUID value = UUID.fromString(raw);
            return value.toString().equals(raw) ? value : null;
        } catch (RuntimeException exception) {
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

    record Route(
            String path,
            TimeAbsenceOwnerContract.Request request,
            String queryFingerprint) {
    }
}
