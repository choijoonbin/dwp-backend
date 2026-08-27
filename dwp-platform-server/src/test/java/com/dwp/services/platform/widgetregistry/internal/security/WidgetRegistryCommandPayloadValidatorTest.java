package com.dwp.services.platform.widgetregistry.internal.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WidgetRegistryCommandPayloadValidatorTest {

    private static final String UUID = "40000000-0000-4000-8000-000000000001";
    private static final String UUID_2 = "40000000-0000-4000-8000-000000000002";
    private static final String SHA256 = "a".repeat(64);
    private static final String COMMON = "\"reasonCode\":\"CONTRACT_TEST\","
            + "\"reasonText\":\"Executable contract test.\",\"expectedVersion\":0";

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void acceptsEveryClosedCommandBranchAndExposesOnlyCreateOwnership() throws Exception {
        Map<String, CommandCase> cases = commandCases();

        assertThat(cases).hasSize(21);
        assertThat(WidgetRegistryCommandPayloadValidator.branchCount()).isEqualTo(21);
        for (var entry : cases.entrySet()) {
            JsonNode payload = objectMapper.readTree(entry.getValue().payload());
            var validation = WidgetRegistryCommandPayloadValidator.validate(
                    entry.getKey(), entry.getValue().commandType(), payload);
            if (Set.of(
                    "createWidgetDefinition",
                    "createWidgetDefinitionVersion",
                    "updateWidgetDefinitionVersion").contains(entry.getKey())) {
                assertThat(validation.ownerProductKey()).isEqualTo("core.workspace");
            } else {
                assertThat(validation.ownerProductKey()).isNull();
            }
        }
    }

    @Test
    void rejectsUnknownMissingAndCrossBranchPayloadMembersForAllTwentyOneBranches()
            throws Exception {
        for (var entry : commandCases().entrySet()) {
            ObjectNode payload = (ObjectNode) objectMapper.readTree(entry.getValue().payload());
            ObjectNode unknown = payload.deepCopy();
            unknown.put("unexpected", true);
            ObjectNode missing = payload.deepCopy();
            missing.remove("expectedVersion");

            assertThatThrownBy(() -> WidgetRegistryCommandPayloadValidator.validate(
                    entry.getKey(), entry.getValue().commandType(), unknown))
                    .as(entry.getKey() + " additionalProperties:false")
                    .isInstanceOf(WidgetRegistryRequestBinding.BindingException.class);
            assertThatThrownBy(() -> WidgetRegistryCommandPayloadValidator.validate(
                    entry.getKey(), entry.getValue().commandType(), missing))
                    .as(entry.getKey() + " required expectedVersion")
                    .isInstanceOf(WidgetRegistryRequestBinding.BindingException.class);
            assertThatThrownBy(() -> WidgetRegistryCommandPayloadValidator.validate(
                    entry.getKey(), "WRONG_TYPE", payload))
                    .as(entry.getKey() + " exact operation/type branch")
                    .isInstanceOf(WidgetRegistryRequestBinding.BindingException.class);
        }
    }

    @Test
    void enforcesReasonDefinitionArrayAndRuntimeBoundaryContracts() throws Exception {
        ObjectNode create = payload("createWidgetDefinition");
        assertRejected(create.deepCopy().put("definitionKey", "ab"),
                "createWidgetDefinition", "CREATE_DEFINITION");
        assertRejected(create.deepCopy().put("reasonCode", "lower-case"),
                "createWidgetDefinition", "CREATE_DEFINITION");
        assertRejected(create.deepCopy().put("reasonCode", "A".repeat(65)),
                "createWidgetDefinition", "CREATE_DEFINITION");
        assertRejected(create.deepCopy().put("reasonText", "x".repeat(501)),
                "createWidgetDefinition", "CREATE_DEFINITION");
        assertRejected(create.deepCopy().put("reasonText", "line\nbreak"),
                "createWidgetDefinition", "CREATE_DEFINITION");

        ObjectNode decision = payload("decideWidgetDefinitionVersion");
        decision.withArray("evidenceIds").add(UUID);
        decision.withArray("evidenceIds").add(UUID);
        assertRejected(decision, "decideWidgetDefinitionVersion", "DECIDE");

        ObjectNode runtime = payload("disableWidgetRuntimeControl");
        runtime.put("targetType", "GLOBAL").put("targetId", UUID);
        assertRejected(runtime, "disableWidgetRuntimeControl", "DISABLE_RUNTIME_CONTROL");

        for (String integerSpelling : new String[]{"1.0", "1e0"}) {
            JsonNode transition = objectMapper.readTree(
                    "{\"reasonCode\":\"TEST\",\"reasonText\":\"Reason.\","
                            + "\"expectedVersion\":" + integerSpelling + "}");
            assertThatCode(() -> WidgetRegistryCommandPayloadValidator.validate(
                    "submitWidgetDefinitionVersion", "SUBMIT", transition))
                    .as("JSON Schema mathematical integer " + integerSpelling)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void validatesTheEmbeddedManifestAsAClosedExecutableContract() throws Exception {
        ObjectNode createVersion = payload("createWidgetDefinitionVersion");
        ObjectNode manifest = (ObjectNode) createVersion.get("manifest");

        assertThatCode(() -> WidgetRegistryCommandPayloadValidator.validate(
                "createWidgetDefinitionVersion", "CREATE_VERSION", createVersion))
                .doesNotThrowAnyException();

        ObjectNode unknown = createVersion.deepCopy();
        ((ObjectNode) unknown.get("manifest")).put("script", "alert(1)");
        assertRejected(unknown, "createWidgetDefinitionVersion", "CREATE_VERSION");

        ObjectNode unsafeAuthority = createVersion.deepCopy();
        ((ObjectNode) unsafeAuthority.get("manifest"))
                .withArray("requiredAuthorities")
                .set(0, objectMapper.getNodeFactory().textNode("ADMIN"));
        assertRejected(unsafeAuthority, "createWidgetDefinitionVersion", "CREATE_VERSION");

        ObjectNode looseRecipient = createVersion.deepCopy();
        ((ObjectNode) ((ObjectNode) looseRecipient.get("manifest")).get("privacy"))
                .put("recipientContextBinding", false);
        assertRejected(looseRecipient, "createWidgetDefinitionVersion", "CREATE_VERSION");

        ObjectNode unsortedAuthorities = createVersion.deepCopy();
        ((ObjectNode) unsortedAuthorities.get("manifest"))
                .putArray("requiredAuthorities")
                .add("APP.ZETA:VIEW")
                .add("APP.ALPHA:VIEW");
        assertRejected(unsortedAuthorities, "createWidgetDefinitionVersion", "CREATE_VERSION");

        ObjectNode invalidPolicyContext = createVersion.deepCopy();
        ((ObjectNode) ((ObjectNode) invalidPolicyContext.get("manifest")).get("placement"))
                .putArray("supportedContexts")
                .add("FLOW_GOVERNED");
        assertRejected(invalidPolicyContext, "createWidgetDefinitionVersion", "CREATE_VERSION");

        ObjectNode defaultOutsideAllowed = createVersion.deepCopy();
        ((ObjectNode) ((ObjectNode) defaultOutsideAllowed.get("manifest")).get("placement"))
                .put("defaultSize", "full");
        assertRejected(defaultOutsideAllowed, "createWidgetDefinitionVersion", "CREATE_VERSION");

        ObjectNode reversedItemLimit = createVersion.deepCopy();
        ObjectNode configuration = ((ObjectNode) reversedItemLimit.get("manifest"))
                .putObject("configurationContract");
        configuration.put("sourceKey", "WORK_ITEMS");
        configuration.putArray("fieldKeys").add("title");
        configuration.putArray("filterPresets").add("OPEN");
        configuration.putObject("itemLimit").put("min", 10).put("max", 1);
        assertRejected(reversedItemLimit, "createWidgetDefinitionVersion", "CREATE_VERSION");

        assertThat(manifest.get("configurationContract").isNull()).isTrue();
    }

    private void assertRejected(ObjectNode payload, String operationId, String commandType) {
        assertThatThrownBy(() -> WidgetRegistryCommandPayloadValidator.validate(
                operationId, commandType, payload))
                .isInstanceOf(WidgetRegistryRequestBinding.BindingException.class);
    }

    private ObjectNode payload(String operationId) throws Exception {
        return (ObjectNode) objectMapper.readTree(commandCases().get(operationId).payload());
    }

    private static Map<String, CommandCase> commandCases() {
        Map<String, CommandCase> cases = new LinkedHashMap<>();
        cases.put("createWidgetDefinition", command("CREATE_DEFINITION", """
                {"definitionKey":"core.workspace.command-rail","ownerProductKey":"core.workspace",
                "ownerTeamKey":"workspace-experience","riskTier":"MEDIUM",
                "dataClassification":"INTERNAL",%s}
                """.formatted(COMMON)));
        cases.put("createWidgetDefinitionVersion", command("CREATE_VERSION", """
                {"semanticVersion":"1.0.0","manifest":%s,%s}
                """.formatted(manifest(), COMMON)));
        cases.put("updateWidgetDefinitionVersion", command("UPDATE_VERSION", """
                {"manifest":%s,%s}
                """.formatted(manifest(), COMMON)));
        cases.put("validateWidgetDefinitionVersion", command("VALIDATE",
                "{\"manifestHash\":\"" + SHA256 + "\"," + COMMON + "}"));
        cases.put("submitWidgetDefinitionVersion", command("SUBMIT", "{" + COMMON + "}"));
        cases.put("decideWidgetDefinitionVersion", command("DECIDE", """
                {"decision":"APPROVE","validationRunId":"%s","evidenceIds":[],%s}
                """.formatted(UUID, COMMON)));
        cases.put("reworkWidgetDefinitionVersion", command("REWORK", """
                {"rejectedDecisionId":"%s",%s}
                """.formatted(UUID, COMMON)));
        cases.put("recordWidgetCertificationEvidence", command("RECORD_EVIDENCE", """
                {"evidenceType":"SECURITY","decision":"PASS","manifestHash":"%s",
                "evidenceRef":"artifact/security/report","evidenceSha256":"%s",%s}
                """.formatted(SHA256, SHA256, COMMON)));
        cases.put("waiveWidgetCertificationEvidence", command("WAIVE_EVIDENCE", """
                {"manifestHash":"%s","waiverExpiresAt":"2026-09-01T00:00:00Z",
                "waiverReason":"Time-bound waiver.","trackingTicketRef":"SEC-42",%s}
                """.formatted(SHA256, COMMON)));
        cases.put("publishWidgetDefinitionVersion", command("PUBLISH", """
                {"channel":"STABLE","validationRunId":"%s","evidenceIds":[],
                "manifestHash":"%s",%s}
                """.formatted(UUID, SHA256, COMMON)));
        cases.put("deprecateWidgetDefinitionVersion", command("DEPRECATE", """
                {"replacementVersionId":"%s","deprecationEndsAt":"2026-09-01T00:00:00Z",%s}
                """.formatted(UUID, COMMON)));
        cases.put("quarantineWidgetDefinitionVersion", command("QUARANTINE",
                safetyPayload()));
        cases.put("approveWidgetQuarantineClearance", command("APPROVE_QUARANTINE_CLEARANCE", """
                {"quarantineEventId":"%s","reviewDecision":"APPROVE","evidenceRefs":[],%s}
                """.formatted(UUID, COMMON)));
        cases.put("clearWidgetVersionQuarantine", command("CLEAR_QUARANTINE", """
                {"clearanceApprovalId":"%s","quarantineEventId":"%s",%s}
                """.formatted(UUID, UUID_2, COMMON)));
        cases.put("revokeWidgetDefinitionVersion", command("REVOKE", safetyPayload()));
        cases.put("retireWidgetDefinition", command("RETIRE", """
                {"replacementDefinitionId":null,"impactRevision":"impact-r42",%s}
                """.formatted(COMMON)));
        cases.put("promoteWidgetReleaseChannel", command("PROMOTE", """
                {"versionId":"%s","validationRunId":"%s","manifestHash":"%s",%s}
                """.formatted(UUID, UUID_2, SHA256, COMMON)));
        cases.put("rollbackWidgetReleaseChannel", command("ROLLBACK", """
                {"restoreVersionId":"%s","expectedCurrentVersionId":"%s",%s}
                """.formatted(UUID, UUID_2, COMMON)));
        cases.put("disableWidgetRuntimeControl", command("DISABLE_RUNTIME_CONTROL", """
                {"scope":"RUNTIME_RENDER","targetType":"GLOBAL","targetId":null,
                "publicReasonCode":"INCIDENT","internalIncidentRef":"INC-42",%s}
                """.formatted(COMMON)));
        cases.put("approveWidgetRuntimeControlEnable", command("APPROVE_RUNTIME_CONTROL_ENABLE", """
                {"controlRevision":1,"evidenceRefs":[],%s}
                """.formatted(COMMON)));
        cases.put("enableWidgetRuntimeControl", command("ENABLE_RUNTIME_CONTROL", """
                {"enableApprovalId":"%s","controlRevision":1,%s}
                """.formatted(UUID, COMMON)));
        return cases;
    }

    private static String safetyPayload() {
        return "{\"publicReasonCode\":\"INCIDENT\",\"internalIncidentRef\":\"INC-42\","
                + COMMON + "}";
    }

    private static CommandCase command(String commandType, String payload) {
        return new CommandCase(commandType, payload);
    }

    private static String manifest() {
        return """
                {"schemaVersion":1,"definitionKey":"core.workspace.command-rail",
                "owner":{"productKey":"core.workspace","sourceAppResourceKey":"APP.CORE.WORKSPACE"},
                "renderer":{"kind":"NATIVE","rendererKey":"core.workspace.command-rail",
                "minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],
                "requiredAuthorities":["APP.CORE.WORKSPACE:VIEW"],
                "placement":{"supportedContexts":["CLASSIC_PERSONAL"],"policyClass":"PERSONAL",
                "canHide":true,"defaultSize":"medium","allowedSizes":["medium"],
                "defaultHeight":"standard","allowedHeights":["standard"]},
                "configurationContract":null,"dataCapabilities":["WORK_ITEMS"],
                "actionCapabilities":[],"sharing":{"presetEligible":true},
                "operations":{"freshnessSeconds":30,"analyticsKey":"core.workspace.command-rail"},
                "privacy":{"classification":"INTERNAL","retention":"NONE",
                "recipientContextBinding":true}}
                """;
    }

    private record CommandCase(String commandType, String payload) {
    }
}
