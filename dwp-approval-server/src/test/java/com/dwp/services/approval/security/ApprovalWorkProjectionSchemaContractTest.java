package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalWorkProjectionSchemaContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode document() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/product-authorization/approval-work-projections-v7.generated.json")) {
            assertThat(input).isNotNull();
            return (ObjectNode) mapper.readTree(input);
        }
    }

    @Test
    void validatesFinalFiveBindingsAndTheirCompleteSchemaGraph() throws Exception {
        var document = document();
        ApprovalWorkProjectionSchemaContract.validateDocument(mapper, document);
        assertThat(document.path("bindings").size()).isEqualTo(5);
        assertThat(document.path("schemas").size()).isEqualTo(10);
        for (var binding : document.path("bindings")) {
            String route = binding.path("routeContractKey").asText();
            assertThat(ApprovalWorkProjectionSchemaContract.matches(route, "full-work", binding)).isTrue();
            for (String field : Set.of("responseSchemaKey", "openApiSchemaSha256",
                    "schemaVersion", "additionalProperties", "projectionPolicyKey", "apiBindingKey")) {
                var changed = (ObjectNode) binding.deepCopy();
                if (field.equals("schemaVersion")) changed.put(field, 2);
                else if (field.equals("additionalProperties")) changed.put(field, true);
                else changed.put(field, "mutated");
                assertThat(ApprovalWorkProjectionSchemaContract.matches(route, "full-work", changed)).isFalse();
            }
            assertThat(ApprovalWorkProjectionSchemaContract.matches(route, "auditor", binding)).isFalse();
        }
    }

    @Test
    void rejectsChangedTransitiveSchemaEvenWithRecomputedClosureAndEnvelopeHashes() throws Exception {
        var changed = document();
        var schema = (ObjectNode) changed.path("schemas").path("DraftReconciliation");
        schema.put("additionalProperties", true);
        changed.put("schemaClosureSha256",
                ApprovalPepProjectionLineage.sha256(mapper, changed.path("schemas")));
        changed.remove("checksum");
        changed.put("checksum", ApprovalPepProjectionLineage.sha256(mapper, changed));
        assertThatThrownBy(() -> ApprovalWorkProjectionSchemaContract.validateDocument(mapper, changed))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum drift");
    }
}

