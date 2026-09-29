package com.dwp.services.people.workforce;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class People360RepositoryTest {

    @Test
    void personLookupIsTenantBoundAndNeverFallsBackToGlobalPublicId() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<MapSqlParameterSource> parameters = new AtomicReference<>();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<People360Repository.PersonRow>>any()))
                .thenAnswer(invocation -> {
                    sql.set(invocation.getArgument(0));
                    parameters.set(invocation.getArgument(1));
                    return List.of();
                });
        People360Repository repository = new People360Repository(jdbc);
        UUID personId = UUID.randomUUID();

        assertThat(repository.findPerson(19L, personId)).isEmpty();

        assertThat(normalized(sql.get()))
                .contains("WHERE tenant_id = :tenantId")
                .contains("AND public_id = :personPublicId")
                .contains("AND lifecycle_state <> 'MERGED'");
        assertThat(parameters.get().getValue("tenantId")).isEqualTo(19L);
        assertThat(parameters.get().getValue("personPublicId")).isEqualTo(personId);
    }

    @Test
    void employmentReadBindsOneAsOfAcrossRelationshipAssignmentAndManagerSlices() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<MapSqlParameterSource> parameters = new AtomicReference<>();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<People360Repository.CurrentEmploymentRow>>any()))
                .thenAnswer(invocation -> {
                    sql.set(invocation.getArgument(0));
                    parameters.set(invocation.getArgument(1));
                    return List.of();
                });
        People360Repository repository = new People360Repository(jdbc);
        LocalDate asOf = LocalDate.of(2026, 9, 17);

        assertThat(repository.findCurrentEmployments(19L, 42L, asOf)).isEmpty();

        String normalized = normalized(sql.get());
        assertThat(normalized)
                .contains("relationship.start_date <= :asOf")
                .contains("relationship.end_date >= :asOf")
                .contains("assignment.primary_assignment = TRUE")
                .contains("assignment.effective_start_date <= :asOf")
                .contains("assignment.effective_end_date >= :asOf")
                .contains("manager_assignment.effective_start_date <= :asOf")
                .contains("manager_assignment.effective_end_date >= :asOf")
                .contains("WHERE worker.tenant_id = :tenantId")
                .contains("AND worker.person_id = :personId")
                .doesNotContain("CURRENT_DATE");
        assertThat(parameters.get().getValue("tenantId")).isEqualTo(19L);
        assertThat(parameters.get().getValue("personId")).isEqualTo(42L);
        assertThat(parameters.get().getValue("asOf")).isEqualTo(Date.valueOf(asOf));
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
