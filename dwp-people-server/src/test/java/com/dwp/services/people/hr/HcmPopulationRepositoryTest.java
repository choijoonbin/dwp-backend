package com.dwp.services.people.hr;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HcmPopulationRepositoryTest {

    @Test
    void actorMutationProofLocksCurrentWorkerRelationshipAndAssignment() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<MapSqlParameterSource> parameters = new AtomicReference<>();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<HcmPopulationRepository.ActorWorkforce>>any()))
                .thenAnswer(invocation -> {
                    sql.set(invocation.getArgument(0));
                    parameters.set(invocation.getArgument(1));
                    return List.of();
                });
        HcmPopulationRepository repository = new HcmPopulationRepository(jdbc);
        UUID personId = UUID.randomUUID();

        Optional<HcmPopulationRepository.ActorWorkforce> result =
                repository.actorForMutation(19L, personId);

        assertThat(result).isEmpty();
        assertThat(normalized(sql.get()))
                .contains("JOIN ppl_workers worker ON worker.tenant_id = person.tenant_id")
                .contains("JOIN ppl_work_relationships relationship ON relationship.tenant_id = worker.tenant_id")
                .contains("JOIN ppl_assignments assignment ON assignment.tenant_id = relationship.tenant_id")
                .contains("WHERE person.tenant_id = :tenantId AND person.public_id = :personPublicId")
                .endsWith("FOR SHARE OF worker, relationship, assignment");
        assertThat(parameters.get().getValue("tenantId")).isEqualTo(19L);
        assertThat(parameters.get().getValue("personPublicId")).isEqualTo(personId);
    }

    @Test
    void targetMutationProofLocksOnlyAWorkerInsideTheSelectedPopulation() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<MapSqlParameterSource> parameters = new AtomicReference<>();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Long>>any()))
                .thenAnswer(invocation -> {
                    sql.set(invocation.getArgument(0));
                    parameters.set(invocation.getArgument(1));
                    return List.of();
                });
        HcmPopulationRepository repository = new HcmPopulationRepository(jdbc);
        UUID organizationId = UUID.randomUUID();
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        31L, "MANAGER-31", false, Set.of(organizationId),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "policy-v8");

        boolean locked = repository.lockWorkerInPopulation(19L, scope, 47L);

        assertThat(locked).isFalse();
        assertThat(normalized(sql.get()))
                .contains("WHERE person.tenant_id = :tenantId")
                .contains("AND worker.worker_id = :targetWorkerId")
                .contains("AND worker.worker_id <> :actorWorkerId")
                .contains("assignment.manager_assignment_key = :managerAssignmentKey")
                .contains("organization.public_id IN (:organizationIds)")
                .endsWith("FOR SHARE OF worker, relationship, assignment");
        assertThat(parameters.get().getValue("tenantId")).isEqualTo(19L);
        assertThat(parameters.get().getValue("targetWorkerId")).isEqualTo(47L);
        assertThat(parameters.get().getValue("actorWorkerId")).isEqualTo(31L);
        assertThat(parameters.get().getValue("managerAssignmentKey")).isEqualTo("MANAGER-31");
        assertThat(parameters.get().getValue("organizationIds")).isEqualTo(Set.of(organizationId));
    }

    @Test
    void actorMutationProofFailsClosedBeforeSqlWhenIdentityIsIncomplete() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HcmPopulationRepository repository = new HcmPopulationRepository(jdbc);

        assertThat(repository.actorForMutation(null, UUID.randomUUID())).isEmpty();
        assertThat(repository.actorForMutation(19L, null)).isEmpty();
    }

    @Test
    void approvalQueuesProjectDecisionEvidenceForTimeAndAbsence() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<HrDtos.ApprovalItem>>any()))
                .thenAnswer(invocation -> {
                    sql.set(invocation.getArgument(0));
                    return List.of();
                });
        HcmPopulationRepository repository = new HcmPopulationRepository(jdbc);
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        31L, "MANAGER-31", true, Set.of(),
                        Set.of("EMPLOYMENT"), "policy-v9");

        repository.teamQueue(19L, scope, "TIME");

        assertThat(normalized(sql.get()))
                .contains("card.period_start_date, card.period_end_date")
                .contains("card.scheduled_minutes, card.recorded_minutes")
                .contains("card.exception_count");

        repository.teamQueue(19L, scope, "ABSENCE");

        assertThat(normalized(sql.get()))
                .contains("request.start_at, request.end_at, request.requested_minutes")
                .contains("request.reason")
                .contains("LEFT JOIN abs_leave_balances balance")
                .contains("AS available_minutes");
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
