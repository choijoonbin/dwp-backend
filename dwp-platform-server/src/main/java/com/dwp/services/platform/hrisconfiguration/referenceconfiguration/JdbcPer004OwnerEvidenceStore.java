package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.sha256;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;

@Repository
class JdbcPer004OwnerEvidenceStore implements Per004OwnerEvidenceStore {
    private final JdbcTemplate jdbc;
    JdbcPer004OwnerEvidenceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void checkCreateEligible(long tenantId, PopulationScope scope, UUID id, String schema) {
        bind(tenantId);
        var owners = jdbc.queryForList("""
                SELECT domain, configuration_key, scope_type, scope_id FROM hris_reference_configurations
                 WHERE tenant_id = ? AND configuration_id = ?
                """, tenantId, id);
        if (!owners.isEmpty()) {
            var owner = owners.getFirst();
            if (!"PER".equals(owner.get("domain")) || !schema.equals(owner.get("configuration_key"))
                    || !scope.type().name().equals(owner.get("scope_type")) || !scope.id().equals(owner.get("scope_id"))) throw conflict();
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM hris_reference_configuration_versions
                 WHERE tenant_id = ? AND configuration_id = ? AND domain = 'PER'
                   AND schema_key IN ('dwp.hris.performance.review-form.v1','dwp.hris.performance.rating-scale.v1','dwp.hris.performance.policy.v1')
                   AND lifecycle_state IN ('DRAFT','VALIDATED','SIMULATED','PENDING_APPROVAL','APPROVED','SCHEDULED'))
                """, Boolean.class, tenantId, id))) throw conflict();
    }

    @Override public void admitCreate(CreateIdentity identity) {
        bind(identity.tenantId());
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, lockKey(identity));
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM hris_reference_configuration_create_fences
                    WHERE tenant_id = ? AND identity_digest = ?)
                """, Boolean.class, identity.tenantId(), identityDigest(identity)))) throw conflict();
    }

    @Override public void recordPreparation(CreateIdentity identity, long version, OwnerReceipt receipt,
                                             UUID createReceipt, PopulationScope scope, OffsetDateTime now) {
        bind(identity.tenantId());
        insert(receipt);
        int changed = jdbc.update("""
                UPDATE hris_reference_configuration_versions
                   SET owner_preparation_receipt_id = ?, owner_preparation_evidence_digest = ?, owner_scope_digest = ?,
                       aggregate_version = aggregate_version + 1, updated_at = ?
                 WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                   AND aggregate_version = 0 AND lifecycle_state = 'DRAFT'
                   AND scope_type = ? AND scope_id = ? AND owner_preparation_receipt_id IS NULL
                """, receipt.ownerReceiptId(), receipt.evidenceDigest(), receipt.tuple().scopeDigest(), now,
                identity.tenantId(), identity.configurationId(), version, scope.type().name(), scope.id());
        if (changed != 1) throw conflict();
        snapshot(identity.tenantId(), identity.configurationId(), version, now);
        jdbc.update("""
                INSERT INTO hris_reference_configuration_owner_outbox (
                    tenant_id, event_id, event_type, configuration_id, configuration_version,
                    platform_create_receipt_id, owner_preparation_receipt_id,
                    create_subject_principal_public_id, originating_action,
                    create_idempotency_key, create_request_digest, created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, identity.tenantId(), UUID.randomUUID(), FINALIZE_EVENT, identity.configurationId(),
                version, createReceipt, receipt.ownerReceiptId(), identity.createSubjectPrincipalPublicId(),
                identity.originatingAction(), identity.createIdempotencyKey(), identity.createRequestDigest(), now, now);
    }

    @Override public Optional<OwnerReceipt> receipt(long tenantId, UUID id, long version, ReceiptKind kind) {
        bind(tenantId);
        return jdbc.query("""
                SELECT * FROM hris_reference_configuration_owner_receipts
                 WHERE tenant_id = ? AND configuration_id = ? AND configuration_version = ? AND kind = ?
                """, this::receipt, tenantId, id, version, kind.name()).stream().findFirst();
    }

    @Override public void transition(VersionDetail detail, PopulationScope scope, OwnerReceipt receipt,
                                     OffsetDateTime now) {
        OwnerTuple t = receipt.tuple();
        bind(t.tenantId());
        insert(receipt);
        int changed;
        if (receipt.kind() == ReceiptKind.VALIDATION) {
            changed = jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET lifecycle_state = 'VALIDATED', aggregate_version = aggregate_version + 1,
                           owner_validation_receipt_id = ?, owner_validation_evidence_digest = ?,
                           validation_receipt_id = ?, validation_evidence_digest = ?,
                           validation_schema_revision = ?, validation_scope_digest = ?, validated_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND scope_type = ? AND scope_id = ? AND aggregate_version = ? AND lifecycle_state = 'DRAFT'
                       AND owner_finalization_receipt_id = ?
                    """, receipt.ownerReceiptId(), receipt.evidenceDigest(), receipt.ownerReceiptId(),
                    receipt.evidenceDigest(), t.schemaVersion(), t.scopeDigest(), receipt.occurredAt(), now,
                    t.tenantId(), t.configurationId(), t.version(), scope.type().name(), scope.id(),
                    detail.aggregateVersion(), receipt.priorOwnerReceiptId());
        } else if (receipt.kind() == ReceiptKind.SIMULATION) {
            changed = jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET lifecycle_state = 'SIMULATED', aggregate_version = aggregate_version + 1,
                           owner_simulation_receipt_id = ?, owner_simulation_evidence_digest = ?,
                           simulation_receipt_id = ?, simulation_validation_receipt_id = ?,
                           simulation_evidence_digest = ?, simulation_impact_digest = ?,
                           simulated_at = ?, simulation_fresh_until = ?, updated_at = ?
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND scope_type = ? AND scope_id = ? AND aggregate_version = ? AND lifecycle_state = 'VALIDATED'
                       AND owner_validation_receipt_id = ?
                    """, receipt.ownerReceiptId(), receipt.evidenceDigest(), receipt.ownerReceiptId(),
                    receipt.priorOwnerReceiptId(), receipt.evidenceDigest(), receipt.impactDigest(),
                    receipt.occurredAt(), receipt.revalidateAt(), now, t.tenantId(), t.configurationId(), t.version(),
                    scope.type().name(), scope.id(), detail.aggregateVersion(), receipt.priorOwnerReceiptId());
        } else throw conflict();
        if (changed != 1) throw conflict();
        snapshot(t.tenantId(), t.configurationId(), t.version(), now);
    }

    @Override public FinalizeRequest pendingFinalization(long tenantId, UUID id, long version) {
        bind(tenantId);
        List<FinalizeRequest> found = jdbc.query("""
                SELECT r.*, o.platform_create_receipt_id, v.aggregate_version
                  FROM hris_reference_configuration_owner_outbox o
                  JOIN hris_reference_configuration_owner_receipts r
                    ON r.tenant_id = o.tenant_id AND r.owner_receipt_id = o.owner_preparation_receipt_id
                  JOIN hris_reference_configuration_versions v ON v.tenant_id = o.tenant_id
                    AND v.configuration_id = o.configuration_id AND v.version = o.configuration_version
                 WHERE o.tenant_id = ? AND o.configuration_id = ? AND o.configuration_version = ?
                   AND o.quarantined_at IS NULL AND o.acknowledged_at IS NULL AND o.next_attempt_at <= CURRENT_TIMESTAMP
                """, (rs, row) -> new FinalizeRequest(tuple(rs), rs.getObject("owner_receipt_id", UUID.class),
                rs.getObject("platform_create_receipt_id", UUID.class), rs.getLong("aggregate_version")), tenantId, id, version);
        if (found.size() != 1) throw conflict();
        return found.getFirst();
    }

    @Override public FinalizationAcknowledgement acknowledge(FinalizeRequest request, OwnerReceipt receipt,
                                                             OffsetDateTime now) {
        OwnerTuple t = request.tuple();
        bind(t.tenantId());
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.*, v.aggregate_version, v.owner_finalization_receipt_id
                  FROM hris_reference_configuration_owner_outbox o
                  JOIN hris_reference_configuration_versions v ON v.tenant_id = o.tenant_id
                    AND v.configuration_id = o.configuration_id AND v.version = o.configuration_version
                 WHERE o.tenant_id = ? AND o.configuration_id = ? AND o.configuration_version = ?
                 FOR UPDATE OF o, v
                """, t.tenantId(), t.configurationId(), t.version());
        if (rows.size() != 1) throw conflict();
        var row = rows.getFirst();
        if (!request.platformCreateReceiptId().equals(row.get("platform_create_receipt_id"))
                || !request.ownerPreparationReceiptId().equals(row.get("owner_preparation_receipt_id"))
                || row.get("quarantined_at") != null) throw conflict();
        Optional<OwnerReceipt> prior = receipt(t.tenantId(), t.configurationId(), t.version(), ReceiptKind.FINALIZATION);
        if (prior.isPresent()) {
            if (!t.equals(prior.get().tuple()) || !prior.get().equals(receipt) || !receipt.ownerReceiptId().equals(row.get("owner_finalization_receipt_id"))
                    || row.get("acknowledged_at") == null) throw conflict();
            // The acknowledgement's aggregate is the immutable binding history, even after later commands.
            Long aggregate = jdbc.queryForObject("""
                    SELECT min(aggregate_version) FROM hris_reference_configuration_version_history
                     WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                       AND snapshot->>'owner_finalization_receipt_id' = ?
                    """, Long.class, t.tenantId(), t.configurationId(), t.version(), receipt.ownerReceiptId().toString());
            return new FinalizationAcknowledgement(t.configurationId(), t.version(), receipt.ownerReceiptId(),
                    request.platformCreateReceiptId(), aggregate);
        }
        if (((Number) row.get("aggregate_version")).longValue() != request.expectedAggregateVersion()) throw conflict();
        insert(receipt);
        int changed = jdbc.update("""
                UPDATE hris_reference_configuration_versions
                   SET owner_finalization_receipt_id = ?, owner_finalization_evidence_digest = ?,
                       aggregate_version = aggregate_version + 1, updated_at = ?
                 WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                   AND lifecycle_state = 'DRAFT' AND aggregate_version = ?
                   AND owner_preparation_receipt_id = ? AND owner_finalization_receipt_id IS NULL
                """, receipt.ownerReceiptId(), receipt.evidenceDigest(), now, t.tenantId(), t.configurationId(),
                t.version(), request.expectedAggregateVersion(), request.ownerPreparationReceiptId());
        if (changed != 1) throw conflict();
        snapshot(t.tenantId(), t.configurationId(), t.version(), now);
        jdbc.update("""
                UPDATE hris_reference_configuration_owner_outbox SET acknowledged_at = ?
                 WHERE tenant_id = ? AND configuration_id = ? AND configuration_version = ?
                   AND acknowledged_at IS NULL AND quarantined_at IS NULL
                """, now, t.tenantId(), t.configurationId(), t.version());
        return new FinalizationAcknowledgement(t.configurationId(), t.version(), receipt.ownerReceiptId(),
                request.platformCreateReceiptId(), request.expectedAggregateVersion() + 1);
    }

    @Override public void failedDelivery(long tenantId, UUID id, long version, OffsetDateTime now) {
        bind(tenantId);
        jdbc.update("""
                UPDATE hris_reference_configuration_owner_outbox
                   SET attempt_count = attempt_count + 1,
                       next_attempt_at = ? + (interval '1 second' * power(2, attempt_count + 1)),
                       quarantined_at = CASE WHEN attempt_count = 7 THEN ? ELSE NULL END
                 WHERE tenant_id = ? AND configuration_id = ? AND configuration_version = ?
                   AND acknowledged_at IS NULL AND quarantined_at IS NULL AND attempt_count < 8
                """, now, now, tenantId, id, version);
    }

    @Override public CreateLookupResponse lookup(CreateIdentity identity, OffsetDateTime now) {
        bind(identity.tenantId());
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockKey(identity));
        if (!Boolean.TRUE.equals(locked)) return lookup(identity, LookupState.UNKNOWN, null, null, null, now);
        var rows = jdbc.queryForList("""
                SELECT o.configuration_id, o.configuration_version, o.create_request_digest,
                       o.owner_preparation_receipt_id, o.platform_create_receipt_id
                  FROM hris_reference_configuration_owner_outbox o
                  JOIN hris_reference_configuration_versions v ON v.tenant_id = o.tenant_id
                    AND v.configuration_id = o.configuration_id AND v.version = o.configuration_version
                  JOIN hris_reference_configuration_command_receipts c ON c.tenant_id = o.tenant_id
                    AND c.receipt_id = o.platform_create_receipt_id
                 WHERE o.tenant_id = ? AND o.create_subject_principal_public_id = ?
                   AND o.originating_action = ? AND o.create_idempotency_key = ?
                """, identity.tenantId(), identity.createSubjectPrincipalPublicId(),
                identity.originatingAction(), identity.createIdempotencyKey());
        if (!rows.isEmpty()) {
            var r = rows.getFirst();
            if (rows.size() != 1 || !identity.configurationId().equals(r.get("configuration_id"))
                    || !identity.createRequestDigest().equals(r.get("create_request_digest"))
                    || !identity.ownerPreparationReceiptId().equals(r.get("owner_preparation_receipt_id"))) {
                return lookup(identity, LookupState.UNKNOWN, null, null, null, now);
            }
            return lookup(identity, LookupState.BOUND, ((Number) r.get("configuration_version")).longValue(),
                    (UUID) r.get("platform_create_receipt_id"), null, now);
        }
        Boolean ownerExists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM hris_reference_configuration_owner_receipts
                    WHERE tenant_id = ? AND (owner_receipt_id = ? OR configuration_id = ?))
                    OR EXISTS(SELECT 1 FROM hris_reference_configuration_versions WHERE tenant_id = ? AND configuration_id = ?)
                    OR EXISTS(SELECT 1 FROM hris_reference_configuration_command_receipts WHERE tenant_id = ?
                        AND originating_action = ? AND idempotency_key = ?
                        AND receipt_payload->>'subjectPrincipalPublicId' = ?)
                """, Boolean.class, identity.tenantId(), identity.ownerPreparationReceiptId(), identity.configurationId(),
                identity.tenantId(), identity.configurationId(), identity.tenantId(), identity.originatingAction(),
                identity.createIdempotencyKey(), identity.createSubjectPrincipalPublicId().toString());
        if (Boolean.TRUE.equals(ownerExists)) return lookup(identity, LookupState.UNKNOWN, null, null, null, now);
        jdbc.update("""
                INSERT INTO hris_reference_configuration_create_fences
                    (tenant_id, identity_digest, fence_id, configuration_id, created_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id, identity_digest) DO NOTHING
                """, identity.tenantId(), identityDigest(identity), UUID.randomUUID(), identity.configurationId(), now);
        UUID fence = jdbc.queryForObject("""
                SELECT fence_id FROM hris_reference_configuration_create_fences
                 WHERE tenant_id = ? AND identity_digest = ?
                """, UUID.class, identity.tenantId(), identityDigest(identity));
        return lookup(identity, LookupState.ABSENT_FINAL, null, null, fence, now);
    }

    @Override public List<OwnerProjection> project(long tenantId, OwnerProjectionRequest request) {
        bind(tenantId);
        List<OwnerProjection> result = new ArrayList<>();
        for (ProjectionTarget target : request.targets()) {
            result.addAll(jdbc.query("""
                    SELECT h.snapshot, c.configuration_key FROM hris_reference_configuration_version_history h
                      JOIN hris_reference_configurations c ON c.tenant_id = h.tenant_id AND c.configuration_id = h.configuration_id
                     WHERE h.tenant_id = ? AND h.configuration_id = ? AND h.captured_at <= ?
                       AND h.snapshot->>'domain' = 'PER'
                       AND EXISTS(SELECT 1 FROM hris_reference_configuration_owner_receipts r
                           WHERE r.tenant_id = h.tenant_id AND r.configuration_id = h.configuration_id
                             AND r.configuration_version = h.version AND r.kind = 'PREPARATION' AND r.scope_digest = ?)
                     ORDER BY h.version DESC, h.aggregate_version DESC LIMIT 1
                    """, (rs, row) -> {
                com.fasterxml.jackson.databind.JsonNode node;
                try { node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rs.getString("snapshot")); }
                catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new SQLException("PER004_SNAPSHOT_INVALID"); }
                String state = node.get("lifecycle_state").asText();
                boolean eligible = List.of("PUBLISHED", "REJECTED", "RETIRED", "FAILED_ACTIVATION").contains(state);
                String wireState = eligible ? "NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE" : state;
                return new OwnerProjection(target.configurationId(), node.get("version").asLong(),
                        node.get("aggregate_version").asLong(), wireState, node.get("schema_key").asText(),
                        target.scopeDigest(), OffsetDateTime.parse(node.get("effective_from").asText()),
                        node.get("scheduled_at").isNull() ? null : OffsetDateTime.parse(node.get("scheduled_at").asText()),
                        !node.get("owner_finalization_receipt_id").isNull(),
                        eligible, rs.getString("configuration_key"), node.get("effective_to").isNull() ? null
                                : OffsetDateTime.parse(node.get("effective_to").asText()), node.get("schema_version").asInt(),
                        node.get("payload_reference").asText(), node.get("payload_digest").asText());
            }, tenantId, target.configurationId(), request.asOf(), target.scopeDigest()));
        }
        return List.copyOf(result);
    }

    private CreateLookupResponse lookup(CreateIdentity id, LookupState state, Long version, UUID create, UUID fence,
                                        OffsetDateTime now) {
        String digest = sha256((identityDigest(id) + "|" + state + "|" + version + "|" + create + "|" + fence)
                .getBytes(StandardCharsets.UTF_8));
        return new CreateLookupResponse(id, state, version, create, fence, digest, now, null);
    }

    private void insert(OwnerReceipt r) {
        OwnerTuple t = r.tuple();
        jdbc.update("""
                INSERT INTO hris_reference_configuration_owner_receipts (
                    tenant_id, owner_receipt_id, configuration_id, configuration_version, kind, domain,
                    schema_key, schema_version, payload_reference, payload_digest, scope_digest,
                    owner_service, prior_owner_receipt_id, evidence_digest, occurred_at, revalidate_at, impact_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, t.tenantId(), r.ownerReceiptId(), t.configurationId(), t.version(), r.kind().name(), t.domain(),
                t.schemaKey(), t.schemaVersion(), t.payloadReference(), t.payloadDigest(), t.scopeDigest(), r.ownerService(),
                r.priorOwnerReceiptId(), r.evidenceDigest(), r.occurredAt(), r.revalidateAt(), r.impactDigest());
    }

    private OwnerReceipt receipt(ResultSet rs, int row) throws SQLException {
        return new OwnerReceipt(ReceiptKind.valueOf(rs.getString("kind")), tuple(rs), rs.getString("owner_service"),
                rs.getObject("owner_receipt_id", UUID.class), rs.getObject("prior_owner_receipt_id", UUID.class),
                rs.getString("evidence_digest"), "SUCCEEDED", rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("revalidate_at", OffsetDateTime.class), rs.getString("impact_digest"));
    }

    private OwnerTuple tuple(ResultSet rs) throws SQLException {
        return new OwnerTuple(rs.getLong("tenant_id"), rs.getObject("configuration_id", UUID.class),
                rs.getLong("configuration_version"), rs.getString("domain"), rs.getString("schema_key"),
                rs.getInt("schema_version"), rs.getString("payload_reference"), rs.getString("payload_digest"),
                rs.getString("scope_digest"));
    }

    private void snapshot(long tenant, UUID id, long version, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO hris_reference_configuration_version_history
                    (tenant_id, configuration_id, version, aggregate_version, scope_type, scope_id, captured_at, snapshot)
                SELECT tenant_id, configuration_id, version, aggregate_version, scope_type, scope_id, ?, to_jsonb(v)
                  FROM hris_reference_configuration_versions v WHERE tenant_id = ? AND configuration_id = ? AND version = ?
                """, now, tenant, id, version);
    }

    static String identityDigest(CreateIdentity identity) {
        return sha256((identity.tenantId() + "|" + identity.configurationId() + "|" + identity.createSubjectPrincipalPublicId()
                + "|" + identity.originatingAction() + "|" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(identity.createIdempotencyKey().getBytes(StandardCharsets.UTF_8)) + "|"
                + identity.createRequestDigest() + "|" + identity.ownerPreparationReceiptId()).getBytes(StandardCharsets.UTF_8));
    }

    private long lockKey(CreateIdentity identity) {
        return ByteBuffer.wrap(java.util.HexFormat.of().parseHex(identityDigest(identity))).getLong();
    }
    private void bind(long tenant) {
        if (tenant <= 0) throw conflict();
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)", String.class, Long.toString(tenant));
    }
    private BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "PER004_OWNER_EVIDENCE_CONFLICT"); }
}
