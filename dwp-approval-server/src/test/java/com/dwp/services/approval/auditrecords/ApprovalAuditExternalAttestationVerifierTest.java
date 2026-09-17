package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.auditrecords.ApprovalAuditModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalAuditExternalAttestationVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-16T03:00:00Z");
    private static final String ISSUER = "urn:dwp:trusted-archive";
    private static final String IDENTITY = "archive-attestor-prod";
    private static final String KEY_ID = "archive-ed25519-2026-09";

    private ObjectMapper mapper;
    private KeyPair trustedKey;
    private ApprovalAuditExternalAttestationVerifier verifier;
    private Scope scope;
    private ExportReceipt export;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        trustedKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier = new ApprovalAuditExternalAttestationVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                new ApprovalAuditExternalAttestationVerifier.TrustedAttestor(
                        ISSUER, IDENTITY, KEY_ID, trustedKey.getPublic()));
        scope = new Scope(42, "RS_APPROVALS", 17, Set.of(Capability.VIEW));
        export = receipt(UUID.randomUUID(), "a".repeat(64));
    }

    @Test
    void trustedSignatureIsBoundToIssuerIdentityExportAndManifest() throws Exception {
        ExternalAttestationSubmission submitted = signed(
                claims(ISSUER, IDENTITY, export.exportId(), export.manifestSha256()),
                trustedKey.getPrivate());

        VerifiedExternalAttestation verified = verifier.verify(scope, export, submitted);

        assertThat(verified.issuer()).isEqualTo(ISSUER);
        assertThat(verified.attestorIdentity()).isEqualTo(IDENTITY);
        assertThat(verified.keyId()).isEqualTo(KEY_ID);
        assertThat(verified.verificationReference()).matches("verified:[a-f0-9]{64}");
    }

    @Test
    void aSignatureFromAnUntrustedKeyIsRejected() throws Exception {
        KeyPair untrusted = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ExternalAttestationSubmission submitted = signed(
                claims(ISSUER, IDENTITY, export.exportId(), export.manifestSha256()),
                untrusted.getPrivate());

        assertForbidden(() -> verifier.verify(scope, export, submitted));
    }

    @Test
    void trustedKeyCannotOverrideThePinnedIssuerOrAttestorIdentity() throws Exception {
        ExternalAttestationSubmission wrongIssuer = signed(
                claims("urn:dwp:other-archive", IDENTITY,
                        export.exportId(), export.manifestSha256()),
                trustedKey.getPrivate());
        ExternalAttestationSubmission wrongIdentity = signed(
                claims(ISSUER, "other-attestor",
                        export.exportId(), export.manifestSha256()),
                trustedKey.getPrivate());

        assertForbidden(() -> verifier.verify(scope, export, wrongIssuer));
        assertForbidden(() -> verifier.verify(scope, export, wrongIdentity));
    }

    @Test
    void trustedKeyCannotRebindEvidenceToAnotherExportOrManifest() throws Exception {
        ExternalAttestationSubmission wrongExport = signed(
                claims(ISSUER, IDENTITY, UUID.randomUUID(), export.manifestSha256()),
                trustedKey.getPrivate());
        ExternalAttestationSubmission wrongManifest = signed(
                claims(ISSUER, IDENTITY, export.exportId(), "b".repeat(64)),
                trustedKey.getPrivate());

        assertForbidden(() -> verifier.verify(scope, export, wrongExport));
        assertForbidden(() -> verifier.verify(scope, export, wrongManifest));
    }

    @Test
    void missingTrustConfigurationFailsClosedAsAuthorityUnavailable() throws Exception {
        ApprovalAuditExternalAttestationVerifier unconfigured =
                new ApprovalAuditExternalAttestationVerifier(
                        mapper, Clock.fixed(NOW, ZoneOffset.UTC), null);
        ExternalAttestationSubmission submitted = signed(
                claims(ISSUER, IDENTITY, export.exportId(), export.manifestSha256()),
                trustedKey.getPrivate());

        assertThatThrownBy(() -> unconfigured.verify(scope, export, submitted))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private ApprovalAuditExternalAttestationVerifier.AttestationClaims claims(
            String issuer,
            String identity,
            UUID exportId,
            String manifestSha256) {
        return new ApprovalAuditExternalAttestationVerifier.AttestationClaims(
                issuer, identity, KEY_ID,
                ApprovalAuditExternalAttestationVerifier.AUDIENCE,
                ApprovalAuditExternalAttestationVerifier.PURPOSE,
                42, "RS_APPROVALS", exportId, manifestSha256,
                "WORM", "archive://evidence/42", NOW,
                NOW, NOW.plusSeconds(240),
                "nonce-attestation-100");
    }

    private ExternalAttestationSubmission signed(
            ApprovalAuditExternalAttestationVerifier.AttestationClaims claims,
            PrivateKey privateKey) throws Exception {
        byte[] payload = mapper.writeValueAsBytes(claims);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(payload);
        return new ExternalAttestationSubmission(
                claims.type(), claims.reference(), claims.attestedAt(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }

    private ExportReceipt receipt(UUID exportId, String manifestSha256) {
        return new ExportReceipt(
                exportId, "COMPLETE", "DIGEST_VERIFIED", manifestSha256,
                null, null, null, null, null, null, null, null, 0, NOW);
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
