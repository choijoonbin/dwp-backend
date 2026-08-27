package com.dwp.core.audit;

import com.dwp.audit.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditOutboxRepositoryTest {

    @Test
    void reclaimsExpiredSendingRowsWithAnOpaqueLeaseToken() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<AuditOutboxRepository.ClaimedEvent>>any()))
                .thenReturn(List.of());
        AuditOutboxRepository repository = new AuditOutboxRepository(jdbc, new ObjectMapper());

        repository.claim("worker-a", 25, 30);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(
                sql.capture(),
                parameters.capture(),
                org.mockito.ArgumentMatchers
                        .<RowMapper<AuditOutboxRepository.ClaimedEvent>>any());
        assertThat(sql.getValue())
                .contains("'PENDING', 'FAILED', 'SENDING'")
                .contains("locked_by = :leaseToken");
        assertThat(parameters.getValue().getValue("leaseToken").toString())
                .startsWith("worker-a:")
                .isNotEqualTo("worker-a");
    }

    @Test
    void fencesCompletionWithTheExactClaimToken() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.batchUpdate(anyString(), any(MapSqlParameterSource[].class)))
                .thenReturn(new int[]{1});
        AuditOutboxRepository repository = new AuditOutboxRepository(jdbc, new ObjectMapper());
        AuditOutboxRepository.ClaimedEvent claimed = new AuditOutboxRepository.ClaimedEvent(
                UUID.randomUUID(), event(), 1, "worker-a:lease-7");

        assertThat(repository.markPublished(List.of(claimed))).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource[]> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource[].class);
        verify(jdbc).batchUpdate(sql.capture(), parameters.capture());
        assertThat(sql.getValue())
                .contains("status = 'SENDING'")
                .contains("locked_by = :leaseToken");
        assertThat(parameters.getValue()[0].getValue("leaseToken"))
                .isEqualTo("worker-a:lease-7");
    }

    @Test
    void assumesOnlyAValidatedRelayDatabaseRole() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcOperations operations = mock(JdbcOperations.class);
        when(jdbc.getJdbcOperations()).thenReturn(operations);
        AuditOutboxRepository repository = new AuditOutboxRepository(
                jdbc, new ObjectMapper(), "dwp_notification_audit_relay");

        repository.deletePublishedBefore(Instant.parse("2026-08-01T00:00:00Z"));

        verify(operations).execute("SET LOCAL ROLE dwp_notification_audit_relay");
        assertThatThrownBy(() -> new AuditOutboxRepository(
                jdbc, new ObjectMapper(), "relay; RESET ROLE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

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

    private static AuditEvent event() {
        return AuditEvent.builder()
                .tenantId(1L)
                .category("ADMIN_CHANGE")
                .action("test")
                .sourceService("test-service")
                .targetType("TEST")
                .targetId("test")
                .build();
    }
}
