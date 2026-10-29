package com.dwp.services.people.hris.contracts.hrm011;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Exact HRM-011 route and query contract used both before trust admission and
 * when the request-local verified context is consumed.
 */
final class Hrm011WorkloadRouteContract {

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

    private Hrm011WorkloadRouteContract() {
    }

    static boolean isPlanePath(String path) {
        return path != null && (path.equals(COMPENSATION_PATH)
                || path.startsWith(COMPENSATION_PATH + "/")
                || path.equals(PAYROLL_FACTS_PATH)
                || path.startsWith(PAYROLL_FACTS_PATH + "/"));
    }

    static boolean matchesVerifiedRequest(
            HttpServletRequest request,
            Hrm011WorkloadContract.VerifiedRequestView verified) {
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

    static Route route(HttpServletRequest request) {
        String path = request.getRequestURI();
        String bindingId;
        String expectedPurpose;
        Set<String> projections;
        if (COMPENSATION_PATH.equals(path)) {
            bindingId = COMPENSATION_BINDING;
            expectedPurpose = Hrm011WorkloadContract.SVC003_PURPOSE;
            projections = Set.of(Hrm011WorkloadContract.COMPENSATION);
        } else if (PAYROLL_FACTS_PATH.equals(path)) {
            bindingId = PAYROLL_FACTS_BINDING;
            expectedPurpose = Hrm011WorkloadContract.SVC005_PURPOSE;
            projections = Hrm011WorkloadContract.PAYROLL_PROJECTIONS;
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
        if (!expectedPurpose.equals(purpose) || !projections.contains(projection)) {
            return null;
        }
        try {
            Instant asOf = Instant.parse(rawAsOf);
            int limit = Integer.parseInt(rawLimit);
            if (!asOf.toString().equals(rawAsOf) || limit < 1 || limit > 500
                    || !Integer.toString(limit).equals(rawLimit)) {
                return null;
            }
            var snapshotRequest = new Hrm011WorkloadContract.SnapshotRequest(
                    purpose, asOf, projection, limit, cursor);
            return new Route(path, bindingId, snapshotRequest,
                    fingerprint(path, snapshotRequest));
        } catch (DateTimeParseException | NumberFormatException exception) {
            return null;
        }
    }

    private static boolean exactCorrelationHeader(
            HttpServletRequest request,
            Hrm011WorkloadContract.VerifiedRequestView verified) {
        java.util.List<String> values = Collections.list(
                request.getHeaders("X-Correlation-ID"));
        return values.size() == 1
                && verified.correlationId().toString().equals(values.getFirst());
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
            String path, Hrm011WorkloadContract.SnapshotRequest request) {
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

    record Route(
            String path,
            String bindingId,
            Hrm011WorkloadContract.SnapshotRequest request,
            String queryFingerprint) {
    }
}
