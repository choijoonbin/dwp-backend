package com.dwp.services.platform.widgetregistry.internal.security;

import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryCommandSemanticBinding.Fields;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.CommandTargetBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetRegistryCommandTrustPolicyTest {

    private static final String DEFINITION_ID = "11111111-1111-4111-8111-111111111111";

    @Test
    void derivesEveryHashTargetFromItsClosedPayloadAndRouteSemantics() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<HashCase> cases = List.of(
                new HashCase(
                        "createWidgetDefinition",
                        "CREATE_DEFINITION",
                        "{\"definitionKey\":\"core.workspace.command-rail\"}",
                        definitionKeyTarget(
                                "ade48b8b145ad48e418f91090f7cd696c0765dfadf2de68922cbd7e2f29cbb7e")),
                new HashCase(
                        "createWidgetDefinitionVersion",
                        "CREATE_VERSION",
                        "{\"semanticVersion\":\"1.0.0\"}",
                        definitionSemverTarget(
                                "204a84ac3984d3a14b52b14816749c701e9812aa34453bc821d7b47561bc186b")),
                new HashCase(
                        "promoteWidgetReleaseChannel",
                        "PROMOTE",
                        "{}",
                        definitionChannelTarget(
                                "ebdb0ac52ee5a3b0144b16aecf71122add592a168a935a81a2a6469a2362bdda")),
                new HashCase(
                        "rollbackWidgetReleaseChannel",
                        "ROLLBACK",
                        "{}",
                        definitionChannelTarget(
                                "ebdb0ac52ee5a3b0144b16aecf71122add592a168a935a81a2a6469a2362bdda")),
                new HashCase(
                        "disableWidgetRuntimeControl",
                        "DISABLE_RUNTIME_CONTROL",
                        "{\"scope\":\"RUNTIME_RENDER\",\"targetType\":\"GLOBAL\",\"targetId\":null}",
                        runtimeScopeTarget(
                                "866f3729173fc76cb9c8b68da9868e6dfee0b3e70844a335003ace93a9a24bb9")));

        for (HashCase hashCase : cases) {
            Fields semantic = WidgetRegistryCommandSemanticBinding.preserve(
                    hashCase.operationId(), objectMapper.readTree(hashCase.payload()));
            assertThat(WidgetRegistryCommandTrustPolicy.resolve(
                    hashCase.operationId(), hashCase.commandType(), hashCase.target(), semantic))
                    .as(hashCase.operationId() + " golden preimage")
                    .isNotNull();
            assertThat(WidgetRegistryCommandTrustPolicy.resolve(
                    hashCase.operationId(),
                    hashCase.commandType(),
                    withTargetId(hashCase.target(), "f".repeat(64)),
                    semantic))
                    .as(hashCase.operationId() + " arbitrary signed hash substitution")
                    .isNull();
        }
    }

    @Test
    void runtimeHashRequiresPayloadScopeTypeAndIdToExactlyMatchTheSignedTarget() {
        CommandTargetBinding target = runtimeScopeTarget(
                "866f3729173fc76cb9c8b68da9868e6dfee0b3e70844a335003ace93a9a24bb9");
        Fields mismatchedPayload = new Fields(null, null, "RUNTIME_ACTION", "GLOBAL", null);

        assertThat(WidgetRegistryCommandTrustPolicy.resolve(
                "disableWidgetRuntimeControl",
                "DISABLE_RUNTIME_CONTROL",
                target,
                mismatchedPayload)).isNull();
    }

    private static CommandTargetBinding definitionKeyTarget(String targetId) {
        return target(Set.of("targetType", "targetId"), "DEFINITION_KEY_HASH", targetId,
                null, null, null, null);
    }

    private static CommandTargetBinding definitionSemverTarget(String targetId) {
        return target(Set.of("targetType", "targetId", "definitionId"),
                "DEFINITION_SEMVER_HASH", targetId, DEFINITION_ID, null, null, null);
    }

    private static CommandTargetBinding definitionChannelTarget(String targetId) {
        return target(Set.of("targetType", "targetId", "definitionId", "channel"),
                "DEFINITION_CHANNEL_HASH", targetId, DEFINITION_ID, "STABLE", null, null);
    }

    private static CommandTargetBinding runtimeScopeTarget(String targetId) {
        return target(Set.of(
                        "targetType", "targetId", "controlScope", "runtimeTargetType", "runtimeTargetId"),
                "RUNTIME_CONTROL_SCOPE_HASH", targetId, null, null, "RUNTIME_RENDER", "GLOBAL");
    }

    private static CommandTargetBinding target(
            Set<String> fields,
            String targetType,
            String targetId,
            String definitionId,
            String channel,
            String controlScope,
            String runtimeTargetType) {
        return new CommandTargetBinding(
                fields,
                targetType,
                targetId,
                definitionId,
                null,
                null,
                null,
                channel,
                controlScope,
                runtimeTargetType,
                null);
    }

    private static CommandTargetBinding withTargetId(CommandTargetBinding source, String targetId) {
        return new CommandTargetBinding(
                source.fields(),
                source.targetType(),
                targetId,
                source.definitionId(),
                source.versionId(),
                source.evidenceId(),
                source.controlId(),
                source.channel(),
                source.controlScope(),
                source.runtimeTargetType(),
                source.runtimeTargetId());
    }

    private record HashCase(
            String operationId,
            String commandType,
            String payload,
            CommandTargetBinding target) {
    }
}
