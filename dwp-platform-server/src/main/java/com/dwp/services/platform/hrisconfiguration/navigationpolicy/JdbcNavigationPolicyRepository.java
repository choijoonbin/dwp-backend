package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;

@Repository
class JdbcNavigationPolicyRepository implements NavigationPolicyRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcNavigationPolicyRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void lockIdempotency(long tenantId, long subjectId, String action, String idempotencyKey) {
        bindTenant(tenantId);
        jdbc.query("SELECT pg_advisory_xact_lock(?)", ignored -> null,
                advisoryKey(tenantId, subjectId, action, idempotencyKey));
    }

    @Override
    public Optional<StoredReceipt> findReceipt(
            long tenantId, long subjectId, String action, String idempotencyKey) {
        bindTenant(tenantId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT request_digest, receipt_payload
                      FROM hris_navigation_policy_command_receipts
                     WHERE tenant_id = ? AND subject_id = ?
                       AND originating_action = ? AND idempotency_key = ?
                    """, (rs, row) -> new StoredReceipt(
                    rs.getString("request_digest"),
                    read(rs.getString("receipt_payload"), CommandReceipt.class)),
                    tenantId, subjectId, action, idempotencyKey));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public void saveReceipt(
            long tenantId, long subjectId, String action, String idempotencyKey,
            String requestDigest, CommandReceipt receipt) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_command_receipts (
                    tenant_id, receipt_id, subject_id, originating_action, idempotency_key,
                    request_digest, policy_id, policy_revision, revision_id,
                    receipt_payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, tenantId, receipt.receiptId(), subjectId, action, idempotencyKey,
                requestDigest, receipt.policyId(), receipt.revisionNumber(), receipt.revisionId(),
                write(receipt), receipt.createdAt());
    }

    @Override
    public RevisionView createDraft(
            long tenantId, UUID candidatePolicyId, UUID revisionId, long actorId,
            PolicyDocument policy, String payloadDigest, OffsetDateTime now) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_heads (
                    tenant_id, policy_id, scope_kind, legal_entity_public_id,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, scope_kind, legal_entity_public_id)
                    DO NOTHING
                """, tenantId, candidatePolicyId, policy.scopeKind().name(),
                policy.legalEntityPublicId(), actorId, now, now);
        UUID policyId = jdbc.queryForObject("""
                SELECT policy_id FROM hris_navigation_policy_heads
                 WHERE tenant_id = ? AND scope_kind = ?
                   AND legal_entity_public_id IS NOT DISTINCT FROM ?
                 FOR UPDATE
                """, UUID.class, tenantId, policy.scopeKind().name(), policy.legalEntityPublicId());
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                  FROM hris_navigation_policy_revisions
                 WHERE tenant_id = ? AND policy_id = ?
                """, Long.class, tenantId, policyId);
        long revisionNumber = next == null ? 1 : next;
        Long supersedesRevision = effectiveRevision(tenantId, policyId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_revisions (
                    tenant_id, policy_id, revision, revision_id, lifecycle_state,
                    rollout_mode, workbench_id, policy_payload, payload_digest,
                    aggregate_version, authored_by, supersedes_revision,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, CAST(? AS jsonb), ?, 0, ?, ?, ?, ?)
                """, tenantId, policyId, revisionNumber, revisionId,
                policy.rolloutMode().name(), policy.workbenchId(), write(policy),
                payloadDigest, actorId, supersedesRevision, now, now);
        return findByRevisionId(tenantId, revisionId).orElseThrow();
    }

    @Override
    public Optional<RevisionView> findByRevisionId(long tenantId, UUID revisionId) {
        bindTenant(tenantId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT r.* FROM hris_navigation_policy_revisions r
                     WHERE r.tenant_id = ? AND r.revision_id = ?
                    """, (rs, row) -> revision(rs), tenantId, revisionId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RevisionView> findLatestDraft(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId) {
        bindTenant(tenantId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT r.*
                      FROM hris_navigation_policy_revisions r
                      JOIN hris_navigation_policy_heads h
                        ON h.tenant_id = r.tenant_id AND h.policy_id = r.policy_id
                     WHERE h.tenant_id = ? AND h.scope_kind = ?
                       AND h.legal_entity_public_id IS NOT DISTINCT FROM ?
                       AND r.lifecycle_state IN ('DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'APPROVED')
                     ORDER BY r.revision DESC LIMIT 1
                    """, (rs, row) -> revision(rs), tenantId, scopeKind.name(), legalEntityPublicId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RevisionView> findPublished(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId) {
        bindTenant(tenantId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT r.*
                      FROM hris_navigation_policy_effective e
                      JOIN hris_navigation_policy_revisions r
                        ON r.tenant_id = e.tenant_id AND r.policy_id = e.policy_id
                       AND r.revision = e.revision
                     WHERE e.tenant_id = ? AND e.scope_kind = ?
                       AND e.legal_entity_public_id IS NOT DISTINCT FROM ?
                    """, (rs, row) -> revision(rs), tenantId, scopeKind.name(), legalEntityPublicId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public List<RevisionView> findPublishedHistory(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT r.*
                  FROM hris_navigation_policy_revisions r
                  JOIN hris_navigation_policy_heads h
                    ON h.tenant_id = r.tenant_id AND h.policy_id = r.policy_id
                 WHERE h.tenant_id = ? AND h.scope_kind = ?
                   AND h.legal_entity_public_id IS NOT DISTINCT FROM ?
                   AND r.lifecycle_state = 'PUBLISHED'
                 ORDER BY r.revision DESC
                 LIMIT 50
                """, (rs, row) -> revision(rs),
                tenantId, scopeKind.name(), legalEntityPublicId);
    }

    @Override
    public boolean updateDraft(
            long tenantId, UUID revisionId, long expectedVersion,
            PolicyDocument policy, String payloadDigest, OffsetDateTime now) {
        bindTenant(tenantId);
        return jdbc.update("""
                UPDATE hris_navigation_policy_revisions
                   SET rollout_mode = ?, policy_payload = CAST(? AS jsonb), payload_digest = ?,
                       aggregate_version = aggregate_version + 1, updated_at = ?
                 WHERE tenant_id = ? AND revision_id = ?
                   AND lifecycle_state = 'DRAFT' AND aggregate_version = ?
                """, policy.rolloutMode().name(), write(policy), payloadDigest, now,
                tenantId, revisionId, expectedVersion) == 1;
    }

    @Override
    public boolean transition(
            long tenantId, UUID revisionId, long expectedVersion,
            LifecycleState expectedState, LifecycleState targetState,
            long actorId, String evidenceDigest, OffsetDateTime now) {
        bindTenant(tenantId);
        String evidence = switch (targetState) {
            case VALIDATED -> "validation_digest = ?, validated_at = ?,";
            case PENDING_APPROVAL -> "submission_digest = ?, submitted_at = ?,";
            case APPROVED -> "approval_digest = ?, approved_at = ?, approved_by = ?,";
            case PUBLISHED -> "published_at = ?,";
            default -> throw new IllegalArgumentException("unsupported target state");
        };
        String sql = "UPDATE hris_navigation_policy_revisions SET lifecycle_state = ?, "
                + evidence + " aggregate_version = aggregate_version + 1, updated_at = ? "
                + "WHERE tenant_id = ? AND revision_id = ? AND lifecycle_state = ? "
                + "AND aggregate_version = ?";
        Object[] args = switch (targetState) {
            case VALIDATED, PENDING_APPROVAL -> new Object[]{
                    targetState.name(), evidenceDigest, now, now,
                    tenantId, revisionId, expectedState.name(), expectedVersion};
            case APPROVED -> new Object[]{
                    targetState.name(), evidenceDigest, now, actorId, now,
                    tenantId, revisionId, expectedState.name(), expectedVersion};
            case PUBLISHED -> new Object[]{
                    targetState.name(), now, now,
                    tenantId, revisionId, expectedState.name(), expectedVersion};
            default -> throw new IllegalArgumentException("unsupported target state");
        };
        return jdbc.update(sql, args) == 1;
    }

    @Override
    public boolean publish(
            long tenantId, UUID revisionId, long expectedVersion,
            long actorId, String evidenceDigest, OffsetDateTime now) {
        bindTenant(tenantId);
        UUID policyId;
        try {
            policyId = jdbc.queryForObject("""
                    SELECT h.policy_id
                      FROM hris_navigation_policy_heads h
                      JOIN hris_navigation_policy_revisions r
                        ON r.tenant_id = h.tenant_id AND r.policy_id = h.policy_id
                     WHERE r.tenant_id = ? AND r.revision_id = ?
                     FOR UPDATE OF h
                    """, UUID.class, tenantId, revisionId);
        } catch (EmptyResultDataAccessException ignored) {
            return false;
        }
        Long candidateSupersedes = jdbc.queryForObject("""
                SELECT supersedes_revision
                  FROM hris_navigation_policy_revisions
                 WHERE tenant_id = ? AND revision_id = ?
                """, (rs, row) -> nullableLong(rs, "supersedes_revision"),
                tenantId, revisionId);
        Long currentEffective = effectiveRevision(tenantId, policyId);
        if (!Objects.equals(candidateSupersedes, currentEffective)) {
            return false;
        }
        return jdbc.update("""
                UPDATE hris_navigation_policy_revisions
                   SET lifecycle_state = 'PUBLISHED', published_at = ?,
                       aggregate_version = aggregate_version + 1, updated_at = ?
                 WHERE tenant_id = ? AND revision_id = ?
                   AND lifecycle_state = 'APPROVED' AND aggregate_version = ?
                """, now, now, tenantId, revisionId, expectedVersion) == 1;
    }

    @Override
    public RevisionView restore(
            long tenantId, UUID sourceRevisionId, UUID newRevisionId,
            long expectedCurrentVersion, long actorId, String evidenceDigest,
            OffsetDateTime now) {
        bindTenant(tenantId);
        RevisionView source = findByRevisionId(tenantId, sourceRevisionId).orElseThrow();
        if (source.lifecycleState() != LifecycleState.PUBLISHED) {
            throw new IllegalStateException("HRIS_NAV_POLICY_RESTORE_SOURCE_NOT_PUBLISHED");
        }
        jdbc.queryForObject("""
                SELECT policy_id FROM hris_navigation_policy_heads
                 WHERE tenant_id = ? AND policy_id = ? FOR UPDATE
                """, UUID.class, tenantId, source.policyId());
        RevisionView current = findPublished(
                tenantId, source.policy().scopeKind(), source.policy().legalEntityPublicId())
                .orElseThrow();
        if (current.objectVersion() != expectedCurrentVersion) {
            throw new IllegalStateException("HRIS_NAV_POLICY_OBJECT_VERSION_CONFLICT");
        }
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                  FROM hris_navigation_policy_revisions
                 WHERE tenant_id = ? AND policy_id = ?
                """, Long.class, tenantId, source.policyId());
        long revisionNumber = next == null ? 1 : next;
        jdbc.update("""
                INSERT INTO hris_navigation_policy_revisions (
                    tenant_id, policy_id, revision, revision_id, lifecycle_state,
                    rollout_mode, workbench_id, policy_payload, payload_digest,
                    aggregate_version, authored_by, validated_at, validation_digest,
                    submitted_at, submission_digest, approved_by, approved_at,
                    approval_digest, published_at, restored_from_revision,
                    restored_from_digest, supersedes_revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?, CAST(? AS jsonb), ?, 1, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, source.policyId(), revisionNumber, newRevisionId,
                source.rolloutMode().name(), source.policy().workbenchId(), write(source.policy()),
                source.payloadDigest(), source.authoredBy(), now, evidenceDigest,
                now, evidenceDigest, actorId, now, evidenceDigest, now,
                source.revisionNumber(), source.payloadDigest(), current.revisionNumber(), now, now);
        return findByRevisionId(tenantId, newRevisionId).orElseThrow();
    }

    @Override
    public void activate(long tenantId, RevisionView revision, OffsetDateTime now) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_effective (
                    tenant_id, effective_id, scope_kind, legal_entity_public_id, policy_id,
                    revision, revision_id, payload_digest, rollout_mode, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, scope_kind, legal_entity_public_id)
                    DO UPDATE
                    SET policy_id = EXCLUDED.policy_id, revision = EXCLUDED.revision,
                        revision_id = EXCLUDED.revision_id,
                        payload_digest = EXCLUDED.payload_digest,
                        rollout_mode = EXCLUDED.rollout_mode,
                        published_at = EXCLUDED.published_at
                """, tenantId, UUID.randomUUID(), revision.policy().scopeKind().name(),
                revision.policy().legalEntityPublicId(), revision.policyId(),
                revision.revisionNumber(), revision.revisionId(), revision.payloadDigest(),
                revision.rolloutMode().name(), now);
    }

    @Override
    public void appendAudit(
            long tenantId, RevisionView revision, String action, long actorId,
            UUID correlationId, String evidenceDigest, OffsetDateTime now) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_audit (
                    tenant_id, audit_id, policy_id, policy_revision, action,
                    actor_id, correlation_id, evidence_digest, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, UUID.randomUUID(), revision.policyId(),
                revision.revisionNumber(), action, actorId, correlationId, evidenceDigest, now);
    }

    @Override
    public void appendOutbox(
            long tenantId, RevisionView revision, String payload, OffsetDateTime now) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_outbox (
                    tenant_id, event_id, policy_id, policy_revision,
                    event_type, payload, created_at)
                VALUES (?, ?, ?, ?, 'dwp.hris.navigation-policy.published.v1',
                        CAST(? AS jsonb), ?)
                """, tenantId, UUID.randomUUID(), revision.policyId(),
                revision.revisionNumber(), payload, now);
    }

    private RevisionView revision(ResultSet rs) throws SQLException {
        return new RevisionView(
                rs.getObject("policy_id", UUID.class),
                rs.getObject("revision_id", UUID.class),
                rs.getLong("revision"),
                LifecycleState.valueOf(rs.getString("lifecycle_state")),
                RolloutMode.valueOf(rs.getString("rollout_mode")),
                read(rs.getString("policy_payload"), PolicyDocument.class),
                rs.getString("payload_digest"),
                rs.getLong("aggregate_version"),
                rs.getLong("authored_by"),
                nullableLong(rs, "approved_by"),
                nullableLong(rs, "restored_from_revision"),
                rs.getString("restored_from_digest"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Long effectiveRevision(long tenantId, UUID policyId) {
        try {
            return jdbc.queryForObject("""
                    SELECT revision
                      FROM hris_navigation_policy_effective
                     WHERE tenant_id = ? AND policy_id = ?
                    """, Long.class, tenantId, policyId);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    private void bindTenant(long tenantId) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                String.class, Long.toString(tenantId));
    }

    private long advisoryKey(long tenantId, long subjectId, String action, String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (tenantId + ":" + subjectId + ":" + action + ":" + key)
                            .getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRIS_NAV_POLICY_SERIALIZATION_FAILED", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRIS_NAV_POLICY_DESERIALIZATION_FAILED", exception);
        }
    }
}
