package com.dwp.services.approval.domain;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ApprovalCommandRepositoryTest {

    private final ApprovalCommandRepository repository = new ApprovalCommandRepository(
            mock(NamedParameterJdbcTemplate.class),
            new ObjectMapper().findAndRegisterModules());

    @Test
    void restoresThePublishedWorkflowStepOrderAndRoutingSnapshot() {
        List<ApprovalCommandRepository.RuntimeStep> steps = repository.runtimeSteps("""
                {
                  "schemaVersion": 1,
                  "steps": [
                    {
                      "key": "TEAM_REVIEW",
                      "name": "Team review",
                      "mode": "ANY",
                      "candidateRole": "APPROVAL_OPERATOR",
                      "slaMinutes": 120
                    },
                    {
                      "key": "FINAL_APPROVAL",
                      "mode": "ANY",
                      "candidateRole": "APPROVAL_PUBLISHER",
                      "slaMinutes": 240
                    }
                  ]
                }
                """, 720);

        assertThat(steps).containsExactly(
                new ApprovalCommandRepository.RuntimeStep(
                        "TEAM_REVIEW", "Team review", "ANY", "APPROVAL_OPERATOR", 120),
                new ApprovalCommandRepository.RuntimeStep(
                        "FINAL_APPROVAL", "FINAL_APPROVAL", "ANY", "APPROVAL_PUBLISHER", 240));
    }

    @Test
    void rejectsUnsupportedQuorumModesUntilCandidateSnapshotsAreImplemented() {
        assertThatThrownBy(() -> repository.runtimeSteps("""
                {
                  "steps": [{
                    "key": "FINANCE_REVIEW",
                    "mode": "ALL",
                    "candidateRole": "APPROVAL_OPERATOR",
                    "slaMinutes": 120
                  }]
                }
                """, 240))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsAnInvalidPersistedRoutingPrincipal() {
        assertThatThrownBy(() -> repository.runtimeSteps("""
                {"steps":[{"key":"REVIEW","candidateRole":"finance approvers","slaMinutes":30}]}
                """, 60))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void suppliesAGovernedFallbackForLegacyDefinitionsWithoutSteps() {
        assertThat(repository.runtimeSteps("{}", 90)).containsExactly(
                new ApprovalCommandRepository.RuntimeStep(
                        "PRIMARY_REVIEW", "Primary review", "ANY", "APPROVAL_OPERATOR", 90));
    }

    @Test
    void validatesARequestAgainstThePublishedDynamicFormContract() {
        repository.validateRequestPayload(formSchema(), Map.of(
                "summary", "Budget and risk context",
                "amount", "125000000",
                "currency", "KRW",
                "neededBy", "2026-09-15",
                "createdFrom", "DWP_APPROVALS"));
    }

    @Test
    void rejectsValuesOutsideTheGovernedSelectOptions() {
        assertThatThrownBy(() -> repository.validateRequestPayload(formSchema(), Map.of(
                "summary", "Budget and risk context",
                "amount", "125000000",
                "currency", "BTC",
                "neededBy", "2026-09-15")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsMissingRequiredDynamicFields() {
        assertThatThrownBy(() -> repository.validateRequestPayload(formSchema(), Map.of(
                "summary", "Budget and risk context",
                "currency", "KRW",
                "neededBy", "2026-09-15")))
                .isInstanceOf(BaseException.class);
    }

    private String formSchema() {
        return """
                {
                  "schemaVersion": 2,
                  "fields": [
                    {"key":"summary","type":"TEXTAREA","required":true,"options":[]},
                    {"key":"amount","type":"NUMBER","required":true,"options":[]},
                    {"key":"currency","type":"SELECT","required":true,
                     "options":["KRW","USD"]},
                    {"key":"neededBy","type":"DATE","required":true,"options":[]}
                  ]
                }
                """;
    }
}
