package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolveRequest;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.ReplayReservation;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.ResolverCaller;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.TransportIdentity;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.VerifiedProof;
import java.time.Instant;
import java.util.Objects;

/** Immutable transport evidence. No servlet, browser or session identity crosses this boundary. */
public record HrisServiceGrantAuthorityRequestContext(
        ResolveRequest request,
        byte[] exactBody,
        ResolverCaller resolverCaller,
        TransportIdentity transportIdentity,
        VerifiedProof verifiedProof,
        ReplayReservation replayReservation) {

    public static final String ATTRIBUTE = HrisServiceGrantAuthorityRequestContext.class.getName();

    public HrisServiceGrantAuthorityRequestContext {
        Objects.requireNonNull(request, "request");
        exactBody = Objects.requireNonNull(exactBody, "exactBody").clone();
        Objects.requireNonNull(resolverCaller, "resolverCaller");
        Objects.requireNonNull(transportIdentity, "transportIdentity");
        Objects.requireNonNull(verifiedProof, "verifiedProof");
        Objects.requireNonNull(replayReservation, "replayReservation");
        if (request.tenantId() != replayReservation.tenantId()
                || !verifiedProof.profile().bindingId().equals(replayReservation.bindingId())
                || !request.requestingWorkloadId().equals(replayReservation.callerWorkloadId())) {
            throw new IllegalArgumentException("replay evidence does not match request");
        }
    }

    @Override
    public byte[] exactBody() {
        return exactBody.clone();
    }

    public String jtiDigest() {
        return replayReservation.proofIdSha256();
    }

    public String requestNonceDigest() {
        return replayReservation.requestNonceSha256();
    }

    public Instant proofExpiresAt() {
        return replayReservation.retainUntil();
    }

    public String resolverProofSha256() {
        return verifiedProof.proofSha256();
    }
}
