package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.AuthorityRechecker;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.ReplayStore;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.SigningKeyResolver;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.VerifiedProof;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.VerifiedSigningKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/** Verifies the requester business proof with the exact admitted Approval grant key. */
public final class Hrm013RequesterIdentityProofVerifier {
    private final ObjectMapper mapper;
    private final Clock clock;

    public Hrm013RequesterIdentityProofVerifier(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public Verification verify(
            Hrm013RequesterIdentityIngressFilter.RequestContext context,
            SigningKeyResolver signingKeyResolver,
            ReplayStore replayStore,
            AuthorityRechecker authorityRechecker) {
        AtomicReference<RuntimeException> infrastructureFailure = new AtomicReference<>();
        try {
            var profile = Hrm013WorkloadProofV1.requesterIdentity();
            AtomicReference<VerifiedSigningKey> verifiedKey = new AtomicReference<>();
            VerifiedProof proof = Hrm013WorkloadProofV1.verify(
                    mapper, clock, profile, Hrm013RequesterIdentityAuthorityV1.METHOD,
                    Hrm013RequesterIdentityAuthorityV1.PATH, context.headers(),
                    context.exactBody(), context.transportIdentity(),
                    (selected, selector, keyId) -> {
                        try {
                            VerifiedSigningKey key = signingKeyResolver.resolve(
                                    selected, selector, keyId);
                            if (key != null && !verifiedKey.compareAndSet(null, key))
                                throw Hrm013SafeErrorV1.authorityDenied();
                            return key;
                        } catch (RuntimeException failure) {
                            if (!unavailable(failure)) throw failure;
                            infrastructureFailure.compareAndSet(null, failure); return null;
                        }
                    }, reservation -> {
                        try { return replayStore.reserve(reservation); }
                        catch (RuntimeException failure) {
                            if (!unavailable(failure)) throw failure;
                            infrastructureFailure.compareAndSet(null, failure); return false;
                        }
                    }, (selected, key, claims) -> {
                        try { return authorityRechecker.remainsCurrent(selected, key, claims); }
                        catch (RuntimeException failure) {
                            if (!unavailable(failure)) throw failure;
                            infrastructureFailure.compareAndSet(null, failure); return false;
                        }
                    });
            var request = context.request();
            Map<String, Object> fields = proof.claims().profileClaims();
            if (proof.claims().tenantId() != request.tenantId()
                    || !proof.claims().correlationId().equals(request.correlationId())
                    || !request.approvalRequestId().equals(fields.get("approvalRequestId"))
                    || !request.requestedByActorPublicId()
                    .equals(fields.get("requestedByActorPublicId"))) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            return new Verification(proof, verifiedKey.get());
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (DataAccessException | TransactionException unavailable) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        } catch (RuntimeException failure) {
            if (infrastructureFailure.get() != null)
                throw Hrm013SafeErrorV1.authorityUnavailable(1);
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static boolean unavailable(RuntimeException failure) {
        return failure instanceof DataAccessException || failure instanceof TransactionException
                || failure instanceof Hrm013SafeErrorV1.Failure safe
                && safe.errorCode() == Hrm013SafeErrorV1.ErrorCode.AUTHORITY_UNAVAILABLE;
    }

    public record Verification(VerifiedProof proof, VerifiedSigningKey signingKey) {
        public Verification {
            Objects.requireNonNull(proof); Objects.requireNonNull(signingKey);
        }
    }
}
