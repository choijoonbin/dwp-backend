package com.dwp.services.approval.domain;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

    @Test
    void permitsIncompleteButWellTypedPayloadsWhileSavingADraft() {
        repository.validateRequestPayload(
                formSchema(),
                Map.of("summary", "", "amount", "125000000", "createdFrom", "DWP_APPROVALS"),
                false);

        assertThatThrownBy(() -> repository.validateRequestPayload(
                formSchema(),
                Map.of("summary", "", "amount", "not-a-number"),
                false))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void evaluatesConditionalRoutesWithAnExplicitFailClosedContract() {
        assertThat(repository.matchesRouteCondition(
                """
                {"all":[
                  {"field":"amount","operator":"GTE","value":100000000},
                  {"field":"currency","operator":"IN","value":["KRW","USD"]}
                ]}
                """,
                Map.of("amount", 125000000, "currency", "KRW")))
                .isTrue();
        assertThat(repository.matchesRouteCondition(
                "{\"all\":[{\"field\":\"amount\",\"operator\":\"GTE\",\"value\":100000000}]}",
                Map.of("amount", 1000)))
                .isFalse();
        assertThat(repository.matchesRouteCondition("{}", Map.of("amount", 125000000)))
                .isFalse();
    }

    @Test
    void rejectsUnknownOrUnsafePolicyRuleShapes() {
        repository.validatePolicyRule(
                "REQUIRE_REJECT_REASON", Map.of("minimumLength", 12));
        assertThatThrownBy(() -> repository.validatePolicyRule(
                "REQUIRE_REJECT_REASON",
                Map.of("minimumLength", 1, "arbitraryExpression", "allow-all")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void requiresThePublisherToDifferFromTheLastWorkflowEditor() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        ApprovalCommandRepository governed = new ApprovalCommandRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());

        governed.publishWorkflow(actor(), UUID.randomUUID(), 3L, "correlation");

        org.mockito.ArgumentCaptor<String> sql =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(SqlParameterSource.class));
        assertThat(sql.getAllValues().get(0))
                .contains("COALESCE(updated_by, created_by, -1) <> :userId");
    }

    @Test
    void requiresThePublisherToDifferFromTheLastFormEditor() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        ApprovalCommandRepository governed = new ApprovalCommandRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());

        governed.publishForm(actor(), UUID.randomUUID(), 2L);

        org.mockito.ArgumentCaptor<String> sql =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(SqlParameterSource.class));
        assertThat(sql.getAllValues().get(0))
                .contains("COALESCE(form.updated_by, form.created_by, -1) <> :userId");
    }

    private com.dwp.services.approval.security.ApprovalRequestContext.Actor actor() {
        return new com.dwp.services.approval.security.ApprovalRequestContext.Actor(
                17L, 42L, null, "Publisher",
                Set.of("APPROVAL_PUBLISHER"),
                Set.of("ADMIN.APPROVAL_DESIGN:APPROVE"));
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
