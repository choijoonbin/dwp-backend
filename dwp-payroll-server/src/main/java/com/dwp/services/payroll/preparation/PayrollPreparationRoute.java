package com.dwp.services.payroll.preparation;

import java.util.Set;

enum PayrollPreparationRoute {
    PAGE(
            "route.hcm.operations.payroll-population.page", "GET",
            "/v1/workbench/population", PayrollPreparationRouteKind.PAGE,
            "hcm.operations.payroll-support.view",
            "DATA.HRIS_PAYROLL_STATUS:VIEW",
            "predicate.hcm-payroll-population-authority.v1",
            "PAYROLL_OPERATIONS", "PAY_TARGET_POPULATION",
            Set.of("LEGAL_ENTITY", "PAY_GROUP", "NAMED_POPULATION"), false),
    DATA(
            "route.hcm.operations.payroll-support.data", "GET",
            "/v1/workbench/population", PayrollPreparationRouteKind.DATA,
            "hcm.operations.payroll-support.view",
            "DATA.HRIS_PAYROLL_STATUS:VIEW",
            "predicate.hcm-payroll-population-authority.v1",
            "PAYROLL_OPERATIONS", "PAY_TARGET_POPULATION",
            Set.of("LEGAL_ENTITY", "PAY_GROUP", "NAMED_POPULATION"), false),
    PREPARE(
            "route.hcm.operations.payroll-run.action", "POST", "/v1/runs",
            PayrollPreparationRouteKind.ACTION,
            "hcm.operations.payroll-run.execute",
            "ACTION.HRIS_PAYROLL_RUN:EXECUTE",
            "predicate.hcm-payroll-run-prepare-authority.v1",
            "PAYROLL_PROCESSING", "PAYROLL_LEGAL_ENTITY_GROUP_SCOPE",
            Set.of("LEGAL_ENTITY", "PAY_GROUP", "NAMED_POPULATION"), true);

    private final String routeContractKey;
    private final String method;
    private final String path;
    private final PayrollPreparationRouteKind routeKind;
    private final String capabilityContractKey;
    private final String capabilityCode;
    private final String predicatePolicyKey;
    private final String purposeCode;
    private final String scopeResolver;
    private final Set<String> populationKinds;
    private final boolean commandChallenge;

    PayrollPreparationRoute(
            String routeContractKey,
            String method,
            String path,
            PayrollPreparationRouteKind routeKind,
            String capabilityContractKey,
            String capabilityCode,
            String predicatePolicyKey,
            String purposeCode,
            String scopeResolver,
            Set<String> populationKinds,
            boolean commandChallenge) {
        this.routeContractKey = routeContractKey;
        this.method = method;
        this.path = path;
        this.routeKind = routeKind;
        this.capabilityContractKey = capabilityContractKey;
        this.capabilityCode = capabilityCode;
        this.predicatePolicyKey = predicatePolicyKey;
        this.purposeCode = purposeCode;
        this.scopeResolver = scopeResolver;
        this.populationKinds = Set.copyOf(populationKinds);
        this.commandChallenge = commandChallenge;
    }

    String routeContractKey() { return routeContractKey; }

    String method() { return method; }

    String path() { return path; }

    PayrollPreparationRouteKind routeKind() { return routeKind; }

    String capabilityContractKey() { return capabilityContractKey; }

    String capabilityCode() { return capabilityCode; }

    String predicatePolicyKey() { return predicatePolicyKey; }

    String purposeCode() { return purposeCode; }

    String scopeResolver() { return scopeResolver; }

    Set<String> populationKinds() { return populationKinds; }

    Set<String> activeAccessModes() { return Set.of("ELEVATED"); }

    boolean commandChallenge() { return commandChallenge; }
}
