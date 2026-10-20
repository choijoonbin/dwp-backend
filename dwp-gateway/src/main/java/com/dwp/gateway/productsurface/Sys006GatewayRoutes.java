package com.dwp.gateway.productsurface;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Closed SYS-006 browser-to-owner operation boundary. This class does not mount routes. */
public final class Sys006GatewayRoutes {
    public static final String PURPOSE = "HRIS_SYSTEM_ADMIN";
    public static final String FEATURE_KEY = "HRIS_AUTOMATION_CONTROL_PLANE";
    public static final String CREATE_DEFINITION_ROUTE_KEY =
            "route.hcm.configuration.automation-definition-create.action";

    public static final List<Binding> BINDINGS = List.of(
            binding("SYS-API-017.GET", "PEP-SYS-017", "GET",
                    "/api/platform/v1/admin/hris/automations",
                    "route.hcm.configuration.automation-catalog.data", "DATA"),
            binding("SYS-API-017.POST", "PEP-SYS-018", "POST",
                    "/api/platform/v1/admin/hris/automations",
                    CREATE_DEFINITION_ROUTE_KEY, "ACTION"),
            binding("SYS-API-018", "PEP-SYS-019", "POST",
                    "/api/platform/v1/admin/hris/automations/{automationId}/runs",
                    "route.hcm.configuration.automation-run-queue.action", "ACTION"),
            binding("SYS-API-019", "PEP-SYS-020", "GET",
                    "/api/platform/v1/admin/hris/automation-runs/{runId}",
                    "route.hcm.configuration.automation-run-detail.data", "DATA"),
            binding("SYS-API-020", "PEP-SYS-021", "POST",
                    "/api/platform/v1/admin/hris/automation-runs/{runId}/retry",
                    "route.hcm.configuration.automation-run-retry.action", "ACTION"),
            binding("SYS-API-046", "PEP-SYS-048", "POST",
                    "/api/platform/v1/admin/hris/automations/{automationId}/versions",
                    "route.hcm.configuration.automation-version-create.action", "ACTION"),
            binding("SYS-API-047", "PEP-SYS-049", "POST",
                    "/api/platform/v1/admin/hris/automations/{automationId}/versions/"
                            + "{version}/validate",
                    "route.hcm.configuration.automation-version-validate.action", "ACTION"),
            binding("SYS-API-048", "PEP-SYS-050", "POST",
                    "/api/platform/v1/admin/hris/automations/{automationId}/versions/"
                            + "{version}/submit",
                    "route.hcm.configuration.automation-version-submit.action", "ACTION"),
            binding("SYS-API-049", "PEP-SYS-051", "POST",
                    "/api/platform/v1/admin/hris/automations/{automationId}/versions/"
                            + "{version}/approve",
                    "route.hcm.configuration.automation-version-approve.action", "ACTION"),
            binding("SYS-API-050", "PEP-SYS-052", "POST",
                    "/api/platform/v1/admin/hris/automations/{automationId}/versions/"
                            + "{version}/activate",
                    "route.hcm.configuration.automation-version-activate.action", "ACTION"),
            binding("SYS-API-051", "PEP-SYS-053", "POST",
                    "/api/platform/v1/admin/hris/automations/{automationId}/schedules",
                    "route.hcm.configuration.automation-schedule-create.action", "ACTION"),
            binding("SYS-API-052", "PEP-SYS-054", "POST",
                    "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/submit",
                    "route.hcm.configuration.automation-schedule-submit.action", "ACTION"),
            binding("SYS-API-053", "PEP-SYS-055", "POST",
                    "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/approve",
                    "route.hcm.configuration.automation-schedule-approve.action", "ACTION"),
            binding("SYS-API-054", "PEP-SYS-056", "POST",
                    "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/activate",
                    "route.hcm.configuration.automation-schedule-activate.action", "ACTION"),
            binding("SYS-API-055", "PEP-SYS-057", "POST",
                    "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/suspend",
                    "route.hcm.configuration.automation-schedule-suspend.action", "ACTION"),
            binding("SYS-API-056", "PEP-SYS-058", "POST",
                    "/api/platform/v1/admin/hris/automation-runs/{runId}/cancel",
                    "route.hcm.configuration.automation-run-cancel.action", "ACTION"));

    private static final Map<String, Binding> BY_ROUTE_KEY = BINDINGS.stream()
            .collect(Collectors.toUnmodifiableMap(Binding::routeContractKey, Function.identity()));
    public static final Set<String> ROUTE_KEYS = BY_ROUTE_KEY.keySet();

    private Sys006GatewayRoutes() { }

    public static Binding binding(String routeContractKey) {
        return BY_ROUTE_KEY.get(routeContractKey);
    }

    /** Resource action descriptors carry expectedObjectRevision; collection create forbids it. */
    public static boolean requiresExpectedObjectRevision(String routeContractKey) {
        Binding binding = binding(routeContractKey);
        return binding != null
                && binding.stateChanging()
                && !CREATE_DEFINITION_ROUTE_KEY.equals(routeContractKey);
    }

    /** Claims the three exact public operation families so an unknown sibling cannot fall through. */
    public static boolean claimsPath(String path) {
        return isFamily(path, "/api/platform/v1/admin/hris/automations")
                || isFamily(path, "/api/platform/v1/admin/hris/automation-runs")
                || isFamily(path, "/api/platform/v1/admin/hris/automation-schedules");
    }

    public static boolean matches(
            GeneratedProductRouteCatalog.Route route,
            String requestMethod,
            String requestPath) {
        if (route == null) return false;
        Binding binding = binding(route.routeContractKey());
        return binding != null
                && binding.method().equals(requestMethod)
                && binding.method().equals(route.method())
                && binding.browserPath().equals(route.publicPath())
                && binding.routeKind().equals(route.routeKind())
                && binding.stateChanging() == route.stateChanging()
                && "hcm".equals(route.productKey())
                && "hcm.management".equals(route.surfaceKey())
                && route.exactPattern() != null
                && requestPath != null
                && route.exactPattern().matcher(requestPath).matches();
    }

    private static boolean isFamily(String path, String root) {
        return path != null && (root.equals(path) || path.startsWith(root + "/"));
    }

    private static Binding binding(
            String api,
            String pep,
            String method,
            String browserPath,
            String routeContractKey,
            String routeKind) {
        return new Binding(api, pep, method, browserPath, routeContractKey, routeKind,
                "ACTION".equals(routeKind));
    }

    public record Binding(
            String api,
            String pep,
            String method,
            String browserPath,
            String routeContractKey,
            String routeKind,
            boolean stateChanging) {
    }
}
