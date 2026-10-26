package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.Header;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.ReplayReservation;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.ResolverCaller;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.TransportIdentity;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.VerifiedProof;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.VerifiedSigningKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.PublicKey;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Performs leaf-key bootstrap verification without consulting authority or business storage. */
public final class HrisServiceGrantAuthorityProofVerifier {
    private final ObjectMapper mapper;
    private final Clock clock;

    public HrisServiceGrantAuthorityProofVerifier(ObjectMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public HrisServiceGrantAuthorityRequestContext verify(
            HrisServiceGrantAuthorityV1.ResolveRequest request,
            byte[] exactBody,
            ResolverCaller caller,
            List<Header> headers,
            TransportIdentity transportIdentity,
            PublicKey leafPublicKey) {
        try {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(leafPublicKey, "leafPublicKey");
            var profile = Hrm013WorkloadProofV1.resolverBootstrap(caller);
            AtomicReference<ReplayReservation> captured = new AtomicReference<>();
            VerifiedProof proof = Hrm013WorkloadProofV1.verify(
                    mapper, clock, profile, HrisServiceGrantAuthorityV1.METHOD,
                    HrisServiceGrantAuthorityV1.PATH, headers, exactBody,
                    transportIdentity,
                    (ignoredProfile, ignoredSelector, keyId) -> {
                        if (!transportIdentity.leafCertificateSha256().equals(keyId)) {
                            throw Hrm013SafeErrorV1.authorityDenied();
                        }
                        return new VerifiedSigningKey(
                                leafPublicKey, keyId, caller.workloadId());
                    },
                    reservation -> captured.compareAndSet(null, reservation),
                    (ignoredProfile, ignoredKey, ignoredClaims) -> true);
            requireExactRequestBinding(request, proof);
            ReplayReservation reservation = captured.get();
            if (reservation == null) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            return new HrisServiceGrantAuthorityRequestContext(
                    request, exactBody, caller, transportIdentity, proof, reservation);
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static void requireExactRequestBinding(
            HrisServiceGrantAuthorityV1.ResolveRequest request, VerifiedProof proof) {
        var claims = proof.claims();
        Map<String, Object> fields = claims.profileClaims();
        if (claims.tenantId() != request.tenantId()
                || !claims.correlationId().equals(request.correlationId())
                || claims.tenantGrantRevision() != request.signedTenantGrantRevision()
                || claims.authorizationRevision() != request.signedAuthorizationRevision()
                || !claims.resolutionMode().name().equals(request.resolutionMode().name())
                || !request.requestingWorkloadId().equals(fields.get("requestingWorkloadId"))
                || !request.targetCallerWorkloadId().equals(fields.get("targetCallerWorkloadId"))
                || !request.targetOwnerWorkloadId().equals(fields.get("targetOwnerWorkloadId"))
                || !request.targetBindingId().equals(fields.get("targetBindingId"))
                || !request.targetPurpose().equals(fields.get("targetPurpose"))
                || !drainMatches(request.drainFields(), claims.drainClaims())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static boolean drainMatches(
            HrisServiceGrantAuthorityV1.DrainFields request,
            Hrm013WorkloadProofV1.DrainClaims claims) {
        if (request == null || claims == null) {
            return request == null && claims == null;
        }
        return request.drainRootWorkItemId().equals(claims.drainRootWorkItemId())
                && request.drainRootCreatedAt().equals(claims.drainRootCreatedAt())
                && request.drainRootPayloadSha256().equals(claims.drainRootPayloadSha256())
                && request.drainContinuationKind().name()
                .equals(claims.drainContinuationKind().name())
                && request.drainContinuationEventId().equals(claims.drainContinuationEventId())
                && request.drainContinuationRevision() == claims.drainContinuationRevision()
                && request.drainContinuationPayloadSha256()
                .equals(claims.drainContinuationPayloadSha256());
    }
}
