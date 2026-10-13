package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAction;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact owner routes and pinned authority metadata for BASE-TFR-TIM-010. */
public enum InterpretationOwnerRoute {
    WORKBENCH(
            "GET",
            "/v1/hris/workbench/time-interpretation",
            "IAQ-041",
            "route.hcm.operations.time-interpretation.page",
            Set.of(InterpretationService.VIEW_PERMISSION),
            InterpretationAction.VIEW,
            InterpretationIaProjectionService.VIEW_PURPOSE,
            false),
    RUN(
            "POST",
            "/v1/interpretation-runs",
            "PEP-TIM-009",
            "route.hcm.operations.time-interpretation-run.action",
            Set.of(
                    InterpretationService.RUN_PERMISSION,
                    InterpretationService.SELF_RUN_PERMISSION),
            InterpretationAction.RUN,
            InterpretationService.PURPOSE,
            true),
    TRACE(
            "GET",
            "/v1/interpretation-runs/" + uuid() + "/trace",
            "PEP-TIM-010",
            "route.hcm.operations.time-interpretation-trace.data",
            Set.of(
                    InterpretationService.RUN_PERMISSION,
                    InterpretationService.TRACE_PERMISSION),
            InterpretationAction.TRACE,
            InterpretationService.PURPOSE,
            false);

    public static final long AUTHORIZATION_VERSION = 38;
    public static final String AUTHORIZATION_SHA256 =
            "a41145d6a2faefd2e6030a2ca9a070769019d4db5513ea214bf542dda31c374a";
    public static final String GATEWAY_OPENAPI_SHA256 =
            "453f3620dca02a5ce82df273be986633fb5d694084ca080859bb47472027d234";

    private final String method;
    private final Pattern path;
    private final String bindingId;
    private final String authorityContractKey;
    private final Set<String> permissions;
    private final InterpretationAction action;
    private final String requiredPurpose;
    private final boolean command;

    InterpretationOwnerRoute(
            String method,
            String path,
            String bindingId,
            String authorityContractKey,
            Set<String> permissions,
            InterpretationAction action,
            String requiredPurpose,
            boolean command) {
        this.method = method;
        this.path = Pattern.compile("^" + path + "$");
        this.bindingId = bindingId;
        this.authorityContractKey = authorityContractKey;
        this.permissions = Set.copyOf(permissions);
        this.action = action;
        this.requiredPurpose = requiredPurpose;
        this.command = command;
    }

    public boolean matches(String requestMethod, String requestPath) {
        return method.equals(requestMethod) && path.matcher(requestPath).matches();
    }

    public boolean accepts(Set<String> grantedPermissions) {
        return grantedPermissions != null
                && grantedPermissions.stream().anyMatch(permissions::contains);
    }

    public String bindingId() {
        return bindingId;
    }

    public String authorityContractKey() {
        return authorityContractKey;
    }

    public Set<String> permissions() {
        return permissions;
    }

    public InterpretationAction action() {
        return action;
    }

    public String requiredPurpose() {
        return requiredPurpose;
    }

    public boolean command() {
        return command;
    }

    private static String uuid() {
        return "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    }
}
