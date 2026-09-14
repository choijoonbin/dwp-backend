package com.dwp.services.auth.service;

import static org.assertj.core.api.Assertions.*;
import com.dwp.services.auth.approvalsignatures.SignatureAuthorityProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ApprovalSignatureProjectionContractTest {
    @Test void actualSealedTenUsesZeroProjectionsForActionsAndOneClosedProjectionForData() throws Exception {
        try (var stream=getClass().getResourceAsStream("/product-authorization/product-surfaces-v1.bundle-v10.generated.json")) {
            assertThat(stream).isNotNull();var document=new ObjectMapper().readTree(stream);
            for (var operation:SignatureAuthorityProtocol.Operation.values()) {
                var matches=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                document.path("routes").forEach(route->{if(operation.route().equals(route.path("routeContractKey").asText()))matches.add(route);});
                assertThat(matches).as(operation.name()).hasSize(1);var route=matches.getFirst();
                assertThat(route.path("routeKind").asText()).isEqualTo(operation.mutation()?"ACTION":"DATA");
                assertThat(route.path("accessProfiles").size()).isEqualTo(1);
                var projections=route.path("accessProfiles").get(0).path("responseProjectionBindings");
                if (operation.mutation()) assertThat(projections.isMissingNode()).as(operation.name()).isTrue();
                else assertThat(projections.isArray()).as(operation.name()).isTrue();
                assertThat(projections.size()).as(operation.name()).isEqualTo(operation.mutation()?0:1);
                assertThatCode(()->ApprovalSignatureCurrentAuthorityBridge.requireProjectionCount(operation,projections.size())).doesNotThrowAnyException();
                if (!operation.mutation()) {
                    var projection=projections.get(0);assertThat(projection.path("additionalProperties").asBoolean()).isFalse();
                    assertThat(projection.path("schemaVersion").asInt()).isEqualTo(1);
                    assertThat(projection.path("openApiSchemaSha256").asText()).matches("[a-f0-9]{64}");
                }
            }
        }
    }
    @Test void fabricatedActionProjectionOrMissingDataProjectionNeverPassesTheSourceGuard() {
        for (var operation:SignatureAuthorityProtocol.Operation.values()) {
            assertThatThrownBy(()->ApprovalSignatureCurrentAuthorityBridge.requireProjectionCount(operation,operation.mutation()?1:0))
                    .isInstanceOf(com.dwp.core.exception.BaseException.class);
            assertThatThrownBy(()->ApprovalSignatureCurrentAuthorityBridge.requireProjectionCount(operation,2))
                    .isInstanceOf(com.dwp.core.exception.BaseException.class);
        }
    }
}
