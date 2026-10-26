package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ApprovalRelease62PepContractTest {
    private static final String RESOURCE =
            "product-authorization/approval-pilot-pep-v62.generated.json";
    private static final String CAPABILITY =
            "approvals.work.hris-separation-waiver-task.approve";
    private static final String ROUTE =
            "route.approvals.work.hris-separation-waiver-task-approve.action";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void release62PublishesOnlyTheDedicatedHrm013ApprovalAuthority() throws Exception {
        ObjectNode projection = resource(RESOURCE);

        assertThat(projection.path("registryRef").path("version").asInt()).isEqualTo(62);
        assertThat(projection.path("registryRef").path("sha256").asText())
                .isEqualTo("cb370fd02a896452a64187fca8afb8bb3e3d281e490fba02e76f7a3c0d7f9770");
        assertThat(projection.path("sourceRegistryRouteCount").asInt()).isEqualTo(1158);
        assertThat(projection.path("projectedRouteContractCount").asInt()).isEqualTo(217);
        assertThat(projection.path("bindingPairCount").asInt()).isEqualTo(286);
        assertThat(projection.path("routeKindCounts"))
                .isEqualTo(json.readTree("{\"ACTION\":113,\"DATA\":84,\"PAGE\":20}"));
        assertThat(projection.path("capabilities")).hasSize(40);
        assertThat(projection.path("accessPolicies")).hasSize(1);
        assertThat(projection.path("entitlementExpressions")).hasSize(1);
        assertThat(projection.path("predicatePolicies")).hasSize(18);
        assertThat(projection.path("routes")).hasSize(217);

        JsonNode capability = element(projection.path("capabilities"), "contractKey", CAPABILITY);
        assertThat(capability.path("resolvedCapabilityCode").asText())
                .isEqualTo("ACTION.APPROVAL_TASK:APPROVE");
        assertThat(capability.path("scopeResolver").asText())
                .isEqualTo("DECIDABLE_TASK_SELF_APPROVAL_SOD");
        assertThat(capability.path("riskTier").asText()).isEqualTo("HIGH");
        assertThat(capability.path("activationPolicy").asText())
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(capability.path("routeContractKeys"))
                .isEqualTo(json.readTree("[\"" + ROUTE + "\"]"));

        JsonNode route = element(projection.path("routes"), "routeContractKey", ROUTE);
        assertThat(route.path("routeKind").asText()).isEqualTo("ACTION");
        assertThat(route.path("accessProfiles")).singleElement().satisfies(profile -> {
            assertThat(profile.path("profileKey").asText()).isEqualTo("full-work");
            assertThat(profile.path("activeAccessModes"))
                    .isEqualTo(json.createArrayNode().add("NORMAL").add("ELEVATED"));
            assertThat(profile.path("requiredAccess").path("capabilityContractKey").asText())
                    .isEqualTo(CAPABILITY);
            assertThat(profile.path("targetBindingKinds"))
                    .isEqualTo(json.createArrayNode().add("OBJECT"));
            assertThat(profile.path("predicatePolicyKeys")).isEqualTo(
                    json.createArrayNode()
                            .add("predicate.approval-task-decision.v1")
                            .add("predicate.approval.object-version.v1"));
            assertThat(profile.path("readOnly").asBoolean()).isFalse();
        });
        assertThat(route.path("gatewayApiBindings")).singleElement()
                .satisfies(binding -> {
                    assertThat(binding.path("method").asText()).isEqualTo("POST");
                    assertThat(binding.path("path").asText()).isEqualTo(
                            "/api/approvals/v1/hris/"
                                    + "separation-waiver-tasks/{taskId}/approve");
                });
        assertThat(route.path("servicePepBindings")).singleElement()
                .satisfies(binding -> {
                    assertThat(binding.path("path").asText()).isEqualTo(
                            "/v1/hris/separation-waiver-tasks/{taskId}/approve");
                    assertThat(binding.path("serviceKey").asText()).isEqualTo("approval");
                });
        assertThat(route.path("stepUpCommandBindings")).singleElement()
                .satisfies(binding -> {
                    assertThat(binding.path("bindingKey").asText())
                            .isEqualTo(ROUTE + ".binding.01");
                    assertThat(binding.path("targetType").asText())
                            .isEqualTo("HRIS_SEPARATION_WAIVER_TASK");
                    assertThat(binding.path("targetIdPathParameter").asText())
                            .isEqualTo("taskId");
                    assertThat(binding.path("expectedObjectVersionSource").asText())
                            .isEqualTo("COMMAND_HEADER");
                    assertThat(binding.path("expectedObjectVersionName").asText())
                            .isEqualTo("X-DWP-Expected-Object-Version");
                    assertThat(binding.path("ownerServiceKey").asText())
                            .isEqualTo("approval");
                    assertThat(binding.path("audience").asText())
                            .isEqualTo("dwp-approval-server");
                });
    }

    private ObjectNode resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            JsonNode document = json.readTree(input);
            assertThat(document).isInstanceOf(ObjectNode.class);
            return (ObjectNode) document;
        }
    }

    private static JsonNode element(JsonNode values, String field, String expected) {
        return StreamSupport.stream(values.spliterator(), false)
                .filter(value -> expected.equals(value.path(field).asText()))
                .findFirst()
                .orElseThrow();
    }
}
