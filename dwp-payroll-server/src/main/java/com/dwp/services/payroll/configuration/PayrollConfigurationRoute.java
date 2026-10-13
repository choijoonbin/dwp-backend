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
            "route.hcm.configuration.payroll.page",
            Action.WORKBENCH_VIEW, false, false),
    ELEMENT_VERSION_CREATE(
            "POST", "/v1/elements/versions", "PEP-PAY-004",
            "route.hcm.configuration.payroll-element-version-create.action",
            Action.ELEMENT_VERSION_CREATE, true, false),
    FORMULA_VERSION_CREATE(
            "POST", "/v1/formulas/versions", "PEP-PAY-005",
            "route.hcm.configuration.payroll-formula-version-create.action",
            Action.FORMULA_VERSION_CREATE, true, false),
    FORMULA_VALIDATE(
            "POST", "/v1/formulas/" + uuid() + "/validations", "PEP-PAY-006",
            "route.hcm.configuration.payroll-formula-validate.action",
            Action.FORMULA_VALIDATE, true, false),
    FORMULA_SIMULATE(
            "POST", "/v1/formulas/" + uuid() + "/simulations", "PEP-PAY-007",
            "route.hcm.configuration.payroll-formula-simulate.action",
            Action.FORMULA_SIMULATE, true, false),
    FORMULA_PUBLISH(
            "POST", "/v1/formulas/" + uuid() + "/publish", "PEP-PAY-008",
            "route.hcm.configuration.payroll-formula-publish.action",
            Action.FORMULA_PUBLISH, true, true);

    static final long AUTHORIZATION_VERSION = 38;
    static final String AUTHORIZATION_SHA256 =
            "a41145d6a2faefd2e6030a2ca9a070769019d4db5513ea214bf542dda31c374a";
    static final String GATEWAY_OPENAPI_SHA256 =
            "453f3620dca02a5ce82df273be986633fb5d694084ca080859bb47472027d234";

    private final String method;
    private final Pattern path;
    private final String bindingId;
    private final String authorityContractKey;
    private final Action action;
    private final boolean command;
    private final boolean elevated;

    PayrollConfigurationRoute(
            String method,
            String path,
            String bindingId,
            String authorityContractKey,
            Action action,
            boolean command,
            boolean elevated) {
        this.method = method;
        this.path = Pattern.compile("^" + path + "$");
        this.bindingId = bindingId;
        this.authorityContractKey = authorityContractKey;
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

    String bindingId() {
        return bindingId;
    }

    String authorityContractKey() {
        return authorityContractKey;
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
