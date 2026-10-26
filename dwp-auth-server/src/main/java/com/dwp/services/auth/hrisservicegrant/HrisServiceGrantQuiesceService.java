package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolveRequest;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolveResponse;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.RsaPublicJwk;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** In-process parity boundary for Auth-owned HRM-013 roles; never performs loopback HTTP. */
public final class HrisServiceGrantQuiesceService {
    public static final HrisServiceGrantAuthorityV1.TargetTuple REQUESTER = tuple(
            "SVC-AUT-HRM-013", "dwp-approval-server", "dwp-auth-server",
            "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE");
    public static final HrisServiceGrantAuthorityV1.TargetTuple TIME_DISPATCH =
            dispatch(Hrm013TaskDispatchV1.TIME);
    public static final HrisServiceGrantAuthorityV1.TargetTuple TIME_RECEIPT =
            bridge(Hrm013SeparationBridgeV1.HRM_008_TIME);
    public static final HrisServiceGrantAuthorityV1.TargetTuple PAYROLL_DISPATCH =
            dispatch(Hrm013TaskDispatchV1.PAYROLL);
    public static final HrisServiceGrantAuthorityV1.TargetTuple PAYROLL_RECEIPT =
            bridge(Hrm013SeparationBridgeV1.HRM_008_PAYROLL);
    public static final HrisServiceGrantAuthorityV1.TargetTuple IDENTITY_DISPATCH =
            dispatch(Hrm013TaskDispatchV1.IDENTITY);
    public static final HrisServiceGrantAuthorityV1.TargetTuple IDENTITY_RECEIPT =
            bridge(Hrm013SeparationBridgeV1.HRM_008_IDENTITY);
    public static final HrisServiceGrantAuthorityV1.TargetTuple WAIVER_REQUEST =
            bridge(Hrm013SeparationBridgeV1.APR_002);
    public static final HrisServiceGrantAuthorityV1.TargetTuple WAIVER_DECISION =
            bridge(Hrm013SeparationBridgeV1.HRM_010);

    private static final List<Set<HrisServiceGrantAuthorityV1.TargetTuple>> TASK_PAIRS = List.of(
            exactPair(Hrm013TaskDispatchV1.TIME, Hrm013SeparationBridgeV1.HRM_008_TIME),
            exactPair(Hrm013TaskDispatchV1.PAYROLL, Hrm013SeparationBridgeV1.HRM_008_PAYROLL),
            exactPair(Hrm013TaskDispatchV1.IDENTITY, Hrm013SeparationBridgeV1.HRM_008_IDENTITY));
    private static final Set<HrisServiceGrantAuthorityV1.TargetTuple> WAIVER_GROUP =
            Set.of(WAIVER_REQUEST, WAIVER_DECISION, REQUESTER);
    private static final Comparator<HrisServiceGrantAuthorityV1.TargetTuple> ORDER =
            Comparator.comparing(HrisServiceGrantAuthorityV1.TargetTuple::bindingId)
                    .thenComparing(HrisServiceGrantAuthorityV1.TargetTuple::callerWorkloadId)
                    .thenComparing(HrisServiceGrantAuthorityV1.TargetTuple::ownerWorkloadId)
                    .thenComparing(HrisServiceGrantAuthorityV1.TargetTuple::purpose);

    private final HrisServiceGrantAuthorityRepository repository;
    private final HrisServiceGrantRotationReadinessVerifier readinessVerifier;

    public HrisServiceGrantQuiesceService(HrisServiceGrantAuthorityRepository repository) {
        this(repository, new HrisServiceGrantRotationReadinessVerifier());
    }

    public HrisServiceGrantQuiesceService(HrisServiceGrantAuthorityRepository repository,
            HrisServiceGrantRotationReadinessVerifier readinessVerifier) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.readinessVerifier = Objects.requireNonNull(readinessVerifier, "readinessVerifier");
    }

    public QuiesceBatch beginQuiesce(long tenantId,
            Collection<HrisServiceGrantAuthorityV1.TargetTuple> requested,
            UUID commandCorrelationId) {
        return repository.beginQuiesce(tenantId, expandedLockOrder(requested),
                commandCorrelationId);
    }

    public QuiesceBatch abort(long tenantId,
            Collection<HrisServiceGrantAuthorityV1.TargetTuple> requested,
            UUID commandCorrelationId) {
        return repository.abortQuiesce(tenantId, expandedLockOrder(requested),
                commandCorrelationId);
    }

    public RotationResult cutover(long tenantId,
            Collection<HrisServiceGrantAuthorityV1.TargetTuple> requested,
            List<HrisServiceGrantRotationReadinessVerifier.ReadinessItem> readinessItems,
            SuccessorGrant successor, UUID commandCorrelationId) {
        List<HrisServiceGrantAuthorityV1.TargetTuple> scope = expandedLockOrder(requested);
        return repository.cutover(tenantId, scope, readinessItems, successor,
                commandCorrelationId, readinessVerifier);
    }

    public RotationResult emergencyRevoke(long tenantId,
            String incidentEvidenceDigest, String revokedGrantDigest,
            UUID commandCorrelationId) {
        return repository.emergencyRevoke(tenantId, incidentEvidenceDigest,
                revokedGrantDigest, commandCorrelationId);
    }

    public ResolveResponse authorizeLocalNewWork(ResolveRequest request,
            Hrm013WorkloadProofV1.ReplayReservation replay, String proofSha256) {
        requireMode(request, HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK);
        requireAuthLocal(request);
        requireLocalEvidence(request, replay);
        return repository.resolveLocal(request, proofSha256);
    }

    public ResolveResponse authorizeLocalDrain(ResolveRequest request,
            Hrm013WorkloadProofV1.ReplayReservation replay, String proofSha256) {
        requireMode(request, HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING);
        requireAuthLocal(request);
        requireLocalEvidence(request, replay);
        return repository.resolveLocal(request, proofSha256);
    }

    public ResolveResponse observeLocalReadiness(ResolveRequest request,
            Hrm013WorkloadProofV1.ReplayReservation replay, String proofSha256) {
        requireMode(request, HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE);
        requireAuthLocal(request);
        requireLocalEvidence(request, replay);
        return repository.resolveLocal(request, proofSha256);
    }

    /** Expands pair/group dependencies and returns the single global lock order. */
    public static List<HrisServiceGrantAuthorityV1.TargetTuple> expandedLockOrder(
            Collection<HrisServiceGrantAuthorityV1.TargetTuple> requested) {
        if (requested == null || requested.isEmpty())
            throw new IllegalArgumentException("rotation scope is empty");
        LinkedHashSet<HrisServiceGrantAuthorityV1.TargetTuple> expanded =
                new LinkedHashSet<>(requested);
        TASK_PAIRS.stream().filter(pair -> requested.stream().anyMatch(pair::contains))
                .forEach(expanded::addAll);
        if (requested.stream().anyMatch(WAIVER_GROUP::contains)) expanded.addAll(WAIVER_GROUP);
        ArrayList<HrisServiceGrantAuthorityV1.TargetTuple> ordered =
                new ArrayList<>(expanded);
        ordered.sort(ORDER);
        return List.copyOf(ordered);
    }

    /** Pair/group membership must be complete for admit, cutover, abort and revoke. */
    public static void requireAtomicScope(
            Collection<HrisServiceGrantAuthorityV1.TargetTuple> scope) {
        Set<HrisServiceGrantAuthorityV1.TargetTuple> actual = Set.copyOf(scope);
        List<HrisServiceGrantAuthorityV1.TargetTuple> expanded = expandedLockOrder(actual);
        if (!actual.equals(Set.copyOf(expanded))) throw Hrm013SafeErrorV1.invalidState();
    }

    private static void requireAuthLocal(ResolveRequest request) {
        HrisServiceGrantAuthorityV1.TargetTuple target = request.targetTuple();
        if (!Set.of(REQUESTER, IDENTITY_DISPATCH, IDENTITY_RECEIPT).contains(target)
                || !request.requestingWorkloadId().equals("dwp-auth-server")) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static void requireMode(ResolveRequest request,
            HrisServiceGrantAuthorityV1.ResolutionMode expected) {
        Objects.requireNonNull(request, "request");
        if (request.resolutionMode() != expected) throw Hrm013SafeErrorV1.authorityDenied();
    }

    private static void requireLocalEvidence(ResolveRequest request,
            Hrm013WorkloadProofV1.ReplayReservation replay) {
        Objects.requireNonNull(replay, "replay evidence");
        if (replay.tenantId() != request.tenantId()
                || !replay.bindingId().equals(request.targetBindingId())
                || !replay.callerWorkloadId().equals(request.requestingWorkloadId()))
            throw Hrm013SafeErrorV1.authorityDenied();
    }

    private static HrisServiceGrantAuthorityV1.TargetTuple tuple(
            String binding, String caller, String owner, String purpose) {
        return new HrisServiceGrantAuthorityV1.TargetTuple(binding, caller, owner, purpose);
    }

    private static HrisServiceGrantAuthorityV1.TargetTuple dispatch(
            Hrm013TaskDispatchV1.Profile profile) {
        return tuple(profile.bindingId(), profile.callerWorkloadId(),
                profile.ownerWorkloadId(), profile.purpose());
    }

    private static HrisServiceGrantAuthorityV1.TargetTuple bridge(
            Hrm013SeparationBridgeV1.BindingProfile profile) {
        return tuple(profile.bindingId(), profile.callerWorkloadId(),
                profile.ownerWorkloadId(), profile.purpose());
    }

    private static Set<HrisServiceGrantAuthorityV1.TargetTuple> exactPair(
            Hrm013TaskDispatchV1.Profile dispatch,
            Hrm013SeparationBridgeV1.BindingProfile receipt) {
        if (!dispatch.callbackBindingId().equals(receipt.bindingId())
                || !dispatch.callbackPurpose().equals(receipt.purpose())
                || !dispatch.ownerWorkloadId().equals(receipt.callerWorkloadId())
                || !dispatch.callerWorkloadId().equals(receipt.ownerWorkloadId()))
            throw new ExceptionInInitializerError("HRM-013 task pair registry mismatch");
        return Set.of(dispatch(dispatch), bridge(receipt));
    }

    static void requireOrderedTargets(List<HrisServiceGrantAuthorityV1.TargetTuple> targets) {
        if (targets == null || targets.isEmpty() || Set.copyOf(targets).size() != targets.size())
            throw Hrm013SafeErrorV1.invalidState();
        List<HrisServiceGrantAuthorityV1.TargetTuple> sorted = targets.stream()
                .sorted(ORDER).toList();
        if (!targets.equals(sorted)) throw Hrm013SafeErrorV1.invalidState();
    }

    static void requireCutoverSnapshot(List<HrisServiceGrantAuthorityV1.TargetTuple> affected,
            List<GrantBinding> current, SuccessorGrant successor) {
        Objects.requireNonNull(successor); requireOrderedTargets(affected);
        requireAtomicScope(affected);
        Map<HrisServiceGrantAuthorityV1.TargetTuple, GrantBinding> before = unique(current);
        Map<HrisServiceGrantAuthorityV1.TargetTuple, GrantBinding> after =
                unique(successor.bindings());
        Set<HrisServiceGrantAuthorityV1.TargetTuple> scope = Set.copyOf(affected);
        if (!before.keySet().containsAll(scope) || !before.keySet().containsAll(after.keySet()))
            throw Hrm013SafeErrorV1.invalidState();
        before.forEach((target, binding) -> {
            if (!scope.contains(target) && !binding.equals(after.get(target)))
                throw Hrm013SafeErrorV1.invalidState();
        });
        List<Set<HrisServiceGrantAuthorityV1.TargetTuple>> groups = new ArrayList<>(TASK_PAIRS);
        groups.add(WAIVER_GROUP);
        for (Set<HrisServiceGrantAuthorityV1.TargetTuple> group : groups) {
            if (group.stream().noneMatch(scope::contains)) continue;
            long retained = group.stream().filter(after::containsKey).count();
            if (retained != 0 && retained != group.size())
                throw Hrm013SafeErrorV1.invalidState();
        }
    }

    private static Map<HrisServiceGrantAuthorityV1.TargetTuple, GrantBinding> unique(
            List<GrantBinding> bindings) {
        java.util.LinkedHashMap<HrisServiceGrantAuthorityV1.TargetTuple, GrantBinding> values =
                new java.util.LinkedHashMap<>();
        for (GrantBinding binding : bindings)
            if (values.put(binding.target(), binding) != null)
                throw Hrm013SafeErrorV1.invalidState();
        return Map.copyOf(values);
    }

    static List<HrisServiceGrantAuthorityV1.TargetTuple> orderedBindingTargets(
            List<GrantBinding> bindings) {
        return bindings.stream().map(GrantBinding::target).sorted(ORDER).toList();
    }

    static String fenceDigest(long tenantId, UUID grantSetId,
            HrisServiceGrantAuthorityV1.TargetTuple target, long revision, String state,
            Instant effectiveAt, UUID correlationId) {
        return digestLines(List.of(Long.toString(tenantId), grantSetId.toString(),
                target.bindingId(), target.callerWorkloadId(), target.ownerWorkloadId(),
                target.purpose(), Long.toString(revision), state, effectiveAt.toString(),
                correlationId.toString()));
    }

    static String digestLines(List<String> values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record QuiesceBatch(long tenantId, UUID oldGrantSetId,
            long tenantGrantRevision, long authorizationRevision, Instant effectiveAt,
            List<Fence> fences) {
        public QuiesceBatch { fences = List.copyOf(fences); }
    }

    public record RotationResult(UUID grantSetId, long grantRevision,
            long tenantGrantRevision, long authorizationRevision, List<UUID> fenceIds,
            Instant recordedAt) {
        public RotationResult { fenceIds = List.copyOf(fenceIds); }
    }

    public record SuccessorGrant(UUID grantSetId, long tenantGrantRevision,
            long authorizationRevision, String grantDigest, String rotationReadinessDigest,
            List<GrantBinding> bindings) {
        public SuccessorGrant {
            Objects.requireNonNull(grantSetId);
            requirePositive(tenantGrantRevision, "tenantGrantRevision");
            requirePositive(authorizationRevision, "authorizationRevision");
            requireDigest(grantDigest);
            if (rotationReadinessDigest != null) requireDigest(rotationReadinessDigest);
            bindings = List.copyOf(bindings);
            if (bindings.isEmpty()) throw new IllegalArgumentException("bindings are empty");
        }
    }

    public record GrantBinding(HrisServiceGrantAuthorityV1.TargetTuple target,
            String callerSpiffeId, String callerSigningKeyId,
            RsaPublicJwk callerSigningPublicJwk, String ownerSpiffeId,
            String ownerSigningKeyId, RsaPublicJwk ownerSigningPublicJwk,
            String bindingDigest) {
        public GrantBinding {
            Objects.requireNonNull(target); Objects.requireNonNull(callerSpiffeId);
            Objects.requireNonNull(callerSigningKeyId); Objects.requireNonNull(callerSigningPublicJwk);
            Objects.requireNonNull(ownerSpiffeId); Objects.requireNonNull(ownerSigningKeyId);
            Objects.requireNonNull(ownerSigningPublicJwk); requireDigest(bindingDigest);
            if (!callerSigningKeyId.equals(callerSigningPublicJwk.kid())
                    || !ownerSigningKeyId.equals(ownerSigningPublicJwk.kid())
                    || !callerSpiffeId.endsWith('/' + target.callerWorkloadId())
                    || !ownerSpiffeId.endsWith('/' + target.ownerWorkloadId()))
                throw new IllegalArgumentException("binding credential tuple mismatch");
        }
    }

    public record Fence(UUID id, UUID oldGrantSetId, long revision, String state,
            Instant effectiveAt) {
        boolean quiescing() { return "QUIESCING".equals(state); }
    }

    static ResolveResponse opaque(HrisServiceGrantAuthorityV1.Status status,
            ResolveRequest request) {
        return ResolveResponse.opaque(status, request.correlationId());
    }

    static boolean revisionMismatch(ResolveRequest request,
            HrisServiceGrantAuthorityV1.GrantAuthority authority) {
        return request.signedTenantGrantRevision() != authority.tenantGrantRevision()
                || request.signedAuthorizationRevision() != authority.authorizationRevision();
    }

    static HrisServiceGrantAuthorityV1.ServiceRole serviceRole(ResolveRequest request) {
        if (request.requestingWorkloadId().equals(request.targetCallerWorkloadId()))
            return HrisServiceGrantAuthorityV1.ServiceRole.CALLER;
        if (request.requestingWorkloadId().equals(request.targetOwnerWorkloadId()))
            return HrisServiceGrantAuthorityV1.ServiceRole.OWNER;
        throw Hrm013SafeErrorV1.authorityDenied();
    }

    static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw Hrm013SafeErrorV1.authorityDenied();
    }

    static void requirePositive(long value, String label) {
        if (value < 1) throw new IllegalArgumentException(label + " must be positive");
    }
}
