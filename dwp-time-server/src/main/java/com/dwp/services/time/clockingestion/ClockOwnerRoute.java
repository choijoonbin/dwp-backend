package com.dwp.services.time.clockingestion;

import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAction;
import java.util.regex.Pattern;

/** Exact owner routes; generated Gateway and product contracts remain Control-owned. */
public enum ClockOwnerRoute {
    WORKBENCH(
            "GET",
            "/v1/hris/workbench/clock-ingestion",
            "IAQ-034",
            "route.hcm.operations.time-clock-ingestion.page",
            "DATA.HR_TIME:VIEW",
            ClockAction.VIEW,
            ClockIngestionService.VIEW_PURPOSE,
            false),
    TIME_EXCEPTIONS(
            "GET",
            "/v1/hris/workbench/time-exceptions",
            "IAQ-042",
            "route.hcm.operations.time-exceptions.page",
            "DATA.HR_TIME:VIEW",
            ClockAction.VIEW,
            ClockIngestionService.VIEW_PURPOSE,
            false),
    RECORD(
            "POST",
            "/v1/clock-events",
            "PEP-TIM-007",
            "route.hcm.personal.time-clock-event-record.action",
            "DATA.HR_TIME:CREATE",
            ClockAction.RECORD,
            ClockIngestionService.CAPTURE_PURPOSE,
            false),
    VOID(
            "POST",
            "/v1/clock-events/" + uuid() + "/voids",
            "PEP-TIM-008",
            "route.hcm.operations.time-clock-event-void.action",
            "DATA.HR_TIME:UPDATE",
            ClockAction.CORRECT,
            ClockIngestionService.CAPTURE_PURPOSE,
            true);

    private final String method;
    private final Pattern path;
    private final String bindingId;
    private final String authorityContractKey;
    private final String capability;
    private final ClockAction action;
    private final String requiredPurpose;
    private final boolean stepUpRequired;

    ClockOwnerRoute(
            String method,
            String path,
            String bindingId,
            String authorityContractKey,
            String capability,
            ClockAction action,
            String requiredPurpose,
            boolean stepUpRequired) {
        this.method = method;
        this.path = Pattern.compile("^" + path + "$");
        this.bindingId = bindingId;
        this.authorityContractKey = authorityContractKey;
        this.capability = capability;
        this.action = action;
        this.requiredPurpose = requiredPurpose;
        this.stepUpRequired = stepUpRequired;
    }

    public boolean matches(String requestMethod, String requestPath) {
        return method.equals(requestMethod) && path.matcher(requestPath).matches();
    }

    public String bindingId() {
        return bindingId;
    }

    /** Canonical v36 PAGE/ACTION key consumed by the Control-owned authority bridge. */
    public String authorityContractKey() {
        return authorityContractKey;
    }

    public ClockAction action() {
        return action;
    }

    public String capability() {
        return capability;
    }

    public String requiredPurpose() {
        return requiredPurpose;
    }

    public boolean stepUpRequired() {
        return stepUpRequired;
    }

    private static String uuid() {
        return "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    }
}
