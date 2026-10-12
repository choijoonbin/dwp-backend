package com.dwp.services.people.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Control-owned recognition boundary for the HRM employee-change handoff.
 *
 * <p>The browser bindings remain governed by the generated v36 People PEP. The internal
 * approval callback belongs to a separate workload-authentication filter: that filter may mark
 * the request only after it has verified the signed workload proof and rejected borrowed browser
 * identity. A caller cannot create the marker with an HTTP header.</p>
 */
public final class HrisEmployeeChangeSecurityBridge {

    public static final String REQUESTS_PAGE_ROUTE =
            "route.hcm.operations.employee-change-requests.page";
    public static final String CREATE_ROUTE =
            "route.hcm.operations.employee-change-request-create.action";
    public static final String SUBMIT_ROUTE =
            "route.hcm.operations.employee-change-request-submit.action";
    public static final String APPLY_ROUTE =
            "route.hcm.operations.employee-change-request-apply.action";

    private static final String WORKBENCH_PATH =
            "/v1/hris/workbench/operations/employee/change/requests";
    private static final String REQUESTS_PATH =
            "/v1/hris/employee-change-requests";
    private static final String CALLBACK_PREFIX =
            "/internal/hris-people/v1/employee-change-requests/";
    private static final String CALLBACK_SUFFIX = "/approval-decisions";
    private static final Pattern REQUEST_COMMAND = Pattern.compile(
            "^" + Pattern.quote(REQUESTS_PATH) + "/([^/]+)/(submit|apply)$");
    private static final Pattern CALLBACK = Pattern.compile(
            "^" + Pattern.quote(CALLBACK_PREFIX)
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                    + Pattern.quote(CALLBACK_SUFFIX) + "$",
            Pattern.CASE_INSENSITIVE);
    private static final Object VERIFIED_WORKLOAD_MARKER = new Object();
    private static final String VERIFIED_WORKLOAD_ATTRIBUTE =
            HrisEmployeeChangeSecurityBridge.class.getName() + ".verifiedApprovalWorkload";

    private HrisEmployeeChangeSecurityBridge() {
    }

    /** Returns the one v36 route key for an exact canonical browser binding. */
    public static String browserRouteContract(String method, String path) {
        if (method == null || path == null || !canonical(path)) return null;
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        if ("GET".equals(normalizedMethod) && WORKBENCH_PATH.equals(path)) {
            return REQUESTS_PAGE_ROUTE;
        }
        if ("POST".equals(normalizedMethod) && REQUESTS_PATH.equals(path)) {
            return CREATE_ROUTE;
        }
        if (!"POST".equals(normalizedMethod)) return null;
        var match = REQUEST_COMMAND.matcher(path);
        if (!match.matches()) return null;
        return "submit".equals(match.group(2)) ? SUBMIT_ROUTE : APPLY_ROUTE;
    }

    /**
     * Claims the narrow HRM browser namespaces before method and canonical-path validation.
     * Encoded separators and lookalike child actions therefore fail closed instead of falling
     * through to an unrelated or legacy controller boundary.
     */
    public static boolean claimsBrowserFamily(String path) {
        if (path == null) return false;
        return claimsBrowserFamilyRaw(path) || claimsBrowserFamilyRaw(decodeForClaim(path));
    }

    private static boolean claimsBrowserFamilyRaw(String path) {
        return WORKBENCH_PATH.equals(path)
                || path.startsWith(WORKBENCH_PATH + "/")
                || path.startsWith(WORKBENCH_PATH + ";")
                || REQUESTS_PATH.equals(path)
                || path.startsWith(REQUESTS_PATH + "/")
                || path.startsWith(REQUESTS_PATH + ";");
    }

    /** True only for the canonical People callback owned by the Approval workload bridge. */
    public static boolean isApprovalDecisionCallback(String method, String path) {
        return "POST".equals(method) && path != null && canonical(path)
                && CALLBACK.matcher(path).matches();
    }

    /**
     * Called by the dedicated workload verifier after signature, binding, replay, and forbidden
     * browser-header checks have all succeeded.
     */
    public static void markApprovalWorkloadVerified(HttpServletRequest request) {
        request.setAttribute(VERIFIED_WORKLOAD_ATTRIBUTE, VERIFIED_WORKLOAD_MARKER);
    }

    public static boolean isVerifiedApprovalDecisionCallback(HttpServletRequest request) {
        return isApprovalDecisionCallback(request.getMethod(), request.getRequestURI())
                && request.getAttribute(VERIFIED_WORKLOAD_ATTRIBUTE)
                == VERIFIED_WORKLOAD_MARKER;
    }

    private static boolean canonical(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return path.startsWith("/") && path.indexOf(';') < 0 && path.indexOf('\\') < 0
                && !lower.contains("%2f") && !lower.contains("%5c")
                && !lower.contains("%3b") && !path.contains("//")
                && !path.contains("/./") && !path.contains("/../")
                && !path.endsWith("/.") && !path.endsWith("/..");
    }

    private static String decodeForClaim(String path) {
        String current = path;
        for (int pass = 0; pass < 2; pass++) {
            StringBuilder decoded = new StringBuilder(current.length());
            boolean changed = false;
            for (int index = 0; index < current.length(); index++) {
                if (current.charAt(index) == '%' && index + 2 < current.length()) {
                    int high = Character.digit(current.charAt(index + 1), 16);
                    int low = Character.digit(current.charAt(index + 2), 16);
                    int value = high < 0 || low < 0 ? -1 : (high << 4) + low;
                    if (value >= 0x20 && value <= 0x7e) {
                        decoded.append((char) value);
                        index += 2;
                        changed = true;
                        continue;
                    }
                }
                decoded.append(current.charAt(index));
            }
            if (!changed) return current;
            current = decoded.toString();
        }
        return current;
    }
}
