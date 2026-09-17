package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalDeploymentAttestationVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-16T05:00:00Z");
    private static final String ISSUER = "deployment-owner";
    private static final String IDENTITY = "deployment-health-runtime";
    private static final String KEY_ID = "deployment-key-1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void derivesVerificationReferenceOnlyFromExactSignedClaims() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ApprovalDeploymentAttestationVerifier verifier = verifier(keys);
        Scope scope = scope();
        UUID promotionId = UUID.randomUUID();
        ExternalHealthEvidenceSubmission unsigned = unsigned();
        String[] proof = proof(keys, claims(scope, promotionId, 4, unsigned));
        ExternalHealthEvidenceSubmission signed = withProof(unsigned, proof);

        ExternalHealthEvidence verified = verifier.verify(scope, promotionId, 4, signed);
        assertThat(verified.verificationReference()).matches("verified:[a-f0-9]{64}");
        assertThatThrownBy(() -> verifier.verify(scope, promotionId, 5, signed))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void missingTrustFailsUnavailable() {
        ApprovalDeploymentAttestationVerifier verifier =
                new ApprovalDeploymentAttestationVerifier(
                        mapper, Clock.fixed(NOW, ZoneOffset.UTC), null);
        assertThatThrownBy(() -> verifier.verify(scope(), UUID.randomUUID(), 1, unsigned()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private ApprovalDeploymentAttestationVerifier verifier(KeyPair keys) {
        return new ApprovalDeploymentAttestationVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                new ApprovalDeploymentAttestationVerifier.TrustedAttestor(
                        ISSUER, IDENTITY, KEY_ID, keys.getPublic()));
    }

    private Scope scope() {
        return new Scope(42, "RS_APPROVALS", 20,
                Set.of(Capability.RECORD_EXTERNAL_EVIDENCE));
    }

    private ExternalHealthEvidenceSubmission unsigned() {
        return new ExternalHealthEvidenceSubmission(
                UUID.randomUUID(), "ACTIVATION", HealthOutcome.HEALTHY,
                "provider://deployment/health", "d".repeat(64), NOW, null, null);
    }

    private ApprovalDeploymentAttestationVerifier.Claims claims(
            Scope scope,
            UUID promotionId,
            long version,
            ExternalHealthEvidenceSubmission evidence) {
        return new ApprovalDeploymentAttestationVerifier.Claims(
                ISSUER, IDENTITY, KEY_ID,
                ApprovalDeploymentAttestationVerifier.AUDIENCE,
                ApprovalDeploymentAttestationVerifier.PURPOSE,
                scope.tenantId(), scope.resourceSetKey(), promotionId, version,
                evidence.evidenceId(), evidence.evidenceType(), evidence.outcome().name(),
                evidence.externalReference(), evidence.payloadSha256(),
                evidence.sourceGeneratedAt(), NOW.minusSeconds(1), NOW.plusSeconds(120),
                "deployment-proof-nonce-001");
    }

    private ExternalHealthEvidenceSubmission withProof(
            ExternalHealthEvidenceSubmission value,
            String[] proof) {
        return new ExternalHealthEvidenceSubmission(
                value.evidenceId(), value.evidenceType(), value.outcome(),
                value.externalReference(), value.payloadSha256(), value.sourceGeneratedAt(),
                proof[0], proof[1]);
    }

    private String[] proof(KeyPair keys, Object claims) throws Exception {
        byte[] payload = mapper.writeValueAsBytes(claims);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(payload);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return new String[]{encoder.encodeToString(payload),
                encoder.encodeToString(signature.sign())};
    }
}
