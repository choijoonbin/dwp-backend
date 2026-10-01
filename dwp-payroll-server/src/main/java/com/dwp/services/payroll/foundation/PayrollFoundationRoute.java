package com.dwp.services.payroll.foundation;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationAction;

/** Exact active owner bindings for the payroll-foundation surface. */
enum PayrollFoundationRoute {
    LIST(
            "GET", "/v1/hris/payroll/foundation/configurations",
            "route.hcm.operations.payroll-foundation-configurations.data",
            FoundationAction.VIEW, "DATA.HR_PAY:VIEW", "PAY_TARGET_POPULATION", false, false),
    CREATE(
            "POST", "/v1/hris/payroll/foundation/configurations",
            "route.hcm.operations.payroll-foundation-create.action",
            FoundationAction.EDIT, "DATA.HR_PAY:CREATE", "PAYROLL_LEGAL_ENTITY_SCOPE", true, false),
    DETAIL(
            "GET", "/v1/hris/payroll/foundation/configurations/" + uuid(),
            "route.hcm.operations.payroll-foundation-configuration.data",
            FoundationAction.VIEW, "DATA.HR_PAY:VIEW", "PAY_TARGET_POPULATION", false, false),
    VERSIONS(
            "GET", "/v1/hris/payroll/foundation/configurations/" + uuid() + "/versions",
            "route.hcm.operations.payroll-foundation-versions.data",
            FoundationAction.VIEW, "DATA.HR_PAY:VIEW", "PAY_TARGET_POPULATION", false, false),
    UPDATE(
            "PUT", "/v1/hris/payroll/foundation/configurations/" + uuid(),
            "route.hcm.operations.payroll-foundation-update.action",
            FoundationAction.EDIT, "DATA.HR_PAY:UPDATE", "PAYROLL_LEGAL_ENTITY_SCOPE", true, false),
    SIMULATE(
            "POST", "/v1/hris/payroll/foundation/configurations/" + uuid() + "/simulations",
            "route.hcm.operations.payroll-foundation-simulate.action",
            FoundationAction.SIMULATE, "DATA.HR_PAY:SIMULATE", "PAYROLL_LEGAL_ENTITY_SCOPE", true, false),
    PUBLISH(
            "POST", "/v1/hris/payroll/foundation/configurations/" + uuid() + "/publish",
            "route.hcm.operations.payroll-foundation-publish.action",
            FoundationAction.PUBLISH, "DATA.HR_PAY:PUBLISH", "PAYROLL_LEGAL_ENTITY_SCOPE", true, true),
    REVERSE(
            "POST", "/v1/hris/payroll/foundation/configurations/" + uuid() + "/reversals",
            "route.hcm.operations.payroll-foundation-reverse.action",
            FoundationAction.REVERSE, "DATA.HR_PAY:REVERSE", "PAYROLL_LEGAL_ENTITY_SCOPE", true, true),
    RECEIPT(
            "GET", "/v1/hris/payroll/foundation/receipts/" + uuid(),
            "route.hcm.operations.payroll-foundation-receipt.data",
            FoundationAction.VIEW, "DATA.HR_PAY:VIEW", "PAY_TARGET_POPULATION", false, false),
    RECONCILE(
            "POST", "/v1/hris/payroll/foundation/receipts/" + uuid() + "/reconcile",
            "route.hcm.operations.payroll-foundation-reconcile.action",
            FoundationAction.RECONCILE, "DATA.HR_PAY:RECONCILE", "PAYROLL_LEGAL_ENTITY_SCOPE", true, false);

    private final String method;
    private final Pattern path;
    private final String routeContractKey;
    private final FoundationAction action;
    private final String capability;
    private final String scopeSource;
    private final boolean command;
    private final boolean elevated;

    PayrollFoundationRoute(
            String method,
            String path,
            String routeContractKey,
            FoundationAction action,
            String capability,
            String scopeSource,
            boolean command,
            boolean elevated) {
        this.method = method;
        this.path = Pattern.compile("^" + path + "$");
        this.routeContractKey = routeContractKey;
        this.action = action;
        this.capability = capability;
        this.scopeSource = scopeSource;
        this.command = command;
        this.elevated = elevated;
    }

    static Optional<PayrollFoundationRoute> resolve(String method, String path) {
        return Arrays.stream(values())
                .filter(route -> route.method.equals(method) && route.path.matcher(path).matches())
                .findFirst();
    }

    String routeContractKey() {
        return routeContractKey;
    }

    FoundationAction action() {
        return action;
    }

    String capability() {
        return capability;
    }

    String scopeSource() {
        return scopeSource;
    }

    boolean command() {
        return command;
    }

    boolean requiresElevatedAccess() {
        return elevated;
    }

    private static String uuid() {
        return "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    }
}
