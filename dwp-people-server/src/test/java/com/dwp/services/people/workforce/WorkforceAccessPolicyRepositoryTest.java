package com.dwp.services.people.workforce;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkforceAccessPolicyRepositoryTest {

    @Test
    void resolveBindsPostgresTimestampInsteadOfUnsupportedInstant() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<WorkforceAccessPolicyRepository.PolicyRow>>any()))
                .thenAnswer(invocation -> {
                    MapSqlParameterSource parameters = invocation.getArgument(1);
                    assertThat(parameters.getValue("now")).isInstanceOf(Timestamp.class);
                    return List.of();
                });
        WorkforceAccessPolicyRepository repository = new WorkforceAccessPolicyRepository(jdbc);

        repository.resolve(1L, 37L, Set.of("HR_ADMIN"), Instant.parse("2026-08-14T04:00:00Z"));
    }
}
