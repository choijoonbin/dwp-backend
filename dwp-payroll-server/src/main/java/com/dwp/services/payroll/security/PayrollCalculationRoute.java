package com.dwp.services.payroll.security;

import java.util.Set;

import static com.dwp.services.payroll.security.PayrollCalculationAuthority.Action;

enum PayrollCalculationRoute {
    CALCULATE(
            "route.hcm.operations.payroll-run-calculate.action",
            Action.CALCULATE, "PAYROLL_PROCESSING",
            Set.of("LEGAL_ENTITY", "PAY_GROUP"),
            Set.of("PAY_INPUT", "PAY_RESULT"), false),
    RUN_VIEW(
            "route.hcm.operations.payroll-run.data",
            Action.RUN_VIEW, "PAYROLL_PROCESSING",
            Set.of("LEGAL_ENTITY", "PAY_GROUP", "NAMED_POPULATION"),
            Set.of("PAY_INPUT", "PAY_RESULT", "PAY_RESULT_MASKED"), false),
    APPROVE(
            "route.hcm.operations.payroll-run-approve.action",
            Action.APPROVE, "PAYROLL_PROCESSING",
            Set.of("LEGAL_ENTITY", "PAY_GROUP"),
            Set.of("PAY_RESULT", "TAX_LIMITED"), true),
    FINALIZE(
            "route.hcm.operations.payroll-run-finalize.action",
            Action.FINALIZE, "PAYROLL_PROCESSING",
            Set.of("LEGAL_ENTITY", "PAY_GROUP"),
            Set.of("BANK_PAYMENT", "PAY_RESULT", "PAY_STATEMENT"), true),
    RECEIPT_VIEW(
            "route.hcm.operations.payroll-command-receipt.data",
            Action.RECEIPT_VIEW, "COMMAND_STATUS",
            Set.of("ORIGINATING_COMMAND_SCOPE"),
            Set.of("COMMAND_RECEIPT"), false);
    private final String routeContractKey;
    private final Action action;
    private final String purposeCode;
    private final Set<String> populations;
    private final Set<String> fieldGroups;
    private final boolean highRisk;

    PayrollCalculationRoute(
            String routeContractKey,
            Action action,
            String purposeCode,
            Set<String> populations,
            Set<String> fieldGroups,
            boolean highRisk) {
        this.routeContractKey = routeContractKey;
        this.action = action;
        this.purposeCode = purposeCode;
        this.populations = Set.copyOf(populations);
        this.fieldGroups = Set.copyOf(fieldGroups);
        this.highRisk = highRisk;
    }

    String routeContractKey() { return routeContractKey; }

    Action action() { return action; }

    String purposeCode() { return purposeCode; }

    boolean populationAllowed(String populationKind) {
        return populations.contains(populationKind);
    }

    Set<String> fieldGroups() { return fieldGroups; }

    boolean highRisk() { return highRisk; }

    boolean command() { return action != Action.RUN_VIEW && action != Action.RECEIPT_VIEW; }

}
