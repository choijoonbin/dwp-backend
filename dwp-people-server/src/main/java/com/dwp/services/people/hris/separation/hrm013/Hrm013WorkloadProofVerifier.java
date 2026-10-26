package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Two-stage current-authority proof verifier with durable replay reservation. */
public final class Hrm013WorkloadProofVerifier {
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Hrm013ServiceAuthorityPort authority;
    private final Hrm013PersistencePorts.DigestProofReplayStore replays;

    public Hrm013WorkloadProofVerifier(
            ObjectMapper mapper, Clock clock, Hrm013ServiceAuthorityPort authority,
            Hrm013PersistencePorts.DigestProofReplayStore replays) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.replays = Objects.requireNonNull(replays, "replays");
    }

    public VerifiedCallback verify(
            Hrm013WorkloadProofV1.Profile profile,
            String method,
            String path,
            List<Hrm013WorkloadProofV1.Header> headers,
            byte[] exactBody,
            Hrm013WorkloadProofV1.TransportIdentity transportIdentity) {
        AtomicReference<Hrm013ServiceAuthorityPort.SigningAuthority> resolved =
                new AtomicReference<>();
        Hrm013WorkloadProofV1.VerifiedProof proof = Hrm013WorkloadProofV1.verify(
                mapper, clock, profile, method, path, headers, exactBody,
                transportIdentity,
                (requestedProfile, selector, keyId) -> {
                    Hrm013ServiceAuthorityPort.Query query = query(
                            requestedProfile, selector);
                    Hrm013ServiceAuthorityPort.SigningAuthority current =
                            authority.requireSigningAuthority(query);
                    if (!keyId.equals(current.callerSigningKeyId())) {
                        throw Hrm013SafeErrorV1.authorityDenied();
                    }
                    resolved.set(current);
                    return new Hrm013WorkloadProofV1.VerifiedSigningKey(
                            current.callerSigningPublicKey(),
                            current.callerSigningKeyId(),
                            current.grant().callerWorkloadId());
                },
                reservation -> replays.reserve(
                        new Hrm013PersistenceModels.ReplayDigestReservation(
                                reservation.tenantId(), reservation.bindingId(),
                                reservation.callerWorkloadId(),
                                reservation.proofIdSha256(),
                                reservation.requestNonceSha256(),
                                reservation.retainUntil())),
                (requestedProfile, key, claims) -> {
                    Hrm013ServiceAuthorityPort.SigningAuthority current = resolved.get();
                    return current != null && withinDrainAuthorization(claims, current)
                            && authority.remainsCurrent(current);
                });
        Hrm013ServiceAuthorityPort.SigningAuthority current = resolved.get();
        if (current == null) throw Hrm013SafeErrorV1.authorityDenied();
        requireDrainAuthorization(proof.claims(), current);
        return new VerifiedCallback(proof, current, exactBody);
    }

    static void requireDrainAuthorization(
            Hrm013WorkloadProofV1.ProofClaims claims,
            Hrm013ServiceAuthorityPort.SigningAuthority authority) {
        if (!withinDrainAuthorization(claims, authority)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static boolean withinDrainAuthorization(
            Hrm013WorkloadProofV1.ProofClaims claims,
            Hrm013ServiceAuthorityPort.SigningAuthority authority) {
        if (claims.grantUseMode()
                != Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING) {
            return authority.drainAdmission() == null;
        }
        return authority.drainAdmission() != null
                && !claims.expiresAt().isAfter(
                authority.drainAdmission().drainAuthorizationExpiresAt());
    }

    private static Hrm013ServiceAuthorityPort.Query query(
            Hrm013WorkloadProofV1.Profile profile,
            Hrm013WorkloadProofV1.UntrustedSelector selector) {
        HrisServiceGrantAuthorityV1.ResolutionMode mode =
                selector.authorityMode().drainExisting()
                        ? HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING
                        : HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK;
        HrisServiceGrantAuthorityV1.DrainFields drain = null;
        if (selector.drainClaims() != null) {
            var value = selector.drainClaims();
            drain = new HrisServiceGrantAuthorityV1.DrainFields(
                    value.drainRootWorkItemId(), value.drainRootCreatedAt(),
                    value.drainRootPayloadSha256(),
                    HrisServiceGrantAuthorityV1.DrainContinuationKind.valueOf(
                            value.drainContinuationKind().name()),
                    value.drainContinuationEventId(),
                    value.drainContinuationRevision(),
                    value.drainContinuationPayloadSha256());
        }
        return new Hrm013ServiceAuthorityPort.Query(
                selector.tenantId(), profile.callerWorkloadId(), profile.bindingId(),
                profile.purpose(), selector.authorityMode().tenantGrantRevision(),
                selector.authorityMode().authorizationRevision(), mode, drain);
    }

    public record VerifiedCallback(
            Hrm013WorkloadProofV1.VerifiedProof proof,
            Hrm013ServiceAuthorityPort.SigningAuthority authority,
            byte[] exactBody) {
        public VerifiedCallback {
            Objects.requireNonNull(proof, "proof");
            Objects.requireNonNull(authority, "authority");
            exactBody = Objects.requireNonNull(exactBody, "exactBody").clone();
        }

        @Override public byte[] exactBody() { return exactBody.clone(); }
    }
}
