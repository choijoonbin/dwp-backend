package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/** Proof-first requester identity authority; no fallback directory or fuzzy lookup exists. */
public final class Hrm013RequesterIdentityAuthorityService {
    private final Hrm013RequesterIdentityAuthorityRepository repository;
    private final Hrm013RequesterIdentityProofVerifier proofVerifier;

    public Hrm013RequesterIdentityAuthorityService(
            Hrm013RequesterIdentityAuthorityRepository repository,
            Hrm013RequesterIdentityProofVerifier proofVerifier) {
        this.repository = Objects.requireNonNull(repository);
        this.proofVerifier = Objects.requireNonNull(proofVerifier);
    }

    public Hrm013RequesterIdentityAuthorityV1.ResolveResponse resolve(
            Hrm013RequesterIdentityIngressFilter.RequestContext context) {
        Objects.requireNonNull(context, "context");
        try {
            return resolveAvailable(context);
        } catch (Hrm013SafeErrorV1.Failure failure) {
            if (failure.errorCode() != Hrm013SafeErrorV1.ErrorCode.AUTHORITY_UNAVAILABLE)
                throw failure;
            return unavailable(context.request());
        } catch (DataAccessException | TransactionException unavailable) {
            return unavailable(context.request());
        }
    }

    private Hrm013RequesterIdentityAuthorityV1.ResolveResponse resolveAvailable(
            Hrm013RequesterIdentityIngressFilter.RequestContext context) {
        Hrm013RequesterIdentityAuthorityV1.ResolveRequest request = context.request();
        Hrm013RequesterIdentityProofVerifier.Verification verification = proofVerifier.verify(context,
                repository::resolveSigningKey, repository::reserveReplay,
                repository::remainsCurrent);
        Hrm013WorkloadProofV1.VerifiedProof verified = verification.proof();
        Hrm013WorkloadProofV1.ProofClaims claims = verified.claims();
        Map<String, Object> fields = claims.profileClaims();
        UUID approvalRequestId = uuid(fields.get("approvalRequestId"));
        UUID requestedByActorPublicId = uuid(fields.get("requestedByActorPublicId"));
        if (!request.approvalRequestId().equals(approvalRequestId)
                || !request.requestedByActorPublicId().equals(requestedByActorPublicId)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Hrm013WorkloadProofV1.DrainClaims drain = claims.drainClaims();
        Instant enclosingRequestedAt = drain == null
                ? claims.issuedAt() : drain.drainRootCreatedAt();
        String enclosingPayload = drain == null
                ? claims.requestBodySha256() : drain.drainRootPayloadSha256();
        Hrm013RequesterIdentityAuthorityV1.ProofBinding binding =
                new Hrm013RequesterIdentityAuthorityV1.ProofBinding(
                        Hrm013RequesterIdentityAuthorityV1.BINDING_ID,
                        Hrm013RequesterIdentityAuthorityV1.CALLER_WORKLOAD_ID,
                        Hrm013RequesterIdentityAuthorityV1.OWNER_WORKLOAD_ID,
                        Hrm013RequesterIdentityAuthorityV1.PURPOSE, claims.tenantId(),
                        approvalRequestId, requestedByActorPublicId, claims.correlationId(),
                        claims.tenantGrantRevision(), claims.authorizationRevision(),
                        claims.grantUseMode(), drain, enclosingRequestedAt, enclosingPayload,
                        null, true);
        Hrm013RequesterIdentityAuthorityRepository.DirectoryResult result =
                repository.resolveIdentity(
                        request, binding, verification.signingKey(), claims.expiresAt(),
                        verified.proofSha256());
        if (!result.authorityCurrent() || result.requester() == null) {
            return Hrm013RequesterIdentityAuthorityV1.ResolveResponse.opaque(
                    Hrm013RequesterIdentityAuthorityV1.Status.DENY,
                    request.correlationId());
        }
        try {
            Hrm013RequesterIdentityAuthorityV1.ProofBinding admissionBinding =
                    new Hrm013RequesterIdentityAuthorityV1.ProofBinding(
                    binding.bindingId(), binding.callerWorkloadId(), binding.ownerWorkloadId(),
                    binding.purpose(), binding.tenantId(), binding.approvalRequestId(),
                    binding.requestedByActorPublicId(), binding.correlationId(),
                    binding.tenantGrantRevision(), binding.authorizationRevision(),
                    binding.grantUseMode(), binding.drainClaims(),
                    binding.enclosingApprovalRequestedAt(),
                    binding.enclosingApprovalPayloadSha256(),
                    result.drainAuthorizationExpiresAt(), binding.authorityUsable());
            return Hrm013RequesterIdentityAuthorityV1.admit(
                    request, admissionBinding, result.requester(), result.databaseNow());
        } catch (Hrm013SafeErrorV1.Failure denied) {
            return Hrm013RequesterIdentityAuthorityV1.ResolveResponse.opaque(
                    Hrm013RequesterIdentityAuthorityV1.Status.DENY,
                    request.correlationId());
        }
    }

    private static Hrm013RequesterIdentityAuthorityV1.ResolveResponse unavailable(
            Hrm013RequesterIdentityAuthorityV1.ResolveRequest request) {
        return Hrm013RequesterIdentityAuthorityV1.ResolveResponse.opaque(
                Hrm013RequesterIdentityAuthorityV1.Status.UNAVAILABLE,
                request.correlationId());
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) return uuid;
        if (value instanceof String raw) {
            try { return UUID.fromString(raw); }
            catch (IllegalArgumentException invalid) { throw Hrm013SafeErrorV1.authorityDenied(); }
        }
        throw Hrm013SafeErrorV1.authorityDenied();
    }
}
