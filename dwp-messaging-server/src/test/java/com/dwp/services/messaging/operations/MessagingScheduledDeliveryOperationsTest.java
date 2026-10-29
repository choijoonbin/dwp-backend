package com.dwp.services.messaging.operations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class MessagingScheduledDeliveryOperationsTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void tenantReadinessUsesTenantBoundQueueEvidence() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = evidenceRow(3, 1, 0);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(91L), eq(91L)))
                .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1)).mapRow(row, 0));
        var operations = operations(jdbc);

        MessagingScheduledDeliveryOperations.ScheduledDeliveryStatus status =
                operations.status(91);

        assertThat(status.pendingCount()).isEqualTo(3);
        assertThat(status.overdueCount()).isOne();
        assertThat(status.state()).isEqualTo("READY");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), any(RowMapper.class), eq(91L), eq(91L));
        assertThat(sql.getValue()).contains("tenant_id = ?");
    }

    @Test
    void healthFailsClosedBeforeTheWorkerHasCompletedACycle() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = evidenceRow(0, 0, 0);
        when(row.getObject("last_completed_at", OffsetDateTime.class)).thenReturn(null);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
                .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1)).mapRow(row, 0));
        var operations = operations(jdbc);

        var health = new MessagingScheduledDeliveryHealthIndicator(operations).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails())
                .containsEntry("reasonCode", "SCHEDULED_DELIVERY_NOT_YET_VERIFIED");
    }

    @Test
    void firstFailedCycleExposesItsFailureInsteadOfNotYetVerified() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = evidenceRow(1, 1, 0);
        when(row.getObject("last_completed_at", OffsetDateTime.class)).thenReturn(null);
        when(row.getObject("last_failure_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.now(CLOCK));
        when(row.getString("last_failure_code")).thenReturn("DATABASE_UNAVAILABLE");
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
                .thenAnswer(invocation -> ((RowMapper) invocation.getArgument(1)).mapRow(row, 0));

        var status = operations(jdbc).status();

        assertThat(status.state()).isEqualTo("BLOCKED");
        assertThat(status.reasonCode()).isEqualTo("DATABASE_UNAVAILABLE");
    }

    private MessagingScheduledDeliveryOperations operations(JdbcTemplate jdbc) {
        return new MessagingScheduledDeliveryOperations(
                jdbc, new SimpleMeterRegistry(), true,
                Duration.ofMinutes(2), Duration.ofMinutes(2), CLOCK);
    }

    private ResultSet evidenceRow(long pending, long overdue, long failed) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("pending_count")).thenReturn(pending);
        when(row.getLong("overdue_count")).thenReturn(overdue);
        when(row.getLong("recent_failed_count")).thenReturn(failed);
        when(row.getObject("oldest_overdue_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.now(CLOCK).minusSeconds(30));
        when(row.getObject("last_completed_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.now(CLOCK).minusSeconds(10));
        when(row.getObject("last_failure_at", OffsetDateTime.class)).thenReturn(null);
        when(row.getString("last_failure_code")).thenReturn(null);
        return row;
    }
}
