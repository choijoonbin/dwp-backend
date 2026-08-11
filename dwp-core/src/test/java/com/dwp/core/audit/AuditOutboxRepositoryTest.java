package com.dwp.core.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditOutboxRepositoryTest {

    @Test
    void bindsPublishedRetentionCutoffAsJdbcTimestamp() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AuditOutboxRepository repository = new AuditOutboxRepository(jdbc, new ObjectMapper());
        Instant cutoff = Instant.parse("2026-08-01T00:00:00Z");

        repository.deletePublishedBefore(cutoff);

        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), parameters.capture());
        assertThat(parameters.getValue().getValue("cutoff"))
                .isEqualTo(Timestamp.from(cutoff));
    }
}
