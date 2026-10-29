package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.DrainAdmission;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.GrantAuthority;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ReadinessObservation;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolveRequest;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolveResponse;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ServiceRole;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.Status;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.Fence;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.GrantBinding;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.LocalAdmission;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.LocalVerification;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.QuiesceBatch;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.RotationResult;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.SuccessorGrant;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantRotationReadinessVerifier.VerifiedReceipt;
import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.opaque;
import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.requireDigest;
import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.requirePositive;
import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.revisionMismatch;
import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.serviceRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantAuthorityPersistence.CurrentGrant;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantAuthorityPersistence.ActivityHead;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional V271 persistence boundary; RLS context is always the first statement. */
public final class HrisServiceGrantAuthorityRepository
        implements HrisServiceGrantQuiesceService.AuthorityPort {
    static final String TENANT_CONTEXT = "dwp.auth.hrm013.tenant";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final HrisServiceGrantAuthorityPersistence persistence;
    private final HrisServiceGrantRotationReadinessVerifier readinessVerifier;

    public HrisServiceGrantAuthorityRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this(jdbc, transactionManager, objectMapper,
                new HrisServiceGrantRotationReadinessVerifier(objectMapper));
    }

    public HrisServiceGrantAuthorityRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            HrisServiceGrantRotationReadinessVerifier readinessVerifier) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(objectMapper, "objectMapper");
        this.readinessVerifier = Objects.requireNonNull(
                readinessVerifier, "readinessVerifier");
        this.persistence = new HrisServiceGrantAuthorityPersistence(
                this.jdbc, this.json);
    }

    public ResolveResponse resolve(
            ResolveRequest request, Hrm013WorkloadProofV1.ReplayReservation replay,
            String resolverProofSha256) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(replay, "replay");
        requireDigest(resolverProofSha256);
        ResolveResponse response = transactions.execute(status -> {
            persistence.setTenantContext(request.tenantId());
            if (request.resolutionMode()
                    != HrisServiceGrantAuthorityV1.ResolutionMode.PROBE_MISSING) {
                serviceRole(request);
            }
            persistence.lockAuthorityTuple(request.tenantId(), request.targetTuple());
            persistence.reserveProofPair(replay, "AUTHORITY_RESOLVE");
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
            persistence.setTenantContext(request.tenantId());
            if (request.resolutionMode()
                    == HrisServiceGrantAuthorityV1.ResolutionMode.PROBE_MISSING)
                throw Hrm013SafeErrorV1.authorityDenied();
            serviceRole(request); persistence.lockAuthorityTuple(request.tenantId(), request.targetTuple());
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

    /** Read-only first half of the Auth-local prepare/verify/authorize protocol. */
    public LocalVerification prepareLocalVerification(ResolveRequest request) {
        Objects.requireNonNull(request, "request");
        LocalVerification result = transactions.execute(status -> {
            persistence.setTenantContext(request.tenantId());
            if (request.resolutionMode()
                    != HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK
                    && request.resolutionMode()
                    != HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            serviceRole(request);
            persistence.lockAuthorityTuple(request.tenantId(), request.targetTuple());
            return prepareLocalLocked(request, persistence.transactionId());
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    /**
     * Same-transaction second half.  DRAIN appends exactly one activity only
     * after the caller has verified and reserved the proof.
     */
    public LocalAdmission authorizeLocalVerified(
            LocalVerification preparation, String businessProofSha256) {
        Objects.requireNonNull(preparation, "preparation");
        requireDigest(businessProofSha256);
        LocalAdmission result = transactions.execute(status -> {
            ResolveRequest request = preparation.request();
            persistence.setTenantContext(request.tenantId());
            serviceRole(request);
            persistence.lockAuthorityTuple(request.tenantId(), request.targetTuple());
            long transactionId = persistence.transactionId();
            LocalVerification current = prepareLocalLocked(request, transactionId);
            if (transactionId != preparation.transactionId()
                    || !current.equals(preparation)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            ResolveResponse response = request.resolutionMode()
                    == HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK
                    ? ResolveResponse.admit(preparation.authority(), null,
                            request.correlationId())
                    : appendPreparedDrain(preparation, businessProofSha256);
            return new LocalAdmission(preparation, response, businessProofSha256);
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    /** Rechecks an admitted snapshot without adding another drain activity. */
    public void recheckLocal(LocalAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        transactions.executeWithoutResult(status -> {
            ResolveRequest request = admission.preparation().request();
            persistence.setTenantContext(request.tenantId());
            serviceRole(request);
            persistence.lockAuthorityTuple(request.tenantId(), request.targetTuple());
            LocalVerification current = prepareLocalLocked(
                    request, admission.preparation().transactionId());
            if (!samePreparedAuthority(current, admission.preparation())) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            if (request.resolutionMode()
                    == HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING) {
                requireCurrentDrainAdmission(admission);
            } else if (admission.response().drainAdmission() != null) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
        });
    }

    /** Same authority/fence comparison for a not-yet-consumed preparation. */
    public void recheckLocal(LocalVerification preparation) {
        Objects.requireNonNull(preparation, "preparation");
        transactions.executeWithoutResult(status -> {
            ResolveRequest request = preparation.request();
            persistence.setTenantContext(request.tenantId());
            serviceRole(request);
            persistence.lockAuthorityTuple(request.tenantId(), request.targetTuple());
            LocalVerification current = prepareLocalLocked(
                    request, preparation.transactionId());
            if (!samePreparedAuthority(current, preparation)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
        });
    }

    public QuiesceBatch beginQuiesce(long tenantId,
            List<HrisServiceGrantAuthorityV1.TargetTuple> orderedTargets,
            UUID commandCorrelationId) {
        requirePositive(tenantId, "tenantId");
        HrisServiceGrantQuiesceService.requireOrderedTargets(orderedTargets);
        HrisServiceGrantQuiesceService.requireAtomicScope(orderedTargets);
        Objects.requireNonNull(commandCorrelationId);
        QuiesceBatch result = transactions.execute(status -> {
            persistence.setTenantContext(tenantId); persistence.lockAuthorityBatch(tenantId, orderedTargets);
            CurrentGrant current = persistence.currentGrantForUpdate(tenantId);
            if (!"ADMIT".equals(current.decision())) throw Hrm013SafeErrorV1.invalidState();
            if (!Set.copyOf(HrisServiceGrantQuiesceService.orderedBindingTargets(
                    bindingMaterials(tenantId, current.id()))).containsAll(orderedTargets))
                throw Hrm013SafeErrorV1.invalidState();
            Instant now = persistence.databaseNow();
            List<Fence> fences = new java.util.ArrayList<>();
            for (var target : orderedTargets) {
                Fence prior = latestFence(tenantId, target);
                if (prior != null && prior.quiescing()) throw Hrm013SafeErrorV1.invalidState();
                long revision = prior == null ? 1 : prior.revision() + 1;
                UUID id = UUID.randomUUID();
                persistence.insertFence(id, tenantId, current.id(), target, revision, "QUIESCING",
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
            persistence.setTenantContext(tenantId); persistence.lockAuthorityBatch(tenantId, orderedTargets);
            CurrentGrant grant = persistence.currentGrantForUpdate(tenantId);
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
                persistence.insertFence(id, tenantId, oldGrant, target, revision, "ABORTED", prior.id(),
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
            SuccessorGrant successor, UUID commandCorrelationId) {
        return cutover(tenantId, orderedTargets, readinessItems, successor,
                commandCorrelationId, readinessVerifier);
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
            persistence.setTenantContext(tenantId); persistence.lockAuthorityBatch(tenantId, orderedTargets);
            CurrentGrant current = persistence.currentGrantForUpdate(tenantId);
            if (!"ADMIT".equals(current.decision())) throw Hrm013SafeErrorV1.invalidState();
            HrisServiceGrantQuiesceService.requireCutoverSnapshot(orderedTargets,
                    bindingMaterials(tenantId, current.id()), successor);
            Instant transactionNow = persistence.databaseNow();
            Instant verificationNow = persistence.wallClockNow();
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
                        tenantId, target, current, fence, verificationNow);
                var verified = verifier.verifySigned(
                        new HrisServiceGrantRotationReadinessVerifier.LockedRotation(
                                tenantId, target, current.id(), current.tenantGrantRevision(),
                                current.authorizationRevision(), fence.id(), fence.revision(),
                                fence.effectiveAt(), verificationNow),
                        persistence.lockedAuthority(tenantId, target, current), expected,
                        grouped.get(target));
                verifiedReceipts.addAll(verified.receipts());
            }
            Instant replayLimit = transactionNow.plusSeconds(300);
            verifiedReceipts.forEach(receipt -> {
                Hrm013WorkloadProofV1.ReplayReservation replay = receipt.replay();
                Instant retainUntil = replay.retainUntil().isBefore(replayLimit)
                        ? replay.retainUntil() : replayLimit;
                persistence.reserveProofPair(new Hrm013WorkloadProofV1.ReplayReservation(
                        replay.tenantId(), replay.bindingId(), replay.callerWorkloadId(),
                        replay.proofIdSha256(), replay.requestNonceSha256(), retainUntil),
                        "ROTATION_READINESS");
            });
            String groupReadiness = HrisServiceGrantRotationReadinessVerifier.aggregateVerified(
                    verifiedReceipts);
            if (!groupReadiness.equals(successor.rotationReadinessDigest()))
                throw Hrm013SafeErrorV1.invalidState();
            long grantRevision = current.grantRevision() + 1;
            persistence.insertGrantSet(tenantId, successor.grantSetId(),
                    successor.tenantGrantRevision(), successor.authorizationRevision(),
                    successor.grantDigest(), grantRevision, current.id(), "ADMIT",
                    groupReadiness, null, transactionNow);
            insertBindings(tenantId, successor.grantSetId(), successor.bindings());
            List<UUID> fenceIds = new java.util.ArrayList<>();
            for (int i = 0; i < orderedTargets.size(); i++) {
                var target = orderedTargets.get(i); Fence prior = priorFences.get(i);
                UUID id = UUID.randomUUID(); long revision = prior.revision() + 1;
                persistence.insertFence(id, tenantId, current.id(), target, revision, "CUTOVER", prior.id(),
                        prior.effectiveAt(), commandCorrelationId,
                        HrisServiceGrantQuiesceService.fenceDigest(
                                tenantId, current.id(), target, revision, "CUTOVER",
                                prior.effectiveAt(), commandCorrelationId));
                fenceIds.add(id);
            }
            return new RotationResult(successor.grantSetId(), grantRevision,
                    successor.tenantGrantRevision(), successor.authorizationRevision(),
                    List.copyOf(fenceIds), transactionNow);
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    public RotationResult emergencyRevoke(long tenantId, String incidentEvidenceDigest,
            String revokedGrantDigest, UUID commandCorrelationId) {
        requirePositive(tenantId, "tenantId"); requireDigest(incidentEvidenceDigest);
        requireDigest(revokedGrantDigest); Objects.requireNonNull(commandCorrelationId);
        RotationResult result = transactions.execute(status -> {
            persistence.setTenantContext(tenantId);
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    ignored -> { }, "DWP_AUTH_HRM013_GRANT_V1:" + tenantId);
            CurrentGrant current = persistence.currentGrantForUpdate(tenantId);
            if (!"ADMIT".equals(current.decision())) throw Hrm013SafeErrorV1.invalidState();
            List<GrantBinding> bindings = bindingMaterials(tenantId, current.id());
            for (var target : HrisServiceGrantQuiesceService.orderedBindingTargets(bindings)) {
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                        ignored -> { }, fenceLockKey(tenantId, target));
            }
            UUID revoked = UUID.randomUUID(); Instant now = persistence.databaseNow();
            SuccessorGrant successor = new SuccessorGrant(revoked,
                    current.tenantGrantRevision() + 1, current.authorizationRevision() + 1,
                    revokedGrantDigest, null, bindings);
            persistence.insertGrantSet(tenantId, successor.grantSetId(),
                    successor.tenantGrantRevision(), successor.authorizationRevision(),
                    successor.grantDigest(), current.grantRevision() + 1, current.id(),
                    "REVOKE", null, incidentEvidenceDigest, now);
            insertBindings(tenantId, revoked, bindings);
            return new RotationResult(revoked, current.grantRevision() + 1,
                    successor.tenantGrantRevision(), successor.authorizationRevision(),
                    List.of(), now);
        });
        if (result == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return result;
    }

    private LocalVerification prepareLocalLocked(
            ResolveRequest request, long transactionId) {
        CurrentGrant current = persistence.currentGrant(request.tenantId());
        if (current == null || !"ADMIT".equals(current.decision())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        List<GrantAuthority> candidates = request.resolutionMode()
                == HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK
                ? persistence.currentAuthorities(request) : persistence.authorityAtSignedRevision(request);
        if (candidates.size() != 1) throw Hrm013SafeErrorV1.authorityDenied();
        GrantAuthority authority = candidates.getFirst();
        if (revisionMismatch(request, authority)
                || !current.id().equals(authority.grantSetId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Fence latest = currentFence(request).orElse(null);
        Fence frozenFence = null;
        if (request.resolutionMode()
                == HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK) {
            if (latest != null && latest.quiescing()) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
        } else {
            if (request.drainFields() == null || latest == null || !latest.quiescing()
                    || !latest.oldGrantSetId().equals(authority.grantSetId())) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            frozenFence = latest;
        }
        ServiceRole role = serviceRole(request);
        String digest = HrisServiceGrantQuiesceService.localPreparationDigest(
                request, authority, role, frozenFence, transactionId);
        return new LocalVerification(request, authority, role, frozenFence,
                transactionId, digest);
    }

    private ResolveResponse appendPreparedDrain(
            LocalVerification preparation, String proofSha256) {
        ResolveRequest request = preparation.request();
        GrantAuthority authority = preparation.authority();
        Fence fence = preparation.authorityFence();
        ServiceRole role = preparation.attestingServiceRole();
        HrisServiceGrantAuthorityV1.DrainFields drain = request.drainFields();
        if (fence == null || drain == null) throw Hrm013SafeErrorV1.authorityDenied();
        Instant recordedAt = persistence.databaseNow();
        Long previous = jdbc.queryForObject("""
                SELECT COALESCE(MAX(activity_revision), 0)
                  FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id = ? AND quiesce_fence_id = ?
                   AND attesting_service_role = ?
                """, Long.class, request.tenantId(), fence.id(), role.name());
        long revision = Objects.requireNonNullElse(previous, 0L) + 1;
        int inserted = jdbc.update("""
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
                """, UUID.randomUUID(), request.tenantId(), fence.id(),
                authority.grantSetId(), authority.tenantGrantRevision(),
                authority.authorizationRevision(), request.targetBindingId(),
                request.targetCallerWorkloadId(), request.targetOwnerWorkloadId(),
                request.targetPurpose(), role.name(), request.requestingWorkloadId(),
                revision, drain.drainContinuationKind().name(),
                drain.drainRootWorkItemId(), java.sql.Timestamp.from(
                        drain.drainRootCreatedAt()), drain.drainRootPayloadSha256(),
                drain.drainContinuationEventId(), drain.drainContinuationRevision(),
                drain.drainContinuationPayloadSha256(), proofSha256,
                request.correlationId(), java.sql.Timestamp.from(recordedAt));
        if (inserted != 1) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        DrainAdmission admission = new DrainAdmission(fence.id(), fence.revision(),
                fence.effectiveAt(), role, revision, recordedAt,
                recordedAt.plusSeconds(30), recordedAt.plusSeconds(65));
        return ResolveResponse.admit(authority, admission, request.correlationId());
    }

    private void requireCurrentDrainAdmission(LocalAdmission admission) {
        LocalVerification preparation = admission.preparation();
        ResolveRequest request = preparation.request();
        DrainAdmission drainAdmission = admission.response().drainAdmission();
        Fence fence = preparation.authorityFence();
        if (drainAdmission == null || fence == null
                || !drainAdmission.quiesceFenceId().equals(fence.id())
                || drainAdmission.quiesceRevision() != fence.revision()
                || !drainAdmission.quiesceEffectiveAt().equals(fence.effectiveAt())
                || drainAdmission.attestingServiceRole()
                        != preparation.attestingServiceRole()
                || !persistence.wallClockNow().isBefore(
                        drainAdmission.drainAuthorizationExpiresAt())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        HrisServiceGrantAuthorityV1.DrainFields drain = request.drainFields();
        Integer matches = jdbc.queryForObject("""
                SELECT count(*)
                  FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id=? AND quiesce_fence_id=? AND old_grant_set_id=?
                   AND tenant_grant_revision=? AND authorization_revision=?
                   AND binding_id=? AND caller_workload_id=?
                   AND owner_workload_id=? AND purpose=?
                   AND attesting_service_role=? AND service_workload_id=?
                   AND activity_revision=? AND continuation_kind=?
                   AND root_work_item_id=? AND root_created_at=?
                   AND root_payload_sha256=? AND continuation_event_id=?
                   AND continuation_revision=? AND continuation_payload_sha256=?
                   AND resolver_proof_sha256=? AND correlation_id=?
                   AND recorded_at=?
                """, Integer.class, request.tenantId(), fence.id(),
                preparation.authority().grantSetId(),
                preparation.authority().tenantGrantRevision(),
                preparation.authority().authorizationRevision(),
                request.targetBindingId(), request.targetCallerWorkloadId(),
                request.targetOwnerWorkloadId(), request.targetPurpose(),
                preparation.attestingServiceRole().name(),
                request.requestingWorkloadId(),
                drainAdmission.drainActivityRevision(),
                drain.drainContinuationKind().name(), drain.drainRootWorkItemId(),
                java.sql.Timestamp.from(drain.drainRootCreatedAt()),
                drain.drainRootPayloadSha256(), drain.drainContinuationEventId(),
                drain.drainContinuationRevision(),
                drain.drainContinuationPayloadSha256(), admission.proofSha256(),
                request.correlationId(),
                java.sql.Timestamp.from(drainAdmission.drainActivityRecordedAt()));
        if (matches == null || matches != 1) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static boolean samePreparedAuthority(
            LocalVerification current, LocalVerification expected) {
        return current.request().equals(expected.request())
                && current.authority().equals(expected.authority())
                && current.attestingServiceRole() == expected.attestingServiceRole()
                && Objects.equals(current.authorityFence(), expected.authorityFence())
                && current.preparationSha256().equals(expected.preparationSha256());
    }
    private ResolveResponse resolveNewWork(ResolveRequest request) {
        CurrentGrant current = persistence.currentGrant(request.tenantId());
        if (current == null) return opaque(Status.MISSING, request);
        if (!"ADMIT".equals(current.decision())) return opaque(Status.REVOKE, request);
        List<GrantAuthority> authorities = persistence.currentAuthorities(request);
        if (authorities.isEmpty()) return opaque(Status.MISSING, request);
        if (authorities.size() != 1) return opaque(Status.AMBIGUOUS, request);
        GrantAuthority authority = authorities.getFirst();
        if (revisionMismatch(request, authority)) return opaque(Status.REVISION_MISMATCH, request);
        if (currentFence(request).map(Fence::quiescing).orElse(false)) {
            return opaque(Status.QUIESCING, request);
        }
        return ResolveResponse.admit(authority, null, request.correlationId());
    }
    private ResolveResponse resolveDrain(ResolveRequest request, String proofSha256) {
        CurrentGrant current = persistence.currentGrant(request.tenantId());
        if (current == null) return opaque(Status.MISSING, request);
        if (!"ADMIT".equals(current.decision())) return opaque(Status.REVOKE, request);
        List<GrantAuthority> authorities = persistence.authorityAtSignedRevision(request);
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
        Instant recordedAt = persistence.databaseNow();
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
        CurrentGrant current = persistence.currentGrant(request.tenantId());
        if (current == null) return opaque(Status.MISSING, request);
        if (!"ADMIT".equals(current.decision())) return opaque(Status.REVOKE, request);
        List<GrantAuthority> authorities = persistence.authorityAtSignedRevision(request);
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
        ActivityHead head = persistence.latestActivity(request.tenantId(), fence.id(), role);
        Instant quietSince = head.recordedAt() == null
                ? fence.effectiveAt() : head.recordedAt();
        Instant eligibleAt = quietSince.plusSeconds(65);
        if (persistence.wallClockNow().isBefore(eligibleAt)) return opaque(Status.QUIESCING, request);
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

    static String fenceLockKey(long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target) {
        return "DWP_AUTH_HRM013_FENCE_V1:" + tenantId + ':' + target.bindingId() + ':'
                + target.callerWorkloadId() + ':' + target.ownerWorkloadId() + ':'
                + target.purpose();
    }

    private List<ReadinessObservation> lockedObservations(long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target, CurrentGrant grant,
            Fence fence, Instant now) {
        List<ReadinessObservation> result = new java.util.ArrayList<>();
        for (ServiceRole role : ServiceRole.values()) {
            ActivityHead head = persistence.latestActivity(tenantId, fence.id(), role);
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

    private List<GrantBinding> bindingMaterials(long tenantId, UUID grantSetId) {
        return persistence.bindingMaterials(tenantId, grantSetId).stream()
                .map(value -> new GrantBinding(value.target(), value.callerSpiffeId(),
                        value.callerSigningKeyId(), value.callerSigningPublicJwk(),
                        value.ownerSpiffeId(), value.ownerSigningKeyId(),
                        value.ownerSigningPublicJwk(), value.bindingDigest())).toList();
    }

    private void insertBindings(long tenantId, UUID grantSetId, List<GrantBinding> bindings) {
        persistence.insertBindings(tenantId, grantSetId, bindings.stream()
                .map(value -> new HrisServiceGrantAuthorityPersistence.BindingMaterial(
                        value.target(), value.callerSpiffeId(), value.callerSigningKeyId(),
                        value.callerSigningPublicJwk(), value.ownerSpiffeId(),
                        value.ownerSigningKeyId(), value.ownerSigningPublicJwk(),
                        value.bindingDigest())).toList());
    }

    private Fence latestFence(long tenantId, HrisServiceGrantAuthorityV1.TargetTuple target) {
        var row = persistence.latestFence(tenantId, target);
        return row == null ? null : new Fence(row.id(), row.oldGrantSetId(),
                row.revision(), row.state(), row.effectiveAt());
    }

    private java.util.Optional<Fence> currentFence(ResolveRequest request) {
        return persistence.currentFence(request).map(row -> new Fence(row.id(),
                row.oldGrantSetId(), row.revision(), row.state(), row.effectiveAt()));
    }

}
