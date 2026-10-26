package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact tenant/account-plane directory reader for the requester authority. */
public final class Hrm013RequesterIdentityAuthorityRepository {
    private static final String BINDING = Hrm013RequesterIdentityAuthorityV1.BINDING_ID;
    private static final String CALLER = Hrm013RequesterIdentityAuthorityV1.CALLER_WORKLOAD_ID;
    private static final String OWNER = Hrm013RequesterIdentityAuthorityV1.OWNER_WORKLOAD_ID;
    private static final String PURPOSE = Hrm013RequesterIdentityAuthorityV1.PURPOSE;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public Hrm013RequesterIdentityAuthorityRepository(JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transactions.setTimeout(30);
        this.json = Objects.requireNonNull(objectMapper);
    }

    /** The only authority read allowed before business-proof verification. */
    public Hrm013WorkloadProofV1.VerifiedSigningKey resolveSigningKey(
            Hrm013WorkloadProofV1.Profile profile,
            Hrm013WorkloadProofV1.UntrustedSelector selector, String keyId) {
        if (profile == null || profile.kind() != Hrm013WorkloadProofV1.ProfileKind.BUSINESS
                || !BINDING.equals(profile.bindingId()) || selector == null || keyId == null)
            return null;
        return transactions.execute(status -> {
            setTenant(selector.tenantId());
            lockRequesterAuthority(selector.tenantId());
            List<KeyRow> rows = keyRows(selector);
            if (rows.size() != 1 || !keyId.equals(rows.getFirst().keyId())) return null;
            return new Hrm013WorkloadProofV1.VerifiedSigningKey(
                    rsa(rows.getFirst().jwk()), keyId, CALLER);
        });
    }

    /** Reserves JTI and requestNonce in one transaction or rolls both back. */
    public boolean reserveReplay(Hrm013WorkloadProofV1.ReplayReservation replay) {
        try {
            Boolean result = transactions.execute(status -> {
                setTenant(replay.tenantId());
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                        ignored -> { }, "DWP_AUTH_HRM013_REPLAY_V1:" + replay.tenantId());
                int[] rows = jdbc.batchUpdate("""
                        INSERT INTO auth_hris_service_authority_proof_replays (
                          tenant_id, proof_scope, binding_id, requesting_workload_id,
                          token_kind, token_digest, expires_at, reserved_at)
                        VALUES (?, 'REQUESTER_IDENTITY', ?, ?, ?, ?, ?, transaction_timestamp())
                        """, List.of(
                        new Object[]{replay.tenantId(), replay.bindingId(),
                                replay.callerWorkloadId(), "JTI", replay.proofIdSha256(),
                                java.sql.Timestamp.from(replay.retainUntil())},
                        new Object[]{replay.tenantId(), replay.bindingId(),
                                replay.callerWorkloadId(), "REQUEST_NONCE",
                                replay.requestNonceSha256(),
                                java.sql.Timestamp.from(replay.retainUntil())}));
                if (rows.length != 2 || rows[0] != 1 || rows[1] != 1) {
                    status.setRollbackOnly(); return false;
                }
                return true;
            });
            return Boolean.TRUE.equals(result);
        } catch (DuplicateKeyException replayed) {
            return false;
        }
    }

    /** Shared verifier callback after signature and replay reservation. */
    public boolean remainsCurrent(Hrm013WorkloadProofV1.Profile profile,
            Hrm013WorkloadProofV1.VerifiedSigningKey key,
            Hrm013WorkloadProofV1.ProofClaims claims) {
        if (profile == null || key == null || claims == null) return false;
        Boolean result = transactions.execute(status -> {
            setTenant(claims.tenantId());
            lockRequesterAuthority(claims.tenantId());
            var selector = new Hrm013WorkloadProofV1.UntrustedSelector(claims.tenantId(),
                    claims.authorityMode(), claims.drainClaims());
            List<KeyRow> rows = keyRows(selector);
            return rows.size() == 1 && signingKeyMatches(rows.getFirst(), key);
        });
        return Boolean.TRUE.equals(result);
    }

    /** Reads com_users only between two exact current-authority checks. */
    public DirectoryResult resolveIdentity(
            Hrm013RequesterIdentityAuthorityV1.ResolveRequest request,
            Hrm013RequesterIdentityAuthorityV1.ProofBinding proof,
            Hrm013WorkloadProofV1.VerifiedSigningKey verifiedSigningKey,
            Instant proofExpiresAt, String proofSha256) {
        requireExactRequestBinding(request, proof);
        DirectoryResult result;
        try {
            result = transactions.execute(status -> {
                setTenant(request.tenantId());
                lockRequesterAuthority(request.tenantId());
                Instant now = databaseClockNow();
                if (proofExpiresAt == null || !now.isBefore(proofExpiresAt)
                        || !authorityMatches(proof, verifiedSigningKey))
                    return DirectoryResult.denied(now);
                setStatementTimeout(proofExpiresAt, now);
                Instant drainExpiry = proof.grantUseMode()
                        == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING
                        ? appendRequesterOwnerDrain(
                        request, proof, proofExpiresAt, proofSha256) : null;
                List<Hrm013RequesterIdentityAuthorityV1.RequesterRecord> users = jdbc.query("""
                    SELECT tenant_id, user_id, person_public_id, access_revision,
                           version, identity_plane, status
                      FROM com_users
                     WHERE tenant_id = ? AND person_public_id = ?
                       AND identity_plane = 'TENANT' AND status = 'ACTIVE'
                     ORDER BY user_id LIMIT 2 FOR SHARE
                    """, (rs, row) -> new Hrm013RequesterIdentityAuthorityV1.RequesterRecord(
                    rs.getLong("tenant_id"), rs.getLong("user_id"),
                    rs.getObject("person_public_id", UUID.class),
                    rs.getLong("access_revision"), rs.getLong("version"),
                    plane(rs.getString("identity_plane")), state(rs.getString("status")), 1),
                    request.tenantId(), request.requestedByActorPublicId());
                Instant finalNow = databaseClockNow();
                if (users.size() != 1 || !finalNow.isBefore(proofExpiresAt)
                        || (drainExpiry != null && !finalNow.isBefore(drainExpiry))
                        || !authorityMatches(proof, verifiedSigningKey))
                    return DirectoryResult.denied(finalNow, drainExpiry);
                return new DirectoryResult(users.getFirst(), finalNow, true, drainExpiry);
            });
        } catch (QueryTimeoutException expiredWhileWaiting) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    private Instant appendRequesterOwnerDrain(
            Hrm013RequesterIdentityAuthorityV1.ResolveRequest request,
            Hrm013RequesterIdentityAuthorityV1.ProofBinding proof,
            Instant proofExpiresAt, String proofSha256) {
        if (proofSha256 == null || !proofSha256.matches("[0-9a-f]{64}")
                || proof.drainClaims() == null) throw Hrm013SafeErrorV1.authorityDenied();
        List<DrainAuthority> rows = jdbc.query("""
                SELECT grant_set.grant_set_id, fence.quiesce_fence_id,
                       fence.quiesce_revision, fence.effective_at
                  FROM auth_hris_service_grant_sets grant_set
                  JOIN auth_hris_service_grant_bindings binding
                    ON binding.tenant_id = grant_set.tenant_id
                   AND binding.grant_set_id = grant_set.grant_set_id
                  JOIN auth_hris_service_grant_quiesce_fences fence
                    ON fence.tenant_id = grant_set.tenant_id
                   AND fence.old_grant_set_id = grant_set.grant_set_id
                   AND fence.binding_id = binding.binding_id
                   AND fence.caller_workload_id = binding.caller_workload_id
                   AND fence.owner_workload_id = binding.owner_workload_id
                   AND fence.purpose = binding.purpose
                 WHERE grant_set.tenant_id = ? AND grant_set.tenant_grant_revision = ?
                   AND grant_set.authorization_revision = ? AND grant_set.decision = 'ADMIT'
                   AND binding.binding_id = ? AND binding.caller_workload_id = ?
                   AND binding.owner_workload_id = ? AND binding.purpose = ?
                   AND fence.lifecycle_state = 'QUIESCING'
                   AND fence.quiesce_revision = (
                       SELECT MAX(latest.quiesce_revision)
                         FROM auth_hris_service_grant_quiesce_fences latest
                        WHERE latest.tenant_id = fence.tenant_id
                          AND latest.binding_id = fence.binding_id
                          AND latest.caller_workload_id = fence.caller_workload_id
                          AND latest.owner_workload_id = fence.owner_workload_id
                          AND latest.purpose = fence.purpose)
                 ORDER BY fence.quiesce_revision DESC LIMIT 1 FOR SHARE
                """, (rs, row) -> new DrainAuthority(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getLong(3), rs.getTimestamp(4).toInstant()),
                request.tenantId(), proof.tenantGrantRevision(), proof.authorizationRevision(),
                BINDING, CALLER, OWNER, PURPOSE);
        if (rows.size() != 1) throw Hrm013SafeErrorV1.authorityDenied();
        DrainAuthority authority = rows.getFirst();
        Long latest = jdbc.queryForObject("""
                SELECT COALESCE(MAX(activity_revision), 0)
                  FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id = ? AND quiesce_fence_id = ?
                   AND attesting_service_role = 'OWNER'
                """, Long.class, request.tenantId(), authority.fenceId());
        long revision = Objects.requireNonNullElse(latest, 0L) + 1;
        var drain = proof.drainClaims(); Instant recordedAt = transactionNow();
        Instant drainExpiry = recordedAt.plusSeconds(30);
        if (proofExpiresAt == null || proofExpiresAt.isAfter(drainExpiry)
                || !databaseClockNow().isBefore(proofExpiresAt)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        jdbc.update("""
                INSERT INTO auth_hris_service_grant_drain_activities (
                  drain_activity_id, tenant_id, quiesce_fence_id, old_grant_set_id,
                  tenant_grant_revision, authorization_revision, binding_id,
                  caller_workload_id, owner_workload_id, purpose, attesting_service_role,
                  service_workload_id, activity_revision, continuation_kind,
                  root_work_item_id, root_created_at, root_payload_sha256,
                  continuation_event_id, continuation_revision,
                  continuation_payload_sha256, resolver_proof_sha256, correlation_id,
                  recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OWNER', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), request.tenantId(), authority.fenceId(),
                authority.grantSetId(), proof.tenantGrantRevision(),
                proof.authorizationRevision(), BINDING, CALLER, OWNER, PURPOSE, OWNER,
                revision, drain.drainContinuationKind().name(), drain.drainRootWorkItemId(),
                java.sql.Timestamp.from(drain.drainRootCreatedAt()),
                drain.drainRootPayloadSha256(),
                drain.drainContinuationEventId(), drain.drainContinuationRevision(),
                drain.drainContinuationPayloadSha256(), proofSha256, request.correlationId(),
                java.sql.Timestamp.from(recordedAt));
        return drainExpiry;
    }

    private List<KeyRow> keyRows(Hrm013WorkloadProofV1.UntrustedSelector selector) {
        String current = """
                AND grant_set.grant_revision = (SELECT MAX(latest.grant_revision)
                  FROM auth_hris_service_grant_sets latest
                 WHERE latest.tenant_id = grant_set.tenant_id)
                """;
        String newWork = selector.authorityMode().drainExisting() ? "" : """
                AND NOT EXISTS (SELECT 1
                  FROM auth_hris_service_grant_quiesce_fences fence
                 WHERE fence.tenant_id = grant_set.tenant_id
                   AND fence.binding_id = binding.binding_id
                   AND fence.caller_workload_id = binding.caller_workload_id
                   AND fence.owner_workload_id = binding.owner_workload_id
                   AND fence.purpose = binding.purpose
                   AND fence.lifecycle_state = 'QUIESCING'
                   AND fence.quiesce_revision = (
                       SELECT MAX(latest.quiesce_revision)
                         FROM auth_hris_service_grant_quiesce_fences latest
                        WHERE latest.tenant_id = fence.tenant_id
                          AND latest.binding_id = fence.binding_id
                          AND latest.caller_workload_id = fence.caller_workload_id
                          AND latest.owner_workload_id = fence.owner_workload_id
                          AND latest.purpose = fence.purpose))
                """;
        String drain = selector.authorityMode().drainExisting() ? """
                AND EXISTS (SELECT 1 FROM auth_hris_service_grant_quiesce_fences fence
                 WHERE fence.tenant_id = grant_set.tenant_id
                   AND fence.old_grant_set_id = grant_set.grant_set_id
                   AND fence.binding_id = binding.binding_id
                   AND fence.caller_workload_id = binding.caller_workload_id
                   AND fence.owner_workload_id = binding.owner_workload_id
                   AND fence.purpose = binding.purpose
                   AND fence.lifecycle_state = 'QUIESCING'
                   AND fence.quiesce_revision = (
                       SELECT MAX(latest.quiesce_revision)
                         FROM auth_hris_service_grant_quiesce_fences latest
                        WHERE latest.tenant_id = fence.tenant_id
                          AND latest.binding_id = fence.binding_id
                          AND latest.caller_workload_id = fence.caller_workload_id
                          AND latest.owner_workload_id = fence.owner_workload_id
                          AND latest.purpose = fence.purpose))
                """ : "";
        return jdbc.query("""
                SELECT binding.caller_signing_key_id,
                       binding.caller_signing_public_jwk::text
                  FROM auth_hris_service_grant_sets grant_set
                  JOIN auth_hris_service_grant_bindings binding
                    ON binding.tenant_id = grant_set.tenant_id
                   AND binding.grant_set_id = grant_set.grant_set_id
                 WHERE grant_set.tenant_id = ? AND grant_set.decision = 'ADMIT'
                   AND grant_set.tenant_grant_revision = ?
                   AND grant_set.authorization_revision = ?
                   AND binding.binding_id = ? AND binding.caller_workload_id = ?
                   AND binding.owner_workload_id = ? AND binding.purpose = ?
                """ + current + newWork + drain
                        + " ORDER BY grant_set.grant_revision DESC LIMIT 2",
                (rs, row) -> new KeyRow(rs.getString(1), rs.getString(2)),
                selector.tenantId(), selector.authorityMode().tenantGrantRevision(),
                selector.authorityMode().authorizationRevision(), BINDING, CALLER, OWNER, PURPOSE);
    }

    private boolean authorityMatches(
            Hrm013RequesterIdentityAuthorityV1.ProofBinding proof,
            Hrm013WorkloadProofV1.VerifiedSigningKey verifiedSigningKey) {
        var selector = new Hrm013WorkloadProofV1.UntrustedSelector(proof.tenantId(),
                new Hrm013WorkloadProofV1.BusinessAuthority(proof.grantUseMode(),
                        proof.tenantGrantRevision(), proof.authorizationRevision()),
                proof.drainClaims());
        List<KeyRow> rows = keyRows(selector);
        return rows.size() == 1 && signingKeyMatches(rows.getFirst(), verifiedSigningKey);
    }

    private boolean signingKeyMatches(KeyRow row,
            Hrm013WorkloadProofV1.VerifiedSigningKey key) {
        PublicKey expected = rsa(row.jwk());
        return row.keyId().equals(key.keyId()) && CALLER.equals(key.workloadId())
                && expected instanceof RSAPublicKey expectedRsa
                && key.publicKey() instanceof RSAPublicKey actualRsa
                && expectedRsa.getModulus().equals(actualRsa.getModulus())
                && expectedRsa.getPublicExponent().equals(actualRsa.getPublicExponent());
    }

    private static void requireExactRequestBinding(
            Hrm013RequesterIdentityAuthorityV1.ResolveRequest request,
            Hrm013RequesterIdentityAuthorityV1.ProofBinding proof) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(proof, "proof");
        if (!proof.authorityUsable() || !BINDING.equals(proof.bindingId())
                || !CALLER.equals(proof.callerWorkloadId())
                || !OWNER.equals(proof.ownerWorkloadId())
                || !PURPOSE.equals(proof.purpose())
                || request.tenantId() != proof.tenantId()
                || !request.approvalRequestId().equals(proof.approvalRequestId())
                || !request.requestedByActorPublicId().equals(proof.requestedByActorPublicId())
                || !request.correlationId().equals(proof.correlationId())
                || proof.tenantGrantRevision() < 1 || proof.authorizationRevision() < 1) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Hrm013WorkloadProofV1.DrainClaims drain = proof.drainClaims();
        if (proof.grantUseMode() == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK) {
            if (drain != null || proof.drainAuthorizationExpiresAt() != null)
                throw Hrm013SafeErrorV1.authorityDenied();
            return;
        }
        if (proof.grantUseMode() != Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING
                || drain == null || proof.drainAuthorizationExpiresAt() != null
                || drain.drainContinuationKind()
                != Hrm013WorkloadProofV1.DrainContinuationKind.DEPENDENT_READ
                || !request.approvalRequestId().equals(drain.drainRootWorkItemId())
                || !request.approvalRequestId().equals(drain.drainContinuationEventId())
                || drain.drainContinuationRevision() != 0
                || !proof.enclosingApprovalRequestedAt().equals(drain.drainRootCreatedAt())
                || !proof.enclosingApprovalPayloadSha256().equals(
                drain.drainRootPayloadSha256())
                || !proof.enclosingApprovalPayloadSha256().equals(
                drain.drainContinuationPayloadSha256())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private PublicKey rsa(String raw) {
        try {
            JsonNode node = json.readTree(raw);
            if (node.size() != 6 || !"RS256".equals(node.path("alg").asText())
                    || !"RSA".equals(node.path("kty").asText())
                    || !"sig".equals(node.path("use").asText()))
                throw Hrm013SafeErrorV1.authorityDenied();
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new BigInteger(1, Base64.getUrlDecoder().decode(node.path("n").asText())),
                    new BigInteger(1, Base64.getUrlDecoder().decode(node.path("e").asText()))));
        } catch (Hrm013SafeErrorV1.Failure safe) {
            throw safe;
        } catch (Exception invalid) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private void setTenant(long tenantId) {
        String value = jdbc.queryForObject(
                "SELECT set_config('dwp.auth.hrm013.tenant', ?, true)", String.class,
                Long.toString(tenantId));
        if (!Long.toString(tenantId).equals(value))
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
    }

    private void lockRequesterAuthority(long tenantId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { }, "DWP_AUTH_HRM013_GRANT_V1:" + tenantId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { }, HrisServiceGrantAuthorityRepository.fenceLockKey(tenantId,
                        HrisServiceGrantQuiesceService.REQUESTER));
    }

    private Instant transactionNow() {
        Instant now = jdbc.queryForObject("SELECT transaction_timestamp()",
                (rs, row) -> rs.getTimestamp(1).toInstant());
        if (now == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return now;
    }

    private Instant databaseClockNow() {
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()",
                (rs, row) -> rs.getTimestamp(1).toInstant());
        if (now == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return now;
    }

    private void setStatementTimeout(Instant deadline, Instant now) {
        long remainingMillis = java.time.Duration.between(now, deadline).toMillis();
        if (remainingMillis < 1) throw Hrm013SafeErrorV1.authorityDenied();
        String applied = jdbc.queryForObject(
                "SELECT set_config('statement_timeout', ?, true)", String.class,
                Math.min(remainingMillis, 30_000L) + "ms");
        if (applied == null || applied.isBlank())
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
    }

    private static Hrm013RequesterIdentityAuthorityV1.AccountPlane plane(String raw) {
        return "TENANT".equals(raw) ? Hrm013RequesterIdentityAuthorityV1.AccountPlane.TENANT
                : Hrm013RequesterIdentityAuthorityV1.AccountPlane.PLATFORM;
    }

    private static Hrm013RequesterIdentityAuthorityV1.AccountState state(String raw) {
        if ("ACTIVE".equals(raw)) return Hrm013RequesterIdentityAuthorityV1.AccountState.ACTIVE;
        if ("LOCKED".equals(raw)) return Hrm013RequesterIdentityAuthorityV1.AccountState.LOCKED;
        return Hrm013RequesterIdentityAuthorityV1.AccountState.INACTIVE;
    }

    record KeyRow(String keyId, String jwk) { }
    record DrainAuthority(UUID grantSetId, UUID fenceId, long fenceRevision,
            Instant effectiveAt) { }
    public record DirectoryResult(
            Hrm013RequesterIdentityAuthorityV1.RequesterRecord requester,
            Instant databaseNow, boolean authorityCurrent, Instant drainAuthorizationExpiresAt) {
        static DirectoryResult denied(Instant now) {
            return new DirectoryResult(null, now, false, null);
        }
        static DirectoryResult denied(Instant now, Instant drainExpiry) {
            return new DirectoryResult(null, now, false, drainExpiry);
        }
    }
}
