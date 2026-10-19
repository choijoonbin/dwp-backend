package com.dwp.services.payroll.statutory.pay003;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

enum Pay003Route {
    STATUTORY(
            "GET", Pattern.compile("^/v1/workbench/configuration/statutory$"),
            Set.of("route.hcm.configuration.statutory.page",
                    "route.hcm.configuration.statutory.data"),
            "CONFIG.HRIS_WORKBENCH:VIEW",
            Pay003Models.CONFIGURATION_PURPOSE, "CONFIG_SCOPE",
            "pay003.iaq-026.fields.v1", false),
    TAX_SOCIAL_INSURANCE(
            "GET", Pattern.compile("^/v1/workbench/pay-tax-social-insurance$"),
            Set.of("route.hcm.operations.pay-tax-social-insurance.page",
                    "route.hcm.operations.pay-tax-social-insurance.data"),
            "DATA.HRIS_PAYROLL_STATUS:VIEW",
            Pay003Models.OPERATIONS_PURPOSE, "TARGET_POPULATION",
            "pay003.iaq-048.fields.v1", false),
    ACTIVATE(
            "POST", Pattern.compile(
                    "^/v1/country-packs/[0-9a-f]{8}-[0-9a-f]{4}-"
                            + "[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/activate$"),
            Set.of("route.hcm.operations.country-pack-activate.action"),
            "ACTION.HRIS_STATUTORY_FILING:EXECUTE",
            Pay003Models.ACTIVATION_PURPOSE, "TARGET_POPULATION", null, true);

    private final String method;
    private final Pattern path;
    private final Set<String> routeContractKeys;
    private final String capability;
    private final String purpose;
    private final String populationKind;
    private final String fieldPolicyKey;
    private final boolean highRisk;

    Pay003Route(
            String method,
            Pattern path,
            Set<String> routeContractKeys,
            String capability,
            String purpose,
            String populationKind,
            String fieldPolicyKey,
            boolean highRisk) {
        this.method = method;
        this.path = path;
        this.routeContractKeys = Set.copyOf(routeContractKeys);
        this.capability = capability;
        this.purpose = purpose;
        this.populationKind = populationKind;
        this.fieldPolicyKey = fieldPolicyKey;
        this.highRisk = highRisk;
    }

    String routeContractKey() {
        if (routeContractKeys.size() != 1) {
            throw new IllegalStateException("Read route has PAGE and DATA keys");
        }
        return routeContractKeys.iterator().next();
    }

    String capability() {
        return capability;
    }

    String purpose() {
        return purpose;
    }

    String populationKind() {
        return populationKind;
    }

    String fieldPolicyKey() {
        return fieldPolicyKey;
    }

    boolean highRisk() {
        return highRisk;
    }

    static Optional<Pay003Route> resolve(
            String method, String path, String trustedRouteKey) {
        return Set.of(values()).stream()
                .filter(route -> route.method.equals(method)
                        && route.path.matcher(path).matches()
                        && route.routeContractKeys.contains(trustedRouteKey))
                .findFirst();
    }

    static boolean owns(String method, String path) {
        return Set.of(values()).stream().anyMatch(route ->
                route.method.equals(method) && route.path.matcher(path).matches());
    }
}
