package com.dwp.services.payroll.configuration;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.dwp.services.payroll.configuration.PayrollConfigurationAccess.Action;

/** Exact owner bindings for IAQ-055 and PEP-PAY-004..008. */
enum PayrollConfigurationRoute {
    WORKBENCH(
            "GET", "/v1/workbench/settings-payroll", "IAQ-055",
            Action.WORKBENCH_VIEW, false, false),
    ELEMENT_VERSION_CREATE(
            "POST", "/v1/elements/versions", "PEP-PAY-004",
            Action.ELEMENT_VERSION_CREATE, true, false),
    FORMULA_VERSION_CREATE(
            "POST", "/v1/formulas/versions", "PEP-PAY-005",
            Action.FORMULA_VERSION_CREATE, true, false),
    FORMULA_VALIDATE(
            "POST", "/v1/formulas/" + uuid() + "/validations", "PEP-PAY-006",
            Action.FORMULA_VALIDATE, true, false),
    FORMULA_SIMULATE(
            "POST", "/v1/formulas/" + uuid() + "/simulations", "PEP-PAY-007",
            Action.FORMULA_SIMULATE, true, false),
    FORMULA_PUBLISH(
            "POST", "/v1/formulas/" + uuid() + "/publish", "PEP-PAY-008",
            Action.FORMULA_PUBLISH, true, true);

    private final String method;
    private final Pattern path;
    private final String contractId;
    private final Action action;
    private final boolean command;
    private final boolean elevated;

    PayrollConfigurationRoute(
            String method,
            String path,
            String contractId,
            Action action,
            boolean command,
            boolean elevated) {
        this.method = method;
        this.path = Pattern.compile("^" + path + "$");
        this.contractId = contractId;
        this.action = action;
        this.command = command;
        this.elevated = elevated;
    }

    static Optional<PayrollConfigurationRoute> resolve(String method, String path) {
        return Arrays.stream(values())
                .filter(route -> route.method.equals(method)
                        && route.path.matcher(path).matches())
                .findFirst();
    }

    static Set<Action> projectedActions(Set<String> permissions) {
        EnumSet<Action> actions = EnumSet.noneOf(Action.class);
        for (Action action : Action.values()) {
            if (permissions.contains(action.permission())) actions.add(action);
        }
        return Set.copyOf(actions);
    }

    String contractId() {
        return contractId;
    }

    Action action() {
        return action;
    }

    String permission() {
        return action.permission();
    }

    boolean command() {
        return command;
    }

    boolean elevated() {
        return elevated;
    }

    private static String uuid() {
        return "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    }
}
