package com.dwp.services.people.directory;

import com.dwp.services.people.hr.HcmPopulationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeopleDirectoryRepositoryTest {

    @Test
    void assignmentRegisterSearchIncludesAssignmentKey() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.DirectoryRow>>any()))
                .thenReturn(List.of());
        PeopleDirectoryRepository repository = new PeopleDirectoryRepository(jdbc);

        repository.search(
                7L,
                0L,
                "ASG-MINA-PRIMARY",
                null,
                LocalDate.of(2026, 9, 2),
                51,
                true,
                Set.of(),
                true,
                true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(
                sql.capture(),
                parameters.capture(),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.DirectoryRow>>any());

        assertThat(sql.getValue().replaceAll("\\s+", " ").trim().toLowerCase())
                .contains("lower(coalesce(a.assignment_key, '')) like :query");
        assertThat(parameters.getValue().getValue("query"))
                .isEqualTo("%asg-mina-primary%");
    }

    @Test
    void maskedFieldsNeverParticipateInQueryPredicates() {
        NamedParameterJdbcTemplate jdbc = directoryJdbc();
        PeopleDirectoryRepository repository = new PeopleDirectoryRepository(jdbc);

        repository.search(
                7L, 0L, "secret", null, LocalDate.of(2026, 9, 2), 51,
                true, Set.of(), false, false);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.DirectoryRow>>any());
        String normalized = normalized(sql.getValue()).toLowerCase();
        assertThat(normalized)
                .doesNotContain("lower(coalesce(w.worker_number, '')) like :query")
                .doesNotContain("lower(coalesce(a.assignment_key, '')) like :query")
                .doesNotContain("lower(coalesce(grade.name, '')) like :query");
    }

    @Test
    void workforceDirectorySearchUsesOnlyGrantedRestrictedPredicates() {
        NamedParameterJdbcTemplate jdbc = directoryJdbc();
        PeopleDirectoryRepository repository = new PeopleDirectoryRepository(jdbc);

        repository.search(
                7L, 0L, "senior", null, LocalDate.of(2026, 9, 2), 51,
                true, Set.of(), false, true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.DirectoryRow>>any());
        String normalized = normalized(sql.getValue()).toLowerCase();
        assertThat(normalized)
                .doesNotContain("lower(coalesce(w.worker_number, '')) like :query")
                .doesNotContain("lower(coalesce(a.assignment_key, '')) like :query")
                .contains("lower(coalesce(grade.name, '')) like :query");
    }

    @Test
    void targetPopulationPredicateRunsBeforePagination() {
        NamedParameterJdbcTemplate jdbc = directoryJdbc();
        PeopleDirectoryRepository repository = new PeopleDirectoryRepository(jdbc);
        UUID organizationId = UUID.randomUUID();
        HcmPopulationRepository.PopulationScope population =
                new HcmPopulationRepository.PopulationScope(
                        91L, "MANAGER-91", false, Set.of(organizationId),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "policy-v3");

        repository.searchWithinPopulation(
                7L, 0L, null, null, LocalDate.of(2026, 9, 2), 3,
                false, false, population);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.DirectoryRow>>any());
        String normalized = normalized(sql.getValue()).toLowerCase();
        assertThat(normalized.indexOf(
                "and exists ( select 1 from ppl_workers candidate_worker"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(normalized.lastIndexOf(
                        "order by p.person_id asc limit :limit"));
        assertThat(normalized)
                .contains("population_worker.worker_id <> :populationactorworkerid")
                .contains("population_worker.worker_id = candidate_worker.worker_id")
                .contains("population_relationship.work_relationship_id = "
                        + "candidate_relationship.work_relationship_id")
                .contains("population_assignment.manager_assignment_key = "
                        + ":populationmanagerassignmentkey")
                .contains("population_organization.public_id in "
                        + "(:populationorganizationids)")
                .contains("or candidate.manager_assignment_key = "
                        + ":populationmanagerassignmentkey")
                .contains("from ppl_organizations boundary_organization")
                .contains("and not exists (");
        assertThat(parameters.getValue().getValue("populationActorWorkerId"))
                .isEqualTo(91L);
        assertThat(parameters.getValue().getValue("populationOrganizationIds"))
                .isEqualTo(Set.of(organizationId));
    }

    @Test
    void workforceDeepLinkUsesTheSamePopulationPredicateAndScopedProjection() {
        NamedParameterJdbcTemplate jdbc = directoryJdbc();
        PeopleDirectoryRepository repository = new PeopleDirectoryRepository(jdbc);
        UUID personId = UUID.randomUUID();
        HcmPopulationRepository.PopulationScope population =
                new HcmPopulationRepository.PopulationScope(
                        91L, "MANAGER-91", false, Set.of(UUID.randomUUID()),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "policy-v3");

        repository.findByPublicIdWithinPopulation(
                7L, personId, LocalDate.of(2026, 9, 2), population);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.DirectoryRow>>any());
        String normalized = normalized(sql.getValue()).toLowerCase();
        assertThat(normalized)
                .contains("p.public_id = :publicid")
                .contains("candidate.manager_assignment_key = "
                        + ":populationmanagerassignmentkey")
                .contains("candidate_worker.person_id = p.person_id")
                .contains("population_worker.worker_id = candidate_worker.worker_id")
                .contains("population_relationship.work_relationship_id = "
                        + "candidate_relationship.work_relationship_id")
                .contains("and not exists (")
                .contains("population_assignment.manager_assignment_key = "
                        + ":populationmanagerassignmentkey");
        assertThat(parameters.getValue().getValue("populationEnforced")).isEqualTo(true);
    }

    @Test
    void legacyAssignmentDetailIsBoundToAsOfAndAllowedOrganizations() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.AssignmentRow>>any()))
                .thenReturn(List.of());
        PeopleDirectoryRepository repository = new PeopleDirectoryRepository(jdbc);
        LocalDate asOf = LocalDate.of(2026, 9, 2);
        UUID organizationId = UUID.randomUUID();

        repository.findAssignments(7L, 42L, asOf, false, Set.of(organizationId));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(
                sql.capture(), parameters.capture(),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.AssignmentRow>>any());
        assertThat(normalized(sql.getValue()).toLowerCase())
                .contains("relationship.start_date <= :asof")
                .contains("relationship.end_date >= :asof")
                .contains("a.effective_start_date <= :asof")
                .contains("a.effective_end_date >= :asof")
                .contains("org.public_id in (:organizationids)");
        assertThat(parameters.getValue().getValue("organizationIds"))
                .isEqualTo(Set.of(organizationId));
        assertThat(parameters.getValue().hasValue("asOf")).isTrue();
    }

    @Test
    void legacyWorkforceEntitiesAreBoundToAsOfAndAllowedOrganizations() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.WorkforceEntityRow>>any()))
                .thenReturn(List.of());
        PeopleDirectoryRepository repository = new PeopleDirectoryRepository(jdbc);
        LocalDate asOf = LocalDate.of(2026, 9, 2);
        UUID organizationId = UUID.randomUUID();

        repository.findWorkforceEntities(7L, 42L, asOf, false, Set.of(organizationId));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(
                sql.capture(), parameters.capture(),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.WorkforceEntityRow>>any());
        assertThat(normalized(sql.getValue()).toLowerCase())
                .contains("assignment.effective_start_date <= :asof")
                .contains("assignment.effective_end_date >= :asof")
                .contains("relationship.start_date <= :asof")
                .contains("relationship.end_date >= :asof")
                .contains("organization.public_id in (:organizationids)");
        assertThat(parameters.getValue().getValue("organizationIds"))
                .isEqualTo(Set.of(organizationId));
        assertThat(parameters.getValue().hasValue("asOf")).isTrue();
    }

    @Test
    void tenantWideLegacyWorkforceEntitiesDoNotApplyAnOrganizationPredicate() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.WorkforceEntityRow>>any()))
                .thenReturn(List.of());
        PeopleDirectoryRepository repository = new PeopleDirectoryRepository(jdbc);

        repository.findWorkforceEntities(
                7L, 42L, LocalDate.of(2026, 9, 2), true, Set.of());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(
                sql.capture(), parameters.capture(),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.WorkforceEntityRow>>any());
        assertThat(sql.getValue().toLowerCase())
                .doesNotContain("organization.public_id in (:organizationids)");
        assertThat(parameters.getValue().hasValue("organizationIds")).isFalse();
    }

    private NamedParameterJdbcTemplate directoryJdbc() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<PeopleDirectoryRepository.DirectoryRow>>any()))
                .thenReturn(List.of());
        return jdbc;
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
