package com.dwp.services.time.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Receipt-first application service; task execution is downstream of durable admission. */
@Service
@ConditionalOnProperty(prefix = "dwp.time.hrm013-task-dispatch", name = "enabled",
        havingValue = "true")
public final class Hrm013TimeTaskDispatchService {
    public record VerifiedDispatch(
            Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch body,
            byte[] exactBody, Hrm013WorkloadProofV1.VerifiedProof proof) {
        public VerifiedDispatch {
            Objects.requireNonNull(body); Objects.requireNonNull(exactBody);
            Objects.requireNonNull(proof); exactBody = exactBody.clone();
        }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }

    public record AdmissionResponse(
            byte[] exactBody, String receiptProof, boolean exactReplay) {
        public AdmissionResponse {
            Objects.requireNonNull(exactBody); exactBody = exactBody.clone();
            if (receiptProof == null || receiptProof.isBlank()) {
                throw new IllegalArgumentException("receipt proof required");
            }
        }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }

    private final Hrm013TimeTaskDispatchStore store;
    private final AuthHrm013ServiceAuthorityClient authority;
    private final Hrm013TimeWorkloadCredentialProvider credentials;
    private final ObjectMapper mapper;
    private final Clock clock;

    public Hrm013TimeTaskDispatchService(
            Hrm013TimeTaskDispatchStore store,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013TimeWorkloadCredentialProvider credentials,
            ObjectMapper mapper) {
        this(store, authority, credentials, mapper, Clock.systemUTC());
    }

    Hrm013TimeTaskDispatchService(
            Hrm013TimeTaskDispatchStore store,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013TimeWorkloadCredentialProvider credentials,
            ObjectMapper mapper, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.authority = Objects.requireNonNull(authority);
        this.credentials = Objects.requireNonNull(credentials);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    public AdmissionResponse admit(VerifiedDispatch verified) {
        Objects.requireNonNull(verified);
        var profile = Hrm013TaskDispatchV1.TIME;
        long tenantId = verified.proof().claims().tenantId();
        Hrm013TaskDispatchV1.requireProfileBinding(profile, tenantId, verified.body());
        byte[] canonical = Hrm013TaskDispatchV1.encode(profile, tenantId, verified.body());
        if (!java.security.MessageDigest.isEqual(canonical, verified.exactBody())) {
            throw Hrm013SafeErrorV1.digestConflict();
        }
        String requestDigest = Hrm013TaskDispatchV1.requestBodySha256(verified.exactBody());
        Instant admittedAt = clock.instant();
        var receipt = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        verified.body().dispatchEventId(), verified.body().taskPublicId(),
                        verified.body().payloadSha256(), requestDigest, admittedAt,
                        "0".repeat(64), verified.body().correlationId()));
        byte[] receiptBody = Hrm013TaskDispatchV1.encode(receipt);
        var current = authority.resolveAdmissionOwner(verified.proof().claims());
        var lease = current.lease();
        Instant authorityExpiresAt = current.drainAdmission() == null
                ? lease.expiresAt()
                : current.drainAdmission().drainAuthorizationExpiresAt();
        authorityExpiresAt = authorityExpiresAt.isBefore(
                verified.proof().claims().expiresAt())
                ? authorityExpiresAt : verified.proof().claims().expiresAt();
        String receiptProof = credentials.admissionReceiptProof(
                lease, receipt, receiptBody, authorityExpiresAt);
        var candidate = new Hrm013TimeTaskDispatchStore.AdmissionCandidate(
                tenantId, verified.body(), verified.exactBody(), requestDigest,
                verified.proof().proofSha256(), verified.proof().claims().tenantGrantRevision(),
                verified.proof().claims().authorizationRevision(),
                verified.proof().claims().grantUseMode().name(),
                drainJson(verified.proof().claims().drainClaims()), receiptBody,
                receiptProof, admittedAt, authorityExpiresAt);
        var stored = store.admit(candidate);
        if (stored.kind() == Hrm013TimeTaskDispatchStore.AdmissionKind.DIGEST_CONFLICT) {
            throw Hrm013SafeErrorV1.digestConflict();
        }
        var storedReceipt = Hrm013TaskDispatchV1.decodeAdmissionReceipt(
                stored.exactReceiptBody());
        Hrm013TaskDispatchV1.requireAdmissionReceiptBinding(
                Hrm013TaskDispatchV1.TIME, tenantId, verified.body(),
                verified.exactBody(), storedReceipt);
        String freshReceiptProof = credentials.admissionReceiptProof(
                lease, storedReceipt, stored.exactReceiptBody(), authorityExpiresAt);
        return new AdmissionResponse(stored.exactReceiptBody(), freshReceiptProof,
                stored.kind() == Hrm013TimeTaskDispatchStore.AdmissionKind.EXACT_REPLAY);
    }

    public void complete(long tenantId, UUID dispatchEventId,
            UUID externalReceiptPublicId, UUID correlationId) {
        terminal(tenantId, dispatchEventId, "COMPLETED",
                externalReceiptPublicId, correlationId);
    }

    public void fail(long tenantId, UUID dispatchEventId,
            UUID externalReceiptPublicId, UUID correlationId) {
        terminal(tenantId, dispatchEventId, "FAILED",
                externalReceiptPublicId, correlationId);
    }

    private void terminal(long tenantId, UUID dispatchEventId, String status,
            UUID externalReceiptPublicId, UUID correlationId) {
        store.enqueueTerminal(new Hrm013TimeTaskDispatchStore.TerminalCommand(
                tenantId, dispatchEventId, status, externalReceiptPublicId,
                clock.instant(), correlationId));
    }

    private String drainJson(Hrm013WorkloadProofV1.DrainClaims drain) {
        if (drain == null) return null;
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("drainRootWorkItemId", drain.drainRootWorkItemId());
            value.put("drainRootCreatedAt", drain.drainRootCreatedAt());
            value.put("drainRootPayloadSha256", drain.drainRootPayloadSha256());
            value.put("drainContinuationKind", drain.drainContinuationKind().name());
            value.put("drainContinuationEventId", drain.drainContinuationEventId());
            value.put("drainContinuationRevision", drain.drainContinuationRevision());
            value.put("drainContinuationPayloadSha256",
                    drain.drainContinuationPayloadSha256());
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }
}
