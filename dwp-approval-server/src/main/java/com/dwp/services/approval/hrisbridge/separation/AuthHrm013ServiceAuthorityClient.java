package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Exact, no-redirect SVC-AUT-HRM-012 authority client. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hrm013-bridge", name = "enabled",
        havingValue = "true")
public final class AuthHrm013ServiceAuthorityClient {
    private static final String AUTH_SPIFFE = "spiffe://dwp/service/dwp-auth-server";
    private final ObjectMapper mapper;
    private final HrisSeparationWorkloadCredentialProvider credentials;
    private final URI endpoint;

    AuthHrm013ServiceAuthorityClient(ObjectMapper mapper,
            HrisSeparationWorkloadCredentialProvider credentials,
            @Value("${dwp.approval.hrm013-bridge.auth-url:}") String authUrl) {
        this.mapper = mapper;
        this.credentials = credentials;
        this.endpoint = endpoint(authUrl, HrisServiceGrantAuthorityV1.PATH);
        HrisSeparationWorkloadCredentialProvider.requireEndpoint(
                endpoint, HrisServiceGrantAuthorityV1.PATH);
    }

    HrisServiceGrantAuthorityV1.ResolveResponse resolve(
            HrisServiceGrantAuthorityV1.ResolveRequest request) {
        return resolveWithSnapshot(request).response();
    }

    ResolvedAttempt resolveWithSnapshot(
            HrisServiceGrantAuthorityV1.ResolveRequest request) {
        Objects.requireNonNull(request);
        Hrm013WorkloadProofV1.GrantUseMode mode = switch (request.resolutionMode()) {
            case NEW_WORK -> Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK;
            case DRAIN_EXISTING, READINESS_OBSERVE ->
                    Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING;
            case PROBE_MISSING -> throw HrisSeparationProtocolException.denied();
        };
        var snapshot = credentials.current(
                request.tenantId(), request.targetBindingId(), mode);
        var response = resolve(request, snapshot);
        Instant expiresAt = response.status() == HrisServiceGrantAuthorityV1.Status.ADMIT
                && response.drainAdmission() != null
                ? earlier(snapshot.lease().expiresAt(),
                        response.drainAdmission().drainAuthorizationExpiresAt())
                : earlier(snapshot.lease().expiresAt(),
                        credentials.now().plusSeconds(30));
        return new ResolvedAttempt(response, snapshot, expiresAt);
    }

    record ResolvedAttempt(HrisServiceGrantAuthorityV1.ResolveResponse response,
            HrisSeparationWorkloadCredentialProvider.Snapshot snapshot,
            Instant authorityExpiresAt) { }

    Instant now() {
        return credentials.now();
    }

    HrisServiceGrantAuthorityV1.ResolveResponse resolve(
            HrisServiceGrantAuthorityV1.ResolveRequest request,
            HrisSeparationWorkloadCredentialProvider.Snapshot snapshot) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(snapshot);
        requireLease(request, snapshot.lease());
        byte[] body = HrisServiceGrantAuthorityV1.encodeRequest(mapper, request);
        String proof = credentials.resolverProof(snapshot, request, body);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            for (var header : Hrm013WorkloadProofV1.headers(
                    Hrm013WorkloadProofV1.resolverBootstrap(
                            Hrm013WorkloadProofV1.ResolverCaller.APPROVAL),
                    proof, request.correlationId())) {
                builder.header(header.name(), header.value());
            }
            HttpResponse<InputStream> response = credentials.client(snapshot).send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try {
                HrisSeparationWorkloadCredentialProvider.requirePeer(
                        response, endpoint, AUTH_SPIFFE);
            } catch (RuntimeException untrustedPeer) {
                close(response.body());
                throw untrustedPeer;
            }
            if (response.statusCode() != 200
                    || !response.headers().allValues("Content-Type")
                    .equals(List.of("application/json"))) {
                close(response.body());
                throw HrisSeparationProtocolException.unavailable();
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(HrisServiceGrantAuthorityV1.MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > HrisServiceGrantAuthorityV1.MAX_RESPONSE_BYTES) {
                throw HrisSeparationProtocolException.unavailable();
            }
            var decoded = HrisServiceGrantAuthorityV1.decodeResponse(mapper, bytes);
            if (!request.correlationId().equals(decoded.correlationId())) {
                throw HrisSeparationProtocolException.unavailable();
            }
            if (decoded.status() == HrisServiceGrantAuthorityV1.Status.ADMIT) {
                credentials.requireAttested(snapshot, decoded.grantAuthority());
            }
            return decoded;
        } catch (HrisSeparationProtocolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    /** Exact HRM-010 authority for one terminal-child creation or delivery attempt. */
    public record TerminalAdmission(
            HrisSeparationWorkloadCredentialProvider.Snapshot snapshot,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain,
            Instant authorityExpiresAt,
            HrisServiceGrantAuthorityV1.GrantAuthority callbackAuthority,
            HrisServiceGrantAuthorityV1.GrantAuthority requestAuthority,
            HrisServiceGrantAuthorityV1.DrainAdmission callbackDrain,
            HrisServiceGrantAuthorityV1.DrainAdmission requestDrain) {
        public TerminalAdmission {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(authorityExpiresAt);
            Objects.requireNonNull(callbackAuthority);
            Objects.requireNonNull(requestAuthority);
            if ((mode == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING)
                    != (drain != null)
                    || (mode == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING)
                    != (callbackDrain != null && requestDrain != null)) {
                throw new IllegalArgumentException("Terminal authority mode is invalid.");
            }
        }

        boolean remainsCurrent(TerminalAdmission other) {
            return other != null && mode == other.mode
                    && snapshot.lease().equals(other.snapshot.lease())
                    && callbackAuthority.equals(other.callbackAuthority)
                    && requestAuthority.equals(other.requestAuthority)
                    && sameDrain(callbackDrain, other.callbackDrain)
                    && sameDrain(requestDrain, other.requestDrain);
        }
    }

    /**
     * Obtains fresh Auth authority for an HRM-010 attempt.  NEW_WORK is tried only
     * for the exact frozen grant.  A quiescing/mismatched current lease falls back
     * to the one permitted TERMINAL_CHILD continuation of the frozen APR-002 root.
     */
    public TerminalAdmission authorizeTerminalChild(long tenantId, UUID grantSetId,
            long tenantGrantRevision, long authorizationRevision,
            String peopleSigningKeyId, String approvalSigningKeyId,
            UUID supersedesGrantSetId,
            UUID approvalRequestId,
            Instant rootCreatedAt, String rootPayloadSha256, UUID callbackEventId,
            String callbackPayloadSha256, UUID correlationId) {
        Objects.requireNonNull(grantSetId); Objects.requireNonNull(peopleSigningKeyId);
        Objects.requireNonNull(approvalSigningKeyId);
        Objects.requireNonNull(approvalRequestId); Objects.requireNonNull(rootCreatedAt);
        Objects.requireNonNull(rootPayloadSha256); Objects.requireNonNull(callbackEventId);
        Objects.requireNonNull(callbackPayloadSha256); Objects.requireNonNull(correlationId);

        HrisSeparationWorkloadCredentialProvider.Snapshot current = null;
        try {
            current = credentials.current(tenantId,
                    HrisSeparationPersistenceModels.CALLBACK_BINDING,
                    Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK);
        } catch (HrisSeparationProtocolException unavailable) {
            // A quiescing old grant may be present only in the drain-only slot.
        }
        if (matches(current, tenantId, grantSetId, tenantGrantRevision,
                authorizationRevision, approvalSigningKeyId)) {
            var response = resolveAttempt(current,
                    Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null, correlationId);
            if (response.status() == HrisServiceGrantAuthorityV1.Status.ADMIT) {
                var companion = resolveApr002Companion(tenantId, grantSetId,
                        tenantGrantRevision, authorizationRevision,
                        peopleSigningKeyId, approvalSigningKeyId,
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null,
                        correlationId);
                requireSameGrant(response.grantAuthority(),
                        companion.grantAuthority());
                requireLocalProjection(response.grantAuthority(), tenantId,
                        grantSetId, tenantGrantRevision, authorizationRevision,
                        peopleSigningKeyId, approvalSigningKeyId,
                        supersedesGrantSetId);
                requireLocalProjection(companion.grantAuthority(), tenantId,
                        grantSetId, tenantGrantRevision, authorizationRevision,
                        peopleSigningKeyId, approvalSigningKeyId,
                        supersedesGrantSetId);
                return new TerminalAdmission(current,
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null,
                        earlier(earlier(current.lease().expiresAt(),
                                companion.authorityExpiresAt()),
                                credentials.now().plusSeconds(30)),
                        response.grantAuthority(), companion.grantAuthority(),
                        null, null);
            }
            if (response.status() != HrisServiceGrantAuthorityV1.Status.QUIESCING) {
                throw statusFailure(response.status());
            }
        }

        var drain = new Hrm013WorkloadProofV1.DrainClaims(
                approvalRequestId, rootCreatedAt, rootPayloadSha256,
                Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                callbackEventId, 1, callbackPayloadSha256);
        var snapshot = credentials.current(tenantId,
                HrisSeparationPersistenceModels.CALLBACK_BINDING,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING);
        if (!matches(snapshot, tenantId, grantSetId, tenantGrantRevision,
                authorizationRevision, approvalSigningKeyId)) {
            throw HrisSeparationProtocolException.denied();
        }
        var response = resolveAttempt(snapshot,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING, drain,
                correlationId);
        if (response.status() != HrisServiceGrantAuthorityV1.Status.ADMIT) {
            throw statusFailure(response.status());
        }
        if (response.drainAdmission() == null
                || response.drainAdmission().attestingServiceRole()
                        != HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                || !credentials.now().isBefore(
                        response.drainAdmission().drainAuthorizationExpiresAt())) {
            throw HrisSeparationProtocolException.denied();
        }
        var rootDrain = new Hrm013WorkloadProofV1.DrainClaims(
                approvalRequestId, rootCreatedAt, rootPayloadSha256,
                Hrm013WorkloadProofV1.DrainContinuationKind.ROOT_DELIVERY,
                approvalRequestId, 0, rootPayloadSha256);
        var companion = resolveApr002Companion(tenantId, grantSetId,
                tenantGrantRevision, authorizationRevision, peopleSigningKeyId,
                approvalSigningKeyId,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                rootDrain, correlationId);
        requireSameGrant(response.grantAuthority(), companion.grantAuthority());
        requireSameDrainCohort(response.drainAdmission(), companion.drain());
        requireLocalProjection(response.grantAuthority(), tenantId,
                grantSetId, tenantGrantRevision, authorizationRevision,
                peopleSigningKeyId, approvalSigningKeyId,
                supersedesGrantSetId);
        requireLocalProjection(companion.grantAuthority(), tenantId,
                grantSetId, tenantGrantRevision, authorizationRevision,
                peopleSigningKeyId, approvalSigningKeyId,
                supersedesGrantSetId);
        return new TerminalAdmission(snapshot,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING, drain,
                earlier(earlier(
                        response.drainAdmission().drainAuthorizationExpiresAt(),
                        snapshot.lease().expiresAt()),
                        companion.authorityExpiresAt()),
                response.grantAuthority(), companion.grantAuthority(),
                response.drainAdmission(), companion.drain());
    }

    /** Re-resolves both group members and rejects rotation/revocation races. */
    public void recheckTerminalChild(TerminalAdmission admitted,
            long tenantId, UUID grantSetId, long tenantGrantRevision,
            long authorizationRevision, String peopleSigningKeyId,
            String approvalSigningKeyId, UUID supersedesGrantSetId,
            UUID approvalRequestId, Instant rootCreatedAt,
            String rootPayloadSha256, UUID callbackEventId,
            String callbackPayloadSha256, UUID correlationId) {
        Objects.requireNonNull(admitted);
        credentials.requireLive(admitted.authorityExpiresAt());
        var current = authorizeTerminalChild(tenantId, grantSetId,
                tenantGrantRevision, authorizationRevision, peopleSigningKeyId,
                approvalSigningKeyId, supersedesGrantSetId, approvalRequestId,
                rootCreatedAt, rootPayloadSha256, callbackEventId,
                callbackPayloadSha256, correlationId);
        if (!admitted.remainsCurrent(current)) {
            throw HrisSeparationProtocolException.denied();
        }
        credentials.requireLive(admitted.authorityExpiresAt());
    }

    private CompanionAdmission resolveApr002Companion(long tenantId,
            UUID grantSetId, long tenantGrantRevision, long authorizationRevision,
            String peopleSigningKeyId, String approvalSigningKeyId,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain, UUID correlationId) {
        var snapshot = credentials.current(tenantId,
                HrisSeparationPersistenceModels.REQUEST_BINDING, mode);
        var lease = snapshot.lease();
        if (lease.tenantId() != tenantId || !lease.grantSetId().equals(grantSetId)
                || lease.tenantGrantRevision() != tenantGrantRevision
                || lease.authorizationRevision() != authorizationRevision
                || !HrisSeparationPersistenceModels.REQUEST_PURPOSE.equals(lease.purpose())
                || !HrisSeparationPersistenceModels.PEOPLE_WORKLOAD.equals(
                        lease.callerWorkloadId())
                || !HrisSeparationPersistenceModels.APPROVAL_WORKLOAD.equals(
                        lease.ownerWorkloadId())
                || lease.serviceRole()
                        != HrisSeparationWorkloadCredentialProvider.ServiceRole.OWNER
                || !approvalSigningKeyId.equals(lease.businessSigningKeyId())) {
            throw HrisSeparationProtocolException.denied();
        }
        var response = resolveAttempt(snapshot, mode, drain, correlationId);
        if (response.status() != HrisServiceGrantAuthorityV1.Status.ADMIT) {
            throw statusFailure(response.status());
        }
        if (response.grantAuthority() == null
                || !peopleSigningKeyId.equals(
                        response.grantAuthority().callerSigningKeyId())
                || !approvalSigningKeyId.equals(
                        response.grantAuthority().ownerSigningKeyId())) {
            throw HrisSeparationProtocolException.denied();
        }
        Instant expiresAt = mode == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING
                ? earlier(requireDrainExpiry(response,
                        HrisServiceGrantAuthorityV1.ServiceRole.OWNER), lease.expiresAt())
                : earlier(lease.expiresAt(), credentials.now().plusSeconds(30));
        return new CompanionAdmission(response.grantAuthority(),
                response.drainAdmission(), expiresAt);
    }

    private Instant requireDrainExpiry(
            HrisServiceGrantAuthorityV1.ResolveResponse response,
            HrisServiceGrantAuthorityV1.ServiceRole role) {
        if (response.drainAdmission() == null
                || response.drainAdmission().attestingServiceRole() != role
                || !credentials.now().isBefore(
                        response.drainAdmission().drainAuthorizationExpiresAt())) {
            throw HrisSeparationProtocolException.denied();
        }
        return response.drainAdmission().drainAuthorizationExpiresAt();
    }

    private record CompanionAdmission(
            HrisServiceGrantAuthorityV1.GrantAuthority grantAuthority,
            HrisServiceGrantAuthorityV1.DrainAdmission drain,
            Instant authorityExpiresAt) { }

    private static void requireLocalProjection(
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            long tenantId, UUID grantSetId, long tenantGrantRevision,
            long authorizationRevision, String peopleSigningKeyId,
            String approvalSigningKeyId, UUID supersedesGrantSetId) {
        if (authority == null || authority.tenantId() != tenantId
                || !authority.grantSetId().equals(grantSetId)
                || authority.tenantGrantRevision() != tenantGrantRevision
                || authority.authorizationRevision() != authorizationRevision
                || !"ADMIT".equals(authority.decision())
                || !Objects.equals(authority.supersedesGrantSetId(),
                        supersedesGrantSetId)
                || !(peopleSigningKeyId.equals(authority.callerSigningKeyId())
                        && approvalSigningKeyId.equals(
                                authority.ownerSigningKeyId())
                    || approvalSigningKeyId.equals(
                                authority.callerSigningKeyId())
                        && peopleSigningKeyId.equals(
                                authority.ownerSigningKeyId()))) {
            throw HrisSeparationProtocolException.denied();
        }
    }

    private static void requireSameDrainCohort(
            HrisServiceGrantAuthorityV1.DrainAdmission left,
            HrisServiceGrantAuthorityV1.DrainAdmission right) {
        if (left == null || right == null
                || !left.quiesceFenceId().equals(right.quiesceFenceId())
                || left.quiesceRevision() != right.quiesceRevision()
                || !left.quiesceEffectiveAt().equals(
                        right.quiesceEffectiveAt())) {
            throw HrisSeparationProtocolException.denied();
        }
    }

    private static boolean sameDrain(
            HrisServiceGrantAuthorityV1.DrainAdmission admitted,
            HrisServiceGrantAuthorityV1.DrainAdmission rechecked) {
        if (admitted == null || rechecked == null) return admitted == rechecked;
        return admitted.quiesceFenceId().equals(rechecked.quiesceFenceId())
                && admitted.quiesceRevision() == rechecked.quiesceRevision()
                && admitted.quiesceEffectiveAt().equals(
                        rechecked.quiesceEffectiveAt())
                && admitted.attestingServiceRole()
                        == rechecked.attestingServiceRole()
                && rechecked.drainActivityRevision()
                        > admitted.drainActivityRevision()
                && !rechecked.drainActivityRecordedAt().isBefore(
                        admitted.drainActivityRecordedAt());
    }

    static void requireSameGrant(HrisServiceGrantAuthorityV1.GrantAuthority left,
            HrisServiceGrantAuthorityV1.GrantAuthority right) {
        if (left == null || right == null
                || !left.grantSetId().equals(right.grantSetId())
                || left.grantRevision() != right.grantRevision()
                || left.tenantId() != right.tenantId()
                || left.tenantGrantRevision() != right.tenantGrantRevision()
                || left.authorizationRevision() != right.authorizationRevision()
                || !left.decision().equals(right.decision())
                || !Objects.equals(left.supersedesGrantSetId(),
                        right.supersedesGrantSetId())
                || !left.grantDigest().equals(right.grantDigest())
                || !left.recordedAt().equals(right.recordedAt())
                || !left.callerSigningKeyId().equals(right.ownerSigningKeyId())
                || !left.ownerSigningKeyId().equals(right.callerSigningKeyId())) {
            throw HrisSeparationProtocolException.denied();
        }
    }

    static HrisSeparationProtocolException statusFailure(
            HrisServiceGrantAuthorityV1.Status status) {
        return status == HrisServiceGrantAuthorityV1.Status.UNAVAILABLE
                ? HrisSeparationProtocolException.unavailable()
                : HrisSeparationProtocolException.denied();
    }

    private HrisServiceGrantAuthorityV1.ResolveResponse resolveAttempt(
            HrisSeparationWorkloadCredentialProvider.Snapshot snapshot,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain, UUID correlationId) {
        var lease = snapshot.lease();
        var request = new HrisServiceGrantAuthorityV1.ResolveRequest(
                lease.tenantId(), HrisSeparationPersistenceModels.APPROVAL_WORKLOAD,
                lease.callerWorkloadId(), lease.ownerWorkloadId(), lease.bindingId(),
                lease.purpose(), mode == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK
                        ? HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK
                        : HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING,
                lease.tenantGrantRevision(), lease.authorizationRevision(),
                correlationId, drain(drain));
        return resolve(request, snapshot);
    }

    private static HrisServiceGrantAuthorityV1.DrainFields drain(
            Hrm013WorkloadProofV1.DrainClaims value) {
        if (value == null) return null;
        return new HrisServiceGrantAuthorityV1.DrainFields(
                value.drainRootWorkItemId(), value.drainRootCreatedAt(),
                value.drainRootPayloadSha256(),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.valueOf(
                        value.drainContinuationKind().name()),
                value.drainContinuationEventId(), value.drainContinuationRevision(),
                value.drainContinuationPayloadSha256());
    }

    private static boolean matches(
            HrisSeparationWorkloadCredentialProvider.Snapshot snapshot,
            long tenantId, UUID grantSetId, long tenantGrantRevision,
            long authorizationRevision, String signingKeyId) {
        if (snapshot == null) return false;
        var lease = snapshot.lease();
        return lease.tenantId() == tenantId
                && lease.grantSetId().equals(grantSetId)
                && lease.tenantGrantRevision() == tenantGrantRevision
                && lease.authorizationRevision() == authorizationRevision
                && HrisSeparationPersistenceModels.CALLBACK_BINDING.equals(
                        lease.bindingId())
                && HrisSeparationPersistenceModels.CALLBACK_PURPOSE.equals(
                        lease.purpose())
                && HrisSeparationPersistenceModels.APPROVAL_WORKLOAD.equals(
                        lease.callerWorkloadId())
                && HrisSeparationPersistenceModels.PEOPLE_WORKLOAD.equals(
                        lease.ownerWorkloadId())
                && lease.serviceRole()
                        == HrisSeparationWorkloadCredentialProvider.ServiceRole.CALLER
                && lease.businessSigningKeyId().equals(signingKeyId);
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static void requireLease(HrisServiceGrantAuthorityV1.ResolveRequest request,
            HrisSeparationWorkloadCredentialProvider.Lease lease) {
        String serviceWorkload = lease.serviceRole()
                == HrisSeparationWorkloadCredentialProvider.ServiceRole.CALLER
                ? lease.callerWorkloadId() : lease.ownerWorkloadId();
        if (request.tenantId() != lease.tenantId()
                || !request.requestingWorkloadId().equals(serviceWorkload)
                || request.signedTenantGrantRevision() != lease.tenantGrantRevision()
                || request.signedAuthorizationRevision() != lease.authorizationRevision()
                || !request.targetBindingId().equals(lease.bindingId())
                || !request.targetPurpose().equals(lease.purpose())
                || !request.targetCallerWorkloadId().equals(lease.callerWorkloadId())
                || !request.targetOwnerWorkloadId().equals(lease.ownerWorkloadId())
                || !lease.callerSpiffeId().equals(
                        "spiffe://dwp/service/" + lease.callerWorkloadId())
                || !lease.ownerSpiffeId().equals(
                        "spiffe://dwp/service/" + lease.ownerWorkloadId())) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    private static URI endpoint(String base, String path) {
        if (base == null || base.isBlank()) throw new IllegalStateException(
                "Enabled HRM-013 runtime requires the Auth HTTPS endpoint.");
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        return root.resolve(path.substring(1));
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (java.io.IOException ignored) { }
    }
}
