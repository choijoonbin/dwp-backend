package com.dwp.services.approval.incidents;

import com.dwp.core.common.ErrorCode;
import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.incidents.IncidentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentRecoveryAttestationVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-16T02:00:00Z");
    private static final String ISSUER = "recovery-owner";
    private static final String IDENTITY = "recovery-executor";
    private static final String KEY_ID = "recovery-key-1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ApprovalDocumentCanonical canonical = new ApprovalDocumentCanonical(mapper);

    @Test
    void verifiesTargetVersionFencedReceiptAndRejectsRebinding() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        IncidentRecoveryAttestationVerifier verifier = verifier(keys);
        ExecutionRequest request = request(7);
        IncidentRecoveryAttestationVerifier.Claims claims = claims(verifier, request);
        SignedExecutionReceipt receipt = proof(keys, claims);

        VerifiedStageCompletion verified = verifier.verify(request, receipt);
        assertThat(verified.state()).isEqualTo(StageState.SUCCEEDED);
        assertThat(verified.receiptVerificationReference())
                .matches("verified:[a-f0-9]{64}");
        assertThatThrownBy(() -> verifier.verify(request(8), receipt))
                .isInstanceOfSatisfying(IncidentRejected.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void absentTrustIsUnavailableAndLocalFallbackCanOnlyBeUnknown() {
        IncidentRecoveryAttestationVerifier verifier = new IncidentRecoveryAttestationVerifier(
                mapper, canonical, Clock.fixed(NOW, ZoneOffset.UTC), null);
        ExecutionRequest request = request(7);
        assertThatThrownBy(() -> verifier.verify(request,
                new SignedExecutionReceipt("payload", "signature")))
                .isInstanceOfSatisfying(IncidentRejected.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        VerifiedStageCompletion unknown = verifier.unknown(
                request, NOW, "EXECUTOR_NOT_CONFIGURED");
        assertThat(unknown.state()).isEqualTo(StageState.UNKNOWN_REMOTE_OUTCOME);
        assertThat(unknown.receiptVerificationReference()).isNull();
    }

    private IncidentRecoveryAttestationVerifier verifier(KeyPair keys) {
        return new IncidentRecoveryAttestationVerifier(
                mapper, canonical, Clock.fixed(NOW, ZoneOffset.UTC),
                new IncidentRecoveryAttestationVerifier.TrustedExecutor(
                        ISSUER, IDENTITY, KEY_ID, keys.getPublic()));
    }

    private ExecutionRequest request(long targetVersion) {
        return new ExecutionRequest(
                42, "RS_APPROVALS",
                UUID.fromString("00000000-0000-4000-8000-000000000101"),
                UUID.fromString("00000000-0000-4000-8000-000000000102"),
                1, 3, 2, ActionKind.REPLAY, TargetType.OUTBOX_EVENT,
                UUID.fromString("00000000-0000-4000-8000-000000000103"),
                targetVersion, "replay-stage-1");
    }

    private IncidentRecoveryAttestationVerifier.Claims claims(
            IncidentRecoveryAttestationVerifier verifier,
            ExecutionRequest request) {
        Map<String, Object> result = Map.of("committedVersion", 8);
        IncidentRecoveryAttestationVerifier.Claims draft =
                new IncidentRecoveryAttestationVerifier.Claims(
                        ISSUER, IDENTITY, KEY_ID,
                        IncidentRecoveryAttestationVerifier.AUDIENCE,
                        IncidentRecoveryAttestationVerifier.PURPOSE,
                        request.tenantId(), request.resourceSetKey(), request.incidentId(),
                        request.planId(), request.stageNumber(), request.expectedPlanVersion(),
                        request.expectedStageVersion(), request.actionKind().name(),
                        request.targetType().name(), request.targetId(),
                        request.expectedTargetVersion(), request.executionKey(),
                        StageState.SUCCEEDED.name(), result, "", NOW,
                        NOW.minusSeconds(1), NOW.plusSeconds(120),
                        "recovery-proof-nonce-001");
        return new IncidentRecoveryAttestationVerifier.Claims(
                draft.issuer(), draft.executorIdentity(), draft.keyId(), draft.audience(),
                draft.purpose(), draft.tenantId(), draft.resourceSetKey(), draft.incidentId(),
                draft.planId(), draft.stageNumber(), draft.expectedPlanVersion(),
                draft.expectedStageVersion(), draft.actionKind(), draft.targetType(),
                draft.targetId(), draft.expectedTargetVersion(), draft.executionKey(),
                draft.outcome(), draft.result(), verifier.expectedEvidence(request, draft),
                draft.completedAt(), draft.issuedAt(), draft.expiresAt(), draft.nonce());
    }

    private SignedExecutionReceipt proof(KeyPair keys, Object claims) throws Exception {
        byte[] payload = mapper.writeValueAsBytes(claims);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(payload);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return new SignedExecutionReceipt(
                encoder.encodeToString(payload), encoder.encodeToString(signature.sign()));
    }
}
