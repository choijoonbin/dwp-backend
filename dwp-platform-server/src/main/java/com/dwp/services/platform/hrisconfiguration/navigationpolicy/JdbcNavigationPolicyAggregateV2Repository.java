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

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;

@Repository
class JdbcNavigationPolicyAggregateV2Repository
        implements NavigationPolicyAggregateV2Repository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcNavigationPolicyAggregateV2Repository(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void lockIdempotency(
            long tenantId, long subjectId, String action, String idempotencyKey) {
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
                      FROM hris_navigation_policy_v2_command_receipts
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
    public boolean canonicalReceiptExists(
            long tenantId, UUID subjectPrincipalPublicId, UUID receiptId) {
        bindPrincipal(tenantId, subjectPrincipalPublicId);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND subject_principal_public_id = ? AND public_id = ?
                """, Integer.class, tenantId, subjectPrincipalPublicId, receiptId);
        return count != null && count == 1;
    }

    @Override
    public void saveReceipt(
            long tenantId, long subjectId, String action, String idempotencyKey,
            String requestDigest, CommandReceipt receipt) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_v2_command_receipts (
                    tenant_id, receipt_id, subject_id, originating_action, idempotency_key,
                    request_digest, policy_id, policy_revision, revision_id,
                    payload_digest, receipt_payload, created_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, r.payload_digest, CAST(? AS jsonb), ?
                  FROM hris_navigation_policy_v2_revisions r
                 WHERE r.tenant_id = ? AND r.policy_id = ? AND r.revision = ?
                   AND r.revision_id = ?
                """, tenantId, receipt.receiptId(), subjectId, action, idempotencyKey,
                requestDigest, receipt.policyId(), receipt.revisionNumber(),
                receipt.revisionId(), write(receipt), receipt.createdAt(), tenantId,
                receipt.policyId(), receipt.revisionNumber(), receipt.revisionId());
    }

    @Override
    public void saveCanonicalReceipt(
            long tenantId, NavigationPolicyRepository.CanonicalReceipt receipt) {
        bindPrincipal(tenantId, receipt.subjectPrincipalPublicId());
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    command_receipt_id, public_id, tenant_id, command_type,
                    originating_action, subject_principal_public_id,
                    population_scope_digest, authority_population_scope_digest,
                    field_policy_revision, authority_field_policy_revision, purpose_code,
                    authorization_revision, authority_authorization_revision,
                    origin_route_contract_key, origin_context_key, origin_context_scope_key,
                    origin_decision_revision, idempotency_key, request_digest,
                    aggregate_public_id, aggregate_revision, status, result_ref,
                    correlation_id, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receipt.receiptId(), receipt.receiptId(), tenantId,
                receipt.commandType(), receipt.originatingAction(),
                receipt.subjectPrincipalPublicId(), receipt.populationScopeDigest(),
                receipt.authorityPopulationScopeDigest(), receipt.fieldPolicyRevision(),
                receipt.authorityFieldPolicyRevision(), receipt.purposeCode(),
                receipt.authorizationRevision(), receipt.authorityAuthorizationRevision(),
                receipt.originRouteContractKey(), receipt.originContextKey(),
                receipt.originContextScopeKey(), receipt.originDecisionRevision(),
                receipt.idempotencyKey(), receipt.requestDigest(), receipt.aggregatePublicId(),
                receipt.aggregateRevision(), receipt.status(), receipt.resultRef(),
                receipt.correlationId(), receipt.createdAt(), receipt.createdAt());
    }

    @Override
    public AggregateRevisionView createDraft(
            long tenantId, UUID candidatePolicyId, UUID revisionId, long actorId,
            AggregatePolicyDocument policy, String payloadDigest, OffsetDateTime now) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_v2_heads (
                    tenant_id, policy_id, scope_kind, legal_entity_public_id,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, scope_kind, legal_entity_public_id) DO NOTHING
                """, tenantId, candidatePolicyId, policy.scopeKind().name(),
                policy.legalEntityPublicId(), actorId, now, now);
        UUID policyId = jdbc.queryForObject("""
                SELECT policy_id FROM hris_navigation_policy_v2_heads
                 WHERE tenant_id = ? AND scope_kind = ?
                   AND legal_entity_public_id IS NOT DISTINCT FROM ? FOR UPDATE
                """, UUID.class, tenantId, policy.scopeKind().name(),
                policy.legalEntityPublicId());
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                  FROM hris_navigation_policy_v2_revisions
                 WHERE tenant_id = ? AND policy_id = ?
                """, Long.class, tenantId, policyId);
        long revisionNumber = next == null ? 1 : next;
        Long supersedes = effectiveRevision(tenantId, policyId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_v2_revisions (
                    tenant_id, policy_id, revision, revision_id, lifecycle_state,
                    rollout_mode, policy_payload, payload_digest, aggregate_version,
                    authored_by, supersedes_revision, topology_revision, topology_sha256,
                    ia_register_sha256, shell_register_sha256, task_group_register_sha256,
                    generated_ia_sha256, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, CAST(? AS jsonb), ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, policyId, revisionNumber, revisionId,
                policy.rolloutMode().name(), write(policy), payloadDigest, actorId, supersedes,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256,
                NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256, now, now);
        return findByRevisionId(tenantId, revisionId).orElseThrow();
    }

    @Override
    public Optional<AggregateRevisionView> findByRevisionId(
            long tenantId, UUID revisionId) {
        bindTenant(tenantId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT r.* FROM hris_navigation_policy_v2_revisions r
                     WHERE r.tenant_id = ? AND r.revision_id = ?
                    """, (rs, row) -> revision(rs), tenantId, revisionId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AggregateRevisionView> findLatestDraft(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId) {
        bindTenant(tenantId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT r.* FROM hris_navigation_policy_v2_revisions r
                      JOIN hris_navigation_policy_v2_heads h
                        ON h.tenant_id = r.tenant_id AND h.policy_id = r.policy_id
                     WHERE h.tenant_id = ? AND h.scope_kind = ?
                       AND h.legal_entity_public_id IS NOT DISTINCT FROM ?
                       AND r.lifecycle_state IN ('DRAFT','VALIDATED','PENDING_APPROVAL','APPROVED')
                     ORDER BY r.revision DESC LIMIT 1
                    """, (rs, row) -> revision(rs), tenantId, scopeKind.name(),
                    legalEntityPublicId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AggregateRevisionView> findPublishedRevision(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId) {
        bindTenant(tenantId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT r.* FROM hris_navigation_policy_v2_effective e
                      JOIN hris_navigation_policy_v2_revisions r
                        ON r.tenant_id = e.tenant_id AND r.policy_id = e.policy_id
                       AND r.revision = e.revision AND r.revision_id = e.revision_id
                       AND r.payload_digest = e.payload_digest
                     WHERE e.tenant_id = ? AND e.scope_kind = ?
                       AND e.legal_entity_public_id IS NOT DISTINCT FROM ?
                    """, (rs, row) -> revision(rs), tenantId, scopeKind.name(),
                    legalEntityPublicId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public List<AggregateRevisionView> findPublishedHistory(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT r.* FROM hris_navigation_policy_v2_revisions r
                  JOIN hris_navigation_policy_v2_heads h
                    ON h.tenant_id = r.tenant_id AND h.policy_id = r.policy_id
                 WHERE h.tenant_id = ? AND h.scope_kind = ?
                   AND h.legal_entity_public_id IS NOT DISTINCT FROM ?
                   AND r.lifecycle_state = 'PUBLISHED'
                 ORDER BY r.revision DESC LIMIT 50
                """, (rs, row) -> revision(rs), tenantId, scopeKind.name(),
                legalEntityPublicId);
    }

    @Override
    public boolean updateDraft(
            long tenantId, UUID revisionId, long expectedVersion,
            AggregatePolicyDocument policy, String payloadDigest, OffsetDateTime now) {
        bindTenant(tenantId);
        return jdbc.update("""
                UPDATE hris_navigation_policy_v2_revisions
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
            default -> throw new IllegalArgumentException("unsupported target state");
        };
        String sql = "UPDATE hris_navigation_policy_v2_revisions SET lifecycle_state = ?, "
                + evidence + " aggregate_version = aggregate_version + 1, updated_at = ? "
                + "WHERE tenant_id = ? AND revision_id = ? AND lifecycle_state = ? "
                + "AND aggregate_version = ?";
        Object[] args = targetState == LifecycleState.APPROVED
                ? new Object[]{targetState.name(), evidenceDigest, now, actorId, now,
                tenantId, revisionId, expectedState.name(), expectedVersion}
                : new Object[]{targetState.name(), evidenceDigest, now, now,
                tenantId, revisionId, expectedState.name(), expectedVersion};
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
                    SELECT h.policy_id FROM hris_navigation_policy_v2_heads h
                      JOIN hris_navigation_policy_v2_revisions r
                        ON r.tenant_id = h.tenant_id AND r.policy_id = h.policy_id
                     WHERE r.tenant_id = ? AND r.revision_id = ? FOR UPDATE OF h
                    """, UUID.class, tenantId, revisionId);
        } catch (EmptyResultDataAccessException ignored) {
            return false;
        }
        Long candidateSupersedes = jdbc.queryForObject("""
                SELECT supersedes_revision FROM hris_navigation_policy_v2_revisions
                 WHERE tenant_id = ? AND revision_id = ?
                """, (rs, row) -> nullableLong(rs, "supersedes_revision"),
                tenantId, revisionId);
        if (!Objects.equals(candidateSupersedes, effectiveRevision(tenantId, policyId))) {
            return false;
        }
        return jdbc.update("""
                UPDATE hris_navigation_policy_v2_revisions
                   SET lifecycle_state = 'PUBLISHED', published_at = ?,
                       aggregate_version = aggregate_version + 1, updated_at = ?
                 WHERE tenant_id = ? AND revision_id = ?
                   AND lifecycle_state = 'APPROVED' AND aggregate_version = ?
                """, now, now, tenantId, revisionId, expectedVersion) == 1;
    }

    @Override
    public AggregateRevisionView restore(
            long tenantId, UUID sourceRevisionId, UUID newRevisionId,
            long expectedCurrentVersion, long actorId, String evidenceDigest,
            OffsetDateTime now) {
        bindTenant(tenantId);
        AggregateRevisionView source = findByRevisionId(tenantId, sourceRevisionId)
                .orElseThrow();
        if (source.lifecycleState() != LifecycleState.PUBLISHED) {
            throw new IllegalStateException("HRIS_NAV_V2_RESTORE_SOURCE_NOT_PUBLISHED");
        }
        jdbc.queryForObject("""
                SELECT policy_id FROM hris_navigation_policy_v2_heads
                 WHERE tenant_id = ? AND policy_id = ? FOR UPDATE
                """, UUID.class, tenantId, source.policyId());
        AggregateRevisionView current = findPublishedRevision(
                tenantId, source.policy().scopeKind(), source.policy().legalEntityPublicId())
                .orElseThrow();
        if (current.objectVersion() != expectedCurrentVersion) {
            throw new IllegalStateException("HRIS_NAV_V2_OBJECT_VERSION_CONFLICT");
        }
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                  FROM hris_navigation_policy_v2_revisions
                 WHERE tenant_id = ? AND policy_id = ?
                """, Long.class, tenantId, source.policyId());
        long revisionNumber = next == null ? 1 : next;
        jdbc.update("""
                INSERT INTO hris_navigation_policy_v2_revisions (
                    tenant_id, policy_id, revision, revision_id, lifecycle_state,
                    rollout_mode, policy_payload, payload_digest, aggregate_version,
                    authored_by, validated_at, validation_digest, submitted_at,
                    submission_digest, approved_by, approved_at, approval_digest,
                    published_at, restored_from_revision, restored_from_digest,
                    supersedes_revision, topology_revision, topology_sha256,
                    ia_register_sha256, shell_register_sha256, task_group_register_sha256,
                    generated_ia_sha256, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PUBLISHED', ?, CAST(? AS jsonb), ?, 1, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, source.policyId(), revisionNumber, newRevisionId,
                source.rolloutMode().name(), write(source.policy()), source.payloadDigest(),
                source.authoredBy(), now, evidenceDigest, now, evidenceDigest, actorId,
                now, evidenceDigest, now, source.revisionNumber(), source.payloadDigest(),
                current.revisionNumber(), source.topologyRevision(), source.topologySha256(),
                source.iaRegisterSha256(), source.shellRegisterSha256(),
                source.taskGroupRegisterSha256(), source.generatedIaSha256(), now, now);
        return findByRevisionId(tenantId, newRevisionId).orElseThrow();
    }

    @Override
    public void activate(long tenantId, AggregateRevisionView revision) {
        bindTenant(tenantId);
        int activated = jdbc.update("""
                INSERT INTO hris_navigation_policy_v2_effective (
                    tenant_id, effective_id, scope_kind, legal_entity_public_id, policy_id,
                    revision, revision_id, payload_digest, rollout_mode, published_at)
                SELECT r.tenant_id, ?, h.scope_kind, h.legal_entity_public_id, r.policy_id,
                       r.revision, r.revision_id, r.payload_digest, r.rollout_mode,
                       r.published_at
                  FROM hris_navigation_policy_v2_revisions r
                  JOIN hris_navigation_policy_v2_heads h
                    ON h.tenant_id = r.tenant_id AND h.policy_id = r.policy_id
                 WHERE r.tenant_id = ? AND r.policy_id = ? AND r.revision = ?
                   AND r.revision_id = ? AND r.payload_digest = ?
                   AND r.lifecycle_state = 'PUBLISHED' AND r.published_at IS NOT NULL
                ON CONFLICT (tenant_id, scope_kind, legal_entity_public_id) DO UPDATE
                    SET policy_id = EXCLUDED.policy_id, revision = EXCLUDED.revision,
                        revision_id = EXCLUDED.revision_id,
                        payload_digest = EXCLUDED.payload_digest,
                        rollout_mode = EXCLUDED.rollout_mode,
                        published_at = EXCLUDED.published_at
                """, UUID.randomUUID(), tenantId, revision.policyId(),
                revision.revisionNumber(), revision.revisionId(), revision.payloadDigest());
        if (activated != 1) {
            throw new IllegalStateException(
                    "HRIS_NAV_V2_PUBLISHED_REVISION_IDENTITY_MISMATCH");
        }
    }

    @Override
    public void appendAudit(
            long tenantId, AggregateRevisionView revision, String action, long actorId,
            UUID correlationId, String evidenceDigest, OffsetDateTime now) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_v2_audit (
                    tenant_id, audit_id, policy_id, policy_revision, revision_id,
                    payload_digest, action,
                    actor_id, correlation_id, evidence_digest, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, UUID.randomUUID(), revision.policyId(),
                revision.revisionNumber(), revision.revisionId(), revision.payloadDigest(),
                action, actorId, correlationId, evidenceDigest, now);
    }

    @Override
    public void appendOutbox(
            long tenantId, AggregateRevisionView revision, String payload,
            OffsetDateTime now) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO hris_navigation_policy_v2_outbox (
                    tenant_id, event_id, policy_id, policy_revision, revision_id,
                    payload_digest,
                    event_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'dwp.hris.navigation-policy.aggregate.published.v2',
                        CAST(? AS jsonb), ?)
                """, tenantId, UUID.randomUUID(), revision.policyId(),
                revision.revisionNumber(), revision.revisionId(), revision.payloadDigest(),
                payload, now);
    }

    @Override
    public Optional<PublishedAggregate> findPublished(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId) {
        return findPublishedRevision(tenantId, scopeKind, legalEntityPublicId)
                .map(value -> new PublishedAggregate(
                        value.policyId(), value.revisionId(), value.revisionNumber(),
                        value.policy(), value.payloadDigest(), value.topologyRevision(),
                        value.topologySha256(), value.iaRegisterSha256(),
                        value.shellRegisterSha256(), value.taskGroupRegisterSha256(),
                        value.generatedIaSha256(), value.publishedAt()));
    }

    private AggregateRevisionView revision(ResultSet rs) throws SQLException {
        return new AggregateRevisionView(
                rs.getObject("policy_id", UUID.class),
                rs.getObject("revision_id", UUID.class), rs.getLong("revision"),
                LifecycleState.valueOf(rs.getString("lifecycle_state")),
                RolloutMode.valueOf(rs.getString("rollout_mode")),
                read(rs.getString("policy_payload"), AggregatePolicyDocument.class),
                rs.getString("payload_digest"), rs.getLong("aggregate_version"),
                rs.getLong("authored_by"), nullableLong(rs, "approved_by"),
                nullableLong(rs, "restored_from_revision"),
                rs.getString("restored_from_digest"), rs.getString("topology_revision"),
                rs.getString("topology_sha256"), rs.getString("ia_register_sha256"),
                rs.getString("shell_register_sha256"),
                rs.getString("task_group_register_sha256"),
                rs.getString("generated_ia_sha256"),
                rs.getObject("published_at", OffsetDateTime.class),
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
                    SELECT revision FROM hris_navigation_policy_v2_effective
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

    private void bindPrincipal(long tenantId, UUID subjectPrincipalPublicId) {
        bindTenant(tenantId);
        if (subjectPrincipalPublicId == null) {
            throw new IllegalArgumentException("subjectPrincipalPublicId is required");
        }
        jdbc.queryForObject("SELECT set_config('dwp.principal_public_id', ?, true)",
                String.class, subjectPrincipalPublicId.toString());
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
            throw new IllegalStateException("HRIS_NAV_V2_SERIALIZATION_FAILED", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRIS_NAV_V2_DESERIALIZATION_FAILED", exception);
        }
    }
}
