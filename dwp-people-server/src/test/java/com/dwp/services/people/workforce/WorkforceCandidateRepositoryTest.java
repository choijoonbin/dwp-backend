package com.dwp.services.people.workforce;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkforceCandidateRepositoryTest {

    @Test
    void queryIsTenantCurrentAndContainsNoIdentityAdministrationProjection() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                any(String.class), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<
                        WorkforceCandidateDtos.OrganizationCandidate>>any()))
                .thenReturn(List.of());
        WorkforceCandidateRepository repository = new WorkforceCandidateRepository(jdbc);

        repository.list(7L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<
                        WorkforceCandidateDtos.OrganizationCandidate>>any());
        String normalized = sql.getValue().replaceAll("\\s+", " ").trim().toLowerCase();
        assertThat(normalized)
                .contains("where person.tenant_id = :tenantid")
                .contains("person.lifecycle_state = 'active'")
                .contains("relationship.start_date <= current_date")
                .contains("candidate.effective_start_date <= current_date")
                .contains("organization.lifecycle_state = 'active'")
                .doesNotContain("ppl_contacts", "email", "credential", "role_assignment");
        assertThat(parameters.getValue().getValue("tenantId")).isEqualTo(7L);
    }
}
