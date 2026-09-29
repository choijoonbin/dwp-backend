package com.dwp.services.time.workregime;

import java.util.Set;
import java.util.regex.Pattern;

import com.dwp.services.time.workregime.WorkRegimeModels.Duty;

/** Local owner route identities awaiting their exact central SYS contract bindings. */
public enum WorkRegimeOwnerRoute {
    LIST("GET", "/v1/hris/work-plans", Set.of(
            Duty.TIME_CONFIG_AUTHOR, Duty.TIME_CONFIG_APPROVER,
            Duty.TIME_OPERATOR, Duty.TIME_AUDITOR)),
    CREATE_DRAFT("POST", "/v1/hris/work-plans/drafts", Set.of(Duty.TIME_CONFIG_AUTHOR)),
    SIMULATE("POST", "/v1/hris/work-plans/[0-9a-fA-F-]{36}/simulations",
            Set.of(Duty.TIME_CONFIG_AUTHOR)),
    VALIDATE("POST", "/v1/hris/work-plans/[0-9a-fA-F-]{36}/actions/validate",
            Set.of(Duty.TIME_CONFIG_AUTHOR)),
    SUBMIT_REVIEW("POST", "/v1/hris/work-plans/[0-9a-fA-F-]{36}/actions/submit-review",
            Set.of(Duty.TIME_CONFIG_AUTHOR)),
    APPLY_APPROVAL("POST", "/v1/hris/work-plans/[0-9a-fA-F-]{36}/actions/apply-approval",
            Set.of(Duty.TIME_CONFIG_APPROVER)),
    PUBLISH("POST", "/v1/hris/work-plans/[0-9a-fA-F-]{36}/actions/publish",
            Set.of(Duty.TIME_CONFIG_APPROVER)),
    READ_RECEIPT("GET", "/v1/hris/work-plan-receipts/[0-9a-fA-F-]{36}", Set.of(
            Duty.TIME_CONFIG_AUTHOR, Duty.TIME_CONFIG_APPROVER,
            Duty.TIME_OPERATOR, Duty.TIME_AUDITOR));

    private final String method;
    private final Pattern path;
    private final Set<Duty> acceptedDuties;

    WorkRegimeOwnerRoute(String method, String pathPattern, Set<Duty> acceptedDuties) {
        this.method = method;
        this.path = Pattern.compile("^" + pathPattern + "$");
        this.acceptedDuties = Set.copyOf(acceptedDuties);
    }

    boolean matches(String requestMethod, String requestPath) {
        return method.equals(requestMethod) && path.matcher(requestPath).matches();
    }

    boolean accepts(Set<Duty> duties) {
        return duties.stream().anyMatch(acceptedDuties::contains);
    }
}
