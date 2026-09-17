package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.auditrecords.ApprovalAuditModels.*;

@Component
final class ApprovalAuditExternalAttestationVerifier {
    static final String AUDIENCE = "dwp-approval-server";
    static final String PURPOSE = "APPROVAL_AUDIT_EXPORT_ATTESTATION_V1";
    private static final Duration MAX_PROOF_LIFETIME = Duration.ofMinutes(5);
    private static final Set<String> SUPPORTED_TYPES =
            Set.of("WORM", "KMS_SIGNATURE", "QUALIFIED_ARCHIVE");

    record TrustedAttestor(
            String issuer,
            String identity,
            String keyId,
            PublicKey publicKey) {
    }

    record AttestationClaims(
            String issuer,
            String attestorIdentity,
            String keyId,
            String audience,
            String purpose,
            long tenantId,
            String resourceSetKey,
            UUID exportId,
            String manifestSha256,
            String type,
            String reference,
            Instant attestedAt,
            Instant issuedAt,
            Instant expiresAt,
            String nonce) {
    }

    private final ObjectMapper mapper;
    private final Clock clock;
    private final TrustedAttestor trusted;

    @Autowired
    ApprovalAuditExternalAttestationVerifier(
            ObjectMapper mapper,
            @Value("${approval.audit-records.external-attestation.trusted-public-key-x509-base64:}")
            String publicKey,
            @Value("${approval.audit-records.external-attestation.trusted-issuer:}")
            String issuer,
            @Value("${approval.audit-records.external-attestation.trusted-attestor-identity:}")
            String identity,
            @Value("${approval.audit-records.external-attestation.trusted-key-id:}")
            String keyId) {
        this(mapper, Clock.systemUTC(), configured(publicKey, issuer, identity, keyId));
    }

    ApprovalAuditExternalAttestationVerifier(
            ObjectMapper mapper,
            Clock clock,
            TrustedAttestor trusted) {
        this.mapper = mapper.copy()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = clock;
        this.trusted = trusted;
    }

    VerifiedExternalAttestation verify(
            Scope scope,
            ExportReceipt export,
            ExternalAttestationSubmission submitted) {
        if (trusted == null) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "No trusted external audit attestor is configured.");
        }
        try {
            requireSubmission(export, submitted);
            byte[] payload = Base64.getUrlDecoder().decode(
                    submitted.evidencePayloadBase64Url());
            byte[] signatureBytes = Base64.getUrlDecoder().decode(
                    submitted.evidenceSignatureBase64Url());
            if (payload.length == 0 || payload.length > 8_192 || signatureBytes.length != 64) {
                throw forbidden();
            }
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(trusted.publicKey());
            signature.update(payload);
            if (!signature.verify(signatureBytes)) {
                throw forbidden();
            }
            AttestationClaims claims = mapper.readValue(payload, AttestationClaims.class);
            requireExactBinding(scope, export, submitted, claims);
            String proofSha256 = ApprovalAuditRedactor.sha256(
                    new String(payload, StandardCharsets.UTF_8)
                            + ":" + submitted.evidenceSignatureBase64Url());
            return new VerifiedExternalAttestation(
                    submitted.type(), submitted.reference(), submitted.attestedAt(),
                    trusted.issuer(), trusted.identity(), trusted.keyId(),
                    "verified:" + proofSha256);
        } catch (BaseException rejected) {
            throw rejected;
        } catch (Exception invalid) {
            throw forbidden();
        }
    }

    private void requireSubmission(
            ExportReceipt export,
            ExternalAttestationSubmission submitted) {
        if (export == null || !"COMPLETE".equals(export.status())
                || export.manifestSha256() == null
                || !export.manifestSha256().matches("[a-f0-9]{64}")
                || submitted == null
                || !SUPPORTED_TYPES.contains(submitted.type())
                || submitted.reference() == null
                || !submitted.reference().equals(submitted.reference().strip())
                || submitted.reference().isBlank()
                || submitted.reference().length() > 500
                || submitted.attestedAt() == null
                || export.completedAt() == null
                || submitted.attestedAt().isBefore(export.completedAt())
                || submitted.attestedAt().isAfter(clock.instant().plusSeconds(60))
                || submitted.evidencePayloadBase64Url() == null
                || !submitted.evidencePayloadBase64Url().matches("[A-Za-z0-9_-]{1,12000}")
                || submitted.evidenceSignatureBase64Url() == null
                || !submitted.evidenceSignatureBase64Url().matches("[A-Za-z0-9_-]{86}")) {
            throw forbidden();
        }
    }

    private void requireExactBinding(
            Scope scope,
            ExportReceipt export,
            ExternalAttestationSubmission submitted,
            AttestationClaims claims) {
        Instant now = clock.instant();
        if (claims == null
                || !trusted.issuer().equals(claims.issuer())
                || !trusted.identity().equals(claims.attestorIdentity())
                || !trusted.keyId().equals(claims.keyId())
                || !AUDIENCE.equals(claims.audience())
                || !PURPOSE.equals(claims.purpose())
                || scope.tenantId() != claims.tenantId()
                || !scope.resourceSetKey().equals(claims.resourceSetKey())
                || !export.exportId().equals(claims.exportId())
                || !export.manifestSha256().equals(claims.manifestSha256())
                || !submitted.type().equals(claims.type())
                || !submitted.reference().equals(claims.reference())
                || !submitted.attestedAt().equals(claims.attestedAt())
                || claims.issuedAt() == null
                || claims.expiresAt() == null
                || claims.issuedAt().isBefore(claims.attestedAt())
                || claims.issuedAt().isAfter(now.plusSeconds(5))
                || claims.issuedAt().isBefore(now.minus(MAX_PROOF_LIFETIME))
                || !claims.expiresAt().isAfter(now)
                || !claims.expiresAt().isAfter(claims.issuedAt())
                || Duration.between(claims.issuedAt(), claims.expiresAt())
                        .compareTo(MAX_PROOF_LIFETIME) > 0
                || claims.nonce() == null
                || !claims.nonce().matches("[A-Za-z0-9._:-]{16,160}")) {
            throw forbidden();
        }
    }

    private static TrustedAttestor configured(
            String publicKey,
            String issuer,
            String identity,
            String keyId) {
        if (publicKey.isBlank() && issuer.isBlank() && identity.isBlank() && keyId.isBlank()) {
            return null;
        }
        if (publicKey.isBlank() || issuer.isBlank() || identity.isBlank() || keyId.isBlank()) {
            throw new IllegalArgumentException(
                    "External audit attestor configuration must be complete.");
        }
        if (!issuer.equals(issuer.strip()) || issuer.length() > 160
                || !identity.equals(identity.strip()) || identity.length() > 160
                || !keyId.equals(keyId.strip()) || keyId.length() > 120) {
            throw new IllegalArgumentException(
                    "External audit attestor identity configuration is invalid.");
        }
        try {
            PublicKey parsed = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            return new TrustedAttestor(issuer, identity, keyId, parsed);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "External audit attestor public key is invalid.", invalid);
        }
    }

    private static BaseException forbidden() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The external audit attestation is not trusted or is not exactly bound.");
    }
}
