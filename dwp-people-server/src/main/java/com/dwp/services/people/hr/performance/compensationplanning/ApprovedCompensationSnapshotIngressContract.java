package com.dwp.services.people.hr.performance.compensationplanning;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Canonical wire contract for the isolated approved-compensation snapshot
 * ingress. Route parsing is shared by admission and request-context
 * revalidation so neither boundary needs to depend on the other.
 */
final class ApprovedCompensationSnapshotIngressContract {

    static final String PATH_PREFIX =
            "/internal/hris-performance/v1/approved-compensation-plan-snapshots/";

    private static final Set<String> QUERY_FIELDS = Set.of(
            "snapshotRevision", "payloadDigest");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    private ApprovedCompensationSnapshotIngressContract() {
    }

    static boolean isExactGet(String method, String path) {
        return "GET".equals(method) && canonicalSnapshotId(path) != null;
    }

    static boolean isPlanePath(String path) {
        return path != null && (path.equals(
                PATH_PREFIX.substring(0, PATH_PREFIX.length() - 1))
                || path.startsWith(PATH_PREFIX));
    }

    static Route route(HttpServletRequest request) {
        UUID snapshotId = canonicalSnapshotId(request.getRequestURI());
        if (snapshotId == null
                || !request.getParameterMap().keySet().equals(QUERY_FIELDS)) {
            return null;
        }
        String revision = singleParameter(request, "snapshotRevision");
        String digest = singleParameter(request, "payloadDigest");
        if (revision == null || digest == null || !DIGEST.matcher(digest).matches()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(revision);
            if (parsed <= 0 || !Long.toString(parsed).equals(revision)) return null;
            return new Route(snapshotId, parsed, digest);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static UUID canonicalSnapshotId(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX)) return null;
        String raw = path.substring(PATH_PREFIX.length());
        if (raw.isEmpty() || raw.indexOf('/') >= 0) return null;
        try {
            UUID value = UUID.fromString(raw);
            return value.toString().equals(raw) ? value : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String singleParameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1) return null;
        String value = values[0];
        return value == null || value.isBlank() || !value.equals(value.strip())
                ? null : value;
    }

    record Route(UUID snapshotId, long snapshotRevision, String payloadDigest) {
    }
}
