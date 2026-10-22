package com.dwp.services.payroll.statutory.pay004;

import java.util.Optional;
import java.util.Set;

enum Pay004Route {
    RETIREMENT(Set.of(
            "route.hcm.operations.pay-retirement.page",
            "route.hcm.operations.pay-retirement.data"));

    private static final String METHOD = "GET";
    private static final String PATH = "/v1/workbench/pay-retirement";
    private final Set<String> routeContractKeys;

    Pay004Route(Set<String> routeContractKeys) {
        this.routeContractKeys = Set.copyOf(routeContractKeys);
    }

    String capability() { return Pay004Models.CAPABILITY; }
    String purpose() { return Pay004Models.PURPOSE; }
    String populationKind() { return "TARGET_POPULATION"; }
    String fieldPolicyKey() { return Pay004Models.FIELD_POLICY; }

    static Optional<Pay004Route> resolve(
            String method, String path, String routeContractKey) {
        if (METHOD.equals(method) && PATH.equals(path)
                && RETIREMENT.routeContractKeys.contains(routeContractKey)) {
            return Optional.of(RETIREMENT);
        }
        return Optional.empty();
    }

    static boolean owns(String method, String path) {
        return METHOD.equals(method) && PATH.equals(path);
    }
}
