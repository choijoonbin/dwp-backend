package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Revision;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationAttentionGovernanceRepository {

    private static final String SELECT_COLUMNS = """
            governance_id, state, max_active_user_rules, max_vip_rules,
            max_follow_rules, approved_topic_allowlist::text AS approved_topic_allowlist,
            mandatory_policy_precedence, minimum_analytics_cohort,
            independent_reviewer_required, revision_number, version, change_reason,
            created_by, created_at, approved_by, approved_at, updated_by, updated_at,
            decision_reason, supersedes_governance_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NotificationAttentionGovernanceRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<Revision> active(long tenantId) {
        return single("""
                SELECT %s
                  FROM ntf_attention_governance_revisions
                 WHERE tenant_id = :tenantId
                   AND state = 'PUBLISHED'
                """.formatted(SELECT_COLUMNS), params(tenantId));
    }

    public List<Revision> drafts(long tenantId) {
        return jdbc.query("""
                SELECT %s
                  FROM ntf_attention_governance_revisions
                 WHERE tenant_id = :tenantId
                   AND state = 'DRAFT'
                 ORDER BY revision_number DESC
                """.formatted(SELECT_COLUMNS), params(tenantId), this::map);
    }

    public long latestRevisionNumber(long tenantId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_number), 0)
                  FROM ntf_attention_governance_revisions
                 WHERE tenant_id = :tenantId
                """, params(tenantId), Long.class);
        return value == null ? 0L : value;
    }

    public Revision createDraft(
            long tenantId,
            long actorId,
            Settings settings,
            String changeReason,
            long revisionNumber,
            UUID supersedesGovernanceId) {
        UUID governanceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ntf_attention_governance_revisions (
                    governance_id, tenant_id, state, max_active_user_rules,
                    max_vip_rules, max_follow_rules, approved_topic_allowlist,
                    mandatory_policy_precedence, minimum_analytics_cohort,
                    independent_reviewer_required, revision_number, version,
                    change_reason, created_by, updated_by, supersedes_governance_id)
                VALUES (
                    :governanceId, :tenantId, 'DRAFT', :maxActiveUserRules,
                    :maxVipRules, :maxFollowRules, CAST(:approvedTopics AS jsonb),
                    :mandatoryPolicyPrecedence, :minimumAnalyticsCohort,
                    :independentReviewerRequired, :revisionNumber, 1,
                    :changeReason, :actorId, :actorId, :supersedesGovernanceId)
                """, values(tenantId, settings)
                .addValue("governanceId", governanceId)
                .addValue("actorId", actorId)
                .addValue("changeReason", changeReason)
                .addValue("revisionNumber", revisionNumber)
                .addValue("supersedesGovernanceId", supersedesGovernanceId));
        return find(tenantId, governanceId).orElseThrow();
    }

    public Optional<Revision> find(long tenantId, UUID governanceId) {
        return single("""
                SELECT %s
                  FROM ntf_attention_governance_revisions
                 WHERE tenant_id = :tenantId
                   AND governance_id = :governanceId
                """.formatted(SELECT_COLUMNS), params(tenantId)
                .addValue("governanceId", governanceId));
    }

    public Optional<Revision> findForUpdate(long tenantId, UUID governanceId) {
        return single("""
                SELECT %s
                  FROM ntf_attention_governance_revisions
                 WHERE tenant_id = :tenantId
                   AND governance_id = :governanceId
                 FOR UPDATE
                """.formatted(SELECT_COLUMNS), params(tenantId)
                .addValue("governanceId", governanceId));
    }

    public boolean publish(
            long tenantId,
            UUID governanceId,
            long approverId,
            long expectedVersion,
            String reason) {
        MapSqlParameterSource values = params(tenantId)
                .addValue("governanceId", governanceId)
                .addValue("actorId", approverId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("reason", reason);
        jdbc.update("""
                UPDATE ntf_attention_governance_revisions
                   SET state = 'SUPERSEDED',
                       version = version + 1,
                       updated_by = :actorId,
                       updated_at = CURRENT_TIMESTAMP,
                       decision_reason = 'Superseded by a newer published revision'
                 WHERE tenant_id = :tenantId
                   AND state = 'PUBLISHED'
                """, values);
        return jdbc.update("""
                UPDATE ntf_attention_governance_revisions
                   SET state = 'PUBLISHED',
                       version = version + 1,
                       approved_by = :actorId,
                       approved_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId,
                       updated_at = CURRENT_TIMESTAMP,
                       decision_reason = :reason
                 WHERE tenant_id = :tenantId
                   AND governance_id = :governanceId
                   AND state = 'DRAFT'
                   AND version = :expectedVersion
                   AND created_by <> :actorId
                """, values) == 1;
    }

    public boolean decideDraft(
            long tenantId,
            UUID governanceId,
            long actorId,
            long expectedVersion,
            String state,
            String reason,
            boolean independentReviewer) {
        String reviewerClause = independentReviewer ? "AND created_by <> :actorId" : "";
        return jdbc.update("""
                UPDATE ntf_attention_governance_revisions
                   SET state = :state,
                       version = version + 1,
                       updated_by = :actorId,
                       updated_at = CURRENT_TIMESTAMP,
                       decision_reason = :reason
                 WHERE tenant_id = :tenantId
                   AND governance_id = :governanceId
                   AND state = 'DRAFT'
                   AND version = :expectedVersion
                """ + reviewerClause, params(tenantId)
                .addValue("governanceId", governanceId)
                .addValue("actorId", actorId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("state", state)
                .addValue("reason", reason)) == 1;
    }

    private Optional<Revision> single(String sql, MapSqlParameterSource params) {
        List<Revision> rows = jdbc.query(sql, params, this::map);
        return rows.stream().findFirst();
    }

    private Revision map(ResultSet resultSet, int rowNumber) throws SQLException {
        Settings settings = new Settings(
                resultSet.getInt("max_active_user_rules"),
                resultSet.getInt("max_vip_rules"),
                resultSet.getInt("max_follow_rules"),
                topics(resultSet.getString("approved_topic_allowlist")),
                resultSet.getBoolean("mandatory_policy_precedence"),
                resultSet.getInt("minimum_analytics_cohort"),
                resultSet.getBoolean("independent_reviewer_required"));
        return new Revision(
                resultSet.getObject("governance_id", UUID.class),
                resultSet.getString("state"),
                settings,
                resultSet.getLong("revision_number"),
                Long.toString(resultSet.getLong("version")),
                resultSet.getString("change_reason"),
                nullableLong(resultSet, "created_by"),
                resultSet.getTimestamp("created_at").toInstant(),
                nullableLong(resultSet, "approved_by"),
                instant(resultSet, "approved_at"),
                nullableLong(resultSet, "updated_by"),
                resultSet.getTimestamp("updated_at").toInstant(),
                resultSet.getString("decision_reason"),
                resultSet.getObject("supersedes_governance_id", UUID.class));
    }

    private MapSqlParameterSource values(long tenantId, Settings settings) {
        return params(tenantId)
                .addValue("maxActiveUserRules", settings.maxActiveUserRules())
                .addValue("maxVipRules", settings.maxVipRules())
                .addValue("maxFollowRules", settings.maxFollowRules())
                .addValue("approvedTopics", json(settings.approvedTopicAllowlist()))
                .addValue("mandatoryPolicyPrecedence", settings.mandatoryPolicyPrecedence())
                .addValue("minimumAnalyticsCohort", settings.minimumAnalyticsCohort())
                .addValue("independentReviewerRequired", settings.independentReviewerRequired());
    }

    private MapSqlParameterSource params(long tenantId) {
        return new MapSqlParameterSource("tenantId", tenantId);
    }

    private List<String> topics(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid attention governance topic allowlist.", exception);
        }
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize attention governance settings.", exception);
        }
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private java.time.Instant instant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
