package com.dwp.services.platform.hrisconfiguration.explorer;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

/**
 * Closed adapter for the exact capability-form PAGE decisions currently deployed in v43.
 * POLICY-only and unbound IA paths are intentionally absent.
 */
public final class HrisExplorerV43RouteAdapter {

    public static final String OPERATIONAL_DWP_CONTROL_ROUTE_KEY =
            "hris.explorer.dwp-governance";

    private static final Map<String, RouteSpec> DEPLOYED = Map.ofEntries(
            route("my-employment-documents", "route.hcm.personal.employment-documents.page",
                    "hcm.personal.profile.view"),
            route("team-time", "route.hcm.team.time.page", "hcm.team.time.read"),
            route("hr-people", "route.hcm.operations.people.page",
                    "hcm.operations.workforce.read"),
            route("hr-employment-assignments",
                    "route.hcm.operations.employment-assignments.page",
                    "hcm.operations.people.view"),
            route("hr-employee-change-requests",
                    "route.hcm.operations.employee-change-requests.page",
                    "hcm.operations.people.view"),
            route("time-clock-ingestion", "route.hcm.operations.time-clock-ingestion.page",
                    "hcm.operations.time.view"),
            route("time-interpretation", "route.hcm.operations.time-interpretation.page",
                    "hcm.operations.time.view"),
            route("time-exceptions", "route.hcm.operations.time-exceptions.page",
                    "hcm.operations.time.view"),
            route("performance-review-operations",
                    "route.hcm.operations.performance-review-operations.page",
                    "hcm.operations.performance.view"),
            route("performance-multirater-surveys",
                    "route.hcm.operations.performance-multirater-surveys.page",
                    "hcm.operations.performance.view"),
            route("settings-reference", "route.hcm.configuration.settings-reference.page",
                    "hcm.configuration.workbench.view"),
            route("settings-payroll", "route.hcm.configuration.payroll.page",
                    "hcm.configuration.workbench.view"),
            route("hcm-operations-compensation-planning",
                    "route.hcm.operations.compensation-planning.page",
                    "hcm.compensation.plan.view"),
            route("dwp-governance", "route.hcm.audit.evidence.page",
                    "hcm.audit.evidence.view"));

    private HrisExplorerV43RouteAdapter() {
    }

    public static Set<String> deployedRouteKeys() {
        return DEPLOYED.keySet();
    }

    public static ExplorerAuthorization authorization(String routeKey) {
        RouteSpec spec = DEPLOYED.get(routeKey);
        return spec == null ? null : spec.authorization();
    }

    public static RouteSpec routeSpec(String routeKey) {
        return DEPLOYED.get(routeKey);
    }

    public static boolean exactDecision(
            String routeKey, ExplorerAuthorization authorization) {
        RouteSpec spec = DEPLOYED.get(routeKey);
        return spec != null && authorization != null
                && authorization.equals(spec.authorization());
    }

    private static Map.Entry<String, RouteSpec> route(
            String sourceNodeKey, String routeContractKey, String capabilityKey) {
        String surface = routeContractKey.startsWith("route.hcm.personal.")
                ? "hcm.personal"
                : routeContractKey.startsWith("route.hcm.team.")
                ? "hcm.team"
                : routeContractKey.startsWith("route.hcm.operations.")
                ? "hcm.operations" : "hcm.management";
        return Map.entry("hris.explorer." + sourceNodeKey,
                new RouteSpec(surface, new ExplorerAuthorization(
                        routeContractKey, CapabilityMode.SINGLE,
                        List.of(capabilityKey))));
    }

    public record RouteSpec(String surfaceKey, ExplorerAuthorization authorization) {
    }
}
