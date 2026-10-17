package com.dwp.services.auth.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrisNavigationPolicyDecisionDtosTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsOnlyTheClosedFreshnessRequestWithoutAnyCallerCandidateSet() throws Exception {
        String canonical = """
                {
                  "tenantId":19,
                  "actorId":71,
                  "activeAccessMode":"NORMAL",
                  "expectedPersonPublicId":"33000000-0000-0000-0000-000000000071",
                  "expectedScopeKey":"scope-00000000000000000000000000000000",
                  "expectedRegistryVersion":47,
                  "expectedRegistryChecksum":"925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e",
                  "expectedDecisionRevision":null
                }
                """;

        HrisNavigationPolicyDecisionDtos.BatchRequest request = mapper.readValue(
                canonical, HrisNavigationPolicyDecisionDtos.BatchRequest.class);

        assertThat(request.tenantId()).isEqualTo(19L);
        assertThat(request.actorId()).isEqualTo(71L);
        assertThat(request.expectedRegistryVersion()).isEqualTo(47L);
        assertThat(request.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "tenantId", "actorId", "activeAccessMode", "expectedPersonPublicId",
                        "expectedScopeKey", "expectedRegistryVersion",
                        "expectedRegistryChecksum", "expectedDecisionRevision")
                .doesNotContain("candidates", "paths", "nodeIds", "routeContractKeys");
    }

    @Test
    void rejectsCallerInjectedPathsNodesCandidatesAndLegalEntity() {
        for (String field : new String[] {
                "candidates", "paths", "nodeIds", "routeContractKeys",
                "requestedLegalEntityPublicId"}) {
            String hostile = """
                    {
                      "tenantId":19,
                      "actorId":71,
                      "activeAccessMode":"NORMAL",
                      "expectedPersonPublicId":"33000000-0000-0000-0000-000000000071",
                      "expectedScopeKey":"scope-00000000000000000000000000000000",
                      "expectedRegistryVersion":47,
                      "expectedRegistryChecksum":"925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e",
                      "%s":[]
                    }
                    """.formatted(field);

            assertThatThrownBy(() -> mapper.readValue(
                    hostile, HrisNavigationPolicyDecisionDtos.BatchRequest.class))
                    .as("field %s must be rejected", field)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage(
                            "Unknown HRIS navigation POLICY decision request field: " + field);
        }
    }
}
