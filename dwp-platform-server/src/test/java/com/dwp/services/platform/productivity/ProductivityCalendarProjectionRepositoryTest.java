package com.dwp.services.platform.productivity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductivityCalendarProjectionRepositoryTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void queryIsStrictlyScopedAndFailClosedAgainstRevokedConnectorState() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        ProductivityCalendarProjectionRepository repository =
                new ProductivityCalendarProjectionRepository(jdbc, new ObjectMapper());
        UUID connectorId = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-28T00:00:00Z");
        Instant to = Instant.parse("2026-11-04T00:00:00Z");

        repository.calendarItems(7L, 42L, connectorId, from, to, 500);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        String normalized = sql.getValue().replaceAll("\\s+", " ").toLowerCase();
        assertThat(normalized)
                .contains("item.tenant_id = :tenantid")
                .contains("item.user_id = :userid")
                .contains("item.productivity_connector_id = :connectorid")
                .contains("item.resource_kind = 'calendar'")
                .contains("item.tombstoned_at is null")
                .contains("item.cancelled = false")
                .contains("item.occurred_at < :rangeend")
                .contains("item.ends_at > :rangestart")
                .contains("connector.lifecycle_state = 'active'")
                .contains("connector.policy_state = 'approved'")
                .contains("connector.health_state in ('healthy', 'degraded')")
                .contains("subject.consent_state = 'connected'")
                .contains("stream.stream_state in ('ready', 'syncing', 'stale', 'reset_required')")
                .doesNotContain("encrypted_cursor")
                .doesNotContain("encrypted_refresh_token");
        MapSqlParameterSource values = (MapSqlParameterSource) parameters.getValue();
        assertThat(values.getValue("tenantId")).isEqualTo(7L);
        assertThat(values.getValue("userId")).isEqualTo(42L);
        assertThat(values.getValue("connectorId")).isEqualTo(connectorId);
        assertThat(values.getValue("rangeStart")).isEqualTo(Timestamp.from(from));
        assertThat(values.getValue("rangeEnd")).isEqualTo(Timestamp.from(to));
        assertThat(values.getValue("calendarScope")).isEqualTo("[\"Calendars.Read\"]");
        assertThat(values.getValue("limit"))
                .isEqualTo(ProductivityCalendarProjectionRepository.MAX_QUERY_ROWS);
    }
}
