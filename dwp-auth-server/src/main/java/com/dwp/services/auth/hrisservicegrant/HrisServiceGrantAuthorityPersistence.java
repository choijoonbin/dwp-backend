package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.GrantAuthority;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolveRequest;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.RsaPublicJwk;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ServiceRole;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL, row mapping and tenant locks shared by grant authority transactions. */
final class HrisServiceGrantAuthorityPersistence {
    static final String TENANT_CONTEXT = "dwp.auth.hrm013.tenant";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    HrisServiceGrantAuthorityPersistence(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void lockAuthorityBatch(long tenantId,
            List<HrisServiceGrantAuthorityV1.TargetTuple> orderedTargets) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { }, "DWP_AUTH_HRM013_GRANT_V1:" + tenantId);
        for (var target : orderedTargets) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    ignored -> { }, fenceLockKey(tenantId, target));
        }
    }

    CurrentGrant currentGrantForUpdate(long tenantId) {
        List<CurrentGrant> rows = jdbc.query("""
                SELECT grant_set_id, grant_revision, tenant_grant_revision,
                       authorization_revision, decision
                  FROM auth_hris_service_grant_sets
                 WHERE tenant_id = ? ORDER BY grant_revision DESC LIMIT 1 FOR UPDATE
                """, (rs, row) -> new CurrentGrant(rs.getObject(1, UUID.class), rs.getLong(2),
                rs.getLong(3), rs.getLong(4), rs.getString(5)), tenantId);
        if (rows.size() != 1) throw Hrm013SafeErrorV1.invalidState();
        return rows.getFirst();
    }

    FenceRow latestFence(long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target) {
        List<FenceRow> rows = jdbc.query("""
                SELECT quiesce_fence_id, old_grant_set_id, quiesce_revision,
                       lifecycle_state, effective_at
                  FROM auth_hris_service_grant_quiesce_fences
                 WHERE tenant_id = ? AND binding_id = ? AND caller_workload_id = ?
                   AND owner_workload_id = ? AND purpose = ?
                 ORDER BY quiesce_revision DESC LIMIT 1 FOR UPDATE
                """, (rs, row) -> new FenceRow(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4),
                rs.getTimestamp(5).toInstant()), tenantId, target.bindingId(),
                target.callerWorkloadId(), target.ownerWorkloadId(), target.purpose());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    void insertFence(UUID id, long tenantId, UUID oldGrantSetId,
            HrisServiceGrantAuthorityV1.TargetTuple target, long revision, String state,
            UUID supersedes, Instant effectiveAt, UUID correlationId, String digest) {
        jdbc.update("""
                INSERT INTO auth_hris_service_grant_quiesce_fences (
                  quiesce_fence_id, tenant_id, old_grant_set_id, binding_id,
                  caller_workload_id, owner_workload_id, purpose, quiesce_revision,
                  lifecycle_state, supersedes_quiesce_fence_id, effective_at,
                  successor_command_correlation_id, fence_digest, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, transaction_timestamp())
                """, id, tenantId, oldGrantSetId, target.bindingId(),
                target.callerWorkloadId(), target.ownerWorkloadId(), target.purpose(),
                revision, state, supersedes, java.sql.Timestamp.from(effectiveAt),
                correlationId, digest);
    }
    void insertGrantSet(long tenantId, UUID successorId, long tenantGrantRevision,
            long authorizationRevision, String grantDigest,
            long grantRevision, UUID predecessor, String decision, String readinessDigest,
            String incidentDigest, Instant recordedAt) {
        jdbc.update("""
                INSERT INTO auth_hris_service_grant_sets (
                  grant_set_id, tenant_id, grant_revision, tenant_grant_revision,
                  authorization_revision, decision, supersedes_grant_set_id,
                  rotation_readiness_digest, incident_evidence_digest, grant_digest,
                  recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, successorId, tenantId, grantRevision,
                tenantGrantRevision, authorizationRevision, decision,
                predecessor, readinessDigest, incidentDigest, grantDigest,
                java.sql.Timestamp.from(recordedAt));
    }

    void insertBindings(long tenantId, UUID grantSetId,
            List<BindingMaterial> bindings) {
        for (BindingMaterial binding : bindings) {
            jdbc.update("""
                    INSERT INTO auth_hris_service_grant_bindings (
                      grant_binding_id, tenant_id, grant_set_id, binding_id,
                      caller_workload_id, caller_spiffe_id, caller_signing_key_id,
                      caller_signing_public_jwk, owner_workload_id, owner_spiffe_id,
                      owner_signing_key_id, owner_signing_public_jwk, purpose, binding_digest)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    """, UUID.randomUUID(), tenantId, grantSetId, binding.target().bindingId(),
                    binding.target().callerWorkloadId(), binding.callerSpiffeId(),
                    binding.callerSigningKeyId(), jwkJson(binding.callerSigningPublicJwk()),
                    binding.target().ownerWorkloadId(), binding.ownerSpiffeId(),
                    binding.ownerSigningKeyId(), jwkJson(binding.ownerSigningPublicJwk()),
                    binding.target().purpose(), binding.bindingDigest());
        }
    }

    List<BindingMaterial> bindingMaterials(long tenantId, UUID grantSetId) {
        return jdbc.query("""
                SELECT binding_id, caller_workload_id, caller_spiffe_id,
                       caller_signing_key_id, caller_signing_public_jwk::text,
                       owner_workload_id, owner_spiffe_id, owner_signing_key_id,
                       owner_signing_public_jwk::text, purpose, binding_digest
                  FROM auth_hris_service_grant_bindings
                 WHERE tenant_id = ? AND grant_set_id = ? ORDER BY binding_id,
                       caller_workload_id, owner_workload_id, purpose FOR SHARE
                """, (rs, row) -> new BindingMaterial(new HrisServiceGrantAuthorityV1.TargetTuple(
                rs.getString(1), rs.getString(2), rs.getString(6), rs.getString(10)),
                rs.getString(3), rs.getString(4), readJwk(rs.getString(5)),
                rs.getString(7), rs.getString(8), readJwk(rs.getString(9)),
                rs.getString(11)), tenantId, grantSetId);
    }

    GrantAuthority lockedAuthority(long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target, CurrentGrant grant) {
        ResolveRequest request = new ResolveRequest(tenantId, target.callerWorkloadId(),
                target.callerWorkloadId(), target.ownerWorkloadId(), target.bindingId(),
                target.purpose(), HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE,
                grant.tenantGrantRevision(), grant.authorizationRevision(), UUID.randomUUID(), null);
        List<GrantAuthority> rows = authorityAtSignedRevision(request);
        if (rows.size() != 1 || !rows.getFirst().grantSetId().equals(grant.id()))
            throw Hrm013SafeErrorV1.invalidState();
        return rows.getFirst();
    }

    private String jwkJson(RsaPublicJwk value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw Hrm013SafeErrorV1.invalidState(); }
    }

    List<GrantAuthority> currentAuthorities(ResolveRequest request) {
        return queryAuthorities(request, """
                AND grant_set.grant_revision = (
                    SELECT MAX(current_set.grant_revision)
                      FROM auth_hris_service_grant_sets current_set
                     WHERE current_set.tenant_id = grant_set.tenant_id)
                AND grant_set.decision = 'ADMIT'
                """);
    }

    List<GrantAuthority> authorityAtSignedRevision(ResolveRequest request) {
        return queryAuthorities(request,
                "AND grant_set.tenant_grant_revision = ? AND grant_set.authorization_revision = ? "
                        + "AND grant_set.decision = 'ADMIT'",
                request.signedTenantGrantRevision(), request.signedAuthorizationRevision());
    }

    CurrentGrant currentGrant(long tenantId) {
        List<CurrentGrant> rows = jdbc.query("""
                SELECT grant_set_id, grant_revision, tenant_grant_revision,
                       authorization_revision, decision
                  FROM auth_hris_service_grant_sets
                 WHERE tenant_id = ? ORDER BY grant_revision DESC LIMIT 1 FOR SHARE
                """, (rs, row) -> new CurrentGrant(rs.getObject(1, UUID.class), rs.getLong(2),
                rs.getLong(3), rs.getLong(4), rs.getString(5)), tenantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<GrantAuthority> queryAuthorities(
            ResolveRequest request, String suffix, Object... suffixParameters) {
        String sql = """
                SELECT grant_set.grant_set_id, grant_set.grant_revision,
                       grant_set.tenant_id, grant_set.tenant_grant_revision,
                       grant_set.authorization_revision, grant_set.decision,
                       binding.caller_workload_id, binding.caller_spiffe_id,
                       binding.caller_signing_key_id,
                       binding.caller_signing_public_jwk::text,
                       binding.owner_workload_id, binding.owner_spiffe_id,
                       binding.owner_signing_key_id,
                       binding.owner_signing_public_jwk::text,
                       binding.binding_id, binding.purpose,
                       grant_set.supersedes_grant_set_id, grant_set.grant_digest,
                       grant_set.recorded_at
                  FROM auth_hris_service_grant_sets grant_set
                  JOIN auth_hris_service_grant_bindings binding
                    ON binding.tenant_id = grant_set.tenant_id
                   AND binding.grant_set_id = grant_set.grant_set_id
                 WHERE grant_set.tenant_id = ? AND binding.binding_id = ?
                   AND binding.caller_workload_id = ? AND binding.owner_workload_id = ?
                   AND binding.purpose = ?
                """ + suffix + " ORDER BY grant_set.grant_revision DESC LIMIT 2";
        Object[] parameters = new Object[5 + suffixParameters.length];
        parameters[0] = request.tenantId(); parameters[1] = request.targetBindingId();
        parameters[2] = request.targetCallerWorkloadId();
        parameters[3] = request.targetOwnerWorkloadId(); parameters[4] = request.targetPurpose();
        System.arraycopy(suffixParameters, 0, parameters, 5, suffixParameters.length);
        return jdbc.query(sql, (rs, row) -> new GrantAuthority(
                rs.getObject("grant_set_id", UUID.class), rs.getLong("grant_revision"),
                rs.getLong("tenant_id"), rs.getLong("tenant_grant_revision"),
                rs.getLong("authorization_revision"), rs.getString("decision"),
                rs.getString("caller_workload_id"), rs.getString("caller_spiffe_id"),
                rs.getString("caller_signing_key_id"),
                readJwk(rs.getString("caller_signing_public_jwk")),
                rs.getString("owner_workload_id"), rs.getString("owner_spiffe_id"),
                rs.getString("owner_signing_key_id"),
                readJwk(rs.getString("owner_signing_public_jwk")),
                rs.getString("binding_id"), rs.getString("purpose"),
                rs.getObject("supersedes_grant_set_id", UUID.class),
                rs.getString("grant_digest"), rs.getTimestamp("recorded_at").toInstant()),
                parameters);
    }

    java.util.Optional<FenceRow> currentFence(ResolveRequest request) {
        List<FenceRow> rows = jdbc.query("""
                SELECT quiesce_fence_id, old_grant_set_id, quiesce_revision,
                       lifecycle_state, effective_at
                  FROM auth_hris_service_grant_quiesce_fences
                 WHERE tenant_id = ? AND binding_id = ? AND caller_workload_id = ?
                   AND owner_workload_id = ? AND purpose = ?
                 ORDER BY quiesce_revision DESC LIMIT 1 FOR UPDATE
                """, (rs, row) -> new FenceRow(rs.getObject("quiesce_fence_id", UUID.class),
                rs.getObject("old_grant_set_id", UUID.class),
                rs.getLong("quiesce_revision"), rs.getString("lifecycle_state"),
                rs.getTimestamp("effective_at").toInstant()), request.tenantId(),
                request.targetBindingId(), request.targetCallerWorkloadId(),
                request.targetOwnerWorkloadId(), request.targetPurpose());
        return rows.stream().findFirst();
    }

    ActivityHead latestActivity(long tenantId, UUID fenceId, ServiceRole role) {
        List<ActivityHead> rows = jdbc.query("""
                SELECT activity_revision, recorded_at
                  FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id = ? AND quiesce_fence_id = ?
                   AND attesting_service_role = ?
                 ORDER BY activity_revision DESC LIMIT 1 FOR SHARE
                """, (rs, row) -> new ActivityHead(rs.getLong("activity_revision"),
                rs.getTimestamp("recorded_at").toInstant()), tenantId, fenceId, role.name());
        return rows.isEmpty() ? new ActivityHead(0, null) : rows.getFirst();
    }

    void setTenantContext(long tenantId) {
        requirePositive(tenantId, "tenantId");
        String set = jdbc.queryForObject(
                "SELECT set_config('" + TENANT_CONTEXT + "', ?, true)",
                String.class, Long.toString(tenantId));
        if (!Long.toString(tenantId).equals(set)) throw Hrm013SafeErrorV1.authorityUnavailable(1);
    }

    void lockAuthorityTuple(long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { }, "DWP_AUTH_HRM013_GRANT_V1:" + tenantId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { }, fenceLockKey(tenantId, target));
    }

    static String fenceLockKey(long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target) {
        return "DWP_AUTH_HRM013_FENCE_V1:" + tenantId + ':' + target.bindingId() + ':'
                + target.callerWorkloadId() + ':' + target.ownerWorkloadId() + ':'
                + target.purpose();
    }

    void reserveProofPair(
            Hrm013WorkloadProofV1.ReplayReservation replay, String proofScope) {
        if (replay.tenantId() < 1 || replay.proofIdSha256() == null
                || replay.requestNonceSha256() == null
                || replay.proofIdSha256().equals(replay.requestNonceSha256())
                || replay.retainUntil() == null) throw Hrm013SafeErrorV1.authorityDenied();
        requireDigest(replay.proofIdSha256()); requireDigest(replay.requestNonceSha256());
        try {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    ignored -> { }, "DWP_AUTH_HRM013_REPLAY_V1:" + replay.tenantId());
            int[] inserted = jdbc.batchUpdate("""
                    INSERT INTO auth_hris_service_authority_proof_replays (
                        tenant_id, proof_scope, binding_id, requesting_workload_id,
                        token_kind, token_digest, expires_at, reserved_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, transaction_timestamp())
                    """, List.of(
                    new Object[]{replay.tenantId(), proofScope, replay.bindingId(),
                            replay.callerWorkloadId(), "JTI", replay.proofIdSha256(),
                            java.sql.Timestamp.from(replay.retainUntil())},
                    new Object[]{replay.tenantId(), proofScope, replay.bindingId(),
                            replay.callerWorkloadId(), "REQUEST_NONCE",
                            replay.requestNonceSha256(),
                            java.sql.Timestamp.from(replay.retainUntil())}));
            if (inserted.length != 2 || inserted[0] != 1 || inserted[1] != 1)
                throw Hrm013SafeErrorV1.authorityDenied();
        } catch (DataAccessException failure) {
            throw failure instanceof org.springframework.dao.DuplicateKeyException ? Hrm013SafeErrorV1.authorityDenied() : Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    Instant databaseNow() {
        Instant now = jdbc.queryForObject("SELECT transaction_timestamp()",
                (rs, row) -> rs.getTimestamp(1).toInstant());
        if (now == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return now;
    }

    Instant wallClockNow() {
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()",
                (rs, row) -> rs.getTimestamp(1).toInstant());
        if (now == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return now;
    }

    long transactionId() {
        Long value = jdbc.queryForObject("SELECT txid_current()", Long.class);
        if (value == null || value < 1) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        return value;
    }
    private RsaPublicJwk readJwk(String raw) {
        try {
            Map<?, ?> value = json.readValue(raw, Map.class);
            return new RsaPublicJwk(text(value, "alg"), text(value, "e"), text(value, "kid"),
                    text(value, "kty"), text(value, "n"), text(value, "use"));
        } catch (Exception invalid) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }
    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String string)) throw Hrm013SafeErrorV1.authorityDenied();
        return string;
    }
    record CurrentGrant(UUID id, long grantRevision, long tenantGrantRevision,
            long authorizationRevision, String decision) { }
    record ActivityHead(long revision, Instant recordedAt) { }
    record FenceRow(UUID id, UUID oldGrantSetId, long revision, String state,
            Instant effectiveAt) { }
    record BindingMaterial(HrisServiceGrantAuthorityV1.TargetTuple target,
            String callerSpiffeId, String callerSigningKeyId, RsaPublicJwk callerSigningPublicJwk,
            String ownerSpiffeId, String ownerSigningKeyId, RsaPublicJwk ownerSigningPublicJwk,
            String bindingDigest) { }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static void requirePositive(long value, String label) {
        if (value < 1) throw new IllegalArgumentException(label + " must be positive");
    }
}
