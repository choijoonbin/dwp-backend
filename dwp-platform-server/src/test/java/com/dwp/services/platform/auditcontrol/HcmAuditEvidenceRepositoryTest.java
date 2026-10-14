package com.dwp.services.platform.auditcontrol;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HcmAuditEvidenceRepositoryTest {

    private static final Instant SNAPSHOT = Instant.parse("2026-10-14T06:00:00Z");

    private final NamedParameterJdbcTemplate jdbc =
            mock(NamedParameterJdbcTemplate.class);
    private final HcmAuditEvidenceRepository repository =
            new HcmAuditEvidenceRepository(jdbc);

    @Test
    @SuppressWarnings("unchecked")
    void listUsesTenantSnapshotCanonicalFingerprintAndStableKeysetWithoutCounts() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        HcmAuditEvidenceCriteria criteria = HcmAuditEvidenceCriteria.of(
                7L, AuditWindow.D7, "ADMIN_CHANGE", "HIGH", "SUCCESS",
                "dwp-people-server", "policy_update", 50, SNAPSHOT,
                Set.of("dwp-people-server"));
        HcmAuditEvidenceCursorCodec.CursorPosition cursor =
                new HcmAuditEvidenceCursorCodec.CursorPosition(
                        SNAPSHOT, SNAPSHOT.minusSeconds(30), UUID.randomUUID());

        repository.list(criteria, cursor, 51);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        String statement = sql.getValue();
        assertThat(statement)
                .contains("tenant_id = :tenantId")
                .contains("occurred_at <= :snapshotAt")
                .contains("ingested_at <= :snapshotAt")
                .contains("source_module ~ '^hris\\.[a-z0-9._-]+$'")
                .contains("outcome = 'DENIED' AND policy_decision = 'DENY'")
                .contains("outcome IN ('SUCCESS','FAILED') AND policy_decision = 'ALLOW'")
                .contains("before_state = '{}'::jsonb")
                .contains("jsonb_exists_all(metadata,")
                .contains("metadata -")
                .contains("event_id < :cursorEventId")
                .contains("ORDER BY occurred_at DESC, event_id DESC")
                .doesNotContainIgnoringCase("count(")
                .doesNotContain("actor_principal")
                .doesNotContain("actor_display_name")
                .doesNotContain("before_state,")
                .doesNotContain("after_state,");
        MapSqlParameterSource values = (MapSqlParameterSource) parameters.getValue();
        assertThat(values.getValue("tenantId")).isEqualTo(7L);
        assertThat(values.getValue("limit")).isEqualTo(51);
        assertThat(values.getValue("cursorEventId")).isEqualTo(cursor.eventId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailBindsTenantEventAndSameFingerprintInOneStatement() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        UUID eventId = UUID.randomUUID();

        repository.findById(7L, eventId, SNAPSHOT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("tenant_id = :tenantId")
                .contains("event_id = :eventId")
                .contains("ingested_at <= :snapshotAt")
                .contains("jsonb_exists_all(metadata,")
                .contains("LIMIT 1");
        MapSqlParameterSource values = (MapSqlParameterSource) parameters.getValue();
        assertThat(values.getValue("tenantId")).isEqualTo(7L);
        assertThat(values.getValue("eventId")).isEqualTo(eventId);
    }
}
