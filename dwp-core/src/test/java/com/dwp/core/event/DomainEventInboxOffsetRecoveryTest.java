package com.dwp.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainEventInboxOffsetRecoveryTest {

    private static final String RELATIONSHIP_ID = "345c0d85-83ea-4a80-8584-9cc4273ec5f3";
    private static final String CONTENT_DIGEST = "a".repeat(64);
    private static final String SCOPE_DIGEST = "b".repeat(64);
    private static final OffsetDateTime DATABASE_NOW =
            OffsetDateTime.parse("2026-10-21T02:03:04Z");

    @Test
    void gapRecoveryUsesExactOffsetCasSettlesDeferredOnlyAndWritesPairedAudit() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventInboxRepository repository = repository(jdbc);
        stubOffset(jdbc, 4L);
        stubQueryForObject(jdbc, 0L);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("INSERT INTO sys_domain_event_offsets")) return 0;
                    if (sql.contains("UPDATE sys_domain_event_inbox")) return 2;
                    return 1;
                });

        DomainEventInboxOffsetRecoverySupport.OffsetRecoveryResult result =
                repository.recoverOffsetFromOwnerSnapshot(request(
                        DomainEventInboxOffsetRecoverySupport.RecoveryMode.GAP, 4, 8));

        assertThat(result.priorOffset()).isEqualTo(4);
        assertThat(result.recoveredOffset()).isEqualTo(8);
        assertThat(result.settledInboxRows()).isEqualTo(2);
        assertThat(result.reasonCode()).isEqualTo(
                DomainEventInboxOffsetRecoverySupport.GAP_REASON_CODE);

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, org.mockito.Mockito.times(5)).update(
                statements.capture(), parameters.capture());
        assertThat(statements.getAllValues()).anySatisfy(sql -> assertThat(sql)
                .contains("last_sequence = :recoveredOffset")
                .contains("last_event_id = NULL")
                .contains("last_sequence = :expectedPriorOffset"));
        assertThat(statements.getAllValues()).anySatisfy(sql -> assertThat(sql)
                .contains("status = 'DUPLICATE'")
                .contains("status = 'DEFERRED'")
                .contains("event_type = :eventType")
                .doesNotContain("REPLAY_PENDING"));
        MapSqlParameterSource replay = parameters.getAllValues().stream()
                .filter(value -> value.hasValue("requestedBy"))
                .findFirst().orElseThrow();
        assertThat(replay.getValue("targetId"))
                .isEqualTo("TIM003|41|WORK_RELATIONSHIP|" + RELATIONSHIP_ID);
        assertThat(replay.getValue("reason")).isEqualTo(
                "{\"actor\":\"service:dwp-time-server:tim003-owner-recovery\","
                        + "\"expectedPriorOffset\":4,"
                        + "\"ownerContentSha256\":\"" + CONTENT_DIGEST + "\","
                        + "\"ownerScopeDigest\":\"" + SCOPE_DIGEST + "\","
                        + "\"reasonCode\":\"TIM003_OWNER_SNAPSHOT_RECOVERY\","
                        + "\"recoveredOffset\":8,"
                        + "\"source\":\"urn:dwp:people:hris:employment\"}");
        assertThat(replay.getValue("requestedAt")).isEqualTo(DATABASE_NOW.toInstant());
    }

    @Test
    void ownerBaselineRequiresZeroInboxAndSupportsZeroToZeroCheckpoint() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventInboxRepository repository = repository(jdbc);
        stubOffset(jdbc, 0L);
        stubQueryForObject(jdbc, 0L);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        DomainEventInboxOffsetRecoverySupport.OffsetRecoveryResult result =
                repository.recoverOffsetFromOwnerSnapshot(request(
                        DomainEventInboxOffsetRecoverySupport.RecoveryMode.OWNER_BASELINE,
                        0,
                        0));

        assertThat(result.recoveredOffset()).isZero();
        assertThat(result.settledInboxRows()).isZero();
        assertThat(result.reasonCode()).isEqualTo(
                DomainEventInboxOffsetRecoverySupport.BASELINE_REASON_CODE);
        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(4)).update(
                statements.capture(), any(MapSqlParameterSource.class));
        assertThat(statements.getAllValues())
                .noneMatch(sql -> sql.contains("UPDATE sys_domain_event_inbox"));
    }

    @Test
    void ownerBaselineRejectsAnyInboxRowBeforeOffsetMutation() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventInboxRepository repository = repository(jdbc);
        stubOffset(jdbc, 0L);
        stubQueryForObject(jdbc, 1L);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> invocation.<String>getArgument(0)
                        .contains("INSERT INTO sys_domain_event_offsets") ? 1 : 0);

        assertThatThrownBy(() -> repository.recoverOffsetFromOwnerSnapshot(request(
                DomainEventInboxOffsetRecoverySupport.RecoveryMode.OWNER_BASELINE,
                0,
                7)))
                .isInstanceOf(
                        DomainEventInboxOffsetRecoverySupport.OffsetRecoveryConflictException.class)
                .hasMessageContaining("zero matching inbox rows");

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(statements.capture(), any(MapSqlParameterSource.class));
        assertThat(statements.getValue()).contains("INSERT INTO sys_domain_event_offsets");
    }

    @Test
    void gapRecoveryRefusesDeadReplayOrPayloadConflictEvidence() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventInboxRepository repository = repository(jdbc);
        stubOffset(jdbc, 3L);
        stubQueryForObject(jdbc, 1L);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> invocation.<String>getArgument(0)
                        .contains("INSERT INTO sys_domain_event_offsets") ? 0 : 1);

        assertThatThrownBy(() -> repository.recoverOffsetFromOwnerSnapshot(request(
                DomainEventInboxOffsetRecoverySupport.RecoveryMode.GAP,
                3,
                9)))
                .isInstanceOf(
                        DomainEventInboxOffsetRecoverySupport.OffsetRecoveryConflictException.class)
                .hasMessageContaining("cannot settle replay, dead, or payload-conflict");
    }

    @Test
    void staleExpectedOffsetFailsBeforeAnyCheckpointOrAuditWrite() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventInboxRepository repository = repository(jdbc);
        stubOffset(jdbc, 6L);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);

        assertThatThrownBy(() -> repository.recoverOffsetFromOwnerSnapshot(request(
                DomainEventInboxOffsetRecoverySupport.RecoveryMode.GAP,
                5,
                9)))
                .isInstanceOf(
                        DomainEventInboxOffsetRecoverySupport.OffsetRecoveryConflictException.class)
                .hasMessageContaining("expected prior offset");
    }

    private static DomainEventInboxRepository repository(NamedParameterJdbcTemplate jdbc) {
        return new DomainEventInboxRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
    }

    private static DomainEventInboxOffsetRecoverySupport.OffsetRecoveryRequest request(
            DomainEventInboxOffsetRecoverySupport.RecoveryMode mode,
            long expected,
            long recovered) {
        return new DomainEventInboxOffsetRecoverySupport.OffsetRecoveryRequest(
                DomainEventInboxStrictValidationSupport.TIM003_CONSUMER_NAME,
                41L,
                DomainEventInboxStrictValidationSupport.TIM003_EVENT_SOURCE,
                DomainEventInboxStrictValidationSupport.TIM003_EVENT_TYPE,
                DomainEventInboxStrictValidationSupport.TIM003_AGGREGATE_TYPE,
                RELATIONSHIP_ID,
                expected,
                recovered,
                mode,
                DomainEventInboxOffsetRecoverySupport.RECOVERY_ACTOR,
                CONTENT_DIGEST,
                SCOPE_DIGEST,
                "9d87aa4f-81ad-40c0-82c9-fdd0cbb39d58",
                Map.of(
                        "projectionVersion", 11,
                        "authorizationRevision", 5));
    }

    @SuppressWarnings("unchecked")
    private static void stubOffset(NamedParameterJdbcTemplate jdbc, long offset) {
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)))
                .thenReturn(List.of(offset));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubQueryForObject(NamedParameterJdbcTemplate jdbc, long inboxCount) {
        when(jdbc.queryForObject(
                anyString(),
                any(MapSqlParameterSource.class),
                any(Class.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    return sql.contains("COUNT(*)") ? inboxCount : DATABASE_NOW;
                });
    }
}
