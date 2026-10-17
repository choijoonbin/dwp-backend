package com.dwp.services.time.close;

import com.dwp.services.time.close.TimeCloseModels.CloseAction;
import java.util.Set;
import java.util.regex.Pattern;

/** The eight sealed v50 owner routes and their coupled purpose/permission profiles. */
public enum TimeCloseOwnerRoute {
    COMMAND_CENTER(
            "GET", "/v1/hris/workbench/time-command-center", "IAQ-039",
            "route.hcm.operations.time-command-center.page", "DATA.HR_TIME:VIEW",
            null, CloseAction.QUERY, "TIME_OPERATIONS",
            Set.of("TIME_PERIOD", "EXCEPTION_SUMMARY", "COMMAND_RECEIPT"), false),
    DAILY_CLOSE(
            "GET", "/v1/hris/workbench/time-daily-close", "IAQ-043",
            "route.hcm.operations.time-daily-close.page", "DATA.HR_TIME:VIEW",
            null, CloseAction.QUERY, "TIME_CLOSE",
            Set.of("TIME_PERIOD", "CLOSE_STATUS", "IMPACT_SUMMARY"), false),
    MONTHLY_CLOSE(
            "GET", "/v1/hris/workbench/time-monthly-close", "IAQ-044",
            "route.hcm.operations.time-monthly-close.page", "DATA.HR_TIME:VIEW",
            null, CloseAction.QUERY, "TIME_CLOSE",
            Set.of("TIME_PERIOD", "CLOSE_STATUS", "PAYROLL_HANDOFF"), false),
    VALIDATE(
            "POST", "/v1/close-periods/" + uuid() + "/validations", "PEP-TIM-022",
            "route.hcm.operations.time-close-validate.action",
            "ACTION.HRIS_TIME_CLOSE:EXECUTE", "HRIS_DUTY_TIME_CLOSE_OPERATOR",
            CloseAction.VALIDATE, "WORK_TIME_PERIOD_CLOSURE", Set.of(), true),
    CLOSE(
            "POST", "/v1/close-periods/" + uuid() + "/close", "PEP-TIM-023",
            "route.hcm.operations.time-close.action",
            "ACTION.HRIS_TIME_CLOSE:EXECUTE", "HRIS_DUTY_TIME_CLOSE_OPERATOR",
            CloseAction.CLOSE, "WORK_TIME_PERIOD_CLOSURE", Set.of(), true),
    REOPEN(
            "POST", "/v1/close-periods/" + uuid() + "/reopen", "PEP-TIM-024",
            "route.hcm.operations.time-reopen.action",
            "ACTION.HRIS_TIME_REOPEN:MANAGE", "HRIS_DUTY_TIME_REOPEN_OPERATOR",
            CloseAction.REOPEN, "WORK_TIME_PERIOD_CLOSURE", Set.of(), true),
    HANDOFF_CREATE(
            "POST", "/v1/close-periods/" + uuid() + "/payroll-handoffs",
            "PEP-TIM-025", "route.hcm.operations.time-payroll-handoff-create.action",
            "ACTION.HRIS_TIME_CLOSE:EXECUTE", "HRIS_DUTY_TIME_CLOSE_OPERATOR",
            CloseAction.HANDOFF_CREATE, "CLOSED_TIME_PAYROLL_TRANSFER", Set.of(), true),
    HANDOFF_QUERY(
            "GET", "/v1/payroll-handoffs/" + uuid(), "PEP-TIM-026",
            "route.hcm.operations.time-payroll-handoff.data",
            "ACTION.HRIS_TIME_CLOSE:EXECUTE", "HRIS_DUTY_TIME_CLOSE_OPERATOR",
            CloseAction.HANDOFF_QUERY, "CLOSED_TIME_PAYROLL_TRANSFER",
            Set.of("PAYROLL_HANDOFF"), false);

    public static final long AUTHORIZATION_VERSION = 50;
    /** Exact v50 registry checksum, not a local copy's byte hash. */
    public static final String PRODUCT_AUTHORIZATION_CHECKSUM =
            "c52222caad2749c4048c694f6582e157f6ab1184f73237f0944c3d88807a2760";
    /** Exact checksum embedded in the central close-projection artifact. */
    public static final String CLOSE_PROJECTION_CHECKSUM =
            "f90550903aeea52aeba79593fe8be63d002f03767bebd7ec677d0fb0b98513d0";
    public static final String CLOSE_PROJECTION_SCHEMA_CLOSURE_SHA256 =
            "49fc874c27dabb495b6d554b9dde6ef2391a4dd1d617d9fbbf062d10f5d6b265";

    private final String method;
    private final Pattern path;
    private final String bindingId;
    private final String routeContractKey;
    private final String permission;
    private final String responsibility;
    private final CloseAction action;
    private final String purpose;
    private final Set<String> fieldGroups;
    private final boolean command;

    TimeCloseOwnerRoute(
            String method,
            String path,
            String bindingId,
            String routeContractKey,
            String permission,
            String responsibility,
            CloseAction action,
            String purpose,
            Set<String> fieldGroups,
            boolean command) {
        this.method = method;
        this.path = Pattern.compile("^" + path + "$");
        this.bindingId = bindingId;
        this.routeContractKey = routeContractKey;
        this.permission = permission;
        this.responsibility = responsibility;
        this.action = action;
        this.purpose = purpose;
        this.fieldGroups = Set.copyOf(fieldGroups);
        this.command = command;
    }

    public boolean matches(String requestMethod, String requestPath) {
        return method.equals(requestMethod) && path.matcher(requestPath).matches();
    }

    public boolean accepts(Set<String> permissions, Set<String> responsibilities) {
        return permissions != null && permissions.contains(permission)
                && (responsibility == null
                    || (responsibilities != null && responsibilities.contains(responsibility)));
    }

    public boolean freshCommandStepUp() {
        return this == CLOSE || this == REOPEN;
    }

    public String bindingId() { return bindingId; }

    public String routeContractKey() { return routeContractKey; }

    public String permission() { return permission; }

    public String responsibility() { return responsibility; }

    public CloseAction action() { return action; }

    public String purpose() { return purpose; }

    public Set<String> fieldGroups() { return fieldGroups; }

    public boolean command() { return command; }

    private static String uuid() {
        return "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    }
}
