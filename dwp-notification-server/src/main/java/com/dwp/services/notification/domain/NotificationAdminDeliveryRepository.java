package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationModels.DeliveryLane;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

final class NotificationAdminDeliveryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    NotificationAdminDeliveryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<DeliveryLane> deliveryLanes(long tenantId) {
        return jdbc.query("""
                SELECT lane.qos_lane,
                       COUNT(job.job_id) FILTER (
                           WHERE job.state IN ('QUEUED', 'LEASED')
                       ) AS queued,
                       COALESCE(MAX(EXTRACT(EPOCH FROM (
                           CURRENT_TIMESTAMP - job.scheduled_at
                       ))) FILTER (
                           WHERE job.state IN ('QUEUED', 'LEASED')
                       ), 0) AS oldest_age_seconds,
                       COUNT(job.job_id) FILTER (
                           WHERE job.state = 'SENT'
                             AND job.updated_at >= CURRENT_TIMESTAMP - INTERVAL '1 minute'
                       ) AS throughput_per_minute,
                       CASE WHEN COUNT(job.job_id) = 0 THEN 0
                            ELSE 100.0 * COUNT(job.job_id) FILTER (
                                WHERE job.state = 'FAILED'
                            ) / COUNT(job.job_id)
                       END AS failure_rate
                  FROM (
                      VALUES ('CRITICAL'), ('INTERACTIVE'), ('BULK')
                  ) lane(qos_lane)
                  LEFT JOIN ntf_delivery_jobs job
                    ON job.tenant_id = :tenantId
                   AND job.qos_lane = lane.qos_lane
                 GROUP BY lane.qos_lane
                 ORDER BY CASE lane.qos_lane
                     WHEN 'CRITICAL' THEN 1
                     WHEN 'INTERACTIVE' THEN 2
                     ELSE 3
                 END
                """, new MapSqlParameterSource("tenantId", tenantId),
                (resultSet, rowNumber) -> {
            long oldest = resultSet.getLong("oldest_age_seconds");
            double failures = resultSet.getDouble("failure_rate");
            String state = failures >= 10 || oldest >= 900
                    ? "DEGRADED" : "HEALTHY";
            return new DeliveryLane(
                    resultSet.getString("qos_lane"),
                    resultSet.getLong("queued"),
                    oldest,
                    resultSet.getDouble("throughput_per_minute"),
                    failures,
                    state);
        });
    }

    NotificationAdminDeliverySnapshot deliveryQueue(long tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (
                           WHERE state = 'FAILED' AND attempt_count < 5
                       ) AS retry_queue,
                       COUNT(*) FILTER (
                           WHERE state = 'FAILED' AND attempt_count >= 5
                       ) AS dead_letter_queue,
                       COUNT(*) FILTER (WHERE state = 'UNKNOWN') AS unknown_outcomes
                  FROM ntf_delivery_jobs
                 WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId),
                (resultSet, rowNumber) -> new NotificationAdminDeliverySnapshot(
                        resultSet.getLong("retry_queue"),
                        resultSet.getLong("dead_letter_queue"),
                        resultSet.getLong("unknown_outcomes")));
    }
}
