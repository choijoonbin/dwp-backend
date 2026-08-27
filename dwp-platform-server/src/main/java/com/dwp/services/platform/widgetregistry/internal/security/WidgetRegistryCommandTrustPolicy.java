package com.dwp.services.platform.widgetregistry.internal.security;

import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.CommandTargetBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryCommandSemanticBinding.Fields;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact command operation/type to service-scope and Provider-permission trust mapping. */
final class WidgetRegistryCommandTrustPolicy {

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> CONTROL_SCOPES = Set.of(
            "CATALOG_MUTATIONS", "CATALOG_DISCOVERY", "RUNTIME_RENDER", "RUNTIME_ACTION");

    private static final Map<String, CommandRule> RULES = Map.ofEntries(
            rule("createWidgetDefinition", "CREATE_DEFINITION", "widget-registry.write",
                    "WIDGET_DEFINITION_WRITE"),
            rule("createWidgetDefinitionVersion", "CREATE_VERSION", "widget-registry.write",
                    "WIDGET_DEFINITION_WRITE"),
            rule("updateWidgetDefinitionVersion", "UPDATE_VERSION", "widget-registry.write",
                    "WIDGET_DEFINITION_WRITE"),
            rule("validateWidgetDefinitionVersion", "VALIDATE", "widget-registry.write",
                    "WIDGET_DEFINITION_WRITE"),
            rule("submitWidgetDefinitionVersion", "SUBMIT", "widget-registry.write",
                    "WIDGET_DEFINITION_WRITE"),
            rule("reworkWidgetDefinitionVersion", "REWORK", "widget-registry.write",
                    "WIDGET_DEFINITION_WRITE"),
            rule("decideWidgetDefinitionVersion", "DECIDE", "widget-registry.review",
                    "WIDGET_DEFINITION_REVIEW"),
            rule("recordWidgetCertificationEvidence", "RECORD_EVIDENCE", "widget-registry.review",
                    "WIDGET_DEFINITION_REVIEW"),
            rule("approveWidgetQuarantineClearance", "APPROVE_QUARANTINE_CLEARANCE",
                    "widget-registry.review", "WIDGET_DEFINITION_REVIEW"),
            rule("approveWidgetRuntimeControlEnable", "APPROVE_RUNTIME_CONTROL_ENABLE",
                    "widget-registry.review", "WIDGET_DEFINITION_REVIEW"),
            rule("waiveWidgetCertificationEvidence", "WAIVE_EVIDENCE", "widget-registry.waive",
                    "WIDGET_EVIDENCE_WAIVE"),
            rule("publishWidgetDefinitionVersion", "PUBLISH", "widget-registry.release",
                    "WIDGET_DEFINITION_RELEASE"),
            rule("deprecateWidgetDefinitionVersion", "DEPRECATE", "widget-registry.release",
                    "WIDGET_DEFINITION_RELEASE"),
            rule("clearWidgetVersionQuarantine", "CLEAR_QUARANTINE", "widget-registry.release",
                    "WIDGET_DEFINITION_RELEASE"),
            rule("revokeWidgetDefinitionVersion", "REVOKE", "widget-registry.safety",
                    "WIDGET_DEFINITION_REVOKE"),
            rule("retireWidgetDefinition", "RETIRE", "widget-registry.release",
                    "WIDGET_DEFINITION_RELEASE"),
            rule("promoteWidgetReleaseChannel", "PROMOTE", "widget-registry.release",
                    "WIDGET_DEFINITION_RELEASE"),
            rule("rollbackWidgetReleaseChannel", "ROLLBACK", "widget-registry.release",
                    "WIDGET_DEFINITION_RELEASE"),
            rule("quarantineWidgetDefinitionVersion", "QUARANTINE", "widget-registry.safety",
                    "WIDGET_DEFINITION_REVOKE"),
            rule("disableWidgetRuntimeControl", "DISABLE_RUNTIME_CONTROL", "widget-registry.safety",
                    "WIDGET_DEFINITION_REVOKE"),
            rule("enableWidgetRuntimeControl", "ENABLE_RUNTIME_CONTROL", "widget-registry.release",
                    "WIDGET_DEFINITION_RELEASE"));

    private WidgetRegistryCommandTrustPolicy() {
    }

    static Requirement resolve(String operationId, String commandType) {
        if (operationId == null || commandType == null) return null;
        CommandRule rule = RULES.get(operationId);
        if (rule == null || !rule.commandType().equals(commandType)) return null;
        return new Requirement(rule.serviceScope(), rule.providerPermission());
    }

    static Requirement resolve(
            String operationId,
            String commandType,
            CommandTargetBinding target,
            Fields semanticFields) {
        Requirement requirement = resolve(operationId, commandType);
        if (requirement == null
                || !validTarget(targetContract(operationId), target, semanticFields)) return null;
        return requirement;
    }

    static int ruleCount() {
        return RULES.size();
    }

    private static Map.Entry<String, CommandRule> rule(
            String operationId,
            String commandType,
            String serviceScope,
            String providerPermission) {
        return Map.entry(operationId, new CommandRule(commandType, serviceScope, providerPermission));
    }

    record Requirement(String serviceScope, String providerPermission) {
    }

    private static TargetContract targetContract(String operationId) {
        return switch (operationId) {
            case "createWidgetDefinition" -> TargetContract.DEFINITION_KEY_HASH;
            case "createWidgetDefinitionVersion" -> TargetContract.DEFINITION_SEMVER_HASH;
            case "waiveWidgetCertificationEvidence" -> TargetContract.EVIDENCE;
            case "retireWidgetDefinition" -> TargetContract.DEFINITION;
            case "promoteWidgetReleaseChannel", "rollbackWidgetReleaseChannel" ->
                    TargetContract.DEFINITION_CHANNEL_HASH;
            case "disableWidgetRuntimeControl" -> TargetContract.RUNTIME_CONTROL_SCOPE_HASH;
            case "approveWidgetRuntimeControlEnable", "enableWidgetRuntimeControl" ->
                    TargetContract.RUNTIME_CONTROL;
            default -> TargetContract.VERSION;
        };
    }

    private static boolean validTarget(
            TargetContract contract,
            CommandTargetBinding target,
            Fields semantic) {
        if (contract == null || target == null || target.fields() == null || semantic == null) return false;
        return switch (contract) {
            case DEFINITION_KEY_HASH -> exact(target, "DEFINITION_KEY_HASH", SHA256,
                    Set.of("targetType", "targetId"))
                    && semantic.definitionKey() != null
                    && target.targetId().equals(sha256(semantic.definitionKey()));
            case DEFINITION_SEMVER_HASH -> exact(target, "DEFINITION_SEMVER_HASH", SHA256,
                    Set.of("targetType", "targetId", "definitionId"))
                    && uuid(target.definitionId())
                    && semantic.normalizedSemanticVersion() != null
                    && target.targetId().equals(sha256(
                            target.definitionId() + "\n" + semantic.normalizedSemanticVersion()));
            case VERSION -> exact(target, "VERSION", UUID,
                    Set.of("targetType", "targetId", "versionId"))
                    && semantic.empty() && target.targetId().equals(target.versionId());
            case EVIDENCE -> exact(target, "EVIDENCE", UUID,
                    Set.of("targetType", "targetId", "versionId", "evidenceId"))
                    && semantic.empty() && uuid(target.versionId())
                    && target.targetId().equals(target.evidenceId());
            case DEFINITION -> exact(target, "DEFINITION", UUID,
                    Set.of("targetType", "targetId", "definitionId"))
                    && semantic.empty() && target.targetId().equals(target.definitionId());
            case DEFINITION_CHANNEL_HASH -> exact(target, "DEFINITION_CHANNEL_HASH", SHA256,
                    Set.of("targetType", "targetId", "definitionId", "channel"))
                    && semantic.empty()
                    && uuid(target.definitionId())
                    && ("STABLE".equals(target.channel()) || "PREVIEW".equals(target.channel()))
                    && target.targetId().equals(sha256(
                            target.definitionId() + "\n" + target.channel()));
            case RUNTIME_CONTROL_SCOPE_HASH -> exact(target, "RUNTIME_CONTROL_SCOPE_HASH", SHA256,
                    Set.of("targetType", "targetId", "controlScope", "runtimeTargetType",
                            "runtimeTargetId"))
                    && validRuntimeTarget(target, semantic)
                    && target.targetId().equals(sha256(
                            target.controlScope() + "\n" + target.runtimeTargetType() + "\n"
                                    + (target.runtimeTargetId() == null
                                    ? "GLOBAL" : target.runtimeTargetId())));
            case RUNTIME_CONTROL -> exact(target, "RUNTIME_CONTROL", UUID,
                    Set.of("targetType", "targetId", "controlId"))
                    && semantic.empty() && target.targetId().equals(target.controlId());
        };
    }

    private static boolean exact(
            CommandTargetBinding target,
            String targetType,
            Pattern targetId,
            Set<String> fields) {
        return fields.equals(target.fields())
                && targetType.equals(target.targetType())
                && target.targetId() != null
                && targetId.matcher(target.targetId()).matches();
    }

    private static boolean validRuntimeTarget(CommandTargetBinding target, Fields semantic) {
        if (!CONTROL_SCOPES.contains(target.controlScope())
                || target.runtimeTargetType() == null
                || !target.controlScope().equals(semantic.controlScope())
                || !target.runtimeTargetType().equals(semantic.runtimeTargetType())
                || !java.util.Objects.equals(target.runtimeTargetId(), semantic.runtimeTargetId())) {
            return false;
        }
        return "GLOBAL".equals(target.runtimeTargetType())
                ? target.runtimeTargetId() == null
                : ("DEFINITION".equals(target.runtimeTargetType())
                        || "VERSION".equals(target.runtimeTargetType()))
                        && uuid(target.runtimeTargetId());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available.", exception);
        }
    }

    private static boolean uuid(String value) {
        return value != null && UUID.matcher(value).matches();
    }

    private enum TargetContract {
        DEFINITION_KEY_HASH,
        DEFINITION_SEMVER_HASH,
        VERSION,
        EVIDENCE,
        DEFINITION,
        DEFINITION_CHANNEL_HASH,
        RUNTIME_CONTROL_SCOPE_HASH,
        RUNTIME_CONTROL
    }

    private record CommandRule(String commandType, String serviceScope, String providerPermission) {
    }
}
