package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/** Application service that preserves proof/replay/read ordering for the resolver. */
public final class HrisServiceGrantAuthorityService {
    private static final Set<HrisServiceGrantAuthorityV1.TargetTuple> SEALED_TARGETS =
            sealedTargets();
    private final HrisServiceGrantAuthorityRepository repository;

    public HrisServiceGrantAuthorityService(HrisServiceGrantAuthorityRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public HrisServiceGrantAuthorityV1.ResolveResponse resolve(
            HrisServiceGrantAuthorityRequestContext context) {
        Objects.requireNonNull(context, "context");
        HrisServiceGrantAuthorityV1.ResolveRequest request = context.request();
        Hrm013WorkloadProofV1.VerifiedProof proof = context.verifiedProof();
        if (proof == null || proof.claims() == null
                || proof.profile().kind() != Hrm013WorkloadProofV1.ProfileKind.RESOLVER_BOOTSTRAP
                || proof.claims().tenantId() != request.tenantId()
                || !proof.claims().correlationId().equals(request.correlationId())
                || !proof.claims().issuer().equals(request.requestingWorkloadId())
                || !proof.claims().subject().equals(request.requestingWorkloadId())
                || !proof.claims().bindingId().equals(context.resolverCaller().bindingId())
                || !proof.proofSha256().equals(context.resolverProofSha256())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        requireBootstrapAdmission(context, proof);
        Hrm013WorkloadProofV1.ReplayReservation replay =
                new Hrm013WorkloadProofV1.ReplayReservation(request.tenantId(),
                        context.resolverCaller().bindingId(),
                        request.requestingWorkloadId(), context.jtiDigest(),
                        context.requestNonceDigest(), context.proofExpiresAt());
        try {
            return repository.resolve(request, replay, context.resolverProofSha256());
        } catch (DataAccessException | TransactionException unavailable) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    private static void requireBootstrapAdmission(
            HrisServiceGrantAuthorityRequestContext context,
            Hrm013WorkloadProofV1.VerifiedProof proof) {
        HrisServiceGrantAuthorityV1.ResolveRequest request = context.request();
        Hrm013WorkloadProofV1.ProofClaims value = proof.claims();
        Map<String, Object> fields = value.profileClaims();
        HrisServiceGrantAuthorityV1.DrainFields drain = value.drainClaims() == null
                ? null : new HrisServiceGrantAuthorityV1.DrainFields(
                value.drainClaims().drainRootWorkItemId(),
                value.drainClaims().drainRootCreatedAt(),
                value.drainClaims().drainRootPayloadSha256(),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.valueOf(
                        value.drainClaims().drainContinuationKind().name()),
                value.drainClaims().drainContinuationEventId(),
                value.drainClaims().drainContinuationRevision(),
                value.drainClaims().drainContinuationPayloadSha256());
        var claims = new HrisServiceGrantAuthorityV1.BootstrapClaims(
                value.bindingId(), value.issuer(), value.audience(), value.subject(),
                value.purpose(), value.method(), value.path(), value.tenantId(),
                text(fields, "requestingWorkloadId"), text(fields, "targetCallerWorkloadId"),
                text(fields, "targetOwnerWorkloadId"), text(fields, "targetBindingId"),
                text(fields, "targetPurpose"),
                HrisServiceGrantAuthorityV1.ResolutionMode.valueOf(
                        value.resolutionMode().name()),
                value.tenantGrantRevision(), value.authorizationRevision(),
                value.correlationId(), drain, value.requestBodySha256(),
                value.authentication());
        HrisServiceGrantAuthorityV1.requireBootstrapAdmission(
                HrisServiceGrantAuthorityV1.bootstrapProfile(
                        context.resolverCaller().bindingId()),
                request, claims, context.exactBody(), SEALED_TARGETS::contains);
    }

    private static Set<HrisServiceGrantAuthorityV1.TargetTuple> sealedTargets() {
        LinkedHashSet<HrisServiceGrantAuthorityV1.TargetTuple> targets = new LinkedHashSet<>();
        for (Hrm013SeparationBridgeV1.BindingProfile profile
                : Hrm013SeparationBridgeV1.PROFILES) {
            targets.add(new HrisServiceGrantAuthorityV1.TargetTuple(profile.bindingId(),
                    profile.callerWorkloadId(), profile.ownerWorkloadId(), profile.purpose()));
        }
        for (Hrm013TaskDispatchV1.Profile profile : Hrm013TaskDispatchV1.PROFILES) {
            targets.add(new HrisServiceGrantAuthorityV1.TargetTuple(profile.bindingId(),
                    profile.callerWorkloadId(), profile.ownerWorkloadId(), profile.purpose()));
        }
        targets.add(new HrisServiceGrantAuthorityV1.TargetTuple(
                Hrm013RequesterIdentityAuthorityV1.BINDING_ID,
                Hrm013RequesterIdentityAuthorityV1.CALLER_WORKLOAD_ID,
                Hrm013RequesterIdentityAuthorityV1.OWNER_WORKLOAD_ID,
                Hrm013RequesterIdentityAuthorityV1.PURPOSE));
        return Set.copyOf(targets);
    }

    private static String text(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof String text) || text.isBlank())
            throw Hrm013SafeErrorV1.authorityDenied();
        return text;
    }
}
