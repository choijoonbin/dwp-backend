package com.dwp.services.platform.auditcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditControlRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void relatedEventsBindsNullableLookupKeysWithExplicitSqlTypes() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AuditControlRepository repository = new AuditControlRepository(jdbc, new ObjectMapper());
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        AuditControlDtos.Event anchor = eventWithNullableLookupKeys();

        assertThat(repository.relatedEvents(7L, anchor, 25)).isEmpty();

        var parameters = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(anyString(), parameters.capture(), any(RowMapper.class));
        assertThat(parameters.getValue().getValue("correlationId")).isNull();
        assertThat(parameters.getValue().getSqlType("correlationId")).isEqualTo(Types.VARCHAR);
        assertThat(parameters.getValue().getValue("actorId")).isNull();
        assertThat(parameters.getValue().getSqlType("actorId")).isEqualTo(Types.VARCHAR);
    }

    private AuditControlDtos.Event eventWithNullableLookupKeys() {
        Instant occurredAt = Instant.parse("2026-08-11T00:00:00Z");
        return new AuditControlDtos.Event(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                occurredAt,
                occurredAt,
                7L,
                "AUTHORIZATION",
                "identity.role.updated",
                "SUCCESS",
                "MEDIUM",
                45,
                "SYSTEM",
                null,
                null,
                null,
                List.of(),
                "dwp-auth-server",
                "identity",
                null,
                "development",
                "ROLE",
                "WORKSPACE_MEMBER",
                "워크스페이스 구성원",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(),
                "STANDARD",
                "0".repeat(64));
    }
}
