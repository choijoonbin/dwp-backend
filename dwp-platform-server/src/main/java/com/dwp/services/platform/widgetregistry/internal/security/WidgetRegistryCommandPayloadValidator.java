package com.dwp.services.platform.widgetregistry.internal.security;

import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryRequestBinding.BindingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/** Closed runtime validator for all 21 widget-registry command payload branches. */
final class WidgetRegistryCommandPayloadValidator {

    private static final Map<String, String> COMMAND_TYPES = Map.ofEntries(
            type("createWidgetDefinition", "CREATE_DEFINITION"),
            type("createWidgetDefinitionVersion", "CREATE_VERSION"),
            type("updateWidgetDefinitionVersion", "UPDATE_VERSION"),
            type("validateWidgetDefinitionVersion", "VALIDATE"),
            type("submitWidgetDefinitionVersion", "SUBMIT"),
            type("decideWidgetDefinitionVersion", "DECIDE"),
            type("reworkWidgetDefinitionVersion", "REWORK"),
            type("recordWidgetCertificationEvidence", "RECORD_EVIDENCE"),
            type("waiveWidgetCertificationEvidence", "WAIVE_EVIDENCE"),
            type("publishWidgetDefinitionVersion", "PUBLISH"),
            type("deprecateWidgetDefinitionVersion", "DEPRECATE"),
            type("quarantineWidgetDefinitionVersion", "QUARANTINE"),
            type("approveWidgetQuarantineClearance", "APPROVE_QUARANTINE_CLEARANCE"),
            type("clearWidgetVersionQuarantine", "CLEAR_QUARANTINE"),
            type("revokeWidgetDefinitionVersion", "REVOKE"),
            type("retireWidgetDefinition", "RETIRE"),
            type("promoteWidgetReleaseChannel", "PROMOTE"),
            type("rollbackWidgetReleaseChannel", "ROLLBACK"),
            type("disableWidgetRuntimeControl", "DISABLE_RUNTIME_CONTROL"),
            type("approveWidgetRuntimeControlEnable", "APPROVE_RUNTIME_CONTROL_ENABLE"),
            type("enableWidgetRuntimeControl", "ENABLE_RUNTIME_CONTROL"));
    private static final Set<String> RISK_TIERS = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> DATA_CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> EVIDENCE_TYPES = Set.of(
            "MANIFEST", "SECURITY", "PRIVACY", "A11Y", "PERFORMANCE", "LOCALIZATION");
    private static final Set<String> CONTROL_SCOPES = Set.of(
            "CATALOG_MUTATIONS", "CATALOG_DISCOVERY", "RUNTIME_RENDER", "RUNTIME_ACTION");

    private WidgetRegistryCommandPayloadValidator() {
    }

    static Validation validate(String operationId, String commandType, JsonNode payload)
            throws BindingException {
        WidgetRegistryJsonContract.require(
                commandType != null && commandType.equals(COMMAND_TYPES.get(operationId)));
        String ownerProductKey = switch (operationId) {
            case "createWidgetDefinition" -> validateDefinitionCreate(payload);
            case "createWidgetDefinitionVersion" -> validateVersionCreate(payload);
            case "updateWidgetDefinitionVersion" -> validateVersionUpdate(payload);
            case "validateWidgetDefinitionVersion" -> validateManifestHash(payload);
            case "submitWidgetDefinitionVersion" -> validateTransition(payload);
            case "decideWidgetDefinitionVersion" -> validateDecision(payload);
            case "reworkWidgetDefinitionVersion" -> validateRework(payload);
            case "recordWidgetCertificationEvidence" -> validateEvidence(payload);
            case "waiveWidgetCertificationEvidence" -> validateWaiver(payload);
            case "publishWidgetDefinitionVersion" -> validatePublish(payload);
            case "deprecateWidgetDefinitionVersion" -> validateDeprecate(payload);
            case "quarantineWidgetDefinitionVersion", "revokeWidgetDefinitionVersion" ->
                    validateSafetyTransition(payload);
            case "approveWidgetQuarantineClearance" -> validateClearanceApproval(payload);
            case "clearWidgetVersionQuarantine" -> validateClearanceExecution(payload);
            case "retireWidgetDefinition" -> validateDefinitionRetire(payload);
            case "promoteWidgetReleaseChannel" -> validateChannelTransition(payload);
            case "rollbackWidgetReleaseChannel" -> validateChannelRollback(payload);
            case "disableWidgetRuntimeControl" -> validateRuntimeDisable(payload);
            case "approveWidgetRuntimeControlEnable" -> validateRuntimeEnableApproval(payload);
            case "enableWidgetRuntimeControl" -> validateRuntimeEnable(payload);
            default -> throw WidgetRegistryJsonContract.invalid();
        };
        return new Validation(ownerProductKey);
    }

    static int branchCount() {
        return COMMAND_TYPES.size();
    }

    private static String validateDefinitionCreate(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "definitionKey", "ownerProductKey", "ownerTeamKey", "riskTier",
                "dataClassification", "reasonCode", "reasonText", "expectedVersion"));
        WidgetRegistryJsonContract.text(
                payload, "definitionKey", 3, 128, WidgetRegistryJsonContract.LOWER_KEY);
        String ownerProductKey = WidgetRegistryJsonContract.text(
                payload, "ownerProductKey", 3, 128, WidgetRegistryJsonContract.LOWER_KEY);
        WidgetRegistryJsonContract.text(
                payload, "ownerTeamKey", 3, 128, WidgetRegistryJsonContract.LOWER_KEY);
        WidgetRegistryJsonContract.enumText(payload, "riskTier", RISK_TIERS);
        WidgetRegistryJsonContract.enumText(payload, "dataClassification", DATA_CLASSIFICATIONS);
        common(payload);
        WidgetRegistryJsonContract.require(
                WidgetRegistryJsonContract.nonNegativeInteger(payload, "expectedVersion") == 0);
        return ownerProductKey;
    }

    private static String validateVersionCreate(JsonNode payload) throws BindingException {
        exact(payload,
                Set.of("semanticVersion", "manifest", "reasonCode", "reasonText", "expectedVersion"),
                Set.of("predecessorVersionId"));
        WidgetRegistryJsonContract.text(
                payload, "semanticVersion", 1, 128, WidgetRegistryJsonContract.SEMANTIC_VERSION);
        String ownerProductKey = WidgetRegistryManifestValidator.validate(
                WidgetRegistryJsonContract.requiredNode(payload, "manifest"));
        WidgetRegistryJsonContract.optionalUuid(payload, "predecessorVersionId");
        common(payload);
        return ownerProductKey;
    }

    private static String validateVersionUpdate(JsonNode payload) throws BindingException {
        exact(payload,
                Set.of("manifest", "reasonCode", "reasonText", "expectedVersion"),
                Set.of("predecessorVersionId"));
        String ownerProductKey = WidgetRegistryManifestValidator.validate(
                WidgetRegistryJsonContract.requiredNode(payload, "manifest"));
        WidgetRegistryJsonContract.optionalUuid(payload, "predecessorVersionId");
        common(payload);
        return ownerProductKey;
    }

    private static String validateManifestHash(JsonNode payload) throws BindingException {
        exact(payload, Set.of("manifestHash", "reasonCode", "reasonText", "expectedVersion"));
        WidgetRegistryJsonContract.sha256(payload, "manifestHash");
        common(payload);
        return null;
    }

    private static String validateTransition(JsonNode payload) throws BindingException {
        exact(payload, Set.of("reasonCode", "reasonText", "expectedVersion"));
        common(payload);
        return null;
    }

    private static String validateDecision(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "decision", "validationRunId", "evidenceIds", "reasonCode", "reasonText",
                "expectedVersion"));
        WidgetRegistryJsonContract.enumText(payload, "decision", Set.of("APPROVE", "REJECT"));
        WidgetRegistryJsonContract.uuid(payload, "validationRunId");
        WidgetRegistryJsonContract.uuidArray(payload, "evidenceIds", 64);
        common(payload);
        return null;
    }

    private static String validateRework(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "rejectedDecisionId", "reasonCode", "reasonText", "expectedVersion"));
        WidgetRegistryJsonContract.uuid(payload, "rejectedDecisionId");
        common(payload);
        return null;
    }

    private static String validateEvidence(JsonNode payload) throws BindingException {
        exact(payload,
                Set.of("evidenceType", "decision", "manifestHash", "evidenceRef",
                        "evidenceSha256", "reasonCode", "reasonText", "expectedVersion"),
                Set.of("expiresAt", "reviewNote"));
        WidgetRegistryJsonContract.enumText(payload, "evidenceType", EVIDENCE_TYPES);
        WidgetRegistryJsonContract.enumText(payload, "decision", Set.of("PASS", "FAIL"));
        WidgetRegistryJsonContract.sha256(payload, "manifestHash");
        WidgetRegistryJsonContract.opaque(payload, "evidenceRef");
        WidgetRegistryJsonContract.sha256(payload, "evidenceSha256");
        WidgetRegistryJsonContract.optionalTimestamp(payload, "expiresAt");
        WidgetRegistryJsonContract.optionalReasonText(payload, "reviewNote");
        common(payload);
        return null;
    }

    private static String validateWaiver(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "manifestHash", "waiverExpiresAt", "waiverReason", "trackingTicketRef",
                "reasonCode", "reasonText", "expectedVersion"));
        WidgetRegistryJsonContract.sha256(payload, "manifestHash");
        WidgetRegistryJsonContract.timestamp(payload, "waiverExpiresAt");
        WidgetRegistryJsonContract.reasonText(payload, "waiverReason");
        WidgetRegistryJsonContract.opaque(payload, "trackingTicketRef");
        common(payload);
        return null;
    }

    private static String validatePublish(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "channel", "validationRunId", "evidenceIds", "manifestHash", "reasonCode",
                "reasonText", "expectedVersion"));
        WidgetRegistryJsonContract.enumText(payload, "channel", Set.of("STABLE", "PREVIEW"));
        WidgetRegistryJsonContract.uuid(payload, "validationRunId");
        WidgetRegistryJsonContract.uuidArray(payload, "evidenceIds", 64);
        WidgetRegistryJsonContract.sha256(payload, "manifestHash");
        common(payload);
        return null;
    }

    private static String validateDeprecate(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "replacementVersionId", "deprecationEndsAt", "reasonCode", "reasonText",
                "expectedVersion"));
        WidgetRegistryJsonContract.uuid(payload, "replacementVersionId");
        WidgetRegistryJsonContract.timestamp(payload, "deprecationEndsAt");
        common(payload);
        return null;
    }

    private static String validateSafetyTransition(JsonNode payload) throws BindingException {
        exact(payload,
                Set.of("publicReasonCode", "internalIncidentRef", "reasonCode", "reasonText",
                        "expectedVersion"),
                Set.of("replacementVersionId", "expiresAt"));
        upperCode(payload, "publicReasonCode");
        WidgetRegistryJsonContract.opaque(payload, "internalIncidentRef");
        WidgetRegistryJsonContract.optionalUuid(payload, "replacementVersionId");
        WidgetRegistryJsonContract.optionalTimestamp(payload, "expiresAt");
        common(payload);
        return null;
    }

    private static String validateClearanceApproval(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "quarantineEventId", "reviewDecision", "evidenceRefs", "reasonCode",
                "reasonText", "expectedVersion"));
        WidgetRegistryJsonContract.uuid(payload, "quarantineEventId");
        WidgetRegistryJsonContract.require("APPROVE".equals(
                WidgetRegistryJsonContract.text(payload, "reviewDecision", 7, 7, null)));
        WidgetRegistryJsonContract.opaqueArray(payload, "evidenceRefs", 64);
        common(payload);
        return null;
    }

    private static String validateClearanceExecution(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "clearanceApprovalId", "quarantineEventId", "reasonCode", "reasonText",
                "expectedVersion"));
        WidgetRegistryJsonContract.uuid(payload, "clearanceApprovalId");
        WidgetRegistryJsonContract.uuid(payload, "quarantineEventId");
        common(payload);
        return null;
    }

    private static String validateDefinitionRetire(JsonNode payload) throws BindingException {
        exact(payload,
                Set.of("impactRevision", "reasonCode", "reasonText", "expectedVersion"),
                Set.of("replacementDefinitionId"));
        if (payload.has("replacementDefinitionId")) {
            JsonNode replacement = payload.get("replacementDefinitionId");
            if (!replacement.isNull()) WidgetRegistryJsonContract.uuid(payload, "replacementDefinitionId");
        }
        WidgetRegistryJsonContract.opaque(payload, "impactRevision");
        common(payload);
        return null;
    }

    private static String validateChannelTransition(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "versionId", "validationRunId", "manifestHash", "reasonCode", "reasonText",
                "expectedVersion"));
        WidgetRegistryJsonContract.uuid(payload, "versionId");
        WidgetRegistryJsonContract.uuid(payload, "validationRunId");
        WidgetRegistryJsonContract.sha256(payload, "manifestHash");
        common(payload);
        return null;
    }

    private static String validateChannelRollback(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "restoreVersionId", "expectedCurrentVersionId", "reasonCode", "reasonText",
                "expectedVersion"));
        WidgetRegistryJsonContract.uuid(payload, "restoreVersionId");
        WidgetRegistryJsonContract.uuid(payload, "expectedCurrentVersionId");
        common(payload);
        return null;
    }

    private static String validateRuntimeDisable(JsonNode payload) throws BindingException {
        exact(payload,
                Set.of("scope", "targetType", "targetId", "publicReasonCode",
                        "internalIncidentRef", "reasonCode", "reasonText", "expectedVersion"),
                Set.of("expiresAt"));
        WidgetRegistryJsonContract.enumText(payload, "scope", CONTROL_SCOPES);
        String targetType = WidgetRegistryJsonContract.enumText(
                payload, "targetType", Set.of("GLOBAL", "DEFINITION", "VERSION"));
        String targetId = WidgetRegistryJsonContract.nullableUuid(payload, "targetId");
        WidgetRegistryJsonContract.require("GLOBAL".equals(targetType) == (targetId == null));
        WidgetRegistryJsonContract.optionalTimestamp(payload, "expiresAt");
        upperCode(payload, "publicReasonCode");
        WidgetRegistryJsonContract.opaque(payload, "internalIncidentRef");
        common(payload);
        return null;
    }

    private static String validateRuntimeEnableApproval(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "controlRevision", "evidenceRefs", "reasonCode", "reasonText", "expectedVersion"));
        WidgetRegistryJsonContract.nonNegativeInteger(payload, "controlRevision");
        WidgetRegistryJsonContract.opaqueArray(payload, "evidenceRefs", 64);
        common(payload);
        return null;
    }

    private static String validateRuntimeEnable(JsonNode payload) throws BindingException {
        exact(payload, Set.of(
                "enableApprovalId", "controlRevision", "reasonCode", "reasonText",
                "expectedVersion"));
        WidgetRegistryJsonContract.uuid(payload, "enableApprovalId");
        WidgetRegistryJsonContract.nonNegativeInteger(payload, "controlRevision");
        common(payload);
        return null;
    }

    private static void common(JsonNode payload) throws BindingException {
        upperCode(payload, "reasonCode");
        WidgetRegistryJsonContract.reasonText(payload, "reasonText");
        WidgetRegistryJsonContract.nonNegativeInteger(payload, "expectedVersion");
    }

    private static void upperCode(JsonNode payload, String field) throws BindingException {
        WidgetRegistryJsonContract.text(
                payload, field, 1, 64, WidgetRegistryJsonContract.UPPER_CODE);
    }

    private static void exact(JsonNode payload, Set<String> required) throws BindingException {
        exact(payload, required, Set.of());
    }

    private static void exact(JsonNode payload, Set<String> required, Set<String> optional)
            throws BindingException {
        WidgetRegistryJsonContract.exactObject(payload, required, optional);
    }

    private static Map.Entry<String, String> type(String operationId, String commandType) {
        return Map.entry(operationId, commandType);
    }

    record Validation(String ownerProductKey) {
    }
}
