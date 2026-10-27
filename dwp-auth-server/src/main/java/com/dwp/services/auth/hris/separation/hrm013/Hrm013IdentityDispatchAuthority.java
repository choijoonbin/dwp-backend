package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.LocalAdmission;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.LocalVerification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityTaskDispatchPersistence.InboxTask;

/** Dispatch proof binding and new-work/drain authority selection. */
final class Hrm013IdentityDispatchAuthority {
    private static final Hrm013TaskDispatchV1.Profile PROFILE = Hrm013TaskDispatchV1.IDENTITY;
    private final ObjectMapper mapper;
    private final HrisServiceGrantQuiesceService authorities;
    private final Hrm013IdentityTaskDispatchPersistence persistence;

    Hrm013IdentityDispatchAuthority(ObjectMapper mapper,
            HrisServiceGrantQuiesceService authorities,
            Hrm013IdentityTaskDispatchPersistence persistence) {
        this.mapper = mapper;
        this.authorities = authorities;
        this.persistence = persistence;
    }

    LocalAdmission currentDispatchAdmission(long tenantId, InboxTask task,
            Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch) {
        var newWork = new Hrm013WorkloadProofV1.BusinessAuthority(
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                task.tenantGrantRevision(), task.authorizationRevision());
        try {
            LocalVerification prepared = prepareDispatchAuthority(
                    tenantId, task.correlationId(), newWork, null);
            return authorities.authorizeLocalVerified(
                    prepared, task.workloadProofSha256());
        } catch (Hrm013SafeErrorV1.Failure rejected) {
            UUID rootId = task.drainRootWorkItemId() == null
                    ? dispatch.dispatchEventId() : task.drainRootWorkItemId();
            Instant rootCreatedAt = task.drainRootCreatedAt() == null
                    ? dispatch.dispatchedAt() : task.drainRootCreatedAt();
            String rootPayload = task.drainRootPayloadSha256() == null
                    ? dispatch.payloadSha256() : task.drainRootPayloadSha256();
            var drain = new Hrm013WorkloadProofV1.DrainClaims(
                    rootId, rootCreatedAt, rootPayload,
                    Hrm013WorkloadProofV1.DrainContinuationKind.ROOT_DELIVERY,
                    dispatch.dispatchEventId(), dispatch.taskOwnerRevision(),
                    dispatch.payloadSha256());
            var drainMode = new Hrm013WorkloadProofV1.BusinessAuthority(
                    Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                    task.tenantGrantRevision(), task.authorizationRevision());
            LocalVerification prepared = prepareDispatchAuthority(
                    tenantId, task.correlationId(), drainMode, drain);
            return authorities.authorizeLocalVerified(
                    prepared, task.workloadProofSha256());
        }
    }

    LocalVerification prepareDispatchAuthority(
            Hrm013WorkloadProofV1.UntrustedSelector selector, UUID correlationId) {
        HrisServiceGrantAuthorityV1.ResolveRequest request = authorityRequest(
                selector.tenantId(), PROFILE.bindingId(), PROFILE.callerWorkloadId(),
                PROFILE.ownerWorkloadId(), PROFILE.purpose(), selector.authorityMode(),
                selector.drainClaims(), correlationId);
        return authorities.prepareLocalVerification(request);
    }

    private LocalVerification prepareDispatchAuthority(long tenantId,
            UUID correlationId, Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        return authorities.prepareLocalVerification(authorityRequest(
                tenantId, PROFILE.bindingId(), PROFILE.callerWorkloadId(),
                PROFILE.ownerWorkloadId(), PROFILE.purpose(), mode, drain,
                correlationId));
    }

    LocalVerification prepareCallbackAuthority(
            long tenantId, UUID correlationId,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        HrisServiceGrantAuthorityV1.ResolveRequest request = authorityRequest(
                tenantId, Hrm013SeparationBridgeV1.HRM_008_IDENTITY.bindingId(),
                Hrm013SeparationBridgeV1.HRM_008_IDENTITY.callerWorkloadId(),
                Hrm013SeparationBridgeV1.HRM_008_IDENTITY.ownerWorkloadId(),
                Hrm013SeparationBridgeV1.HRM_008_IDENTITY.purpose(), mode,
                drain, correlationId);
        return authorities.prepareLocalVerification(request);
    }

    CompletionPlan completionPlan(Hrm013IdentityTerminalCommand command, InboxTask task,
            Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch, Instant occurredAt) {
        if (command.authorityMode() != null) {
            if (!(command.authorityMode()
                    instanceof Hrm013WorkloadProofV1.BusinessAuthority mode)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            var callback = callback(command, task, command.callbackEventId(), occurredAt);
            requireCompletionDrainLineage(
                    command.tenantId(), task, dispatch, callback, mode,
                    command.drainClaims());
            var verification = prepareCallbackAuthority(
                    command.tenantId(), task.correlationId(), mode,
                    command.drainClaims());
            return new CompletionPlan(callback, verification, mode,
                    command.drainClaims());
        }

        var newWork = new Hrm013WorkloadProofV1.BusinessAuthority(
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                task.tenantGrantRevision(), task.authorizationRevision());
        var callback = callback(command, task, command.callbackEventId(), occurredAt);
        try {
            var verification = prepareCallbackAuthority(
                    command.tenantId(), task.correlationId(), newWork, null);
            return new CompletionPlan(callback, verification, newWork, null);
        } catch (Hrm013SafeErrorV1.Failure newWorkRejected) {
            UUID rootId = task.drainRootWorkItemId() == null
                    ? dispatch.dispatchEventId() : task.drainRootWorkItemId();
            Instant rootCreatedAt = task.drainRootCreatedAt() == null
                    ? dispatch.dispatchedAt() : task.drainRootCreatedAt();
            String rootPayload = task.drainRootPayloadSha256() == null
                    ? dispatch.payloadSha256() : task.drainRootPayloadSha256();
            UUID callbackId = Hrm013WorkloadProofV1.terminalChildEventId(
                    command.tenantId(), rootId, dispatch.taskOwnerRevision(),
                    Hrm013SeparationBridgeV1.HRM_008_IDENTITY.bindingId());
            var drainCallback = callback(command, task, callbackId, occurredAt);
            var drain = new Hrm013WorkloadProofV1.DrainClaims(
                    rootId, rootCreatedAt, rootPayload,
                    Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                    callbackId, 1, drainCallback.payloadSha256());
            var drainMode = new Hrm013WorkloadProofV1.BusinessAuthority(
                    Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                    task.tenantGrantRevision(), task.authorizationRevision());
            requireCompletionDrainLineage(
                    command.tenantId(), task, dispatch, drainCallback, drainMode, drain);
            var verification = prepareCallbackAuthority(
                    command.tenantId(), task.correlationId(), drainMode, drain);
            return new CompletionPlan(drainCallback, verification, drainMode, drain);
        }
    }

    private static Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback(
            Hrm013IdentityTerminalCommand command, InboxTask task, UUID callbackEventId,
            Instant occurredAt) {
        return Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callbackEventId, task.casePublicId(), task.taskPublicId(),
                        PROFILE.taskType(), command.status(), 1,
                        command.externalReceiptPublicId(), task.payloadSha256(),
                        occurredAt, "0".repeat(64), task.correlationId()));
    }

    static void requireCompletionDrainLineage(long tenantId, InboxTask task,
            Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        if (!mode.drainExisting()) {
            if (drain != null) throw Hrm013SafeErrorV1.authorityDenied();
            return;
        }
        UUID expectedRootId = task == null || task.drainRootWorkItemId() == null
                ? dispatch.dispatchEventId() : task.drainRootWorkItemId();
        Instant expectedRootCreatedAt = task == null || task.drainRootCreatedAt() == null
                ? dispatch.dispatchedAt() : task.drainRootCreatedAt();
        String expectedRootPayload = task == null || task.drainRootPayloadSha256() == null
                ? dispatch.payloadSha256() : task.drainRootPayloadSha256();
        if (drain == null
                || drain.drainContinuationKind()
                != Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD
                || !drain.drainRootWorkItemId().equals(expectedRootId)
                || !drain.drainRootCreatedAt().equals(expectedRootCreatedAt)
                || !drain.drainRootPayloadSha256().equals(expectedRootPayload)
                || !drain.drainContinuationEventId().equals(callback.callbackEventId())
                || drain.drainContinuationRevision() != callback.ownerRevision()
                || !drain.drainContinuationPayloadSha256()
                        .equals(callback.payloadSha256())
                || !Hrm013WorkloadProofV1.terminalChildEventId(
                        tenantId, expectedRootId, callback.ownerRevision(),
                        Hrm013SeparationBridgeV1.HRM_008_IDENTITY.bindingId())
                        .equals(callback.callbackEventId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static HrisServiceGrantAuthorityV1.ResolveRequest authorityRequest(
            long tenantId, String bindingId, String caller, String owner,
            String purpose, Hrm013WorkloadProofV1.AuthorityMode authorityMode,
            Hrm013WorkloadProofV1.DrainClaims drain, UUID correlationId) {
        if (!(authorityMode instanceof Hrm013WorkloadProofV1.BusinessAuthority business)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        var mode = business.grantUseMode()
                == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK
                ? HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK
                : HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING;
        HrisServiceGrantAuthorityV1.DrainFields fields = drain == null ? null
                : new HrisServiceGrantAuthorityV1.DrainFields(
                        drain.drainRootWorkItemId(), drain.drainRootCreatedAt(),
                        drain.drainRootPayloadSha256(),
                        HrisServiceGrantAuthorityV1.DrainContinuationKind.valueOf(
                                drain.drainContinuationKind().name()),
                        drain.drainContinuationEventId(),
                        drain.drainContinuationRevision(),
                        drain.drainContinuationPayloadSha256());
        return new HrisServiceGrantAuthorityV1.ResolveRequest(
                tenantId, "dwp-auth-server", caller, owner, bindingId, purpose,
                mode, business.tenantGrantRevision(), business.authorizationRevision(),
                correlationId, fields);
    }

    boolean authorityStillBound(
            LocalVerification preparation,
            Hrm013WorkloadProofV1.VerifiedSigningKey key,
            Hrm013WorkloadProofV1.ProofClaims claims) {
        try {
            if (preparation == null) return false;
            var authority = preparation.authority();
            return authority != null
                && authority.tenantId() == claims.tenantId()
                && authority.tenantGrantRevision() == claims.tenantGrantRevision()
                && authority.authorizationRevision() == claims.authorizationRevision()
                && authority.bindingId().equals(PROFILE.bindingId())
                && authority.purpose().equals(PROFILE.purpose())
                && authority.callerWorkloadId().equals(PROFILE.callerWorkloadId())
                && authority.ownerWorkloadId().equals(PROFILE.ownerWorkloadId())
                && authority.callerSigningKeyId().equals(key.keyId());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    HrisServiceGrantAuthorityV1.ResolveResponse requireDispatchAuthority(
            HrisServiceGrantAuthorityV1.ResolveResponse response,
            Hrm013WorkloadProofV1.ProofClaims claims) {
        requireAdmit(response);
        boolean drain = claims.authorityMode().drainExisting();
        HrisServiceGrantAuthorityV1.DrainAdmission admission = response.drainAdmission();
        if (drain != (admission != null)) throw Hrm013SafeErrorV1.authorityDenied();
        if (drain && (admission.attestingServiceRole()
                != HrisServiceGrantAuthorityV1.ServiceRole.OWNER
                || claims.expiresAt().isAfter(admission.drainAuthorizationExpiresAt())
                || !persistence.databaseNow().isBefore(admission.drainAuthorizationExpiresAt()))) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return response;
    }

    Instant authorityExpiry(
            HrisServiceGrantAuthorityV1.ResolveResponse response,
            Instant proofExpiry) {
        Instant drainExpiry = response.drainAdmission() == null
                ? proofExpiry : response.drainAdmission().drainAuthorizationExpiresAt();
        return proofExpiry.isBefore(drainExpiry) ? proofExpiry : drainExpiry;
    }

    static HrisServiceGrantAuthorityV1.GrantAuthority requireAdmit(
            HrisServiceGrantAuthorityV1.ResolveResponse response) {
        if (response == null || response.status()
                != HrisServiceGrantAuthorityV1.Status.ADMIT
                || response.grantAuthority() == null) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return response.grantAuthority();
    }

    static PublicKey rsa(HrisServiceGrantAuthorityV1.RsaPublicJwk jwk) {
        try {
            var decoder = Base64.getUrlDecoder();
            BigInteger modulus = new BigInteger(1, decoder.decode(jwk.n()));
            BigInteger exponent = new BigInteger(1, decoder.decode(jwk.e()));
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(modulus, exponent));
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    static void requireProofBodyBinding(
            Hrm013WorkloadProofV1.ProofClaims claims,
            Hrm013TaskDispatchV1.IdentityTaskDispatch body) {
        Map<String, Object> values = claims.profileClaims();
        if (!body.correlationId().equals(claims.correlationId())
                || !body.dispatchEventId().equals(values.get("dispatchEventId"))
                || !body.casePublicId().equals(values.get("casePublicId"))
                || !body.taskPublicId().equals(values.get("taskPublicId"))
                || !body.taskType().equals(values.get("taskType"))
                || !Long.valueOf(body.taskOwnerRevision())
                        .equals(values.get("taskOwnerRevision"))
                || !body.payloadSha256().equals(values.get("payloadSha256"))) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    long untrustedTenantSelector(String compactProof) {
        try {
            if (compactProof == null
                    || compactProof.length() > Hrm013WorkloadProofV1.MAXIMUM_JWS_BYTES) {
                throw new IllegalArgumentException();
            }
            String[] segments = compactProof.split("\\.", -1);
            if (segments.length != 3) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(segments[1]);
            if (bytes.length == 0 || bytes.length > 14_336) {
                throw new IllegalArgumentException();
            }
            JsonNode node = mapper.readTree(bytes);
            JsonNode tenant = node == null ? null : node.get("tenantId");
            if (tenant == null || !tenant.isIntegralNumber() || !tenant.canConvertToLong()
                    || tenant.longValue() < 1) throw new IllegalArgumentException();
            return tenant.longValue();
        } catch (RuntimeException | java.io.IOException invalid) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    record CompletionPlan(
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback,
            LocalVerification localVerification,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain) { }
}
