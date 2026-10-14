package com.dwp.services.time.timecard;

import com.dwp.services.time.timecard.TimecardModels.PopulationKind;
import com.dwp.services.time.timecard.TimecardModels.TimecardAction;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact v42 owner routes and authority metadata for BASE-TFR-TIM-017. */
public enum TimecardOwnerRoute {
    QUERY(
            "GET", "/v1/timecards/" + uuid(), "PEP-TIM-011",
            "route.hcm.time.timecard.data",
            Set.of("DATA.HR_TIME:UPDATE", "DATA.HR_TIME:APPROVE", "DATA.HR_TIME:VIEW"),
            TimecardAction.VIEW, "TIME_TIMECARD",
            Set.of(PopulationKind.SELF, PopulationKind.TARGET_POPULATION),
            Set.of("LIMITED_PROFILE", "SELF_TIME", "TIME_DETAIL"), false),
    CORRECT(
            "POST", "/v1/timecards/" + uuid() + "/corrections", "PEP-TIM-012",
            "route.hcm.time.timecard-correction.action",
            Set.of("DATA.HR_TIME:UPDATE", "DATA.HR_TIME:CREATE"),
            TimecardAction.CORRECT, "TIME_TIMECARD",
            Set.of(PopulationKind.SELF, PopulationKind.TARGET_POPULATION),
            Set.of("LIMITED_PROFILE", "SELF_TIME", "TIME_DETAIL"), true),
    SUBMIT(
            "POST", "/v1/timecards/" + uuid() + "/submit", "PEP-TIM-013",
            "route.hcm.time.timecard-submit.action",
            Set.of("DATA.HR_TIME:CREATE"), TimecardAction.SUBMIT, "TIME_TIMECARD",
            Set.of(PopulationKind.SELF), Set.of("SELF_TIME"), true),
    DECIDE(
            "POST", "/v1/timecards/" + uuid() + "/decisions", "PEP-TIM-014",
            "route.hcm.time.timecard-decision.action",
            Set.of("DATA.HR_TIME:APPROVE"),
            TimecardAction.DECIDE, "TIME_TIMECARD",
            Set.of(PopulationKind.TARGET_POPULATION),
            Set.of("LIMITED_PROFILE", "TIME_DETAIL"), true),
    RECEIPT(
            "GET", "/v1/command-receipts/" + uuid(), "PEP-TIM-028",
            "route.hcm.time.command-receipt.data",
            Set.of("DATA.HRIS_COMMAND_RECEIPT:VIEW"), TimecardAction.RECEIPT_VIEW,
            "COMMAND_STATUS", Set.of(PopulationKind.TARGET_POPULATION),
            Set.of("COMMAND_RECEIPT"), false);

    public static final long AUTHORIZATION_VERSION = 42;
    public static final String AUTHORIZATION_SHA256 =
            "f73f9a64816ac8f5567a2a7c292821f4a40e6d675915ebc3c3ac8aad7d1fd983";
    public static final String GATEWAY_OPENAPI_SHA256 =
            "50a57ad5aa93178f47d724ca3dfa08a64985de93a099071db66220dfe686c2a2";

    private final String method;
    private final Pattern path;
    private final String bindingId;
    private final String authorityContractKey;
    private final Set<String> permissions;
    private final TimecardAction action;
    private final String requiredPurpose;
    private final Set<PopulationKind> populationKinds;
    private final Set<String> fieldGroups;
    private final boolean command;

    TimecardOwnerRoute(
            String method,
            String path,
            String bindingId,
            String authorityContractKey,
            Set<String> permissions,
            TimecardAction action,
            String requiredPurpose,
            Set<PopulationKind> populationKinds,
            Set<String> fieldGroups,
            boolean command) {
        this.method = method;
        this.path = Pattern.compile("^" + path + "$");
        this.bindingId = bindingId;
        this.authorityContractKey = authorityContractKey;
        this.permissions = Set.copyOf(permissions);
        this.action = action;
        this.requiredPurpose = requiredPurpose;
        this.populationKinds = Set.copyOf(populationKinds);
        this.fieldGroups = Set.copyOf(fieldGroups);
        this.command = command;
    }

    public boolean matches(String requestMethod, String requestPath) {
        return method.equals(requestMethod) && path.matcher(requestPath).matches();
    }

    public boolean accepts(Set<String> grantedPermissions) {
        return grantedPermissions != null
                && grantedPermissions.stream().anyMatch(permissions::contains);
    }

    /** Keeps population and resource action coupled exactly as the sealed v42 profiles. */
    public boolean accepts(PopulationKind kind, Set<String> grantedPermissions) {
        if (!populationKinds.contains(kind) || grantedPermissions == null) return false;
        return switch (this) {
            case QUERY -> kind == PopulationKind.SELF
                    ? grantedPermissions.contains("DATA.HR_TIME:VIEW")
                    : grantedPermissions.contains("DATA.HR_TIME:UPDATE")
                            || grantedPermissions.contains("DATA.HR_TIME:APPROVE");
            case CORRECT -> kind == PopulationKind.SELF
                    ? grantedPermissions.contains("DATA.HR_TIME:CREATE")
                    : grantedPermissions.contains("DATA.HR_TIME:UPDATE");
            case SUBMIT -> kind == PopulationKind.SELF
                    && grantedPermissions.contains("DATA.HR_TIME:CREATE");
            case DECIDE -> kind == PopulationKind.TARGET_POPULATION
                    && grantedPermissions.contains("DATA.HR_TIME:APPROVE");
            case RECEIPT -> kind == PopulationKind.TARGET_POPULATION
                    && grantedPermissions.contains("DATA.HRIS_COMMAND_RECEIPT:VIEW");
        };
    }

    public boolean accepts(PopulationKind kind) {
        return populationKinds.contains(kind);
    }

    public String bindingId() { return bindingId; }

    public String authorityContractKey() { return authorityContractKey; }

    public Set<String> permissions() { return permissions; }

    public TimecardAction action() { return action; }

    public String requiredPurpose() { return requiredPurpose; }

    public Set<String> fieldGroups() { return fieldGroups; }

    public boolean command() { return command; }

    private static String uuid() {
        return "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    }
}
