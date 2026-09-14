package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationModels.DeliveryLane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationAdminDeliveryRepositoryTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

    @Test
    void preservesFacadeQueueSnapshotAndTenantBoundQuery() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("retry_queue")).thenReturn(7L);
        when(row.getLong("dead_letter_queue")).thenReturn(11L);
        when(row.getLong("unknown_outcomes")).thenReturn(13L);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<NotificationAdminDeliverySnapshot>>any()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    MapSqlParameterSource parameters = invocation.getArgument(1);
                    assertThat(parameters.getValue("tenantId")).isEqualTo(42L);
                    assertThat(sql).contains("WHERE tenant_id = :tenantId",
                            "state = 'FAILED' AND attempt_count < 5",
                            "state = 'FAILED' AND attempt_count >= 5", "state = 'UNKNOWN'");
                    RowMapper<NotificationAdminDeliverySnapshot> mapper = invocation.getArgument(2);
                    return mapper.mapRow(row, 0);
                });

        var snapshot = new NotificationAdminRepository(jdbc).deliveryQueue(42L);

        assertThat(snapshot).isEqualTo(
                new NotificationAdminRepository.DeliveryQueueSnapshot(7L, 11L, 13L));
    }

    @Test
    void preservesNullableFacadeResult() {
        assertThat(new NotificationAdminRepository(jdbc).deliveryQueue(42L)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"899, 9.99, HEALTHY", "900, 0, DEGRADED", "0, 10, DEGRADED"})
    void preservesTenantBoundLaneHealthThresholds(long oldest, double failures, String state)
            throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("qos_lane")).thenReturn("INTERACTIVE");
        when(row.getLong("queued")).thenReturn(17L);
        when(row.getLong("oldest_age_seconds")).thenReturn(oldest);
        when(row.getDouble("throughput_per_minute")).thenReturn(19.5);
        when(row.getDouble("failure_rate")).thenReturn(failures);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<DeliveryLane>>any()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    MapSqlParameterSource parameters = invocation.getArgument(1);
                    assertThat(parameters.getValue("tenantId")).isEqualTo(42L);
                    assertThat(sql).contains("job.tenant_id = :tenantId",
                            "VALUES ('CRITICAL'), ('INTERACTIVE'), ('BULK')",
                            "job.state IN ('QUEUED', 'LEASED')", "job.state = 'SENT'",
                            "job.state = 'FAILED'");
                    RowMapper<DeliveryLane> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });

        assertThat(new NotificationAdminRepository(jdbc).deliveryLanes(42L))
                .containsExactly(new DeliveryLane(
                        "INTERACTIVE", 17L, oldest, 19.5, failures, state));
    }
}
