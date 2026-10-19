package com.dwp.gateway.productsurface;

import java.util.List;
import java.util.Set;

/** The sealed PER-004 query boundary; this does not register or mount routes. */
public final class Per004GatewayRoutes {
    public static final String FORMS_PATH =
            "/api/people/v1/hris/performance/workbench/forms-scales";
    public static final String SETTINGS_PATH =
            "/api/people/v1/hris/performance/workbench/settings-performance";
    public static final Set<String> ROUTE_KEYS = Set.of(
            "route.hcm.operations.performance-forms-scales.page",
            "route.hcm.operations.performance-forms-scales.data",
            "route.hcm.configuration.settings-performance.page",
            "route.hcm.configuration.settings-performance.data");
    public static final String INBOUND_CHECKED_ATTRIBUTE =
            Per004GatewayRoutes.class.getName() + ".inboundChecked";
    public static final String TENANT_SELECTOR_ATTRIBUTE =
            Per004GatewayRoutes.class.getName() + ".tenantSelector";
    public static final String VERIFIED_IDENTITY_ATTRIBUTE =
            Per004GatewayRoutes.class.getName() + ".verifiedIdentity";

    private Per004GatewayRoutes() { }

    public static boolean isPath(String path) {
        return FORMS_PATH.equals(path) || SETTINGS_PATH.equals(path);
    }

    public static Policy policy(String routeKey) {
        if (!ROUTE_KEYS.contains(routeKey)) return null;
        boolean settings = routeKey.startsWith("route.hcm.configuration.");
        return settings
                ? new Policy(SETTINGS_PATH, "RESOURCE_SET", "CONFIG_SCOPE",
                        "HRIS_CONFIGURATION", "per004.iaq-056.fields.v1",
                        List.of("POLICY_VERSION", "PERFORMANCE_POLICY", "EFFECTIVE_STATUS"))
                : new Policy(FORMS_PATH, "TARGET_POPULATION", "TARGET_POPULATION",
                        "PERFORMANCE_OPERATIONS", "per004.iaq-020.fields.v1",
                        List.of("FORM_VERSION", "RATING_SCALE", "POLICY_STATUS"));
    }

    public record Policy(String path, String authScopeKind, String ownerScopeKind,
                         String purpose, String fieldPolicyKey, List<String> fieldGroups) { }
}
