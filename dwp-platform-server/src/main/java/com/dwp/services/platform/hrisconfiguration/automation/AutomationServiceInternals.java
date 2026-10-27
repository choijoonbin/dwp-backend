package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;

/** Shared constants, internal evidence and pure command rules. */
final class AutomationServiceInternals {

    static final String CREATE_DEFINITION =
            "hris.action.sys.post.platform.v1.admin.hris.automations";
    static final String QUEUE_RUN =
            "hris.action.sys.post.platform.v1.admin.hris.automations.resource.runs";
    static final String RETRY_RUN =
            "hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.retry";
    static final String CREATE_VERSION =
            "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions";
    static final String VALIDATE_VERSION = CREATE_VERSION + ".resource.validate";
    static final String SUBMIT_VERSION = CREATE_VERSION + ".resource.submit";
    static final String APPROVE_VERSION = CREATE_VERSION + ".resource.approve";
    static final String ACTIVATE_VERSION = CREATE_VERSION + ".resource.activate";
    static final String CREATE_SCHEDULE =
            "hris.action.sys.post.platform.v1.admin.hris.automations.resource.schedules";
    static final String SUBMIT_SCHEDULE =
            "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.submit";
    static final String APPROVE_SCHEDULE =
            "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.approve";
    static final String ACTIVATE_SCHEDULE =
            "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.activate";
    static final String SUSPEND_SCHEDULE =
            "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.suspend";
    static final String CANCEL_RUN =
            "hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.cancel";

    static final String AUTHOR = "HRIS_CONFIG_AUTHOR";
    static final String PUBLISHER = "HRIS_CONFIG_PUBLISHER";
    static final String EXECUTOR = "HRIS_INTEGRATION_EXECUTOR";
    static final Set<String> CATALOG_FIELDS = Set.of(
            "definitionPublicId", "automationKey", "ownerDomain",
            "derivedLifecycleState", "activeVersionNumber", "versionNumber",
            "lifecycleState", "handlerKey", "handlerContractVersion",
            "objectRevision", "schedulePublicId", "pinnedVersionNumber",
            "timezone", "nextFireAt");
    static final String IDEMPOTENCY_POLICY = "REQUIRED_IDEMPOTENCY_KEY";
    static final String STEP_UP_POLICY =
            "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED";

    static int parsePositiveInt(String value) {
        long parsed = parsePositiveLong(value);
        if (parsed > Integer.MAX_VALUE) throw invalid("AUTOMATION_CURSOR_INVALID");
        return (int) parsed;
    }

    static long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw invalid("AUTOMATION_CURSOR_INVALID");
        }
    }

    static Object project(
            String field, Object value, Set<String> requested, Authority authority) {
        if (!requested.contains(field) || authority.omittedFields().contains(field)) {
            return new OmittedField();
        }
        if (authority.maskedFields().contains(field)) return new MaskedField();
        return value;
    }

    static boolean hidden(Object... values) {
        return java.util.Arrays.stream(values).anyMatch(value ->
                value instanceof MaskedField || value instanceof OmittedField);
    }

    static Map<String, Object> target(
            UUID publicId,
            Long version,
            long revision,
            AutomationHandlerRegistryPort.HandlerIdentity handler) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("publicId", publicId);
        target.put("version", version);
        target.put("objectRevision", revision);
        target.put("handler", handler);
        return target;
    }

    static ActionDescriptor action(
            String key,
            String targetType,
            UUID publicId,
            Long version,
            Long revision,
            String href) {
        return new ActionDescriptor(
                "POST", key, targetType, publicId, version, revision,
                href, IDEMPOTENCY_POLICY, STEP_UP_POLICY);
    }

    static String versionHref(UUID definition, long version, String action) {
        String suffix = action.substring(action.lastIndexOf('.') + 1);
        return "/api/platform/v1/admin/hris/automations/" + definition
                + "/versions/" + version + "/" + suffix;
    }

    static String scheduleHref(UUID schedule, String action) {
        return "/api/platform/v1/admin/hris/automation-schedules/" + schedule
                + "/" + action.substring(action.lastIndexOf('.') + 1);
    }

    static String definitionRef(UUID id) {
        return "/v1/admin/hris/automations/" + id;
    }

    static String versionRef(UUID id, long version) {
        return definitionRef(id) + "/versions/" + version;
    }

    static String scheduleRef(UUID id) {
        return "/v1/admin/hris/automation-schedules/" + id;
    }

    static String runRef(UUID id) {
        return "/v1/admin/hris/automation-runs/" + id;
    }

    static void requireRevision(long actual, Long expected) {
        if (expected == null || actual != expected) {
            throw conflict("AUTOMATION_OBJECT_REVISION_CONFLICT");
        }
    }

    static void requireStepUp(Authority authority) {
        if (authority.stepUpEvidenceDigest() == null) {
            throw forbidden("AUTOMATION_STEP_UP_REQUIRED");
        }
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
