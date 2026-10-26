package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.DrainAdmission;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.GrantAuthority;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ReadinessObservation;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolveRequest;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolveResponse;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.RsaPublicJwk;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ServiceRole;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.Status;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.Fence;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.GrantBinding;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.QuiesceBatch;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.RotationResult;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.SuccessorGrant;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantRotationReadinessVerifier.ReadinessItem;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantRotationReadinessVerifier.VerifiedReceipt;
import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.opaque;
import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.requireDigest;
import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.requirePositive;
import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.revisionMismatch;
import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.serviceRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional V271 persistence boundary; RLS context is always the first statement. */
public final class HrisServiceGrantAuthorityRepository {
    static final String TENANT_CONTEXT = "dwp.auth.hrm013.tenant";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public HrisServiceGrantAuthorityRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ResolveResponse resolve(
            ResolveRequest request, Hrm013WorkloadProofV1.ReplayReservation replay,
            String resolverProofSha256) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(replay, "replay");
        requireDigest(resolverProofSha256);
        ResolveResponse response = transactions.execute(status -> {
            setTenantContext(request.tenantId());
            if (request.resolutionMode()
                    != HrisServiceGrantAuthorityV1.ResolutionMode.PROBE_MISSING) {
                serviceRole(request);
            }
            lockAuthorityTuple(request.tenantId(), request.targetTuple());
            reserveProofPair(replay, "AUTHORITY_RESOLVE");
            if (request.resolutionMode()
                    == HrisServiceGrantAuthorityV1.ResolutionMode.PROBE_MISSING) {
                // Deliberately no grant, binding, key or fence read in this branch.
                return ResolveResponse.opaque(Status.MISSING, request.correlationId());
            }
            return switch (request.resolutionMode()) {
                case NEW_WORK -> resolveNewWork(request);
                case DRAIN_EXISTING -> resolveDrain(request, resolverProofSha256);
                case READINESS_OBSERVE -> resolveReadiness(request);
                case PROBE_MISSING -> throw new IllegalStateException("handled above");
            };
        });
        if (response == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return response;
    }

    public ResolveResponse resolveLocal(ResolveRequest request, String businessProofSha256) {
        Objects.requireNonNull(request); requireDigest(businessProofSha256);
        ResolveResponse response = transactions.execute(status -> {
            setTenantContext(request.tenantId());
            if (request.resolutionMode()
                    == HrisServiceGrantAuthorityV1.ResolutionMode.PROBE_MISSING)
                throw Hrm013SafeErrorV1.authorityDenied();
            serviceRole(request); lockAuthorityTuple(request.tenantId(), request.targetTuple());
            return switch (request.resolutionMode()) {
                case NEW_WORK -> resolveNewWork(request);
                case DRAIN_EXISTING -> resolveDrain(request, businessProofSha256);
                case READINESS_OBSERVE -> resolveReadiness(request);
                case PROBE_MISSING -> throw Hrm013SafeErrorV1.authorityDenied();
            };
        });
        if (response == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return response;
    }

    public QuiesceBatch beginQuiesce(long tenantId,
            List<HrisServiceGrantAuthorityV1.TargetTuple> orderedTargets,
            UUID commandCorrelationId) {
        requirePositive(tenantId, "tenantId");
        HrisServiceGrantQuiesceService.requireOrderedTargets(orderedTargets);
        HrisServiceGrantQuiesceService.requireAtomicScope(orderedTargets);
        Objects.requireNonNull(commandCorrelationId);
        QuiesceBatch result = transactions.execute(status -> {
            setTenantContext(tenantId); lockAuthorityBatch(tenantId, orderedTargets);
            CurrentGrant current = currentGrantForUpdate(tenantId);
            if (!"ADMIT".equals(current.decision())) throw Hrm013SafeErrorV1.invalidState();
            if (!Set.copyOf(HrisServiceGrantQuiesceService.orderedBindingTargets(
                    bindingMaterials(tenantId, current.id()))).containsAll(orderedTargets))
                throw Hrm013SafeErrorV1.invalidState();
            Instant now = databaseNow();
            List<Fence> fences = new java.util.ArrayList<>();
            for (var target : orderedTargets) {
                Fence prior = latestFence(tenantId, target);
                if (prior != null && prior.quiescing()) throw Hrm013SafeErrorV1.invalidState();
                long revision = prior == null ? 1 : prior.revision() + 1;
                UUID id = UUID.randomUUID();
                insertFence(id, tenantId, current.id(), target, revision, "QUIESCING",
                        prior == null ? null : prior.id(), now, commandCorrelationId,
                        HrisServiceGrantQuiesceService.fenceDigest(
                                tenantId, current.id(), target, revision, "QUIESCING",
                                now, commandCorrelationId));
                fences.add(new Fence(id, current.id(), revision, "QUIESCING", now));
            }
            return new QuiesceBatch(tenantId, current.id(), current.tenantGrantRevision(),
                    current.authorizationRevision(), now, List.copyOf(fences));
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    public QuiesceBatch abortQuiesce(long tenantId,
            List<HrisServiceGrantAuthorityV1.TargetTuple> orderedTargets,
            UUID commandCorrelationId) {
        requirePositive(tenantId, "tenantId");
        HrisServiceGrantQuiesceService.requireOrderedTargets(orderedTargets);
        HrisServiceGrantQuiesceService.requireAtomicScope(orderedTargets);
        QuiesceBatch result = transactions.execute(status -> {
            setTenantContext(tenantId); lockAuthorityBatch(tenantId, orderedTargets);
            CurrentGrant grant = currentGrantForUpdate(tenantId);
            if (!"ADMIT".equals(grant.decision())) throw Hrm013SafeErrorV1.invalidState();
            List<Fence> aborted = new java.util.ArrayList<>();
            UUID oldGrant = null; Instant effective = null;
            for (var target : orderedTargets) {
                Fence prior = latestFence(tenantId, target);
                if (prior == null || !prior.quiescing()) throw Hrm013SafeErrorV1.invalidState();
                if (!grant.id().equals(prior.oldGrantSetId())
                        || oldGrant != null && (!oldGrant.equals(prior.oldGrantSetId())
                        || !effective.equals(prior.effectiveAt())))
                    throw Hrm013SafeErrorV1.invalidState();
                oldGrant = prior.oldGrantSetId(); effective = prior.effectiveAt();
                UUID id = UUID.randomUUID(); long revision = prior.revision() + 1;
                insertFence(id, tenantId, oldGrant, target, revision, "ABORTED", prior.id(),
                        effective, commandCorrelationId, HrisServiceGrantQuiesceService.fenceDigest(
                                tenantId, oldGrant, target,
                                revision, "ABORTED", effective, commandCorrelationId));
                aborted.add(new Fence(id, oldGrant, revision, "ABORTED", effective));
            }
            return new QuiesceBatch(tenantId, oldGrant, grant.tenantGrantRevision(),
                    grant.authorizationRevision(), effective, List.copyOf(aborted));
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    public RotationResult cutover(long tenantId,
            List<HrisServiceGrantAuthorityV1.TargetTuple> orderedTargets,
            List<ReadinessItem> readinessItems,
            SuccessorGrant successor, UUID commandCorrelationId,
            HrisServiceGrantRotationReadinessVerifier verifier) {
        requirePositive(tenantId, "tenantId");
        HrisServiceGrantQuiesceService.requireOrderedTargets(orderedTargets);
        HrisServiceGrantQuiesceService.requireAtomicScope(orderedTargets);
        Objects.requireNonNull(commandCorrelationId);
        Objects.requireNonNull(verifier);
        RotationResult result = transactions.execute(status -> {
            setTenantContext(tenantId); lockAuthorityBatch(tenantId, orderedTargets);
            CurrentGrant current = currentGrantForUpdate(tenantId);
            if (!"ADMIT".equals(current.decision())) throw Hrm013SafeErrorV1.invalidState();
            HrisServiceGrantQuiesceService.requireCutoverSnapshot(orderedTargets,
                    bindingMaterials(tenantId, current.id()), successor);
            Instant now = databaseNow();
            List<VerifiedReceipt> verifiedReceipts = new java.util.ArrayList<>();
            Map<HrisServiceGrantAuthorityV1.TargetTuple, List<ReadinessItem>> grouped =
                    HrisServiceGrantRotationReadinessVerifier.groupItemsByTarget(
                            readinessItems, orderedTargets, json);
            List<Fence> priorFences = new java.util.ArrayList<>();
            Instant commonEffectiveAt = null;
            for (var target : orderedTargets) {
                Fence fence = latestFence(tenantId, target);
                if (fence == null || !fence.quiescing()
                        || !fence.oldGrantSetId().equals(current.id()))
                    throw Hrm013SafeErrorV1.invalidState();
                if (commonEffectiveAt != null && !commonEffectiveAt.equals(fence.effectiveAt()))
                    throw Hrm013SafeErrorV1.invalidState();
                commonEffectiveAt = fence.effectiveAt();
                priorFences.add(fence);
                List<ReadinessObservation> expected = lockedObservations(
                        tenantId, target, current, fence, now);
                var verified = verifier.verifySigned(
                        new HrisServiceGrantRotationReadinessVerifier.LockedRotation(
                                tenantId, target, current.id(), current.tenantGrantRevision(),
                                current.authorizationRevision(), fence.id(), fence.revision(),
                                fence.effectiveAt(), now),
                        lockedAuthority(tenantId, target, current), expected,
                        grouped.get(target));
                verifiedReceipts.addAll(verified.receipts());
            }
            verifiedReceipts.forEach(receipt -> reserveProofPair(
                    receipt.replay(), "ROTATION_READINESS"));
            String groupReadiness = HrisServiceGrantRotationReadinessVerifier.aggregateVerified(
                    verifiedReceipts);
            if (!groupReadiness.equals(successor.rotationReadinessDigest()))
                throw Hrm013SafeErrorV1.invalidState();
            long grantRevision = current.grantRevision() + 1;
            insertGrantSet(tenantId, successor, grantRevision, current.id(), "ADMIT",
                    groupReadiness, null, now);
            insertBindings(tenantId, successor.grantSetId(), successor.bindings());
            List<UUID> fenceIds = new java.util.ArrayList<>();
            for (int i = 0; i < orderedTargets.size(); i++) {
                var target = orderedTargets.get(i); Fence prior = priorFences.get(i);
                UUID id = UUID.randomUUID(); long revision = prior.revision() + 1;
                insertFence(id, tenantId, current.id(), target, revision, "CUTOVER", prior.id(),
                        prior.effectiveAt(), commandCorrelationId,
                        HrisServiceGrantQuiesceService.fenceDigest(
                                tenantId, current.id(), target, revision, "CUTOVER",
                                prior.effectiveAt(), commandCorrelationId));
                fenceIds.add(id);
            }
            return new RotationResult(successor.grantSetId(), grantRevision,
                    successor.tenantGrantRevision(), successor.authorizationRevision(),
                    List.copyOf(fenceIds), now);
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    public RotationResult emergencyRevoke(long tenantId, String incidentEvidenceDigest,
            String revokedGrantDigest, UUID commandCorrelationId) {
        requirePositive(tenantId, "tenantId"); requireDigest(incidentEvidenceDigest);
        requireDigest(revokedGrantDigest); Objects.requireNonNull(commandCorrelationId);
        RotationResult result = transactions.execute(status -> {
            setTenantContext(tenantId);
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    ignored -> { }, "DWP_AUTH_HRM013_GRANT_V1:" + tenantId);
            CurrentGrant current = currentGrantForUpdate(tenantId);
            if (!"ADMIT".equals(current.decision())) throw Hrm013SafeErrorV1.invalidState();
            List<GrantBinding> bindings = bindingMaterials(tenantId, current.id());
            for (var target : HrisServiceGrantQuiesceService.orderedBindingTargets(bindings)) {
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                        ignored -> { }, fenceLockKey(tenantId, target));
            }
            UUID revoked = UUID.randomUUID(); Instant now = databaseNow();
            SuccessorGrant successor = new SuccessorGrant(revoked,
                    current.tenantGrantRevision() + 1, current.authorizationRevision() + 1,
                    revokedGrantDigest, null, bindings);
            insertGrantSet(tenantId, successor, current.grantRevision() + 1, current.id(),
                    "REVOKE", null, incidentEvidenceDigest, now);
            insertBindings(tenantId, revoked, bindings);
            return new RotationResult(revoked, current.grantRevision() + 1,
                    successor.tenantGrantRevision(), successor.authorizationRevision(),
                    List.of(), now);
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    private ResolveResponse resolveNewWork(ResolveRequest request) {
        CurrentGrant current = currentGrant(request.tenantId());
        if (current == null) return opaque(Status.MISSING, request);
        if (!"ADMIT".equals(current.decision())) return opaque(Status.REVOKE, request);
        List<GrantAuthority> authorities = currentAuthorities(request);
        if (authorities.isEmpty()) return opaque(Status.MISSING, request);
        if (authorities.size() != 1) return opaque(Status.AMBIGUOUS, request);
        GrantAuthority authority = authorities.getFirst();
        if (revisionMismatch(request, authority)) return opaque(Status.REVISION_MISMATCH, request);
        if (currentFence(request).map(Fence::quiescing).orElse(false)) {
            return opaque(Status.QUIESCING, request);
        }
        return ResolveResponse.admit(authority, null, request.correlationId());
    }

    private void lockAuthorityBatch(long tenantId,
            List<HrisServiceGrantAuthorityV1.TargetTuple> orderedTargets) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { }, "DWP_AUTH_HRM013_GRANT_V1:" + tenantId);
        for (var target : orderedTargets) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    ignored -> { }, fenceLockKey(tenantId, target));
        }
    }

    private CurrentGrant currentGrantForUpdate(long tenantId) {
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

    private Fence latestFence(long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target) {
        List<Fence> rows = jdbc.query("""
                SELECT quiesce_fence_id, old_grant_set_id, quiesce_revision,
                       lifecycle_state, effective_at
                  FROM auth_hris_service_grant_quiesce_fences
                 WHERE tenant_id = ? AND binding_id = ? AND caller_workload_id = ?
                   AND owner_workload_id = ? AND purpose = ?
                 ORDER BY quiesce_revision DESC LIMIT 1 FOR UPDATE
                """, (rs, row) -> new Fence(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4),
                rs.getTimestamp(5).toInstant()), tenantId, target.bindingId(),
                target.callerWorkloadId(), target.ownerWorkloadId(), target.purpose());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private void insertFence(UUID id, long tenantId, UUID oldGrantSetId,
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
    private void insertGrantSet(long tenantId, SuccessorGrant successor,
            long grantRevision, UUID predecessor, String decision, String readinessDigest,
            String incidentDigest, Instant recordedAt) {
        jdbc.update("""
                INSERT INTO auth_hris_service_grant_sets (
                  grant_set_id, tenant_id, grant_revision, tenant_grant_revision,
                  authorization_revision, decision, supersedes_grant_set_id,
                  rotation_readiness_digest, incident_evidence_digest, grant_digest,
                  recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, successor.grantSetId(), tenantId, grantRevision,
                successor.tenantGrantRevision(), successor.authorizationRevision(), decision,
                predecessor, readinessDigest, incidentDigest, successor.grantDigest(),
                java.sql.Timestamp.from(recordedAt));
    }

    private void insertBindings(long tenantId, UUID grantSetId,
            List<GrantBinding> bindings) {
        for (GrantBinding binding : bindings) {
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

    private List<GrantBinding> bindingMaterials(long tenantId, UUID grantSetId) {
        return jdbc.query("""
                SELECT binding_id, caller_workload_id, caller_spiffe_id,
                       caller_signing_key_id, caller_signing_public_jwk::text,
                       owner_workload_id, owner_spiffe_id, owner_signing_key_id,
                       owner_signing_public_jwk::text, purpose, binding_digest
                  FROM auth_hris_service_grant_bindings
                 WHERE tenant_id = ? AND grant_set_id = ? ORDER BY binding_id,
                       caller_workload_id, owner_workload_id, purpose FOR SHARE
                """, (rs, row) -> new GrantBinding(new HrisServiceGrantAuthorityV1.TargetTuple(
                rs.getString(1), rs.getString(2), rs.getString(6), rs.getString(10)),
                rs.getString(3), rs.getString(4), readJwk(rs.getString(5)),
                rs.getString(7), rs.getString(8), readJwk(rs.getString(9)),
                rs.getString(11)), tenantId, grantSetId);
    }

    private List<ReadinessObservation> lockedObservations(long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target, CurrentGrant grant,
            Fence fence, Instant now) {
        List<ReadinessObservation> result = new java.util.ArrayList<>();
        for (ServiceRole role : ServiceRole.values()) {
            ActivityHead head = latestActivity(tenantId, fence.id(), role);
            Instant quiet = head.recordedAt() == null ? fence.effectiveAt() : head.recordedAt();
            Instant eligible = quiet.plusSeconds(65);
            if (now.isBefore(eligible)) throw Hrm013SafeErrorV1.invalidState();
            ResolveRequest request = new ResolveRequest(tenantId,
                    role == ServiceRole.CALLER ? target.callerWorkloadId()
                            : target.ownerWorkloadId(),
                    target.callerWorkloadId(), target.ownerWorkloadId(), target.bindingId(),
                    target.purpose(), HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE,
                    grant.tenantGrantRevision(), grant.authorizationRevision(),
                    UUID.fromString("00000000-0000-0000-0000-000000000001"), null);
            String digest = HrisServiceGrantRotationReadinessVerifier.observationDigest(
                    request, grant.id(),
                    grant.tenantGrantRevision(), grant.authorizationRevision(), fence, role,
                    head.revision(), quiet, eligible);
            result.add(new ReadinessObservation(tenantId, grant.id(),
                    grant.tenantGrantRevision(), grant.authorizationRevision(),
                    target.callerWorkloadId(), target.ownerWorkloadId(), target.bindingId(),
                    target.purpose(), fence.id(), fence.revision(), fence.effectiveAt(), role,
                    head.revision(), quiet, eligible, digest));
        }
        return List.copyOf(result);
    }

    private GrantAuthority lockedAuthority(long tenantId,
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

    private ResolveResponse resolveDrain(ResolveRequest request, String proofSha256) {
        CurrentGrant current = currentGrant(request.tenantId());
        if (current == null) return opaque(Status.MISSING, request);
        if (!"ADMIT".equals(current.decision())) return opaque(Status.REVOKE, request);
        List<GrantAuthority> authorities = authorityAtSignedRevision(request);
        if (authorities.isEmpty()) return opaque(Status.MISSING, request);
        if (authorities.size() != 1) return opaque(Status.AMBIGUOUS, request);
        GrantAuthority authority = authorities.getFirst();
        if (revisionMismatch(request, authority)) return opaque(Status.REVISION_MISMATCH, request);
        if (!current.id().equals(authority.grantSetId())) return opaque(Status.STALE, request);
        Fence fence = currentFence(request).orElse(null);
        if (fence == null || !fence.quiescing()
                || !fence.oldGrantSetId().equals(authority.grantSetId())) {
            return opaque(Status.STALE, request);
        }
        ServiceRole role = serviceRole(request);
        HrisServiceGrantAuthorityV1.DrainFields drain = request.drainFields();
        if (drain == null) throw Hrm013SafeErrorV1.authorityDenied();
        Instant recordedAt = databaseNow();
        Long previous = jdbc.queryForObject("""
                SELECT COALESCE(MAX(activity_revision), 0)
                  FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id = ? AND quiesce_fence_id = ?
                   AND attesting_service_role = ?
                """, Long.class, request.tenantId(), fence.id(), role.name());
        long revision = Objects.requireNonNullElse(previous, 0L) + 1;
        jdbc.update("""
                INSERT INTO auth_hris_service_grant_drain_activities (
                    drain_activity_id, tenant_id, quiesce_fence_id, old_grant_set_id,
                    tenant_grant_revision, authorization_revision, binding_id,
                    caller_workload_id, owner_workload_id, purpose,
                    attesting_service_role, service_workload_id, activity_revision,
                    continuation_kind, root_work_item_id, root_created_at,
                    root_payload_sha256, continuation_event_id, continuation_revision,
                    continuation_payload_sha256, resolver_proof_sha256, correlation_id,
                    recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), request.tenantId(), fence.id(), authority.grantSetId(),
                authority.tenantGrantRevision(), authority.authorizationRevision(),
                request.targetBindingId(), request.targetCallerWorkloadId(),
                request.targetOwnerWorkloadId(), request.targetPurpose(), role.name(),
                request.requestingWorkloadId(), revision, drain.drainContinuationKind().name(),
                drain.drainRootWorkItemId(), java.sql.Timestamp.from(drain.drainRootCreatedAt()),
                drain.drainRootPayloadSha256(), drain.drainContinuationEventId(),
                drain.drainContinuationRevision(), drain.drainContinuationPayloadSha256(),
                proofSha256, request.correlationId(), java.sql.Timestamp.from(recordedAt));
        DrainAdmission admission = new DrainAdmission(fence.id(), fence.revision(),
                fence.effectiveAt(), role, revision, recordedAt, recordedAt.plusSeconds(30),
                recordedAt.plusSeconds(65));
        return ResolveResponse.admit(authority, admission, request.correlationId());
    }

    private ResolveResponse resolveReadiness(ResolveRequest request) {
        CurrentGrant current = currentGrant(request.tenantId());
        if (current == null) return opaque(Status.MISSING, request);
        if (!"ADMIT".equals(current.decision())) return opaque(Status.REVOKE, request);
        List<GrantAuthority> authorities = authorityAtSignedRevision(request);
        if (authorities.isEmpty()) return opaque(Status.MISSING, request);
        if (authorities.size() != 1) return opaque(Status.AMBIGUOUS, request);
        GrantAuthority authority = authorities.getFirst();
        if (revisionMismatch(request, authority)) return opaque(Status.REVISION_MISMATCH, request);
        if (!current.id().equals(authority.grantSetId())) return opaque(Status.STALE, request);
        Fence fence = currentFence(request).orElse(null);
        if (fence == null || !fence.quiescing()
                || !fence.oldGrantSetId().equals(authority.grantSetId())) {
            return opaque(Status.STALE, request);
        }
        ServiceRole role = serviceRole(request);
        ActivityHead head = latestActivity(request.tenantId(), fence.id(), role);
        Instant quietSince = head.recordedAt() == null
                ? fence.effectiveAt() : head.recordedAt();
        Instant eligibleAt = quietSince.plusSeconds(65);
        if (databaseNow().isBefore(eligibleAt)) return opaque(Status.QUIESCING, request);
        String digest = HrisServiceGrantRotationReadinessVerifier.observationDigest(request,
                authority.grantSetId(), authority.tenantGrantRevision(),
                authority.authorizationRevision(), fence, role, head.revision(), quietSince,
                eligibleAt);
        ReadinessObservation observation = new ReadinessObservation(request.tenantId(),
                authority.grantSetId(), authority.tenantGrantRevision(),
                authority.authorizationRevision(), request.targetCallerWorkloadId(),
                request.targetOwnerWorkloadId(), request.targetBindingId(),
                request.targetPurpose(), fence.id(), fence.revision(), fence.effectiveAt(),
                role, head.revision(), quietSince, eligibleAt, digest);
        return ResolveResponse.readiness(observation, request.correlationId());
    }

    private List<GrantAuthority> currentAuthorities(ResolveRequest request) {
        return queryAuthorities(request, """
                AND grant_set.grant_revision = (
                    SELECT MAX(current_set.grant_revision)
                      FROM auth_hris_service_grant_sets current_set
                     WHERE current_set.tenant_id = grant_set.tenant_id)
                AND grant_set.decision = 'ADMIT'
                """);
    }

    private List<GrantAuthority> authorityAtSignedRevision(ResolveRequest request) {
        return queryAuthorities(request,
                "AND grant_set.tenant_grant_revision = ? AND grant_set.authorization_revision = ? "
                        + "AND grant_set.decision = 'ADMIT'",
                request.signedTenantGrantRevision(), request.signedAuthorizationRevision());
    }

    private CurrentGrant currentGrant(long tenantId) {
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

    private java.util.Optional<Fence> currentFence(ResolveRequest request) {
        List<Fence> rows = jdbc.query("""
                SELECT quiesce_fence_id, old_grant_set_id, quiesce_revision,
                       lifecycle_state, effective_at
                  FROM auth_hris_service_grant_quiesce_fences
                 WHERE tenant_id = ? AND binding_id = ? AND caller_workload_id = ?
                   AND owner_workload_id = ? AND purpose = ?
                 ORDER BY quiesce_revision DESC LIMIT 1 FOR UPDATE
                """, (rs, row) -> new Fence(rs.getObject("quiesce_fence_id", UUID.class),
                rs.getObject("old_grant_set_id", UUID.class),
                rs.getLong("quiesce_revision"), rs.getString("lifecycle_state"),
                rs.getTimestamp("effective_at").toInstant()), request.tenantId(),
                request.targetBindingId(), request.targetCallerWorkloadId(),
                request.targetOwnerWorkloadId(), request.targetPurpose());
        return rows.stream().findFirst();
    }

    private ActivityHead latestActivity(long tenantId, UUID fenceId, ServiceRole role) {
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

    private void setTenantContext(long tenantId) {
        requirePositive(tenantId, "tenantId");
        String set = jdbc.queryForObject(
                "SELECT set_config('" + TENANT_CONTEXT + "', ?, true)",
                String.class, Long.toString(tenantId));
        if (!Long.toString(tenantId).equals(set)) throw Hrm013SafeErrorV1.authorityUnavailable(1);
    }

    private void lockAuthorityTuple(long tenantId,
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

    private void reserveProofPair(
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

    private Instant databaseNow() {
        Instant now = jdbc.queryForObject("SELECT transaction_timestamp()",
                (rs, row) -> rs.getTimestamp(1).toInstant());
        if (now == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return now;
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
}
