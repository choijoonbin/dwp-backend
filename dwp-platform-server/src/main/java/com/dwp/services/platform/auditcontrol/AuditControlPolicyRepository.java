package com.dwp.services.platform.auditcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class AuditControlPolicyRepository extends AuditControlCaseRepository {
    AuditControlPolicyRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    public AuditControlDtos.RetentionPolicy policy(Long tenantId) {
        ensurePolicyRow(tenantId);
        AuditControlDtos.RetentionPolicy current = readPolicy(tenantId);
        ensurePolicyBaseline(tenantId, current);
        return readPolicy(tenantId);
    }

    public AuditControlDtos.RetentionPolicy lockPolicyForRevisionDraft(Long tenantId) {
        ensurePolicyRow(tenantId);
        lockPolicy(tenantId);
        AuditControlDtos.RetentionPolicy current = readPolicy(tenantId);
        ensurePolicyBaseline(tenantId, current);
        return readPolicy(tenantId);
    }

    private AuditControlDtos.RetentionPolicy readPolicy(Long tenantId) {
        return jdbc.queryForObject("""
                SELECT standard_retention_days, extended_retention_days, export_limit_rows,
                       require_export_reason, integrity_enabled, high_risk_threshold,
                       updated_by, updated_at, active_revision_id, active_revision_number
                  FROM sys_audit_retention_policies WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new AuditControlDtos.RetentionPolicy(
                        rs.getInt("standard_retention_days"), rs.getInt("extended_retention_days"),
                        rs.getInt("export_limit_rows"), rs.getBoolean("require_export_reason"),
                        rs.getBoolean("integrity_enabled"), rs.getInt("high_risk_threshold"),
                        rs.getString("updated_by"), instant(rs, "updated_at"),
                        rs.getObject("active_revision_id", UUID.class),
                        rs.getLong("active_revision_number")));
    }

    public List<AuditControlDtos.PolicyRevision> policyRevisions(
            Long tenantId, int fetchLimit) {
        policy(tenantId);
        expirePolicyApprovals(tenantId);
        return jdbc.query("""
                SELECT revision.audit_policy_revision_id, revision.revision_number,
                       revision.lifecycle_state, revision.standard_retention_days,
                       revision.extended_retention_days, revision.export_limit_rows,
                       revision.require_export_reason, revision.integrity_enabled,
                       revision.high_risk_threshold, revision.baseline_revision_id,
                       revision.rollback_of_revision_id, revision.incident_case_id,
                       revision.change_reason, revision.diff_data::text,
                       revision.content_sha256, revision.created_by, revision.created_at,
                       revision.submitted_by, revision.submitted_at,
                       revision.published_by, revision.published_at, revision.version,
                       approval.audit_policy_approval_id, approval.lifecycle_state AS approval_state,
                       approval.requested_by, approval.requested_at, approval.expires_at,
                       approval.decided_by, approval.decided_at, approval.decision_reason,
                       approval.version AS approval_version,
                       revision.impact_snapshot::text, revision.impact_sha256
                  FROM sys_audit_policy_revisions revision
                 LEFT JOIN sys_audit_policy_approvals approval
                    ON approval.audit_policy_revision_id = revision.audit_policy_revision_id
                 WHERE revision.tenant_id = :tenantId
                 ORDER BY CASE WHEN revision.lifecycle_state IN (
                                      'DRAFT', 'IN_REVIEW', 'APPROVED')
                               THEN 0 ELSE 1 END,
                          revision.revision_number DESC,
                          revision.audit_policy_revision_id DESC
                 LIMIT :fetchLimit
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("fetchLimit", fetchLimit), policyRevisionMapper());
    }

    public Optional<AuditControlDtos.PolicyRevision> policyRevision(Long tenantId, UUID revisionId) {
        policy(tenantId);
        expirePolicyApprovals(tenantId);
        return jdbc.query("""
                SELECT revision.audit_policy_revision_id, revision.revision_number,
                       revision.lifecycle_state, revision.standard_retention_days,
                       revision.extended_retention_days, revision.export_limit_rows,
                       revision.require_export_reason, revision.integrity_enabled,
                       revision.high_risk_threshold, revision.baseline_revision_id,
                       revision.rollback_of_revision_id, revision.incident_case_id,
                       revision.change_reason, revision.diff_data::text,
                       revision.content_sha256, revision.created_by, revision.created_at,
                       revision.submitted_by, revision.submitted_at,
                       revision.published_by, revision.published_at, revision.version,
                       approval.audit_policy_approval_id, approval.lifecycle_state AS approval_state,
                       approval.requested_by, approval.requested_at, approval.expires_at,
                       approval.decided_by, approval.decided_at, approval.decision_reason,
                       approval.version AS approval_version,
                       revision.impact_snapshot::text, revision.impact_sha256
                  FROM sys_audit_policy_revisions revision
                  LEFT JOIN sys_audit_policy_approvals approval
                    ON approval.audit_policy_revision_id = revision.audit_policy_revision_id
                 WHERE revision.tenant_id = :tenantId
                   AND revision.audit_policy_revision_id = :revisionId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("revisionId", revisionId), policyRevisionMapper()).stream().findFirst();
    }

    public UUID createPolicyRevision(
            Long tenantId,
            String actorId,
            AuditControlDtos.PolicyRevisionCreate request,
            UUID baselineRevisionId,
            UUID rollbackOfRevisionId,
            Map<String, Object> diff,
            String contentSha256,
            AuditControlDtos.PolicyImpactSnapshot impactSnapshot) {
        lockPolicy(tenantId);
        return jdbc.queryForObject("""
                INSERT INTO sys_audit_policy_revisions (
                    tenant_id, revision_number, lifecycle_state,
                    standard_retention_days, extended_retention_days,
                    export_limit_rows, require_export_reason, integrity_enabled,
                    high_risk_threshold, baseline_revision_id, rollback_of_revision_id,
                    incident_case_id, change_reason, diff_data, content_sha256,
                    impact_snapshot, impact_sha256, created_by)
                SELECT :tenantId, COALESCE(MAX(revision_number), 0) + 1, 'DRAFT',
                       :standardDays, :extendedDays, :exportLimit, :requireReason,
                       :integrityEnabled, :threshold, :baselineRevisionId,
                       :rollbackOfRevisionId, :incidentCaseId, :changeReason,
                       CAST(:diff AS jsonb), :contentSha256,
                       CAST(:impactSnapshot AS jsonb),
                       encode(digest(CAST(:impactSnapshot AS jsonb)::TEXT, 'sha256'), 'hex'),
                       :actor
                  FROM sys_audit_policy_revisions
                 WHERE tenant_id = :tenantId
                RETURNING audit_policy_revision_id
                """, policyRevisionParameters(tenantId, request)
                .addValue("baselineRevisionId", baselineRevisionId)
                .addValue("rollbackOfRevisionId", rollbackOfRevisionId)
                .addValue("diff", json(diff))
                .addValue("contentSha256", contentSha256)
                .addValue("impactSnapshot", json(impactSnapshot))
                .addValue("actor", actorId), UUID.class);
    }

    public AuditControlDtos.PolicyImpactSnapshot policyImpactSnapshot(
            Long tenantId,
            AuditControlDtos.RetentionPolicy active,
            AuditControlDtos.PolicyRevisionCreate requested,
            Instant observedAt) {
        Instant standardBefore = observedAt.minus(
                Duration.ofDays(active.standardRetentionDays()));
        Instant standardAfter = observedAt.minus(
                Duration.ofDays(requested.standardRetentionDays()));
        Instant extendedBefore = observedAt.minus(
                Duration.ofDays(active.extendedRetentionDays()));
        Instant extendedAfter = observedAt.minus(
                Duration.ofDays(requested.extendedRetentionDays()));
        boolean integrityChanged = active.integrityEnabled() != requested.integrityEnabled();
        // affectedAuditEventCount is the union of event-level retention, risk, and integrity
        // changes. Export limits expose before/after counts separately because the exact rows
        // displaced by a limit depend on the eventual export filter and ordering.
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenantId", tenantId)
                .addValue("observedAt", Timestamp.from(observedAt))
                .addValue("standardBefore", Timestamp.from(standardBefore))
                .addValue("standardAfter", Timestamp.from(standardAfter))
                .addValue("extendedBefore", Timestamp.from(extendedBefore))
                .addValue("extendedAfter", Timestamp.from(extendedAfter))
                .addValue("riskBefore", active.highRiskThreshold())
                .addValue("riskAfter", requested.highRiskThreshold())
                .addValue("integrityChanged", integrityChanged);
        PopulationImpact population = jdbc.queryForObject("""
                WITH population AS (
                    SELECT event.*,
                           (
                               (event.retention_class = 'STANDARD'
                                AND ((event.occurred_at >= :standardBefore)
                                     <> (event.occurred_at >= :standardAfter)))
                               OR (event.retention_class = 'EXTENDED'
                                   AND ((event.occurred_at >= :extendedBefore)
                                        <> (event.occurred_at >= :extendedAfter)))
                               OR ((event.risk_score >= :riskBefore)
                                   <> (event.risk_score >= :riskAfter))
                               OR CAST(:integrityChanged AS boolean)
                           ) AS affected
                      FROM sys_audit_events event
                     WHERE event.tenant_id = :tenantId
                       AND event.occurred_at <= :observedAt
                )
                SELECT COUNT(*) AS audit_event_count,
                       COUNT(*) FILTER (WHERE affected) AS affected_event_count,
                       COUNT(DISTINCT (actor_type, actor_id))
                           FILTER (WHERE affected AND actor_id IS NOT NULL) AS affected_actor_count,
                       COUNT(DISTINCT (target_type, target_id))
                           FILTER (WHERE affected) AS affected_target_count,
                       COUNT(*) FILTER (WHERE retention_class = 'STANDARD')
                           AS standard_event_count,
                       COUNT(*) FILTER (WHERE retention_class = 'EXTENDED')
                           AS extended_event_count,
                       COUNT(*) FILTER (WHERE retention_class = 'LEGAL_HOLD')
                           AS legal_hold_event_count,
                       COUNT(*) FILTER (
                           WHERE retention_class = 'STANDARD'
                             AND ((occurred_at >= :standardBefore)
                                  <> (occurred_at >= :standardAfter)))
                           AS standard_affected_count,
                       COUNT(*) FILTER (
                           WHERE retention_class = 'EXTENDED'
                             AND ((occurred_at >= :extendedBefore)
                                  <> (occurred_at >= :extendedAfter)))
                           AS extended_affected_count,
                       COUNT(*) FILTER (
                           WHERE (risk_score >= :riskBefore)
                              <> (risk_score >= :riskAfter))
                           AS risk_affected_count
                  FROM population
                """, parameters, (result, ignored) -> new PopulationImpact(
                result.getLong("audit_event_count"),
                result.getLong("affected_event_count"),
                result.getLong("affected_actor_count"),
                result.getLong("affected_target_count"),
                result.getLong("standard_event_count"),
                result.getLong("extended_event_count"),
                result.getLong("legal_hold_event_count"),
                result.getLong("standard_affected_count"),
                result.getLong("extended_affected_count"),
                result.getLong("risk_affected_count")));
        long eventCount = population == null ? 0 : population.auditEventCount();
        return new AuditControlDtos.PolicyImpactSnapshot(
                observedAt,
                "COMPLETE_INTERNAL_AUDIT_EVENT_OWNER",
                List.of("PLATFORM_SYS_AUDIT_EVENTS"),
                List.of(
                        "EXTERNAL_PRODUCT_DATA_RETENTION",
                        "EXTERNAL_SHARING_POPULATIONS",
                        "EXTERNAL_LEGAL_HOLD_POPULATIONS",
                        "FILTER_SPECIFIC_EXPORT_POPULATIONS"),
                eventCount,
                population == null ? 0 : population.affectedAuditEventCount(),
                population == null ? 0 : population.affectedActorCount(),
                population == null ? 0 : population.affectedTargetCount(),
                population == null ? 0 : population.standardRetentionEventCount(),
                population == null ? 0 : population.extendedRetentionEventCount(),
                population == null ? 0 : population.legalHoldEventCount(),
                population == null ? 0 : population.standardRetentionAffectedEventCount(),
                population == null ? 0 : population.extendedRetentionAffectedEventCount(),
                population == null ? 0 : population.highRiskClassificationAffectedEventCount(),
                Math.min(eventCount, active.exportLimitRows()),
                Math.min(eventCount, requested.exportLimitRows()),
                active.integrityEnabled() ? eventCount : 0,
                requested.integrityEnabled() ? eventCount : 0);
    }

    public boolean submitPolicyRevision(
            Long tenantId, UUID revisionId, String actorId, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE sys_audit_policy_revisions
                   SET lifecycle_state = 'IN_REVIEW', submitted_by = :actor,
                       submitted_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = :tenantId AND audit_policy_revision_id = :revisionId
                   AND lifecycle_state = 'DRAFT' AND version = :version
                """, revisionParameters(tenantId, revisionId, expectedVersion)
                .addValue("actor", actorId));
        if (updated != 1) return false;
        jdbc.update("""
                INSERT INTO sys_audit_policy_approvals (
                    audit_policy_revision_id, tenant_id, requested_by)
                VALUES (:revisionId, :tenantId, :actor)
                """, revisionParameters(tenantId, revisionId, expectedVersion)
                .addValue("actor", actorId));
        return true;
    }

    public boolean decidePolicyRevision(
            Long tenantId,
            UUID revisionId,
            UUID approvalId,
            String actorId,
            String decision,
            String reason,
            long expectedApprovalVersion) {
        int approvalUpdated = jdbc.update("""
                UPDATE sys_audit_policy_approvals
                   SET lifecycle_state = :decision, decided_by = :actor,
                       decided_at = CURRENT_TIMESTAMP, decision_reason = :reason,
                       version = version + 1
                 WHERE tenant_id = :tenantId
                   AND audit_policy_revision_id = :revisionId
                   AND audit_policy_approval_id = :approvalId
                   AND lifecycle_state = 'PENDING' AND expires_at > CURRENT_TIMESTAMP
                   AND requested_by <> :actor AND version = :version
                """, revisionParameters(tenantId, revisionId, expectedApprovalVersion)
                .addValue("approvalId", approvalId).addValue("actor", actorId)
                .addValue("decision", decision).addValue("reason", reason));
        if (approvalUpdated != 1) return false;
        int revisionUpdated = jdbc.update("""
                UPDATE sys_audit_policy_revisions
                   SET lifecycle_state = :decision, version = version + 1
                 WHERE tenant_id = :tenantId AND audit_policy_revision_id = :revisionId
                   AND lifecycle_state = 'IN_REVIEW'
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("revisionId", revisionId).addValue("decision", decision));
        if (revisionUpdated != 1) {
            throw new IllegalStateException("Audit policy approval lost revision consistency.");
        }
        return true;
    }

    public boolean publishPolicyRevision(
            Long tenantId, UUID revisionId, String actorId, long expectedVersion) {
        lockPolicy(tenantId);
        int revisionUpdated = jdbc.update("""
                UPDATE sys_audit_policy_revisions revision
                   SET lifecycle_state = 'PUBLISHED', published_by = :actor,
                       published_at = CURRENT_TIMESTAMP, version = version + 1
                  FROM sys_audit_retention_policies policy
                 WHERE revision.tenant_id = :tenantId
                   AND revision.audit_policy_revision_id = :revisionId
                   AND revision.lifecycle_state = 'APPROVED'
                   AND revision.version = :version
                   AND policy.tenant_id = revision.tenant_id
                   AND policy.active_revision_id = revision.baseline_revision_id
                """, revisionParameters(tenantId, revisionId, expectedVersion)
                .addValue("actor", actorId));
        if (revisionUpdated != 1) return false;
        jdbc.update("""
                UPDATE sys_audit_policy_revisions
                   SET lifecycle_state = 'SUPERSEDED', version = version + 1
                 WHERE tenant_id = :tenantId AND lifecycle_state = 'PUBLISHED'
                   AND audit_policy_revision_id <> :revisionId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("revisionId", revisionId));
        int activated = jdbc.update("""
                UPDATE sys_audit_retention_policies policy
                   SET standard_retention_days = revision.standard_retention_days,
                       extended_retention_days = revision.extended_retention_days,
                       export_limit_rows = revision.export_limit_rows,
                       require_export_reason = revision.require_export_reason,
                       integrity_enabled = revision.integrity_enabled,
                       high_risk_threshold = revision.high_risk_threshold,
                       active_revision_id = revision.audit_policy_revision_id,
                       active_revision_number = revision.revision_number,
                       updated_by = :actor, updated_at = CURRENT_TIMESTAMP
                  FROM sys_audit_policy_revisions revision
                 WHERE policy.tenant_id = :tenantId
                   AND revision.tenant_id = policy.tenant_id
                   AND revision.audit_policy_revision_id = :revisionId
                   AND revision.lifecycle_state = 'PUBLISHED'
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("revisionId", revisionId).addValue("actor", actorId));
        if (activated != 1) {
            throw new IllegalStateException("Audit policy publication lost active-policy consistency.");
        }
        return true;
    }

    private void ensurePolicyBaseline(
            Long tenantId, AuditControlDtos.RetentionPolicy current) {
        MapSqlParameterSource tenant = new MapSqlParameterSource("tenantId", tenantId);
        boolean hasRevision = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM sys_audit_policy_revisions
                     WHERE tenant_id = :tenantId)
                """, tenant, Boolean.class));
        if (!hasRevision) {
            String baseline = jdbc.queryForObject("""
                    SELECT concat_ws('|', standard_retention_days, extended_retention_days,
                                      export_limit_rows, require_export_reason,
                                      integrity_enabled, high_risk_threshold)
                      FROM sys_audit_retention_policies
                     WHERE tenant_id = :tenantId
                    """, tenant, String.class);
            AuditControlDtos.PolicyRevisionCreate baselineRequest =
                    new AuditControlDtos.PolicyRevisionCreate(
                            current.standardRetentionDays(), current.extendedRetentionDays(),
                            current.exportLimitRows(), current.requireExportReason(),
                            current.integrityEnabled(), current.highRiskThreshold(),
                            "Initial governed baseline", null);
            AuditControlDtos.PolicyImpactSnapshot impact = policyImpactSnapshot(
                    tenantId, current, baselineRequest, Instant.now());
            String impactJson = json(impact);
            jdbc.update("""
                    INSERT INTO sys_audit_policy_revisions (
                        tenant_id, revision_number, lifecycle_state,
                        standard_retention_days, extended_retention_days,
                        export_limit_rows, require_export_reason, integrity_enabled,
                        high_risk_threshold, change_reason, diff_data, content_sha256,
                        impact_snapshot, impact_sha256,
                        created_by, published_by, published_at)
                    SELECT policy.tenant_id, 1, 'PUBLISHED',
                           policy.standard_retention_days, policy.extended_retention_days,
                           policy.export_limit_rows, policy.require_export_reason,
                           policy.integrity_enabled, policy.high_risk_threshold,
                           'Initial governed baseline', '{}'::jsonb,
                           :contentSha256, CAST(:impactSnapshot AS jsonb),
                           encode(digest(CAST(:impactSnapshot AS jsonb)::TEXT, 'sha256'), 'hex'),
                           COALESCE(policy.updated_by, 'SYSTEM'),
                           COALESCE(policy.updated_by, 'SYSTEM'), policy.updated_at
                      FROM sys_audit_retention_policies policy
                     WHERE policy.tenant_id = :tenantId
                       AND NOT EXISTS (
                           SELECT 1 FROM sys_audit_policy_revisions revision
                            WHERE revision.tenant_id = policy.tenant_id)
                    ON CONFLICT (tenant_id, revision_number) DO NOTHING
                    """, tenant
                    .addValue("contentSha256", sha256(baseline))
                    .addValue("impactSnapshot", impactJson));
        }
        jdbc.update("""
                UPDATE sys_audit_retention_policies policy
                   SET active_revision_id = revision.audit_policy_revision_id,
                       active_revision_number = revision.revision_number
                  FROM sys_audit_policy_revisions revision
                 WHERE policy.tenant_id = :tenantId
                   AND policy.active_revision_id IS NULL
                   AND revision.tenant_id = policy.tenant_id
                   AND revision.lifecycle_state = 'PUBLISHED'
                """, tenant);
    }

    private void expirePolicyApprovals(Long tenantId) {
        jdbc.update("""
                UPDATE sys_audit_policy_approvals
                   SET lifecycle_state = 'EXPIRED', version = version + 1
                 WHERE tenant_id = :tenantId AND lifecycle_state = 'PENDING'
                   AND expires_at <= CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("tenantId", tenantId));
        jdbc.update("""
                UPDATE sys_audit_policy_revisions revision
                   SET lifecycle_state = 'CANCELLED', version = version + 1
                 WHERE revision.tenant_id = :tenantId
                   AND revision.lifecycle_state = 'IN_REVIEW'
                   AND EXISTS (
                       SELECT 1 FROM sys_audit_policy_approvals approval
                        WHERE approval.audit_policy_revision_id = revision.audit_policy_revision_id
                          AND approval.lifecycle_state = 'EXPIRED')
                """, new MapSqlParameterSource("tenantId", tenantId));
    }

    private void lockPolicy(Long tenantId) {
        jdbc.queryForObject("""
                SELECT tenant_id FROM sys_audit_retention_policies
                 WHERE tenant_id = :tenantId FOR UPDATE
                """, new MapSqlParameterSource("tenantId", tenantId), Long.class);
    }

    private void ensurePolicyRow(Long tenantId) {
        jdbc.update("""
                INSERT INTO sys_audit_retention_policies (tenant_id)
                VALUES (:tenantId) ON CONFLICT (tenant_id) DO NOTHING
                """, new MapSqlParameterSource("tenantId", tenantId));
    }

    private MapSqlParameterSource policyRevisionParameters(
            Long tenantId, AuditControlDtos.PolicyRevisionCreate request) {
        return new MapSqlParameterSource("tenantId", tenantId)
                .addValue("standardDays", request.standardRetentionDays())
                .addValue("extendedDays", request.extendedRetentionDays())
                .addValue("exportLimit", request.exportLimitRows())
                .addValue("requireReason", request.requireExportReason())
                .addValue("integrityEnabled", request.integrityEnabled())
                .addValue("threshold", request.highRiskThreshold())
                .addValue("incidentCaseId", request.incidentCaseId())
                .addValue("changeReason", request.reason().trim());
    }

    private MapSqlParameterSource revisionParameters(
            Long tenantId, UUID revisionId, long version) {
        return new MapSqlParameterSource("tenantId", tenantId)
                .addValue("revisionId", revisionId)
                .addValue("version", version);
    }

    private record PopulationImpact(
            long auditEventCount,
            long affectedAuditEventCount,
            long affectedActorCount,
            long affectedTargetCount,
            long standardRetentionEventCount,
            long extendedRetentionEventCount,
            long legalHoldEventCount,
            long standardRetentionAffectedEventCount,
            long extendedRetentionAffectedEventCount,
            long highRiskClassificationAffectedEventCount) {
    }

}
