package com.dwp.services.provider.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DataPolicyRepository {

    private static final String POLICY_SELECT = """
            SELECT policy.data_policy_id, policy.policy_key, policy.display_name,
                   policy.description, policy.policy_type, policy.scope_type,
                   policy.scope_ref, policy.owner_service, policy.lifecycle_state,
                   policy.version
              FROM prv_data_policies policy
            """;
    private static final String REVISION_SELECT = """
            SELECT data_policy_revision_id, data_policy_id, revision_number,
                   lifecycle_state, policy_rule, effective_from, effective_to,
                   justification, previous_revision_id, rollback_of_revision_id,
                   impact_snapshot, impact_hash, impact_previewed_at, requested_by,
                   approved_by, submitted_at, approved_at, published_at, version
              FROM prv_data_policy_revisions
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DataPolicyRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<PolicyRow> policies(int fetchLimit) {
        return jdbc.query(
                POLICY_SELECT + """
                        ORDER BY CASE WHEN EXISTS (
                                     SELECT 1
                                       FROM prv_data_policy_revisions revision
                                      WHERE revision.data_policy_id = policy.data_policy_id
                                        AND revision.lifecycle_state IN (
                                            'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE'))
                                      THEN 0 ELSE 1 END,
                                 policy.lifecycle_state, policy.policy_type, policy.policy_key
                        LIMIT :fetchLimit
                        """,
                new MapSqlParameterSource("fetchLimit", fetchLimit),
                this::policyRow);
    }

    public Optional<PolicyRow> policy(UUID policyId) {
        return jdbc.query(
                POLICY_SELECT + " WHERE data_policy_id = :policyId",
                new MapSqlParameterSource("policyId", policyId),
                this::policyRow).stream().findFirst();
    }

    public Optional<PolicyRow> lockPolicy(UUID policyId) {
        return jdbc.query(
                POLICY_SELECT + " WHERE data_policy_id = :policyId FOR UPDATE",
                new MapSqlParameterSource("policyId", policyId),
                this::policyRow).stream().findFirst();
    }

    public PolicyRow createPolicy(
            UUID policyId,
            DataPolicyDtos.CreatePolicyRequest request,
            Long actorId) {
        jdbc.update("""
                INSERT INTO prv_data_policies (
                    data_policy_id, policy_key, display_name, description, policy_type,
                    scope_type, scope_ref, owner_service, created_by, updated_by)
                VALUES (
                    :policyId, :policyKey, :displayName, :description, :policyType,
                    :scopeType, :scopeRef, :ownerService, :actorId, :actorId)
                """, new MapSqlParameterSource("policyId", policyId)
                .addValue("policyKey", request.policyKey())
                .addValue("displayName", request.displayName().trim())
                .addValue("description", request.description().trim())
                .addValue("policyType", request.policyType())
                .addValue("scopeType", request.scopeType())
                .addValue("scopeRef", normalize(request.scopeRef()))
                .addValue("ownerService", request.ownerService().trim())
                .addValue("actorId", actorId));
        return policy(policyId).orElseThrow();
    }

    public List<RevisionRow> revisions(Collection<UUID> policyIds, int fetchLimitPerPolicy) {
        if (policyIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT data_policy_revision_id, data_policy_id, revision_number,
                       lifecycle_state, policy_rule, effective_from, effective_to,
                       justification, previous_revision_id, rollback_of_revision_id,
                       impact_snapshot, impact_hash, impact_previewed_at, requested_by,
                       approved_by, submitted_at, approved_at, published_at, version
                  FROM (
                        SELECT revision.*,
                               ROW_NUMBER() OVER (
                                   PARTITION BY revision.data_policy_id
                                   ORDER BY CASE revision.lifecycle_state
                                       WHEN 'PENDING_APPROVAL' THEN 0
                                       WHEN 'APPROVED' THEN 1
                                       WHEN 'DRAFT' THEN 2
                                       WHEN 'ACTIVE' THEN 3
                                       ELSE 4
                                   END,
                                   revision.revision_number DESC
                               ) AS policy_rank
                          FROM prv_data_policy_revisions revision
                         WHERE revision.data_policy_id IN (:policyIds)
                       ) ranked_revision
                 WHERE policy_rank <= :fetchLimitPerPolicy
                 ORDER BY data_policy_id, policy_rank
                """, new MapSqlParameterSource("policyIds", policyIds)
                .addValue("fetchLimitPerPolicy", fetchLimitPerPolicy), this::revisionRow);
    }

    public Optional<RevisionRow> revision(UUID revisionId) {
        return jdbc.query(
                REVISION_SELECT + " WHERE data_policy_revision_id = :revisionId",
                new MapSqlParameterSource("revisionId", revisionId),
                this::revisionRow).stream().findFirst();
    }

    public List<ScopedActivePolicy> activePolicies(String policyType) {
        return jdbc.query("""
                SELECT policy.data_policy_id, policy.policy_type, policy.scope_type,
                       policy.scope_ref, revision.data_policy_revision_id,
                       revision.policy_rule, revision.effective_from, revision.effective_to
                  FROM prv_data_policies policy
                  JOIN prv_data_policy_revisions revision
                    ON revision.data_policy_id = policy.data_policy_id
                   AND revision.lifecycle_state = 'ACTIVE'
                 WHERE policy.lifecycle_state = 'ACTIVE'
                   AND policy.policy_type = :policyType
                """, new MapSqlParameterSource("policyType", policyType),
                (result, ignored) -> new ScopedActivePolicy(
                        result.getObject("data_policy_id", UUID.class),
                        result.getString("policy_type"),
                        result.getString("scope_type"),
                        result.getString("scope_ref"),
                        result.getObject("data_policy_revision_id", UUID.class),
                        node(result.getString("policy_rule")),
                        instant(result, "effective_from"),
                        instant(result, "effective_to")));
    }

    public RevisionRow createRevision(
            PolicyRow policy,
            UUID revisionId,
            JsonNode rule,
            String justification,
            Instant effectiveFrom,
            Instant effectiveTo,
            UUID rollbackOfRevisionId,
            Long actorId) {
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_number), 0) + 1
                  FROM prv_data_policy_revisions
                 WHERE data_policy_id = :policyId
                """, new MapSqlParameterSource("policyId", policy.policyId()), Integer.class);
        UUID previousRevision = jdbc.query("""
                SELECT data_policy_revision_id
                  FROM prv_data_policy_revisions
                 WHERE data_policy_id = :policyId
                   AND lifecycle_state IN ('ACTIVE', 'SUPERSEDED')
                 ORDER BY published_at DESC NULLS LAST, revision_number DESC
                 LIMIT 1
                """, new MapSqlParameterSource("policyId", policy.policyId()),
                (result, ignored) -> result.getObject("data_policy_revision_id", UUID.class))
                .stream().findFirst().orElse(null);
        jdbc.update("""
                INSERT INTO prv_data_policy_revisions (
                    data_policy_revision_id, data_policy_id, revision_number, policy_rule,
                    effective_from, effective_to, justification, previous_revision_id,
                    rollback_of_revision_id, requested_by)
                VALUES (
                    :revisionId, :policyId, :revisionNumber, CAST(:rule AS JSONB),
                    :effectiveFrom, :effectiveTo, :justification, :previousRevision,
                    :rollbackOfRevision, :actorId)
                """, new MapSqlParameterSource("revisionId", revisionId)
                .addValue("policyId", policy.policyId())
                .addValue("revisionNumber", next)
                .addValue("rule", json(rule))
                .addValue("effectiveFrom", effectiveFrom)
                .addValue("effectiveTo", effectiveTo)
                .addValue("justification", justification.trim())
                .addValue("previousRevision", previousRevision)
                .addValue("rollbackOfRevision", rollbackOfRevisionId)
                .addValue("actorId", actorId));
        return revision(revisionId).orElseThrow();
    }

    public RevisionRow saveImpact(
            UUID revisionId,
            long version,
            DataPolicyDtos.ImpactPreview impact) {
        int changed = jdbc.update("""
                UPDATE prv_data_policy_revisions
                   SET impact_snapshot = CAST(:impact AS JSONB), impact_hash = :impactHash,
                       impact_previewed_at = :previewedAt, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE data_policy_revision_id = :revisionId
                   AND lifecycle_state = 'DRAFT'
                   AND version = :version
                """, new MapSqlParameterSource("revisionId", revisionId)
                .addValue("version", version)
                .addValue("impact", json(objectMapper.valueToTree(impact)))
                .addValue("impactHash", impact.impactHash())
                .addValue("previewedAt", impact.previewedAt()));
        return changed == 1 ? revision(revisionId).orElseThrow() : null;
    }

    public boolean submit(UUID revisionId, long version, Long actorId) {
        int changed = jdbc.update("""
                UPDATE prv_data_policy_revisions
                   SET lifecycle_state = 'PENDING_APPROVAL', submitted_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE data_policy_revision_id = :revisionId
                   AND lifecycle_state = 'DRAFT'
                   AND impact_snapshot IS NOT NULL
                   AND impact_previewed_at > CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                   AND COALESCE(jsonb_array_length(impact_snapshot -> 'blockers'), 0) = 0
                   AND version = :version
                """, new MapSqlParameterSource("revisionId", revisionId)
                .addValue("version", version));
        if (changed == 1) {
            jdbc.update("""
                    INSERT INTO prv_data_policy_approvals (
                        data_policy_approval_id, data_policy_revision_id, requested_by)
                    VALUES (:approvalId, :revisionId, :actorId)
                    """, new MapSqlParameterSource("approvalId", UUID.randomUUID())
                    .addValue("revisionId", revisionId)
                    .addValue("actorId", actorId));
        }
        return changed == 1;
    }

    public boolean decide(
            UUID revisionId,
            long version,
            String decision,
            String reason,
            Long actorId) {
        int approvalChanged = jdbc.update("""
                UPDATE prv_data_policy_approvals
                   SET lifecycle_state = :decision, decided_by = :actorId,
                       decided_at = CURRENT_TIMESTAMP, decision_reason = :reason
                 WHERE data_policy_revision_id = :revisionId
                   AND lifecycle_state = 'PENDING'
                   AND requested_by <> :actorId
                """, new MapSqlParameterSource("decision", decision)
                .addValue("actorId", actorId)
                .addValue("reason", reason)
                .addValue("revisionId", revisionId));
        if (approvalChanged != 1) return false;
        String state = "APPROVED".equals(decision) ? "APPROVED" : "REJECTED";
        int revisionChanged = jdbc.update("""
                UPDATE prv_data_policy_revisions
                   SET lifecycle_state = :state,
                       approved_by = CASE WHEN :decision = 'APPROVED' THEN :actorId ELSE NULL END,
                       approved_at = CASE WHEN :decision = 'APPROVED'
                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE data_policy_revision_id = :revisionId
                   AND lifecycle_state = 'PENDING_APPROVAL'
                   AND requested_by <> :actorId
                   AND version = :version
                """, new MapSqlParameterSource("state", state)
                .addValue("decision", decision)
                .addValue("actorId", actorId)
                .addValue("revisionId", revisionId)
                .addValue("version", version));
        if (revisionChanged != 1) {
            throw new IllegalStateException("Data policy approval transition lost consistency.");
        }
        return true;
    }

    public boolean publish(UUID policyId, UUID revisionId, long version) {
        int prior = jdbc.update("""
                UPDATE prv_data_policy_revisions
                   SET lifecycle_state = 'SUPERSEDED', updated_at = CURRENT_TIMESTAMP
                 WHERE data_policy_id = :policyId
                   AND lifecycle_state = 'ACTIVE'
                """, new MapSqlParameterSource("policyId", policyId));
        int changed = jdbc.update("""
                UPDATE prv_data_policy_revisions
                   SET lifecycle_state = 'ACTIVE', published_at = CURRENT_TIMESTAMP,
                       effective_from = COALESCE(effective_from, CURRENT_TIMESTAMP),
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE data_policy_id = :policyId
                   AND data_policy_revision_id = :revisionId
                   AND lifecycle_state = 'APPROVED'
                   AND approved_by IS NOT NULL
                   AND version = :version
                """, new MapSqlParameterSource("policyId", policyId)
                .addValue("revisionId", revisionId)
                .addValue("version", version));
        if (changed != 1 && prior > 0) {
            throw new IllegalStateException("Data policy publish transition lost consistency.");
        }
        return changed == 1;
    }

    public List<RevisionApprovalRow> approvals(Collection<UUID> revisionIds) {
        if (revisionIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT data_policy_revision_id, data_policy_approval_id, lifecycle_state,
                       requested_by, requested_at, decided_by, decided_at, decision_reason
                  FROM (
                        SELECT approval.*,
                               ROW_NUMBER() OVER (
                                   PARTITION BY approval.data_policy_revision_id
                                   ORDER BY approval.requested_at DESC,
                                            approval.data_policy_approval_id DESC
                               ) AS approval_rank
                          FROM prv_data_policy_approvals approval
                         WHERE approval.data_policy_revision_id IN (:revisionIds)
                       ) ranked_approval
                 WHERE approval_rank = 1
                """, new MapSqlParameterSource("revisionIds", revisionIds),
                (result, ignored) -> new RevisionApprovalRow(
                        result.getObject("data_policy_revision_id", UUID.class),
                        new ApprovalRow(
                                result.getObject("data_policy_approval_id", UUID.class),
                                result.getString("lifecycle_state"),
                                result.getLong("requested_by"),
                                instant(result, "requested_at"),
                                result.getObject("decided_by", Long.class),
                                instant(result, "decided_at"),
                                result.getString("decision_reason"))));
    }

    private PolicyRow policyRow(ResultSet result, int ignored) throws SQLException {
        return new PolicyRow(
                result.getObject("data_policy_id", UUID.class),
                result.getString("policy_key"),
                result.getString("display_name"),
                result.getString("description"),
                result.getString("policy_type"),
                result.getString("scope_type"),
                result.getString("scope_ref"),
                result.getString("owner_service"),
                result.getString("lifecycle_state"),
                result.getLong("version"));
    }

    private RevisionRow revisionRow(ResultSet result, int ignored) throws SQLException {
        return new RevisionRow(
                result.getObject("data_policy_revision_id", UUID.class),
                result.getObject("data_policy_id", UUID.class),
                result.getInt("revision_number"),
                result.getString("lifecycle_state"),
                node(result.getString("policy_rule")),
                instant(result, "effective_from"),
                instant(result, "effective_to"),
                result.getString("justification"),
                result.getObject("previous_revision_id", UUID.class),
                result.getObject("rollback_of_revision_id", UUID.class),
                nullableNode(result.getString("impact_snapshot")),
                result.getString("impact_hash"),
                instant(result, "impact_previewed_at"),
                result.getLong("requested_by"),
                result.getObject("approved_by", Long.class),
                instant(result, "submitted_at"),
                instant(result, "approved_at"),
                instant(result, "published_at"),
                result.getLong("version"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Data policy JSON serialization failed.", exception);
        }
    }

    private JsonNode node(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Data policy JSON is invalid.", exception);
        }
    }

    private JsonNode nullableNode(String value) {
        return value == null ? null : node(value);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record PolicyRow(
            UUID policyId,
            String policyKey,
            String displayName,
            String description,
            String policyType,
            String scopeType,
            String scopeRef,
            String ownerService,
            String lifecycleState,
            long version) {
    }

    public record RevisionRow(
            UUID revisionId,
            UUID policyId,
            int revisionNumber,
            String lifecycleState,
            JsonNode rule,
            Instant effectiveFrom,
            Instant effectiveTo,
            String justification,
            UUID previousRevisionId,
            UUID rollbackOfRevisionId,
            JsonNode impact,
            String impactHash,
            Instant impactPreviewedAt,
            Long requestedBy,
            Long approvedBy,
            Instant submittedAt,
            Instant approvedAt,
            Instant publishedAt,
            long version) {
    }

    public record ApprovalRow(
            UUID approvalId,
            String lifecycleState,
            Long requestedBy,
            Instant requestedAt,
            Long decidedBy,
            Instant decidedAt,
            String reason) {
    }

    public record RevisionApprovalRow(
            UUID revisionId,
            ApprovalRow approval) {
    }

    public record ScopedActivePolicy(
            UUID policyId,
            String policyType,
            String scopeType,
            String scopeRef,
            UUID revisionId,
            JsonNode rule,
            Instant effectiveFrom,
            Instant effectiveTo) {

        public boolean isEffectiveAt(Instant evaluatedAt) {
            return (effectiveFrom == null || !effectiveFrom.isAfter(evaluatedAt))
                    && (effectiveTo == null || effectiveTo.isAfter(evaluatedAt));
        }
    }
}
