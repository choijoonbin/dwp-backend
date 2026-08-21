package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.domain.NotificationModels.AdminTrendPoint;
import com.dwp.services.notification.domain.NotificationModels.DeliveryLane;
import com.dwp.services.notification.domain.NotificationModels.PolicyChannelRule;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicy;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicyChangeRequest;
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
import java.util.Optional;
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

    public List<TenantPolicy> effectivePolicies(long tenantId) {
        return policies("""
                SELECT DISTINCT ON (policy.scope_type, policy.scope_key)
                       policy.policy_id, policy.tenant_id, policy.scope_type, policy.scope_key,
                       policy.state, policy.mandatory, policy.quiet_hours_bypass,
                       policy.digest_mode, policy.change_reason, policy.created_by,
                       policy.approved_by, policy.approved_at, policy.version, policy.created_at
                  FROM ntf_routing_policies policy
                 WHERE policy.state = 'PUBLISHED'
                   AND (policy.tenant_id IS NULL OR policy.tenant_id = :tenantId)
                   AND (policy.effective_from IS NULL
                        OR policy.effective_from <= CURRENT_TIMESTAMP)
                   AND (policy.effective_to IS NULL
                        OR policy.effective_to > CURRENT_TIMESTAMP)
                 ORDER BY policy.scope_type, policy.scope_key,
                          (policy.tenant_id IS NOT NULL) DESC, policy.version DESC
                """, tenantId, null);
    }

    public List<TenantPolicy> policyDrafts(long tenantId) {
        return policies("""
                SELECT policy.policy_id, policy.tenant_id, policy.scope_type, policy.scope_key,
                       policy.state, policy.mandatory, policy.quiet_hours_bypass,
                       policy.digest_mode, policy.change_reason, policy.created_by,
                       policy.approved_by, policy.approved_at, policy.version, policy.created_at
                  FROM ntf_routing_policies policy
                 WHERE policy.tenant_id = :tenantId AND policy.state = 'DRAFT'
                 ORDER BY policy.created_at DESC, policy.policy_id
                """, tenantId, null);
    }

    public Optional<TenantPolicy> policy(long tenantId, UUID policyId) {
        return policies("""
                SELECT policy.policy_id, policy.tenant_id, policy.scope_type, policy.scope_key,
                       policy.state, policy.mandatory, policy.quiet_hours_bypass,
                       policy.digest_mode, policy.change_reason, policy.created_by,
                       policy.approved_by, policy.approved_at, policy.version, policy.created_at
                  FROM ntf_routing_policies policy
                 WHERE policy.tenant_id = :tenantId AND policy.policy_id = :policyId
                """, tenantId, policyId).stream().findFirst();
    }

    public Optional<TenantPolicy> effectivePolicy(
            long tenantId, String scopeType, String scopeKey) {
        return policies("""
                SELECT policy.policy_id, policy.tenant_id, policy.scope_type, policy.scope_key,
                       policy.state, policy.mandatory, policy.quiet_hours_bypass,
                       policy.digest_mode, policy.change_reason, policy.created_by,
                       policy.approved_by, policy.approved_at, policy.version, policy.created_at
                  FROM ntf_routing_policies policy
                 WHERE policy.state = 'PUBLISHED'
                   AND (policy.tenant_id IS NULL OR policy.tenant_id = :tenantId)
                   AND policy.scope_type = :scopeType AND policy.scope_key = :scopeKey
                 ORDER BY (policy.tenant_id IS NOT NULL) DESC, policy.version DESC
                 LIMIT 1
                """, tenantId, null, scopeType, scopeKey).stream().findFirst();
    }

    public boolean policyScopeExists(long tenantId, String scopeType, String scopeKey) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ntf_notification_types type
                 WHERE (type.tenant_id IS NULL OR type.tenant_id = :tenantId)
                   AND type.lifecycle_state = 'ACTIVE'
                   AND ((:scopeType = 'APP' AND type.owner_app_key = :scopeKey)
                     OR (:scopeType = 'TYPE' AND type.type_key = :scopeKey))
                """, policyParams(tenantId, scopeType, scopeKey), Long.class);
        return count != null && count > 0;
    }

    public long affectedTypeCount(long tenantId, String scopeType, String scopeKey) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ntf_notification_types type
                 WHERE (type.tenant_id IS NULL OR type.tenant_id = :tenantId)
                   AND type.lifecycle_state = 'ACTIVE'
                   AND ((:scopeType = 'APP' AND type.owner_app_key = :scopeKey)
                     OR (:scopeType = 'TYPE' AND type.type_key = :scopeKey))
                """, policyParams(tenantId, scopeType, scopeKey), Long.class);
        return count == null ? 0 : count;
    }

    public long observedRecipients30Days(long tenantId, String scopeType, String scopeKey) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT user_notification.user_id)
                  FROM ntf_user_notifications user_notification
                  JOIN ntf_notifications notification
                    ON notification.tenant_id = user_notification.tenant_id
                   AND notification.notification_id = user_notification.notification_id
                  JOIN ntf_notification_type_versions type_version
                    ON type_version.type_version_id = notification.type_version_id
                  JOIN ntf_notification_types type ON type.type_id = type_version.type_id
                 WHERE user_notification.tenant_id = :tenantId
                   AND user_notification.created_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
                   AND ((:scopeType = 'APP' AND type.owner_app_key = :scopeKey)
                     OR (:scopeType = 'TYPE' AND type.type_key = :scopeKey))
                """, policyParams(tenantId, scopeType, scopeKey), Long.class);
        return count == null ? 0 : count;
    }

    public long latestTenantPolicyVersion(long tenantId, String scopeType, String scopeKey) {
        Long version = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0)
                  FROM ntf_routing_policies
                 WHERE tenant_id = :tenantId
                   AND scope_type = :scopeType AND scope_key = :scopeKey
                """, policyParams(tenantId, scopeType, scopeKey), Long.class);
        return version == null ? 0 : version;
    }

    public TenantPolicy createPolicyDraft(
            long tenantId,
            long actorUserId,
            TenantPolicyChangeRequest request,
            long version,
            UUID supersedesPolicyId) {
        UUID policyId = UUID.randomUUID();
        MapSqlParameterSource params = policyParams(
                tenantId, request.scopeType(), request.scopeKey())
                .addValue("policyId", policyId)
                .addValue("version", version)
                .addValue("mandatory", request.mandatory())
                .addValue("quietHoursBypass", request.quietHoursBypass())
                .addValue("digestMode", request.digestMode())
                .addValue("createdBy", actorUserId)
                .addValue("changeReason", request.changeReason().trim())
                .addValue("supersedesPolicyId", supersedesPolicyId);
        jdbc.update("""
                    INSERT INTO ntf_routing_policies (
                        policy_id, tenant_id, scope_type, scope_key, version, state,
                        mandatory, quiet_hours_bypass, digest_mode, created_by,
                        change_reason, supersedes_policy_id)
                    VALUES (
                        :policyId, :tenantId, :scopeType, :scopeKey, :version, 'DRAFT',
                        :mandatory, :quietHoursBypass, :digestMode, :createdBy,
                        :changeReason, :supersedesPolicyId)
                    """, params);
        for (PolicyChannelRule channel : request.channels()) {
            jdbc.update("""
                        INSERT INTO ntf_policy_channel_rules (
                            policy_channel_rule_id, tenant_id, policy_id, channel,
                            enabled, default_mode, user_overridable, max_per_window)
                        VALUES (
                            :ruleId, :tenantId, :policyId, :channel,
                            :enabled, :defaultMode, :userOverridable, :maxPerWindow)
                        """, new MapSqlParameterSource()
                        .addValue("ruleId", UUID.randomUUID())
                        .addValue("tenantId", tenantId)
                        .addValue("policyId", policyId)
                        .addValue("channel", channel.channel())
                        .addValue("enabled", channel.enabled())
                        .addValue("defaultMode", channel.defaultMode())
                    .addValue("userOverridable", channel.userOverridable())
                    .addValue("maxPerWindow", channel.maxPerWindow()));
        }
        appendPolicyOutbox(
                tenantId, policyId, "notification.policy.draft-created",
                "notification-policy-draft:" + policyId);
        return policy(tenantId, policyId).orElseThrow();
    }

    public boolean publishPolicy(
            long tenantId,
            long approverUserId,
            UUID policyId,
            long expectedVersion,
            String approvalReason) {
        int updated = jdbc.update("""
                UPDATE ntf_routing_policies policy
                   SET state = 'PUBLISHED', approved_by = :approvedBy,
                       approved_at = CURRENT_TIMESTAMP,
                       change_reason = policy.change_reason || E'\nApproval: ' || :approvalReason
                 WHERE policy.tenant_id = :tenantId
                   AND policy.policy_id = :policyId
                   AND policy.state = 'DRAFT'
                   AND policy.version = :expectedVersion
                   AND policy.created_by <> :approvedBy
                   AND NOT EXISTS (
                       SELECT 1 FROM ntf_routing_policies newer
                        WHERE newer.tenant_id = policy.tenant_id
                          AND newer.scope_type = policy.scope_type
                          AND newer.scope_key = policy.scope_key
                          AND newer.state = 'PUBLISHED'
                          AND newer.version >= policy.version)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("policyId", policyId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("approvedBy", approverUserId)
                .addValue("approvalReason", approvalReason.trim()));
        if (updated == 1) {
            appendPolicyOutbox(
                    tenantId, policyId, "notification.policy.published",
                    "notification-policy-published:" + policyId);
        }
        return updated == 1;
    }

    private List<TenantPolicy> policies(String sql, long tenantId, UUID policyId) {
        return policies(sql, tenantId, policyId, null, null);
    }

    private List<TenantPolicy> policies(
            String sql,
            long tenantId,
            UUID policyId,
            String scopeType,
            String scopeKey) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId)
                .addValue("policyId", policyId)
                .addValue("scopeType", scopeType)
                .addValue("scopeKey", scopeKey);
        List<PolicyRow> rows = jdbc.query(sql, params, (resultSet, rowNumber) -> new PolicyRow(
                resultSet.getObject("policy_id", UUID.class),
                (Long) resultSet.getObject("tenant_id"),
                resultSet.getString("scope_type"),
                resultSet.getString("scope_key"),
                resultSet.getString("state"),
                resultSet.getBoolean("mandatory"),
                resultSet.getBoolean("quiet_hours_bypass"),
                resultSet.getString("digest_mode"),
                resultSet.getString("change_reason"),
                (Long) resultSet.getObject("created_by"),
                (Long) resultSet.getObject("approved_by"),
                nullableInstant(resultSet.getTimestamp("approved_at")),
                resultSet.getLong("version"),
                instant(resultSet.getTimestamp("created_at"))));
        return rows.stream().map(row -> new TenantPolicy(
                row.policyId(),
                row.scopeType(),
                row.scopeKey(),
                "APP".equals(row.scopeType())
                        ? NotificationQueryRepository.appName(row.scopeKey())
                        : row.scopeKey(),
                row.tenantId() == null ? "PROVIDER_POLICY" : "TENANT_POLICY",
                row.state(),
                row.mandatory(),
                row.quietHoursBypass(),
                row.digestMode(),
                policyChannels(row.policyId()),
                row.changeReason(),
                row.createdBy(),
                row.approvedBy(),
                row.approvedAt(),
                NotificationVersionCodec.external(row.version()),
                row.createdAt())).toList();
    }

    private List<PolicyChannelRule> policyChannels(UUID policyId) {
        return jdbc.query("""
                SELECT channel, enabled, default_mode, user_overridable, max_per_window
                  FROM ntf_policy_channel_rules
                 WHERE policy_id = :policyId
                 ORDER BY CASE channel
                     WHEN 'IN_APP' THEN 1 WHEN 'EMAIL' THEN 2 WHEN 'WEB_PUSH' THEN 3
                     WHEN 'MOBILE_PUSH' THEN 4 WHEN 'TEAMS' THEN 5 ELSE 6 END
                """, new MapSqlParameterSource("policyId", policyId),
                (resultSet, rowNumber) -> new PolicyChannelRule(
                        resultSet.getString("channel"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getString("default_mode"),
                        resultSet.getBoolean("user_overridable"),
                        (Integer) resultSet.getObject("max_per_window")));
    }

    private MapSqlParameterSource policyParams(
            long tenantId, String scopeType, String scopeKey) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("scopeType", scopeType)
                .addValue("scopeKey", scopeKey);
    }

    private void appendPolicyOutbox(
            long tenantId, UUID policyId, String eventType, String eventKey) {
        jdbc.update("""
                INSERT INTO ntf_outbox_events (
                    outbox_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, event_key, payload, occurred_at)
                VALUES (
                    :outboxId, :tenantId, 'NOTIFICATION_POLICY', :aggregateId,
                    :eventType, :eventKey,
                    jsonb_build_object('policyId', CAST(:aggregateId AS text)),
                    CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, event_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("aggregateId", policyId.toString())
                .addValue("eventType", eventType)
                .addValue("eventKey", eventKey));
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

    private Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
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

    private record PolicyRow(
            UUID policyId,
            Long tenantId,
            String scopeType,
            String scopeKey,
            String state,
            boolean mandatory,
            boolean quietHoursBypass,
            String digestMode,
            String changeReason,
            Long createdBy,
            Long approvedBy,
            Instant approvedAt,
            long version,
            Instant createdAt) {
    }
}
