package com.dwp.services.space.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class SpaceGovernanceRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SpaceGovernanceRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public UUID startRun(
            long tenantId,
            String triggerType,
            Long requestedBy,
            String correlationId) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spc_reconciliation_runs (
                    run_id, tenant_id, trigger_type, lifecycle_state,
                    requested_by, correlation_id)
                VALUES (:runId, :tenantId, :triggerType, 'RUNNING',
                    :requestedBy, :correlationId)
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("tenantId", tenantId)
                .addValue("triggerType", triggerType)
                .addValue("requestedBy", requestedBy)
                .addValue("correlationId", correlationId));
        return runId;
    }

    public void completeRun(
            UUID runId,
            int plannedCount,
            int expiredCount,
            int findingCount) {
        jdbc.update("""
                UPDATE spc_reconciliation_runs
                   SET lifecycle_state = 'SUCCEEDED',
                       planned_count = :planned,
                       expired_count = :expired,
                       finding_count = :findings,
                       summary = jsonb_build_object(
                           'plannedEntitlements', :planned,
                           'expiredMemberships', :expired,
                           'openFindings', :findings),
                       completed_at = CURRENT_TIMESTAMP
                 WHERE run_id = :runId AND lifecycle_state = 'RUNNING'
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("planned", plannedCount)
                .addValue("expired", expiredCount)
                .addValue("findings", findingCount));
    }

    public void failRun(UUID runId, String message) {
        jdbc.update("""
                UPDATE spc_reconciliation_runs
                   SET lifecycle_state = 'FAILED',
                       summary = jsonb_build_object('error', :message),
                       completed_at = CURRENT_TIMESTAMP
                 WHERE run_id = :runId AND lifecycle_state = 'RUNNING'
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("message", truncate(message, 900)));
    }

    public int refreshFindings(long tenantId) {
        List<FindingCandidate> candidates = new ArrayList<>();
        candidates.addAll(ownerlessSpaces(tenantId));
        candidates.addAll(deliveryFailures(tenantId));
        candidates.addAll(overdueReviews(tenantId));
        candidates.addAll(expiredMembershipsAwaitingRevoke(tenantId));

        Set<String> activeFingerprints = new HashSet<>();
        for (FindingCandidate candidate : candidates) {
            String fingerprint = sha256(candidate.findingType() + "|" + candidate.targetRef());
            activeFingerprints.add(fingerprint);
            upsertFinding(tenantId, fingerprint, candidate);
        }
        resolveMissingFindings(tenantId, activeFingerprints);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER FROM spc_reconciliation_findings
                 WHERE tenant_id = :tenantId AND lifecycle_state <> 'RESOLVED'
                """, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return count == null ? 0 : count;
    }

    public OperationsMetrics metrics(long tenantId) {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*)::INTEGER FROM spc_entitlement_sync_items
                      WHERE tenant_id = :tenantId
                        AND delivery_state IN ('PENDING', 'IN_PROGRESS', 'RETRY')) AS queued,
                    (SELECT COUNT(*)::INTEGER FROM spc_entitlement_sync_items
                      WHERE tenant_id = :tenantId AND delivery_state = 'DEAD') AS dead,
                    (SELECT COUNT(*)::INTEGER FROM spc_reconciliation_findings
                      WHERE tenant_id = :tenantId AND lifecycle_state <> 'RESOLVED') AS findings,
                    (SELECT COUNT(*)::INTEGER FROM spc_reconciliation_findings
                      WHERE tenant_id = :tenantId AND lifecycle_state <> 'RESOLVED'
                        AND severity IN ('HIGH', 'CRITICAL')) AS high_risk,
                    (SELECT COUNT(*)::INTEGER FROM spc_spaces space
                      WHERE space.tenant_id = :tenantId AND space.lifecycle_state = 'ACTIVE'
                        AND NOT EXISTS (
                            SELECT 1 FROM spc_memberships membership
                             WHERE membership.tenant_id = space.tenant_id
                               AND membership.space_id = space.space_id
                               AND membership.member_role = 'OWNER'
                               AND membership.lifecycle_state = 'ACTIVE'
                               AND membership.valid_from <= CURRENT_TIMESTAMP
                               AND (membership.valid_until IS NULL
                                    OR membership.valid_until > CURRENT_TIMESTAMP))) AS ownerless,
                    (SELECT COUNT(*)::INTEGER FROM spc_lifecycle_reviews
                      WHERE tenant_id = :tenantId AND status = 'OVERDUE') AS overdue,
                    (SELECT COUNT(*)::INTEGER FROM spc_entitlement_sync_items
                      WHERE tenant_id = :tenantId AND delivery_state = 'SUCCEEDED'
                        AND synchronized_at > CURRENT_TIMESTAMP - INTERVAL '24 hours') AS synchronized
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new OperationsMetrics(
                        rs.getInt("queued"), rs.getInt("dead"), rs.getInt("findings"),
                        rs.getInt("high_risk"), rs.getInt("ownerless"),
                        rs.getInt("overdue"), rs.getInt("synchronized")));
    }

    public List<RunSummary> runs(long tenantId, int limit) {
        return jdbc.query("""
                SELECT run_id, trigger_type, lifecycle_state, planned_count,
                       expired_count, finding_count, requested_by, summary,
                       started_at, completed_at
                  FROM spc_reconciliation_runs
                 WHERE tenant_id = :tenantId
                 ORDER BY started_at DESC
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", Math.max(1, Math.min(limit, 50))), (rs, row) ->
                new RunSummary(
                        rs.getObject("run_id", UUID.class), rs.getString("trigger_type"),
                        rs.getString("lifecycle_state"), rs.getInt("planned_count"),
                        rs.getInt("expired_count"), rs.getInt("finding_count"),
                        nullableLong(rs, "requested_by"), json(rs.getString("summary")),
                        instant(rs, "started_at"), instant(rs, "completed_at")));
    }

    public List<FindingSummary> findings(long tenantId, int limit) {
        return jdbc.query("""
                SELECT finding_id, space_id, membership_id, finding_type, severity,
                       lifecycle_state, target_type, target_ref, title, evidence,
                       first_detected_at, last_detected_at
                  FROM spc_reconciliation_findings
                 WHERE tenant_id = :tenantId AND lifecycle_state <> 'RESOLVED'
                 ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                                WHEN 'WARNING' THEN 2 ELSE 3 END,
                          last_detected_at DESC
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", Math.max(1, Math.min(limit, 100))), (rs, row) ->
                new FindingSummary(
                        rs.getObject("finding_id", UUID.class),
                        rs.getObject("space_id", UUID.class),
                        rs.getObject("membership_id", UUID.class),
                        rs.getString("finding_type"), rs.getString("severity"),
                        rs.getString("lifecycle_state"), rs.getString("target_type"),
                        rs.getString("target_ref"), rs.getString("title"),
                        json(rs.getString("evidence")),
                        instant(rs, "first_detected_at"),
                        instant(rs, "last_detected_at")));
    }

    public void recordPolicyEvaluation(
            long tenantId,
            UUID spaceId,
            String policyType,
            String subjectType,
            String subjectRef,
            String decision,
            String enforcementMode,
            String riskLevel,
            String evaluatorType,
            String evaluatorRef,
            String correlationId,
            Map<String, Object> evidence) {
        jdbc.update("""
                INSERT INTO spc_policy_evaluations (
                    evaluation_id, tenant_id, space_id, policy_type,
                    subject_type, subject_ref, decision, enforcement_mode,
                    risk_level, evaluator_type, evaluator_ref, correlation_id, evidence)
                VALUES (:evaluationId, :tenantId, :spaceId, :policyType,
                    :subjectType, :subjectRef, :decision, :enforcementMode,
                    :riskLevel, :evaluatorType, :evaluatorRef, :correlationId,
                    CAST(:evidence AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("evaluationId", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("spaceId", spaceId)
                .addValue("policyType", policyType)
                .addValue("subjectType", subjectType)
                .addValue("subjectRef", subjectRef)
                .addValue("decision", decision)
                .addValue("enforcementMode", enforcementMode)
                .addValue("riskLevel", riskLevel)
                .addValue("evaluatorType", evaluatorType)
                .addValue("evaluatorRef", evaluatorRef)
                .addValue("correlationId", correlationId)
                .addValue("evidence", json(evidence)));
    }

    private List<FindingCandidate> ownerlessSpaces(long tenantId) {
        return jdbc.query("""
                SELECT space.space_id, space.space_key, space.name_en
                  FROM spc_spaces space
                 WHERE space.tenant_id = :tenantId AND space.lifecycle_state = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1 FROM spc_memberships membership
                        WHERE membership.tenant_id = space.tenant_id
                          AND membership.space_id = space.space_id
                          AND membership.member_role = 'OWNER'
                          AND membership.lifecycle_state = 'ACTIVE'
                          AND membership.valid_from <= CURRENT_TIMESTAMP
                          AND (membership.valid_until IS NULL
                               OR membership.valid_until > CURRENT_TIMESTAMP))
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new FindingCandidate(
                        rs.getObject("space_id", UUID.class), null,
                        "OWNERLESS_SPACE", "CRITICAL", "SPACE",
                        rs.getObject("space_id", UUID.class).toString(),
                        "Active Space has no effective owner",
                        Map.of("spaceKey", rs.getString("space_key"),
                                "spaceName", rs.getString("name_en"))));
    }

    private List<FindingCandidate> deliveryFailures(long tenantId) {
        return jdbc.query("""
                SELECT sync_item_id, space_id, membership_id, permission_code,
                       delivery_state, attempt_count, last_error
                  FROM spc_entitlement_sync_items
                 WHERE tenant_id = :tenantId
                   AND (delivery_state = 'DEAD'
                        OR (delivery_state = 'RETRY' AND attempt_count >= 3))
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new FindingCandidate(
                        rs.getObject("space_id", UUID.class),
                        rs.getObject("membership_id", UUID.class),
                        "ENTITLEMENT_DELIVERY",
                        "DEAD".equals(rs.getString("delivery_state")) ? "HIGH" : "WARNING",
                        "SYNC_ITEM", rs.getObject("sync_item_id", UUID.class).toString(),
                        "Space entitlement delivery requires attention",
                        Map.of(
                                "permissionCode", rs.getString("permission_code"),
                                "deliveryState", rs.getString("delivery_state"),
                                "attemptCount", rs.getInt("attempt_count"),
                                "lastError", nullToEmpty(rs.getString("last_error")))));
    }

    private List<FindingCandidate> overdueReviews(long tenantId) {
        return jdbc.query("""
                SELECT lifecycle_review_id, space_id, review_type, due_at
                  FROM spc_lifecycle_reviews
                 WHERE tenant_id = :tenantId AND status = 'OVERDUE'
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new FindingCandidate(
                        rs.getObject("space_id", UUID.class), null,
                        "LIFECYCLE_REVIEW", "HIGH", "LIFECYCLE_REVIEW",
                        rs.getObject("lifecycle_review_id", UUID.class).toString(),
                        "Space lifecycle review is overdue",
                        Map.of("reviewType", rs.getString("review_type"),
                                "dueAt", instant(rs, "due_at").toString())));
    }

    private List<FindingCandidate> expiredMembershipsAwaitingRevoke(long tenantId) {
        return jdbc.query("""
                SELECT membership.membership_id, membership.space_id,
                       COUNT(sync.sync_item_id)::INTEGER AS pending_count
                  FROM spc_memberships membership
                  JOIN spc_entitlement_sync_items sync
                    ON sync.tenant_id = membership.tenant_id
                   AND sync.membership_id = membership.membership_id
                 WHERE membership.tenant_id = :tenantId
                   AND membership.lifecycle_state IN ('EXPIRED', 'REVOKED')
                   AND sync.desired_state = 'REVOKED'
                   AND sync.delivery_state <> 'SUCCEEDED'
                   AND membership.updated_at < CURRENT_TIMESTAMP - INTERVAL '10 minutes'
                 GROUP BY membership.membership_id, membership.space_id
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new FindingCandidate(
                        rs.getObject("space_id", UUID.class),
                        rs.getObject("membership_id", UUID.class),
                        "EXPIRED_MEMBERSHIP", "HIGH", "MEMBERSHIP",
                        rs.getObject("membership_id", UUID.class).toString(),
                        "Expired Space membership still has undelivered revocations",
                        Map.of("pendingRevocations", rs.getInt("pending_count"))));
    }

    private void upsertFinding(
            long tenantId,
            String fingerprint,
            FindingCandidate candidate) {
        jdbc.update("""
                INSERT INTO spc_reconciliation_findings (
                    finding_id, tenant_id, space_id, membership_id, fingerprint,
                    finding_type, severity, lifecycle_state, target_type,
                    target_ref, title, evidence)
                VALUES (:findingId, :tenantId, :spaceId, :membershipId, :fingerprint,
                    :findingType, :severity, 'OPEN', :targetType,
                    :targetRef, :title, CAST(:evidence AS jsonb))
                ON CONFLICT (tenant_id, fingerprint) DO UPDATE SET
                    space_id = EXCLUDED.space_id,
                    membership_id = EXCLUDED.membership_id,
                    severity = EXCLUDED.severity,
                    lifecycle_state = 'OPEN',
                    title = EXCLUDED.title,
                    evidence = EXCLUDED.evidence,
                    last_detected_at = CURRENT_TIMESTAMP,
                    resolved_at = NULL, resolved_by = NULL, resolution_note = NULL,
                    version = spc_reconciliation_findings.version + 1
                """, new MapSqlParameterSource()
                .addValue("findingId", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("spaceId", candidate.spaceId())
                .addValue("membershipId", candidate.membershipId())
                .addValue("fingerprint", fingerprint)
                .addValue("findingType", candidate.findingType())
                .addValue("severity", candidate.severity())
                .addValue("targetType", candidate.targetType())
                .addValue("targetRef", candidate.targetRef())
                .addValue("title", candidate.title())
                .addValue("evidence", json(candidate.evidence())));
    }

    private void resolveMissingFindings(long tenantId, Set<String> activeFingerprints) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
        String activePredicate = "";
        if (!activeFingerprints.isEmpty()) {
            activePredicate = "AND fingerprint NOT IN (:fingerprints)";
            params.addValue("fingerprints", activeFingerprints);
        }
        jdbc.update("""
                UPDATE spc_reconciliation_findings
                   SET lifecycle_state = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP,
                       resolution_note = 'Resolved by desired-state reconciliation.',
                       version = version + 1
                 WHERE tenant_id = :tenantId
                   AND lifecycle_state <> 'RESOLVED'
                   AND finding_type IN ('OWNERLESS_SPACE', 'ENTITLEMENT_DELIVERY',
                                        'EXPIRED_MEMBERSHIP', 'LIFECYCLE_REVIEW')
                   %s
                """.formatted(activePredicate), params);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Space operations evidence could not be serialized.", exception);
        }
    }

    private Map<String, Object> json(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Space operations evidence is invalid.", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private String truncate(String value, int max) {
        String safe = value == null || value.isBlank() ? "Unknown reconciliation failure." : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record OperationsMetrics(
            int queuedDeliveries,
            int deadLetters,
            int openFindings,
            int highRiskFindings,
            int ownerlessSpaces,
            int overdueReviews,
            int synchronizedLast24Hours) {
    }

    public record RunSummary(
            UUID runId,
            String triggerType,
            String lifecycleState,
            int plannedCount,
            int expiredCount,
            int findingCount,
            Long requestedBy,
            Map<String, Object> summary,
            Instant startedAt,
            Instant completedAt) {
    }

    public record FindingSummary(
            UUID findingId,
            UUID spaceId,
            UUID membershipId,
            String findingType,
            String severity,
            String lifecycleState,
            String targetType,
            String targetRef,
            String title,
            Map<String, Object> evidence,
            Instant firstDetectedAt,
            Instant lastDetectedAt) {
    }

    private record FindingCandidate(
            UUID spaceId,
            UUID membershipId,
            String findingType,
            String severity,
            String targetType,
            String targetRef,
            String title,
            Map<String, Object> evidence) {
    }
}
