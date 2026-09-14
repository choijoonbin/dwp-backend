package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ApprovalExtensionV9PepRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private ObjectNode document(String resource) throws Exception {
        try (var input = getClass().getResourceAsStream("/product-authorization/" + resource)) {
            assertThat(input).isNotNull();
            return (ObjectNode) mapper.readTree(input);
        }
    }

    @Test
    void currentConstructorRetainsAllImmutableBaselinesAndExactNewOwnerBindings() {
        var baseline = new ApprovalPilotPepRegistry(mapper, Clock.systemUTC(), 8);
        var current = new ApprovalPilotPepRegistry(mapper);
        assertThat(current.bindingContracts()).hasSize(99).containsAll(baseline.bindingContracts());
        assertThat(current.bindingContracts()).filteredOn(binding -> !baseline.bindingContracts().contains(binding)).hasSize(27);
        assertThatThrownBy(() -> new ApprovalPilotPepRegistry(mapper, Clock.systemUTC(), 10))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unsupported");
    }

    @Test
    void responseRecordsAndRawBinaryHaveDifferentExactMetadataContracts() throws Exception {
        var artifact = document("approval-extension-projections-v9.generated.json");
        ApprovalExtensionProjectionSchemaContract.validateDocument(mapper, artifact);
        for (var binding : artifact.path("bindings")) {
            var metadata = mapper.createObjectNode();
            Set<String> fields = new LinkedHashSet<>(Set.of("apiBindingKey", "projectionPolicyKey", "responseSchemaKey"));
            boolean binary = "RAW_BINARY".equals(binding.path("schemaKind").asText());
            if (!binary) fields.addAll(Set.of("schemaVersion", "openApiSchemaSha256", "additionalProperties"));
            for (String field : fields) metadata.set(field, binding.path(field));
            String key = binding.path("routeContractKey").asText(), profile = binding.path("profileKey").asText();
            assertThat(ApprovalExtensionProjectionSchemaContract.matches(key, profile, metadata)).as(key).isTrue();
            var changed = metadata.deepCopy();
            changed.put("unknown", true);
            assertThat(ApprovalExtensionProjectionSchemaContract.matches(key, profile, changed)).isFalse();
            changed = metadata.deepCopy();
            changed.put("additionalProperties", binary ? false : true);
            assertThat(ApprovalExtensionProjectionSchemaContract.matches(key, profile, changed)).isFalse();
            assertThat(ApprovalExtensionProjectionSchemaContract.matches(key, "auditor", metadata)).isFalse();
        }
    }

    @Test
    void changedTransitiveContentCannotBeAcceptedByRecomputingHashes() throws Exception {
        var changed = document("approval-extension-projections-v9.generated.json");
        ((ObjectNode) changed.path("schemas").path("ApprovalInformationCommandReceipt").path("properties")).putObject("borrowedAuthority");
        var graph = mapper.createObjectNode();
        graph.set("schemas", changed.path("schemas"));
        graph.set("binaryResponses", changed.path("binaryResponses"));
        changed.put("schemaClosureSha256", ApprovalPepProjectionLineage.sha256(mapper, graph));
        changed.remove("checksum");
        changed.put("checksum", ApprovalPepProjectionLineage.sha256(mapper, changed));
        assertThatThrownBy(() -> ApprovalExtensionProjectionSchemaContract.validateDocument(mapper, changed))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum drift");
    }

    @Test
    void receiptIsReadonlyPostAndCannotBorrowAnOrdinaryRequestDecision() {
        var registry = new ApprovalPilotPepRegistry(mapper);
        String path = "/v1/requests/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/information-commands/original:1/receipt";
        String key = "route.approvals.work.information-command-receipt.data";
        var permissions = Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:VIEW");
        var result = registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence("POST", path, permissions,
                "", Set.of(), key, ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));
        assertThat(result.allowed()).isTrue();
        assertThat(result.authorities()).singleElement().satisfies(authority -> {
            assertThat(authority.routeKind()).isEqualTo("DATA");
            assertThat(authority.readOnly()).isTrue();
            assertThat(authority.capabilityContractKey()).isEqualTo("approvals.work.information-command-receipt.read");
            assertThat(authority.predicatePolicyKeys()).containsExactly("predicate.approval.original-information-command-receipt.v1");
        });
        for (String method : Set.of("GET", "HEAD", "PUT")) {
            assertThat(registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence(method, path, permissions,
                    "", Set.of(), key, ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL)).allowed()).isFalse();
        }
        assertThat(registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence("POST", path, permissions,
                "", Set.of(), "route.approvals.work.request-detail.data", ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL)).allowed()).isFalse();
    }
}
