package com.dwp.services.payroll.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Receipt-first command service for the HRM-013 Payroll task-owner boundary. */
@Service
@ConditionalOnProperty(prefix = "dwp.payroll.hrm013-task-dispatch", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class Hrm013PayrollTaskDispatchService {
    private static final String ZERO_DIGEST = "0".repeat(64);
    private final Hrm013PayrollTaskDispatchStore store;
    private final AuthHrm013ServiceAuthorityClient authority;
    private final Hrm013PayrollWorkloadCredentialProvider credentials;
    private final Clock clock;

    @Autowired
    Hrm013PayrollTaskDispatchService(Hrm013PayrollTaskDispatchStore store,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013PayrollWorkloadCredentialProvider credentials) {
        this(store, authority, credentials, Clock.systemUTC());
    }

    Hrm013PayrollTaskDispatchService(
            Hrm013PayrollTaskDispatchStore store, Clock clock) {
        this(store, null, null, clock);
    }

    Hrm013PayrollTaskDispatchService(Hrm013PayrollTaskDispatchStore store,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013PayrollWorkloadCredentialProvider credentials, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.authority = authority;
        this.credentials = credentials;
        this.clock = Objects.requireNonNull(clock);
    }

    AdmissionResponse admit(
            Hrm013PayrollTaskDispatchIngressFilter.VerifiedDispatch verified) {
        Objects.requireNonNull(verified);
        long tenantId = verified.proof().claims().tenantId();
        Hrm013TaskDispatchV1.DispatchBody decoded = Hrm013TaskDispatchV1.decode(
                Hrm013TaskDispatchV1.PAYROLL, tenantId, verified.exactBody());
        if (!(decoded instanceof Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch body)) {
            throw Hrm013SafeErrorV1.malformed();
        }
        requireProofBinding(body, verified.proof());
        requireAuthorityBinding(verified);
        String requestDigest = Hrm013TaskDispatchV1.requestBodySha256(
                verified.exactBody());
        String commandDigest = Hrm013TaskDispatchV1.commandDigest(
                Hrm013TaskDispatchV1.PAYROLL, tenantId, requestDigest);
        if (!commandDigest.equals(verified.proof().commandDigest())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Instant admittedAt = clock.instant();
        var receipt = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        body.dispatchEventId(), body.taskPublicId(), body.payloadSha256(),
                        requestDigest, admittedAt, ZERO_DIGEST, body.correlationId()));
        byte[] receiptBytes = Hrm013TaskDispatchV1.encode(receipt);
        byte[] exactStoredReceipt = store.admit(
                new Hrm013PayrollTaskDispatchStore.Admission(
                tenantId, body, verified.exactBody(), requestDigest, commandDigest,
                verified.proof(), verified.authority(), admittedAt, receiptBytes,
                receipt.receiptSha256()));
        var storedReceipt = Hrm013TaskDispatchV1.decodeAdmissionReceipt(
                exactStoredReceipt);
        Hrm013TaskDispatchV1.requireAdmissionReceiptBinding(
                Hrm013TaskDispatchV1.PAYROLL, tenantId, body,
                verified.exactBody(), storedReceipt);
        if (authority == null || credentials == null) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        var claims = verified.proof().claims();
        var selector = new Hrm013WorkloadProofV1.UntrustedSelector(
                tenantId, claims.authorityMode(), claims.drainClaims());
        if (!authority.remainsCurrent(verified.authority(),
                Hrm013WorkloadProofV1.taskDispatch(
                        Hrm013WorkloadProofV1.TaskOwner.PAYROLL),
                selector, claims.keyId(), claims.correlationId())) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        credentials.requireLive(verified.authority().snapshot(),
                verified.authority().authorityExpiresAt());
        String responseProof = credentials.admissionReceiptProof(
                verified.authority().snapshot(), Hrm013TaskDispatchV1.PAYROLL,
                tenantId, storedReceipt, exactStoredReceipt,
                verified.authority().authorityExpiresAt());
        return new AdmissionResponse(exactStoredReceipt, responseProof);
    }

    /**
     * Called by the Payroll settlement worker only after it has durably decided an
     * HRM-013 task. The callback is committed in the same transaction as the local
     * terminal state; transport happens later from the outbox.
     */
    public void recordOutcome(long tenantId, UUID dispatchEventId, String status,
            UUID externalReceiptPublicId, Instant occurredAt) {
        if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
            throw Hrm013SafeErrorV1.invalidState();
        }
        var record = store.dispatch(tenantId, dispatchEventId)
                .orElseThrow(Hrm013SafeErrorV1::notFound);
        var decoded = Hrm013TaskDispatchV1.decode(Hrm013TaskDispatchV1.PAYROLL,
                tenantId, record.exactRequestBytes());
        UUID root = record.drainRootWorkItemId() == null
                ? record.dispatchEventId() : record.drainRootWorkItemId();
        UUID callbackEventId = Hrm013WorkloadProofV1.terminalChildEventId(
                tenantId, root, 1, Hrm013SeparationBridgeV1.HRM_008_PAYROLL.bindingId());
        UUID externalReceipt = externalReceiptPublicId == null
                ? UUID.nameUUIDFromBytes((tenantId + "\n" + dispatchEventId + "\n" + status)
                        .getBytes(StandardCharsets.UTF_8))
                : externalReceiptPublicId;
        var callback = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callbackEventId, record.casePublicId(), record.taskPublicId(),
                        Hrm013TaskDispatchV1.PAYROLL.taskType(), status,
                        record.ownerRevision(), externalReceipt,
                        record.payloadSha256(), occurredAt, ZERO_DIGEST,
                        record.correlationId()));
        Hrm013TaskDispatchV1.requireCallbackReceiptBinding(
                Hrm013TaskDispatchV1.PAYROLL, decoded,
                Hrm013SeparationBridgeV1.HRM_008_PAYROLL, callback);
        byte[] callbackBytes = Hrm013SeparationBridgeV1.encode(callback);
        store.recordOutcome(new Hrm013PayrollTaskDispatchStore.Outcome(
                tenantId, dispatchEventId, callbackEventId, record.casePublicId(),
                record.taskPublicId(), record.correlationId(), status, occurredAt,
                callbackBytes, callback.payloadSha256(), record.payloadSha256(),
                record.grantUseMode(), root,
                record.drainRootCreatedAt() == null
                        ? record.dispatchedAt() : record.drainRootCreatedAt(),
                record.drainRootPayloadSha256() == null
                        ? record.payloadSha256() : record.drainRootPayloadSha256()));
    }

    private static void requireProofBinding(
            Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch body,
            Hrm013WorkloadProofV1.VerifiedProof proof) {
        Map<String, Object> claims = proof.claims().profileClaims();
        if (!body.correlationId().equals(proof.claims().correlationId())
                || !body.dispatchEventId().equals(claims.get("dispatchEventId"))
                || !body.casePublicId().equals(claims.get("casePublicId"))
                || !body.taskPublicId().equals(claims.get("taskPublicId"))
                || !body.taskType().equals(claims.get("taskType"))
                || !Long.valueOf(body.taskOwnerRevision())
                        .equals(claims.get("taskOwnerRevision"))
                || !body.payloadSha256().equals(claims.get("payloadSha256"))) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static void requireAuthorityBinding(
            Hrm013PayrollTaskDispatchIngressFilter.VerifiedDispatch verified) {
        var claims = verified.proof().claims();
        var grant = verified.authority().grant();
        if (grant.tenantId() != claims.tenantId()
                || grant.tenantGrantRevision() != claims.tenantGrantRevision()
                || grant.authorizationRevision() != claims.authorizationRevision()
                || !grant.bindingId().equals(Hrm013TaskDispatchV1.PAYROLL.bindingId())
                || !grant.callerWorkloadId().equals(
                        Hrm013TaskDispatchV1.PAYROLL.callerWorkloadId())
                || !grant.ownerWorkloadId().equals(
                        Hrm013TaskDispatchV1.PAYROLL.ownerWorkloadId())
                || !grant.purpose().equals(Hrm013TaskDispatchV1.PAYROLL.purpose())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    record AdmissionResponse(byte[] exactReceiptBytes, String receiptProof) {
        AdmissionResponse {
            exactReceiptBytes = Objects.requireNonNull(exactReceiptBytes).clone();
            if (receiptProof == null || receiptProof.isBlank()
                    || receiptProof.length() > Hrm013WorkloadProofV1.MAXIMUM_JWS_BYTES) {
                throw new IllegalArgumentException("Admission receipt proof is invalid.");
            }
        }
        @Override public byte[] exactReceiptBytes() {
            return exactReceiptBytes.clone();
        }
    }
}
