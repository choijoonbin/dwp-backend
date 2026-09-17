package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationNoiseQualityModels.FourEyesGovernanceRow;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseAggregateRow;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseFindingSeverity;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseRisk;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseTrendRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationNoiseQualityRepository {

    static final String AGGREGATE_SQL = """
            WITH recipient_activity AS (
                SELECT fact.*,
                       EXISTS (
                           SELECT 1
                             FROM ntf_notification_quality_completion_facts completion
                            WHERE completion.tenant_id = fact.tenant_id
                              AND completion.user_id = fact.user_id
                              AND completion.type_version_id = fact.type_version_id
                              AND completion.thread_identity_hash = fact.thread_identity_hash
                              AND completion.completed_at >= fact.decided_at
                       ) AS completed
                  FROM ntf_notification_quality_facts fact
                 WHERE fact.tenant_id = :tenantId
                   AND fact.decided_at >= :windowStart
                   AND (CAST(:searchPattern AS text) IS NULL
                        OR LOWER(fact.owner_app_key) LIKE :searchPattern ESCAPE '!'
                        OR LOWER(fact.type_key) LIKE :searchPattern ESCAPE '!'
                        OR LOWER(fact.owner_team) LIKE :searchPattern ESCAPE '!')
            ), eligible AS (
                SELECT contract_id,
                       owner_app_key AS app_key,
                       type_key,
                       owner_team,
                       COUNT(DISTINCT user_id) AS recipient_count,
                       COUNT(*) AS volume,
                       COUNT(DISTINCT user_id) FILTER (
                           WHERE attention_effect = 'MUTE'
                       ) AS muting_recipients,
                       COUNT(*) FILTER (
                           WHERE decision = 'ADMITTED'
                       ) AS deduplication_eligible_occurrences,
                       COUNT(*) FILTER (
                           WHERE decision = 'ADMITTED' AND collapsed
                       ) AS deduplicated_occurrences,
                       COUNT(DISTINCT (user_id, thread_identity_hash)) FILTER (
                           WHERE decision = 'ADMITTED' AND action_required
                       ) AS actionable,
                       COUNT(DISTINCT (user_id, thread_identity_hash)) FILTER (
                           WHERE decision = 'ADMITTED' AND action_required AND completed
                       ) AS completed_actions
                  FROM recipient_activity
                 GROUP BY contract_id, owner_app_key, type_key, owner_team
                HAVING COUNT(DISTINCT user_id) >= :minimumCohortSize
            ), enriched AS (
                SELECT eligible.*,
                       (
                           SELECT policy.policy_id::text
                             FROM ntf_routing_policies policy
                            WHERE policy.tenant_id = :tenantId
                              AND policy.state = 'PUBLISHED'
                              AND ((policy.scope_type = 'TYPE'
                                    AND policy.scope_key = eligible.type_key)
                                   OR (policy.scope_type = 'APP'
                                       AND policy.scope_key = eligible.app_key))
                            ORDER BY CASE policy.scope_type WHEN 'TYPE' THEN 0 ELSE 1 END,
                                     policy.version DESC
                            LIMIT 1
                       ) AS policy_id,
                       (
                           SELECT revision.template_revision_id::text
                             FROM ntf_tenant_template_revisions revision
                             JOIN ntf_notification_type_versions template_version
                               ON template_version.type_version_id = revision.type_version_id
                            WHERE revision.tenant_id = :tenantId
                              AND template_version.type_id = eligible.contract_id
                              AND revision.channel = 'IN_APP'
                              AND revision.state = 'PUBLISHED'
                            ORDER BY revision.revision DESC, revision.created_at DESC
                            LIMIT 1
                       ) AS template_id
                  FROM eligible
            ), rated AS (
                SELECT enriched.*,
                       CASE
                           WHEN muting_recipients::double precision /
                                NULLIF(recipient_count, 0) >= 0.25
                               THEN 'HIGH_MUTE_RATE'
                           WHEN actionable >= :minimumCohortSize
                                AND completed_actions::double precision /
                                    NULLIF(actionable, 0) < 0.20
                               THEN 'LOW_ACTION_CONVERSION'
                           WHEN deduplication_eligible_occurrences >= recipient_count * 5
                                AND deduplicated_occurrences::double precision /
                                    NULLIF(deduplication_eligible_occurrences, 0) < 0.10
                               THEN 'DEDUPLICATION_OPPORTUNITY'
                           ELSE NULL
                       END AS finding_code,
                       CASE
                           WHEN muting_recipients::double precision /
                                NULLIF(recipient_count, 0) >= 0.50
                               THEN 'CRITICAL'
                           WHEN muting_recipients::double precision /
                                NULLIF(recipient_count, 0) >= 0.25
                               THEN 'WARNING'
                           WHEN actionable >= :minimumCohortSize
                                AND completed_actions::double precision /
                                    NULLIF(actionable, 0) < 0.20
                               THEN 'WARNING'
                           WHEN deduplication_eligible_occurrences >= recipient_count * 5
                                AND deduplicated_occurrences::double precision /
                                    NULLIF(deduplication_eligible_occurrences, 0) < 0.10
                               THEN 'INFO'
                           ELSE NULL
                       END AS finding_severity
                  FROM enriched
            ), targeted AS (
                SELECT rated.*,
                       CASE
                           WHEN finding_code = 'HIGH_MUTE_RATE' AND policy_id IS NOT NULL
                               THEN 'POLICY'
                           WHEN finding_code = 'LOW_ACTION_CONVERSION' AND template_id IS NOT NULL
                               THEN 'TEMPLATE'
                           ELSE 'CONTRACT'
                       END AS finding_target,
                       CASE
                           WHEN finding_code = 'HIGH_MUTE_RATE' AND policy_id IS NOT NULL
                               THEN policy_id
                           WHEN finding_code = 'LOW_ACTION_CONVERSION' AND template_id IS NOT NULL
                               THEN template_id
                           ELSE contract_id::text
                       END AS finding_target_key
                  FROM rated
            )
            SELECT contract_id,
                   app_key,
                   type_key,
                   owner_team,
                   recipient_count,
                   volume,
                   muting_recipients,
                   deduplication_eligible_occurrences,
                   deduplicated_occurrences,
                   actionable,
                   completed_actions,
                   finding_code,
                   finding_severity,
                   finding_target,
                   finding_target_key
              FROM targeted
             WHERE (CAST(:risk AS text) IS NULL OR finding_code = :risk)
               AND (CAST(:severity AS text) IS NULL OR finding_severity = :severity)
             ORDER BY volume DESC, app_key, type_key
             LIMIT :limit
            """;

    static final String FATIGUE_SQL = """
            SELECT COUNT(*)
              FROM (
                  SELECT fact.user_id
                    FROM ntf_notification_quality_facts fact
                   WHERE fact.tenant_id = :tenantId
                     AND fact.decided_at >= :windowStart
                     AND fact.contract_id IN (:contractIds)
                     AND fact.decision = 'ADMITTED'
                   GROUP BY fact.user_id
                  HAVING COUNT(*) >= :occurrenceThreshold
              ) exposed
            """;

    static final String TREND_SQL = """
            WITH recipient_activity AS (
                SELECT date_trunc(:bucket, fact.decided_at) AS bucket_start,
                       fact.*,
                       EXISTS (
                           SELECT 1
                             FROM ntf_notification_quality_completion_facts completion
                            WHERE completion.tenant_id = fact.tenant_id
                              AND completion.user_id = fact.user_id
                              AND completion.type_version_id = fact.type_version_id
                              AND completion.thread_identity_hash = fact.thread_identity_hash
                              AND completion.completed_at >= fact.decided_at
                       ) AS completed
                  FROM ntf_notification_quality_facts fact
                 WHERE fact.tenant_id = :tenantId
                   AND fact.decided_at >= :windowStart
                   AND fact.contract_id IN (:contractIds)
            )
            SELECT bucket_start,
                   COUNT(DISTINCT user_id) AS recipient_count,
                   COUNT(*) AS volume,
                   COUNT(DISTINCT user_id) FILTER (
                       WHERE attention_effect = 'MUTE'
                   ) AS muting_recipients,
                   COUNT(*) FILTER (
                       WHERE decision = 'ADMITTED'
                   ) AS deduplication_eligible_occurrences,
                   COUNT(*) FILTER (
                       WHERE decision = 'ADMITTED' AND collapsed
                   ) AS deduplicated_occurrences,
                   COUNT(DISTINCT (user_id, thread_identity_hash)) FILTER (
                       WHERE decision = 'ADMITTED' AND action_required
                   ) AS actionable,
                   COUNT(DISTINCT (user_id, thread_identity_hash)) FILTER (
                       WHERE decision = 'ADMITTED' AND action_required AND completed
                   ) AS completed_actions
              FROM recipient_activity
             GROUP BY bucket_start
            HAVING COUNT(DISTINCT user_id) >= :minimumCohortSize
             ORDER BY bucket_start
            """;

    static final String FOUR_EYES_SQL = """
            SELECT COUNT(*) FILTER (WHERE state = 'PUBLISHED') AS published_count,
                   COUNT(*) FILTER (WHERE state = 'DRAFT') AS draft_count,
                   COALESCE(
                       BOOL_AND(
                           approved_by IS NOT NULL
                           AND approved_at IS NOT NULL
                           AND created_by IS NOT NULL
                           AND approved_by <> created_by
                       ) FILTER (WHERE state = 'PUBLISHED'),
                       FALSE
                   ) AS published_evidence_valid,
                   MAX(COALESCE(approved_at, created_at)) AS updated_at
              FROM ntf_routing_policies
             WHERE tenant_id = :tenantId
               AND state IN ('DRAFT', 'PUBLISHED')
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationNoiseQualityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long observedCohortSize(long tenantId, Instant windowStart, String search) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT fact.user_id)
                  FROM ntf_notification_quality_facts fact
                 WHERE fact.tenant_id = :tenantId
                   AND fact.decided_at >= :windowStart
                   AND (CAST(:searchPattern AS text) IS NULL
                        OR LOWER(fact.owner_app_key) LIKE :searchPattern ESCAPE '!'
                        OR LOWER(fact.type_key) LIKE :searchPattern ESCAPE '!'
                        OR LOWER(fact.owner_team) LIKE :searchPattern ESCAPE '!')
                """, params(tenantId, windowStart, search), Long.class);
        return count == null ? 0 : count;
    }

    public List<NoiseAggregateRow> aggregates(
            long tenantId,
            Instant windowStart,
            int minimumCohortSize,
            int limit,
            String search,
            NoiseFindingSeverity severity,
            NoiseRisk risk) {
        return jdbc.query(
                AGGREGATE_SQL,
                params(tenantId, windowStart, search)
                        .addValue("minimumCohortSize", minimumCohortSize)
                        .addValue("severity", severity == null ? null : severity.name(), Types.VARCHAR)
                        .addValue("risk", risk == null ? null : risk.name(), Types.VARCHAR)
                        .addValue("limit", limit),
                (resultSet, rowNumber) -> new NoiseAggregateRow(
                        resultSet.getObject("contract_id", UUID.class),
                        resultSet.getString("app_key"),
                        resultSet.getString("type_key"),
                        resultSet.getString("owner_team"),
                        resultSet.getLong("recipient_count"),
                        resultSet.getLong("volume"),
                        resultSet.getLong("muting_recipients"),
                        resultSet.getLong("deduplication_eligible_occurrences"),
                        resultSet.getLong("deduplicated_occurrences"),
                        resultSet.getLong("actionable"),
                        resultSet.getLong("completed_actions"),
                        resultSet.getString("finding_code"),
                        resultSet.getString("finding_severity"),
                        resultSet.getString("finding_target"),
                        resultSet.getString("finding_target_key")));
    }

    public long fatigueExposedUsers(
            long tenantId,
            Instant windowStart,
            int occurrenceThreshold,
            Collection<UUID> contractIds) {
        Long count = jdbc.queryForObject(FATIGUE_SQL, params(tenantId, windowStart, null)
                .addValue("occurrenceThreshold", occurrenceThreshold)
                .addValue("contractIds", contractIds), Long.class);
        return count == null ? 0 : count;
    }

    public List<NoiseTrendRow> trend(
            long tenantId,
            Instant windowStart,
            String bucket,
            int minimumCohortSize,
            Collection<UUID> contractIds) {
        return jdbc.query(
                TREND_SQL,
                params(tenantId, windowStart, null)
                        .addValue("bucket", bucket)
                        .addValue("minimumCohortSize", minimumCohortSize)
                        .addValue("contractIds", contractIds),
                (resultSet, rowNumber) -> new NoiseTrendRow(
                        resultSet.getTimestamp("bucket_start").toInstant(),
                        resultSet.getLong("recipient_count"),
                        resultSet.getLong("volume"),
                        resultSet.getLong("muting_recipients"),
                        resultSet.getLong("deduplication_eligible_occurrences"),
                        resultSet.getLong("deduplicated_occurrences"),
                        resultSet.getLong("actionable"),
                        resultSet.getLong("completed_actions")));
    }

    public FourEyesGovernanceRow fourEyesGovernance(long tenantId) {
        return jdbc.queryForObject(
                FOUR_EYES_SQL,
                new MapSqlParameterSource("tenantId", tenantId),
                (resultSet, rowNumber) -> {
                    var updatedAt = resultSet.getTimestamp("updated_at");
                    return new FourEyesGovernanceRow(
                            resultSet.getLong("published_count"),
                            resultSet.getLong("draft_count"),
                            resultSet.getBoolean("published_evidence_valid"),
                            updatedAt == null ? null : updatedAt.toInstant());
                });
    }

    private MapSqlParameterSource params(long tenantId, Instant windowStart, String search) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("windowStart", Timestamp.from(windowStart))
                .addValue("searchPattern", searchPattern(search), Types.VARCHAR);
    }

    private String searchPattern(String search) {
        if (search == null || search.isBlank()) return null;
        String escaped = search.trim().toLowerCase()
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
