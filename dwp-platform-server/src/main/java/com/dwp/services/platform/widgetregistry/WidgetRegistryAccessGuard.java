package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class WidgetRegistryAccessGuard {
    private static final String READ = "WIDGET_CATALOG_READ";
    private static final String WRITE = "WIDGET_DEFINITION_WRITE";
    private static final String REVIEW = "WIDGET_DEFINITION_REVIEW";
    private static final String RELEASE = "WIDGET_DEFINITION_RELEASE";
    private static final String REVOKE = "WIDGET_DEFINITION_REVOKE";
    private static final String TENANT_VIEW = "ADMIN.HOME_WIDGET_POLICY:VIEW";
    private static final String TENANT_MANAGE = "ADMIN.HOME_WIDGET_POLICY:MANAGE";
    private static final String TENANT_PUBLISH = "ADMIN.HOME_WIDGET_POLICY:PUBLISH";
    private static final String TENANT_EXPLAIN = "ADMIN.HOME_WIDGET_POLICY:EXPLAIN";
    private static final String TENANT_AUDIT = "ADMIN.HOME_WIDGET_POLICY:AUDIT";

    void authorize(HttpServletRequest request) {
        Set<String> permissions = values(request.getHeader("X-DWP-Permissions"));
        Set<String> required = required(request);
        if (required.isEmpty() && protectedNamespace(request.getRequestURI())
                && !"OPTIONS".equalsIgnoreCase(request.getMethod())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        if (!required.isEmpty() && required.stream().noneMatch(permissions::contains)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    Set<String> required(HttpServletRequest request) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if ("HEAD".equals(method)) method = "GET";
        String path = request.getRequestURI();

        if (path.equals("/v1/admin/widget-registry/readiness")
                || path.equals("/v1/admin/widget-registry/events")) {
            return get(method, READ);
        }
        if (path.equals("/v1/admin/widget-catalog")) return get(method, TENANT_VIEW);
        if (path.matches("^/v1/admin/widget-catalog/[^/]+/explain$")) {
            return get(method, TENANT_EXPLAIN);
        }
        if (path.startsWith("/v1/admin/widget-policies/")) {
            if ("GET".equals(method)) {
                if (path.endsWith("/impact") || path.endsWith("/revoke-impact")
                        || path.endsWith("/rollback-impact")) return Set.of(TENANT_EXPLAIN);
                if (path.endsWith("/history")) return Set.of(TENANT_AUDIT);
                return Set.of(TENANT_VIEW);
            }
            if ("PUT".equals(method) && path.matches(
                    "^/v1/admin/widget-policies/[^/]+/revisions/[^/]+$")) {
                return Set.of(TENANT_MANAGE);
            }
            if ("POST".equals(method)) {
                if (path.endsWith("/revisions")) return Set.of(TENANT_MANAGE);
                if (path.endsWith("/publish") || path.endsWith("/revoke")
                        || path.endsWith("/rollback")) return Set.of(TENANT_PUBLISH);
            }
            return Set.of();
        }

        if (path.equals("/v1/admin/widget-definitions")) {
            return switch (method) {
                case "GET" -> Set.of(READ);
                case "POST" -> Set.of(WRITE);
                default -> Set.of();
            };
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+$")) return get(method, READ);
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/versions$")) {
            return switch (method) {
                case "GET" -> Set.of(READ);
                case "POST" -> Set.of(WRITE);
                default -> Set.of();
            };
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/retirement-impact$")) {
            return get(method, RELEASE);
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/retire$")) {
            return post(method, RELEASE);
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/channels/[^/]+$")) {
            return get(method, READ);
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/channels/[^/]+/impact$")) {
            return get(method, RELEASE);
        }
        if (path.matches("^/v1/admin/widget-definitions/[^/]+/channels/[^/]+/(promote|rollback)$")) {
            return post(method, RELEASE);
        }

        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+$")) {
            return switch (method) {
                case "GET" -> Set.of(READ);
                case "PUT" -> Set.of(WRITE);
                default -> Set.of();
            };
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+/impact$")) {
            if (!"GET".equals(method)) return Set.of();
            String operation = request.getParameter("operation");
            return Set.of(Set.of("BLOCK", "QUARANTINE", "REVOKE").contains(operation)
                    ? REVOKE : RELEASE);
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+/(validate|submit|rework)$")) {
            return post(method, WRITE);
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+/decision$")) {
            return post(method, REVIEW);
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+/evidence$")) {
            return switch (method) {
                case "GET" -> Set.of(READ);
                case "POST" -> Set.of(REVIEW);
                default -> Set.of();
            };
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+/evidence/[^/]+$")) {
            return get(method, READ);
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+/(publish|deprecate)$")) {
            return post(method, RELEASE);
        }
        if (path.matches("^/v1/admin/widget-definition-versions/[^/]+/(block|quarantine|revoke)$")) {
            return post(method, REVOKE);
        }

        if (path.equals("/v1/admin/widget-runtime-controls")) return get(method, READ);
        if (path.equals("/v1/admin/widget-runtime-controls/disable")) return post(method, REVOKE);
        if (path.matches("^/v1/admin/widget-runtime-controls/[^/]+/enable-approvals$")) {
            return post(method, REVIEW);
        }
        if (path.matches("^/v1/admin/widget-runtime-controls/[^/]+/enable$")) {
            return post(method, RELEASE);
        }
        return Set.of();
    }

    private static boolean protectedNamespace(String path) {
        return path.equals("/v1/admin/widget-definitions")
                || path.startsWith("/v1/admin/widget-definitions/")
                || path.startsWith("/v1/admin/widget-definition-versions/")
                || path.equals("/v1/admin/widget-runtime-controls")
                || path.startsWith("/v1/admin/widget-runtime-controls/")
                || path.startsWith("/v1/admin/widget-registry/")
                || path.equals("/v1/admin/widget-catalog")
                || path.startsWith("/v1/admin/widget-catalog/")
                || path.startsWith("/v1/admin/widget-policies/");
    }

    private static Set<String> get(String method, String permission) {
        return ("GET".equals(method) || "HEAD".equals(method))
                ? Set.of(permission) : Set.of();
    }

    private static Set<String> post(String method, String permission) {
        return "POST".equals(method) ? Set.of(permission) : Set.of();
    }

    private static Set<String> values(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
