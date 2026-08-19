package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.domain.NotificationModels.AdminTrendPoint;
import com.dwp.services.notification.domain.NotificationModels.DeliveryLane;
import com.dwp.services.notification.domain.NotificationModels.TypeContract;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationAdminRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationAdminRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AdminSnapshot snapshot(long tenantId) {
        return jdbc.queryForObject("""
                SELECT (
                           SELECT COUNT(*)
                             FROM ntf_notification_types
                            WHERE lifecycle_state = 'ACTIVE'
                              AND (tenant_id IS NULL OR tenant_id = :tenantId)
                       ) AS active_contracts,
                       (
                           SELECT COUNT(*)
                             FROM ntf_notifications
                            WHERE tenant_id = :tenantId
                              AND created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                       ) AS notifications_24h,
                       (
                           SELECT COUNT(*)
                             FROM ntf_delivery_jobs
                            WHERE tenant_id = :tenantId
                              AND state IN ('QUEUED', 'LEASED')
                       ) AS queued_jobs,
                       (
                           SELECT COUNT(*)
                             FROM ntf_delivery_jobs
                            WHERE tenant_id = :tenantId AND state = 'FAILED'
                       ) AS failed_jobs,
                       (
                           SELECT COUNT(*)
                             FROM ntf_notification_types type
                            WHERE (type.tenant_id IS NULL OR type.tenant_id = :tenantId)
                              AND NOT EXISTS (
                                  SELECT 1
                                    FROM ntf_notification_type_versions type_version
                                    JOIN ntf_template_versions template
                                      ON template.type_version_id =
                                         type_version.type_version_id
                                   WHERE type_version.type_id = type.type_id
                                     AND type_version.lifecycle_state = 'ACTIVE'
                                     AND template.channel = 'IN_APP'
                                     AND template.state = 'PUBLISHED'
                              )
                       ) AS broken_contracts
                """, new MapSqlParameterSource("tenantId", tenantId),
                (resultSet, rowNumber) -> new AdminSnapshot(
                        resultSet.getLong("active_contracts"),
                        resultSet.getLong("notifications_24h"),
                        resultSet.getLong("queued_jobs"),
                        resultSet.getLong("failed_jobs"),
                        resultSet.getLong("broken_contracts")));
    }

    public List<AdminTrendPoint> trend(long tenantId) {
        return jdbc.query("""
                SELECT day.bucket,
                       (
                           SELECT COUNT(*)
                             FROM ntf_notifications notification
                            WHERE notification.tenant_id = :tenantId
                              AND notification.created_at >= day.bucket
                              AND notification.created_at < day.bucket + INTERVAL '1 day'
                       ) AS created,
                       (
                           SELECT COUNT(*)
                             FROM ntf_user_notifications user_notification
                            WHERE user_notification.tenant_id = :tenantId
                              AND user_notification.action_required
                              AND user_notification.created_at >= day.bucket
                              AND user_notification.created_at < day.bucket + INTERVAL '1 day'
                       ) AS actionable,
                       (
                           SELECT COUNT(*)
                             FROM ntf_delivery_jobs job
                            WHERE job.tenant_id = :tenantId
                              AND job.state = 'FAILED'
                              AND job.updated_at >= day.bucket
                              AND job.updated_at < day.bucket + INTERVAL '1 day'
                       ) AS failed,
                       (
                           SELECT COUNT(*)
                             FROM ntf_user_subscription_rules rule
                            WHERE rule.tenant_id = :tenantId
                              AND rule.delivery_mode = 'MUTED'
                              AND rule.updated_at >= day.bucket
                              AND rule.updated_at < day.bucket + INTERVAL '1 day'
                       ) AS muted
                  FROM generate_series(
                      date_trunc('day', CURRENT_TIMESTAMP) - INTERVAL '6 days',
                      date_trunc('day', CURRENT_TIMESTAMP),
                      INTERVAL '1 day'
                  ) AS day(bucket)
                 ORDER BY day.bucket
                """, new MapSqlParameterSource("tenantId", tenantId),
                (resultSet, rowNumber) -> new AdminTrendPoint(
                        resultSet.getTimestamp("bucket").toInstant().toString(),
                        resultSet.getLong("created"),
                        resultSet.getLong("actionable"),
                        resultSet.getLong("failed"),
                        resultSet.getLong("muted")));
    }

    public List<TypeContract> typeContracts(
            long tenantId,
            String query,
            String state,
            String appKey,
            int offset,
            int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue(
                        "query",
                        query == null ? null : "%" + escapeLike(query) + "%",
                        Types.VARCHAR)
                .addValue("state", databaseState(state), Types.VARCHAR)
                .addValue("appKey", appKey, Types.VARCHAR)
                .addValue("offset", offset)
                .addValue("limit", limit);
        return jdbc.query("""
                SELECT type.type_id,
                       type.type_key,
                       COALESCE(
                           NULLIF(type_version.contract_payload ->> 'displayName', ''),
                           type.type_key
                       ) AS display_name,
                       NULLIF(type_version.contract_payload ->> 'description', '') AS description,
                       type.owner_app_key,
                       type.owner_team,
                       type.lifecycle_state,
                       type.updated_at,
                       type_version.source_event_type,
                       type_version.priority,
                       type_version.max_schema_version,
                       type_version.version,
                       ARRAY_REMOVE(ARRAY_AGG(DISTINCT template.channel), NULL) AS channels,
                       EXISTS (
                           SELECT 1
                             FROM ntf_routing_policies policy
                            WHERE (policy.tenant_id IS NULL OR policy.tenant_id = :tenantId)
                              AND policy.scope_type = 'TYPE'
                              AND policy.scope_key = type.type_key
                              AND policy.state = 'PUBLISHED'
                              AND policy.mandatory
                       ) AS mandatory,
                       (
                           SELECT COUNT(*)
                             FROM ntf_notifications notification
                            WHERE notification.tenant_id = :tenantId
                              AND notification.type_version_id =
                                  type_version.type_version_id
                              AND notification.created_at >=
                                  CURRENT_TIMESTAMP - INTERVAL '24 hours'
                       ) AS volume_24h,
                       COUNT(template.template_version_id) FILTER (
                           WHERE template.channel = 'IN_APP'
                             AND template.state = 'PUBLISHED'
                       ) AS active_in_app_templates
                  FROM ntf_notification_types type
                  JOIN LATERAL (
                      SELECT candidate.*
                        FROM ntf_notification_type_versions candidate
                       WHERE candidate.type_id = type.type_id
                       ORDER BY candidate.version DESC
                       LIMIT 1
                  ) type_version ON TRUE
                  LEFT JOIN ntf_template_versions template
                    ON template.type_version_id = type_version.type_version_id
                 WHERE (type.tenant_id IS NULL OR type.tenant_id = :tenantId)
                   AND (CAST(:query AS text) IS NULL
                        OR type.type_key ILIKE :query
                        OR type.owner_team ILIKE :query)
                   AND (CAST(:state AS text) IS NULL OR type.lifecycle_state = :state)
                   AND (CAST(:appKey AS text) IS NULL OR type.owner_app_key = :appKey)
                 GROUP BY type.type_id, type.type_key, type.owner_app_key,
                          type.owner_team, type.lifecycle_state, type.updated_at,
                          type_version.type_version_id, type_version.contract_payload,
                          type_version.source_event_type, type_version.priority,
                          type_version.max_schema_version, type_version.version
                 ORDER BY type.updated_at DESC, type.type_id
                 OFFSET :offset
                 LIMIT :limit
                """, params, (resultSet, rowNumber) -> {
            List<String> channels = stringArray(resultSet.getArray("channels"));
            String lifecycle = resultSet.getString("lifecycle_state");
            long templates = resultSet.getLong("active_in_app_templates");
            return new TypeContract(
                    resultSet.getObject("type_id", UUID.class),
                    resultSet.getString("type_key"),
                    resultSet.getString("display_name"),
                    resultSet.getString("description"),
                    resultSet.getString("owner_app_key"),
                    NotificationQueryRepository.appName(resultSet.getString("owner_app_key")),
                    resultSet.getString("owner_team"),
                    resultSet.getString("source_event_type"),
                    resultSet.getString("priority"),
                    channels,
                    resultSet.getBoolean("mandatory"),
                    apiState(lifecycle),
                    contractHealth(lifecycle, templates),
                    resultSet.getLong("volume_24h"),
                    resultSet.getInt("max_schema_version"),
                    NotificationVersionCodec.external(resultSet.getLong("version")),
                    instant(resultSet.getTimestamp("updated_at")));
        });
    }

    public List<DeliveryLane> deliveryLanes(long tenantId) {
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

    public DeliveryQueueSnapshot deliveryQueue(long tenantId) {
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
                (resultSet, rowNumber) -> new DeliveryQueueSnapshot(
                        resultSet.getLong("retry_queue"),
                        resultSet.getLong("dead_letter_queue"),
                        resultSet.getLong("unknown_outcomes")));
    }

    private String databaseState(String state) {
        if (state == null || state.isBlank()) return null;
        return switch (state.trim().toUpperCase()) {
            case "RETIRED", "QUARANTINED" -> "DISABLED";
            case "IN_REVIEW" -> "DRAFT";
            default -> state.trim().toUpperCase();
        };
    }

    private String apiState(String state) {
        return "DISABLED".equals(state) ? "RETIRED" : state;
    }

    private String contractHealth(String state, long publishedInAppTemplates) {
        if ("ACTIVE".equals(state) && publishedInAppTemplates > 0) return "HEALTHY";
        if ("DISABLED".equals(state)) return "BROKEN";
        return "ATTENTION";
    }

    private List<String> stringArray(Array value) throws SQLException {
        if (value == null) return List.of();
        return Arrays.asList((String[]) value.getArray());
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Instant instant(Timestamp value) {
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    public record AdminSnapshot(
            long activeContracts,
            long notifications24Hours,
            long queuedJobs,
            long failedJobs,
            long brokenContracts) {
    }

    public record DeliveryQueueSnapshot(
            long retryQueue,
            long deadLetterQueue,
            long unknownOutcomes) {
    }
}
