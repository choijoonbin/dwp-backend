package com.dwp.services.payroll.foundation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationAction;

/** Exact v48 owner bindings for the payroll Foundation tab under HCM settings. */
enum PayrollFoundationSettingsRoute {
    LIST("GET", "/v1/settings/payroll/foundation/configurations",
            "route.hcm.configuration.payroll-foundation-configurations.data",
            FoundationAction.VIEW, "CONFIG.HRIS_WORKBENCH:VIEW", false, false),
    CREATE("POST", "/v1/settings/payroll/foundation/configurations",
            "route.hcm.configuration.payroll-foundation-create.action",
            FoundationAction.CREATE, "CONFIG.HRIS_POLICY:UPDATE", true, false),
    DETAIL("GET", "/v1/settings/payroll/foundation/configurations/{configurationId}",
            "route.hcm.configuration.payroll-foundation-configuration.data",
            FoundationAction.VIEW, "CONFIG.HRIS_WORKBENCH:VIEW", false, false),
    VERSIONS("GET", "/v1/settings/payroll/foundation/configurations/{configurationId}/versions",
            "route.hcm.configuration.payroll-foundation-versions.data",
            FoundationAction.VIEW, "CONFIG.HRIS_WORKBENCH:VIEW", false, false),
    UPDATE("PUT", "/v1/settings/payroll/foundation/configurations/{configurationId}",
            "route.hcm.configuration.payroll-foundation-update.action",
            FoundationAction.UPDATE, "CONFIG.HRIS_POLICY:UPDATE", true, false),
    SIMULATE("POST", "/v1/settings/payroll/foundation/configurations/{configurationId}/simulations",
            "route.hcm.configuration.payroll-foundation-simulate.action",
            FoundationAction.SIMULATE, "CONFIG.HRIS_POLICY:UPDATE", true, false),
    PUBLISH("POST", "/v1/settings/payroll/foundation/configurations/{configurationId}/publish",
            "route.hcm.configuration.payroll-foundation-publish.action",
            FoundationAction.PUBLISH, "ACTION.HRIS_POLICY_PUBLISH:PUBLISH", true, true),
    REVERSE("POST", "/v1/settings/payroll/foundation/configurations/{configurationId}/reversals",
            "route.hcm.configuration.payroll-foundation-reverse.action",
            FoundationAction.REVERSE, "ACTION.HRIS_POLICY_PUBLISH:PUBLISH", true, true),
    RECEIPT("GET", "/v1/settings/payroll/foundation/receipts/{commandId}",
            "route.hcm.configuration.payroll-foundation-receipt.data",
            FoundationAction.VIEW, "CONFIG.HRIS_WORKBENCH:VIEW", false, false),
    RECONCILE("POST", "/v1/settings/payroll/foundation/receipts/{commandId}/reconcile",
            "route.hcm.configuration.payroll-foundation-reconcile.action",
            FoundationAction.RECONCILE, "CONFIG.HRIS_POLICY:UPDATE", true, false);

    static final String PATH_PREFIX = "/v1/settings/payroll/foundation";
    static final String PURPOSE = "PAYROLL_CONFIGURATION";

    private final String method;
    private final String pathTemplate;
    private final Pattern path;
    private final String routeContractKey;
    private final FoundationAction action;
    private final String capability;
    private final boolean command;
    private final boolean elevated;

    PayrollFoundationSettingsRoute(
            String method,
            String pathTemplate,
            String routeContractKey,
            FoundationAction action,
            String capability,
            boolean command,
            boolean elevated) {
        this.method = method;
        this.pathTemplate = pathTemplate;
        this.path = Pattern.compile("^" + pathTemplate
                .replace("{configurationId}", uuid())
                .replace("{commandId}", uuid()) + "$");
        this.routeContractKey = routeContractKey;
        this.action = action;
        this.capability = capability;
        this.command = command;
        this.elevated = elevated;
    }

    static Optional<PayrollFoundationSettingsRoute> resolve(String method, String path) {
        return Arrays.stream(values())
                .filter(route -> route.method.equals(method) && route.path.matcher(path).matches())
                .findFirst();
    }

    static Set<FoundationAction> projectedActions(Set<String> permissions) {
        EnumSet<FoundationAction> actions = EnumSet.noneOf(FoundationAction.class);
        if (permissions.contains("CONFIG.HRIS_WORKBENCH:VIEW")) {
            actions.add(FoundationAction.VIEW);
        }
        if (permissions.contains("CONFIG.HRIS_POLICY:UPDATE")) {
            actions.addAll(Set.of(
                    FoundationAction.CREATE,
                    FoundationAction.UPDATE,
                    FoundationAction.SIMULATE,
                    FoundationAction.RECONCILE));
        }
        if (permissions.contains("ACTION.HRIS_POLICY_PUBLISH:PUBLISH")) {
            actions.addAll(Set.of(FoundationAction.PUBLISH, FoundationAction.REVERSE));
        }
        return Set.copyOf(actions);
    }

    String method() { return method; }
    String pathTemplate() { return pathTemplate; }
    String routeContractKey() { return routeContractKey; }
    FoundationAction action() { return action; }
    String capability() { return capability; }
    boolean command() { return command; }
    boolean elevated() { return elevated; }

    String capabilityContractKey() {
        return elevated ? "hcm.configuration.policy.publish"
                : command ? "hcm.configuration.policy.update"
                : "hcm.configuration.workbench.view";
    }

    String profileKey() {
        return elevated ? "configuration-publisher" : "configuration-author";
    }

    Set<String> targetBindingKinds() {
        return this == LIST || this == CREATE
                ? Set.of("CONFIG_SCOPE")
                : Set.of("CONFIG_SCOPE", "OBJECT");
    }

    Set<String> predicatePolicyKeys() {
        return elevated
                ? Set.of(
                        "predicate.hcm-payroll-configuration-authority.v1",
                        "predicate.hcm-payroll-foundation-authority.v1",
                        "predicate.hcm-payroll-foundation-sod.v1")
                : Set.of(
                        "predicate.hcm-payroll-configuration-authority.v1",
                        "predicate.hcm-payroll-foundation-authority.v1");
    }

    private static String uuid() {
        return "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    }
}
