package com.dwp.services.time.workregime;

import java.util.Set;
import java.util.regex.Pattern;

import com.dwp.services.time.workregime.WorkRegimeModels.Duty;

/** Exact active owner bindings for the TIM work-regime surface. */
public enum WorkRegimeOwnerRoute {
    LIST(
            "GET", "/v1/hris/work-plans",
            "route.hcm.operations.work-plans-list.data",
            "DATA.HR_TIME:VIEW", Duty.TIME_AUDITOR, false, false,
            Set.of(Duty.TIME_CONFIG_AUTHOR, Duty.TIME_CONFIG_APPROVER,
                    Duty.TIME_OPERATOR, Duty.TIME_AUDITOR)),
    CREATE_DRAFT(
            "POST", "/v1/hris/work-plans/drafts",
            "route.hcm.operations.work-plan-create.action",
            "DATA.HR_TIME:CREATE", Duty.TIME_CONFIG_AUTHOR, true, false,
            Set.of(Duty.TIME_CONFIG_AUTHOR)),
    SIMULATE("POST", "/v1/hris/work-plans/" + uuid() + "/simulations",
            "route.hcm.operations.work-plan-simulate.action",
            "DATA.HR_TIME:SIMULATE", Duty.TIME_CONFIG_AUTHOR, true, false,
            Set.of(Duty.TIME_CONFIG_AUTHOR)),
    VALIDATE("POST", "/v1/hris/work-plans/" + uuid() + "/actions/validate",
            "route.hcm.operations.work-plan-validate.action",
            "DATA.HR_TIME:UPDATE", Duty.TIME_CONFIG_AUTHOR, true, false,
            Set.of(Duty.TIME_CONFIG_AUTHOR)),
    SUBMIT_REVIEW("POST", "/v1/hris/work-plans/" + uuid() + "/actions/submit-review",
            "route.hcm.operations.work-plan-submit-review.action",
            "DATA.HR_TIME:UPDATE", Duty.TIME_CONFIG_AUTHOR, true, false,
            Set.of(Duty.TIME_CONFIG_AUTHOR)),
    APPLY_APPROVAL("POST", "/v1/hris/work-plans/" + uuid() + "/actions/apply-approval",
            "route.hcm.operations.work-plan-apply-approval.action",
            "DATA.HR_TIME:APPROVE", Duty.TIME_CONFIG_APPROVER, true, false,
            Set.of(Duty.TIME_CONFIG_APPROVER)),
    PUBLISH("POST", "/v1/hris/work-plans/" + uuid() + "/actions/publish",
            "route.hcm.operations.work-plan-publish.action",
            "DATA.HR_TIME:PUBLISH", Duty.TIME_CONFIG_APPROVER, true, true,
            Set.of(Duty.TIME_CONFIG_APPROVER)),
    READ_RECEIPT(
            "GET", "/v1/hris/work-plan-receipts/" + uuid(),
            "route.hcm.operations.work-plan-receipt.data",
            "DATA.HR_TIME:VIEW", Duty.TIME_AUDITOR, false, false,
            Set.of(Duty.TIME_CONFIG_AUTHOR, Duty.TIME_CONFIG_APPROVER,
                    Duty.TIME_OPERATOR, Duty.TIME_AUDITOR));

    private final String method;
    private final Pattern path;
    private final String routeContractKey;
    private final String capability;
    private final Duty projectedDuty;
    private final boolean command;
    private final boolean elevated;
    private final Set<Duty> acceptedDuties;

    WorkRegimeOwnerRoute(
            String method,
            String pathPattern,
            String routeContractKey,
            String capability,
            Duty projectedDuty,
            boolean command,
            boolean elevated,
            Set<Duty> acceptedDuties) {
        this.method = method;
        this.path = Pattern.compile("^" + pathPattern + "$");
        this.routeContractKey = routeContractKey;
        this.capability = capability;
        this.projectedDuty = projectedDuty;
        this.command = command;
        this.elevated = elevated;
        this.acceptedDuties = Set.copyOf(acceptedDuties);
    }

    boolean matches(String requestMethod, String requestPath) {
        return method.equals(requestMethod) && path.matcher(requestPath).matches();
    }

    boolean accepts(Set<Duty> duties) {
        return duties.stream().anyMatch(acceptedDuties::contains);
    }

    String routeContractKey() {
        return routeContractKey;
    }

    String capability() {
        return capability;
    }

    Duty projectedDuty() {
        return projectedDuty;
    }

    String scopeSource() {
        return "TIME_TARGET_POPULATION";
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
