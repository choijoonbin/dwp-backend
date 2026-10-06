package com.dwp.services.people.hr.assignment;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssignmentProposalRepositoryTest {

    @Test
    void submitUsesProposalCasAndNeverWritesTheAssignmentLedger() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of());
        AssignmentProposalRepository repository = repository(jdbc);

        assertThat(repository.submit(7L, UUID.randomUUID(), 4L, 19L)).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<UUID>>any());
        assertThat(normalized(sql.getValue()))
                .startsWith("UPDATE ppl_assignment_change_proposals")
                .contains("aggregate_version = aggregate_version + 1")
                .contains("aggregate_version = :expectedVersion")
                .contains("lifecycle_state = 'VALIDATED'")
                .doesNotContain("UPDATE ppl_assignments")
                .doesNotContain("INSERT INTO ppl_assignments");
    }

    @Test
    void receiptClaimIsTheAtomicIdempotencyArbiter() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<AssignmentProposalRepository.ReceiptRow>>any()))
                .thenReturn(List.of());
        AssignmentProposalRepository repository = repository(jdbc);

        repository.claimReceipt(
                7L, UUID.randomUUID(), UUID.randomUUID(), 19L, UUID.randomUUID(),
                "SUBMIT", "idem-1", "a".repeat(64), "population-v1", "decision-v1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<AssignmentProposalRepository.ReceiptRow>>any());
        assertThat(normalized(sql.getValue()))
                .contains("INSERT INTO ppl_assignment_command_receipts")
                .contains("ON CONFLICT DO NOTHING")
                .contains("RETURNING assignment_command_receipt_id");
    }

    @Test
    void mutationTargetAndLifecycleEvidenceAreLockedAndWrittenTogether() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<AssignmentProposalRepository.TargetAssignment>>any()))
                .thenReturn(List.of());
        AssignmentProposalRepository repository = repository(jdbc);
        repository.targetForMutation(7L, UUID.randomUUID());

        ArgumentCaptor<String> targetSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(targetSql.capture(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<AssignmentProposalRepository.TargetAssignment>>any());
        assertThat(normalized(targetSql.getValue()))
                .endsWith("FOR SHARE OF worker, relationship, assignment");

        AssignmentProposalRepository.ProposalRow proposal = proposalRow();
        repository.appendLifecycle(
                7L, 41L, proposal, "SUBMITTED", "{\"proposalId\":\"x\"}", 19L);
        ArgumentCaptor<String> lifecycleSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(
                lifecycleSql.capture(), any(MapSqlParameterSource.class));
        assertThat(lifecycleSql.getAllValues()).anySatisfy(sql ->
                assertThat(sql).contains("ppl_assignment_change_proposal_events"));
        assertThat(lifecycleSql.getAllValues()).anySatisfy(sql ->
                assertThat(sql).contains("ppl_assignment_proposal_outbox"));
    }

    @Test
    void timelineFiltersEveryEffectiveDatedSliceByTheExactPopulationBoundary() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<AssignmentProposalDtos.TimelineEntry>>any()))
                .thenReturn(List.of());
        AssignmentProposalRepository repository = repository(jdbc);
        UUID organizationId = UUID.randomUUID();
        HcmPopulationRepository.PopulationScope population =
                new HcmPopulationRepository.PopulationScope(
                        91L, "MGR-91", false, Set.of(organizationId),
                        Set.of("EMPLOYMENT"), "policy-v1");

        repository.timeline(7L, target(), population);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(),
                ArgumentMatchers.<RowMapper<AssignmentProposalDtos.TimelineEntry>>any());
        assertThat(normalized(sql.getValue()))
                .contains("assignment.assignment_key = :assignmentKey")
                .contains("assignment.manager_assignment_key = "
                        + ":populationManagerAssignmentKey")
                .contains("organization.public_id IN (:populationOrganizationIds)");
        assertThat(parameters.getValue().getValue("populationOrganizationIds"))
                .isEqualTo(Set.of(organizationId));
    }

    private AssignmentProposalRepository repository(NamedParameterJdbcTemplate jdbc) {
        return new AssignmentProposalRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
    }

    private AssignmentProposalRepository.ProposalRow proposalRow() {
        UUID proposalId = UUID.randomUUID();
        return new AssignmentProposalRepository.ProposalRow(
                31L, proposalId, 11L, UUID.randomUUID(), 12L, UUID.randomUUID(),
                13L, UUID.randomUUID(), "ASG-1", "W-1", "Worker", "TRANSFER",
                LocalDate.of(2026, 11, 1), "TRANSFER", java.util.Map.of(),
                "SUBMITTED", List.of(), "a".repeat(64), "b".repeat(64),
                1L, 2L, 3L, 2L, null, java.time.Instant.now(), null, null,
                java.time.Instant.now(), java.time.Instant.now());
    }

    private AssignmentProposalRepository.TargetAssignment target() {
        return new AssignmentProposalRepository.TargetAssignment(
                11L, UUID.randomUUID(), 12L, UUID.randomUUID(),
                13L, UUID.randomUUID(), "ASG-1", "W-1", "Worker",
                "ACTIVE", true, LocalDate.of(2025, 1, 1), null, 1,
                UUID.randomUUID(), "Engineering", "ENGINEER", "Engineer",
                "SEOUL", "Seoul", "MGR-1", UUID.randomUUID(), "Engineer",
                new BigDecimal("40"), BigDecimal.ONE, "TRANSFER",
                3L, 4L, 5L);
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
