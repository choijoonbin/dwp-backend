package com.dwp.platform.contracts.hris.separation.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed requester-identity authority contract for the HRM-013 APR-002 root. */
public final class Hrm013RequesterIdentityAuthorityV1 {
    public static final String BINDING_ID = "SVC-AUT-HRM-013";
    public static final String CALLER_WORKLOAD_ID = "dwp-approval-server";
    public static final String OWNER_WORKLOAD_ID = "dwp-auth-server";
    public static final String PURPOSE = "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE";
    public static final String METHOD = "POST";
    public static final String PATH =
            "/internal/auth/v1/hris-separation-requester-authority/resolve";
    public static final int MAX_REQUEST_BYTES = 8_192;
    public static final int MAX_RESPONSE_BYTES = 16_384;

    private static final Set<String> REQUEST_FIELDS = Set.of(
            "tenantId", "approvalRequestId", "requestedByActorPublicId", "correlationId");
    private static final Set<String> OPAQUE_RESPONSE_FIELDS = Set.of("status", "correlationId");
    private static final Set<String> ADMIT_RESPONSE_FIELDS = Set.of(
            "status", "requesterUserId", "requesterPersonPublicId",
            "requesterAccessRevision", "requesterVersion", "tenantGrantRevision",
            "authorizationRevision", "correlationId");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private Hrm013RequesterIdentityAuthorityV1() { }

    public enum Status { ADMIT, DENY, UNAVAILABLE }
    public enum AccountPlane { TENANT, PLATFORM }
    public enum AccountState { ACTIVE, INACTIVE, LOCKED }

    public record ResolveRequest(
            long tenantId,
            UUID approvalRequestId,
            UUID requestedByActorPublicId,
            UUID correlationId) {
        public ResolveRequest {
            if (tenantId < 1) throw new IllegalArgumentException("tenantId must be positive");
            Objects.requireNonNull(approvalRequestId, "approvalRequestId");
            Objects.requireNonNull(requestedByActorPublicId, "requestedByActorPublicId");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /**
     * Exact authority and APR-002 root facts already verified from the business proof and
     * current (or old drain-only) service grant.
     */
    public record ProofBinding(
            String bindingId,
            String callerWorkloadId,
            String ownerWorkloadId,
            String purpose,
            long tenantId,
            UUID approvalRequestId,
            UUID requestedByActorPublicId,
            UUID correlationId,
            long tenantGrantRevision,
            long authorizationRevision,
            Hrm013WorkloadProofV1.GrantUseMode grantUseMode,
            Hrm013WorkloadProofV1.DrainClaims drainClaims,
            Instant enclosingApprovalRequestedAt,
            String enclosingApprovalPayloadSha256,
            Instant drainAuthorizationExpiresAt,
            boolean authorityUsable) {
        public ProofBinding {
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(callerWorkloadId, "callerWorkloadId");
            Objects.requireNonNull(ownerWorkloadId, "ownerWorkloadId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(approvalRequestId, "approvalRequestId");
            Objects.requireNonNull(requestedByActorPublicId, "requestedByActorPublicId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(grantUseMode, "grantUseMode");
            Objects.requireNonNull(enclosingApprovalRequestedAt, "enclosingApprovalRequestedAt");
            Objects.requireNonNull(enclosingApprovalPayloadSha256,
                    "enclosingApprovalPayloadSha256");
            if (tenantId < 1 || !SHA256.matcher(enclosingApprovalPayloadSha256).matches()) {
                throw new IllegalArgumentException("proof binding is not canonical");
            }
        }
    }

    /** Result of the single tenant-scoped Auth directory query. */
    public record RequesterRecord(
            long tenantId,
            long requesterUserId,
            UUID requesterPersonPublicId,
            long requesterAccessRevision,
            long requesterVersion,
            AccountPlane accountPlane,
            AccountState accountState,
            int matchingRows) {
        public RequesterRecord {
            Objects.requireNonNull(requesterPersonPublicId, "requesterPersonPublicId");
            Objects.requireNonNull(accountPlane, "accountPlane");
            Objects.requireNonNull(accountState, "accountState");
            if (matchingRows < 0) throw new IllegalArgumentException("matchingRows is negative");
        }
    }

    public record ResolveResponse(
            Status status,
            Long requesterUserId,
            UUID requesterPersonPublicId,
            Long requesterAccessRevision,
            Long requesterVersion,
            Long tenantGrantRevision,
            Long authorizationRevision,
            UUID correlationId) {
        public ResolveResponse {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(correlationId, "correlationId");
            boolean allAuthority = requesterUserId != null && requesterPersonPublicId != null
                    && requesterAccessRevision != null && requesterVersion != null
                    && tenantGrantRevision != null && authorizationRevision != null;
            boolean anyAuthority = requesterUserId != null || requesterPersonPublicId != null
                    || requesterAccessRevision != null || requesterVersion != null
                    || tenantGrantRevision != null || authorizationRevision != null;
            if ((status == Status.ADMIT) != allAuthority || (status != Status.ADMIT && anyAuthority))
                throw new IllegalArgumentException("response projection does not match status");
            if (status == Status.ADMIT && (requesterUserId < 1
                    || requesterAccessRevision < 0 || requesterVersion < 0
                    || tenantGrantRevision < 1 || authorizationRevision < 1))
                throw new IllegalArgumentException("authority values are not canonical");
        }

        public static ResolveResponse opaque(Status status, UUID correlationId) {
            if (status == Status.ADMIT) throw new IllegalArgumentException("ADMIT is not opaque");
            return new ResolveResponse(status, null, null, null, null, null, null, correlationId);
        }
    }

    /**
     * Fail-closed tenant/root/actor/revision/grant-use admission. The caller must invoke it
     * again immediately after its directory read if the authority could have changed.
     */
    public static ResolveResponse admit(
            ResolveRequest request, ProofBinding proof, RequesterRecord requester, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(now, "now");
        if (!proof.authorityUsable()
                || !BINDING_ID.equals(proof.bindingId())
                || !CALLER_WORKLOAD_ID.equals(proof.callerWorkloadId())
                || !OWNER_WORKLOAD_ID.equals(proof.ownerWorkloadId())
                || !PURPOSE.equals(proof.purpose())
                || request.tenantId() != proof.tenantId()
                || !request.approvalRequestId().equals(proof.approvalRequestId())
                || !request.requestedByActorPublicId().equals(proof.requestedByActorPublicId())
                || !request.correlationId().equals(proof.correlationId())
                || proof.tenantGrantRevision() < 1 || proof.authorizationRevision() < 1
                || !grantUseIsBound(proof, now)
                || requester.matchingRows() != 1
                || requester.tenantId() != request.tenantId()
                || requester.requesterUserId() < 1
                || !request.requestedByActorPublicId().equals(
                requester.requesterPersonPublicId())
                || requester.requesterAccessRevision() < 0 || requester.requesterVersion() < 0
                || requester.accountPlane() != AccountPlane.TENANT
                || requester.accountState() != AccountState.ACTIVE) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return new ResolveResponse(Status.ADMIT, requester.requesterUserId(),
                requester.requesterPersonPublicId(), requester.requesterAccessRevision(),
                requester.requesterVersion(), proof.tenantGrantRevision(),
                proof.authorizationRevision(), request.correlationId());
    }

    private static boolean grantUseIsBound(ProofBinding proof, Instant now) {
        if (proof.grantUseMode() == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK) {
            return proof.drainClaims() == null && proof.drainAuthorizationExpiresAt() == null;
        }
        if (proof.grantUseMode() != Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING
                || proof.drainClaims() == null || proof.drainAuthorizationExpiresAt() == null
                || !now.isBefore(proof.drainAuthorizationExpiresAt())) return false;
        Hrm013WorkloadProofV1.DrainClaims drain = proof.drainClaims();
        return drain.drainContinuationKind()
                == Hrm013WorkloadProofV1.DrainContinuationKind.DEPENDENT_READ
                && proof.approvalRequestId().equals(drain.drainRootWorkItemId())
                && proof.approvalRequestId().equals(drain.drainContinuationEventId())
                && drain.drainContinuationRevision() == 0
                && proof.enclosingApprovalRequestedAt().equals(drain.drainRootCreatedAt())
                && proof.enclosingApprovalPayloadSha256().equals(
                drain.drainRootPayloadSha256())
                && proof.enclosingApprovalPayloadSha256().equals(
                drain.drainContinuationPayloadSha256());
    }

    public static byte[] encodeRequest(ObjectMapper mapper, ResolveRequest request) {
        Objects.requireNonNull(mapper); Objects.requireNonNull(request);
        ObjectNode node = mapper.createObjectNode();
        node.put("tenantId", request.tenantId());
        node.put("approvalRequestId", request.approvalRequestId().toString());
        node.put("requestedByActorPublicId", request.requestedByActorPublicId().toString());
        node.put("correlationId", request.correlationId().toString());
        return HrisServiceGrantAuthorityV1.canonicalBytes(mapper, node, MAX_REQUEST_BYTES);
    }

    public static ResolveRequest decodeRequest(ObjectMapper mapper, byte[] exactBytes) {
        JsonNode node = HrisServiceGrantAuthorityV1.strictObject(
                mapper, exactBytes, MAX_REQUEST_BYTES);
        HrisServiceGrantAuthorityV1.exactFields(node, REQUEST_FIELDS);
        try {
            ResolveRequest request = new ResolveRequest(
                    HrisServiceGrantAuthorityV1.integer(node, "tenantId"),
                    HrisServiceGrantAuthorityV1.uuid(node, "approvalRequestId"),
                    HrisServiceGrantAuthorityV1.uuid(node, "requestedByActorPublicId"),
                    HrisServiceGrantAuthorityV1.uuid(node, "correlationId"));
            HrisServiceGrantAuthorityV1.canonical(
                    exactBytes, encodeRequest(mapper, request));
            return request;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static byte[] encodeResponse(ObjectMapper mapper, ResolveResponse response) {
        Objects.requireNonNull(mapper); Objects.requireNonNull(response);
        ObjectNode node = mapper.createObjectNode();
        node.put("status", response.status().name());
        node.put("correlationId", response.correlationId().toString());
        if (response.status() == Status.ADMIT) {
            node.put("requesterUserId", response.requesterUserId());
            node.put("requesterPersonPublicId", response.requesterPersonPublicId().toString());
            node.put("requesterAccessRevision", response.requesterAccessRevision());
            node.put("requesterVersion", response.requesterVersion());
            node.put("tenantGrantRevision", response.tenantGrantRevision());
            node.put("authorizationRevision", response.authorizationRevision());
        }
        return HrisServiceGrantAuthorityV1.canonicalBytes(mapper, node, MAX_RESPONSE_BYTES);
    }

    public static ResolveResponse decodeResponse(ObjectMapper mapper, byte[] exactBytes) {
        JsonNode node = HrisServiceGrantAuthorityV1.strictObject(
                mapper, exactBytes, MAX_RESPONSE_BYTES);
        try {
            Status status = Status.valueOf(HrisServiceGrantAuthorityV1.text(node, "status"));
            HrisServiceGrantAuthorityV1.exactFields(node,
                    status == Status.ADMIT ? ADMIT_RESPONSE_FIELDS : OPAQUE_RESPONSE_FIELDS);
            UUID correlation = HrisServiceGrantAuthorityV1.uuid(node, "correlationId");
            ResolveResponse response = status == Status.ADMIT
                    ? new ResolveResponse(status,
                    HrisServiceGrantAuthorityV1.integer(node, "requesterUserId"),
                    HrisServiceGrantAuthorityV1.uuid(node, "requesterPersonPublicId"),
                    HrisServiceGrantAuthorityV1.integer(node, "requesterAccessRevision"),
                    HrisServiceGrantAuthorityV1.integer(node, "requesterVersion"),
                    HrisServiceGrantAuthorityV1.integer(node, "tenantGrantRevision"),
                    HrisServiceGrantAuthorityV1.integer(node, "authorizationRevision"),
                    correlation)
                    : ResolveResponse.opaque(status, correlation);
            HrisServiceGrantAuthorityV1.canonical(
                    exactBytes, encodeResponse(mapper, response));
            return response;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }
}
