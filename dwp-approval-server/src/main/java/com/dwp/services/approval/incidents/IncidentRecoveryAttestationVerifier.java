package com.dwp.services.approval.incidents;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.incidents.IncidentModels.*;

@Component
final class IncidentRecoveryAttestationVerifier {
    static final String AUDIENCE = "dwp-approval-server";
    static final String PURPOSE = "APPROVAL_INCIDENT_RECOVERY_EXECUTION_V1";
    private static final Duration MAX_PROOF_LIFETIME = Duration.ofMinutes(5);

    record TrustedExecutor(
            String issuer,
            String identity,
            String keyId,
            PublicKey publicKey) {
    }

    record Claims(
            String issuer,
            String executorIdentity,
            String keyId,
            String audience,
            String purpose,
            long tenantId,
            String resourceSetKey,
            UUID incidentId,
            UUID planId,
            int stageNumber,
            long expectedPlanVersion,
            long expectedStageVersion,
            String actionKind,
            String targetType,
            UUID targetId,
            long expectedTargetVersion,
            String executionKey,
            String outcome,
            Map<String, Object> result,
            String evidenceSha256,
            Instant completedAt,
            Instant issuedAt,
            Instant expiresAt,
            String nonce) {
    }

    private final ObjectMapper mapper;
    private final ApprovalDocumentCanonical canonical;
    private final Clock clock;
    private final TrustedExecutor trusted;

    @Autowired
    IncidentRecoveryAttestationVerifier(
            ObjectMapper mapper,
            ApprovalDocumentCanonical canonical,
            @Value("${approval.incidents.recovery-executor.trusted-public-key-x509-base64:}")
            String publicKey,
            @Value("${approval.incidents.recovery-executor.trusted-issuer:}") String issuer,
            @Value("${approval.incidents.recovery-executor.trusted-executor-identity:}")
            String identity,
            @Value("${approval.incidents.recovery-executor.trusted-key-id:}") String keyId) {
        this(mapper, canonical, Clock.systemUTC(),
                configured(publicKey, issuer, identity, keyId));
    }

    IncidentRecoveryAttestationVerifier(
            ObjectMapper mapper,
            ApprovalDocumentCanonical canonical,
            Clock clock,
            TrustedExecutor trusted) {
        this.mapper = mapper.copy()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.canonical = canonical;
        this.clock = clock;
        this.trusted = trusted;
    }

    VerifiedStageCompletion verify(
            ExecutionRequest request,
            SignedExecutionReceipt submitted) {
        if (trusted == null) {
            throw IncidentRejected.unavailable(
                    "No trusted incident-recovery executor is configured.");
        }
        try {
            byte[] payload = payload(submitted == null
                    ? null : submitted.evidencePayloadBase64Url());
            byte[] signatureBytes = signature(submitted.evidenceSignatureBase64Url());
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(trusted.publicKey());
            signature.update(payload);
            if (!signature.verify(signatureBytes)) throw forbidden();
            Claims claims = mapper.readValue(payload, Claims.class);
            requireExactBinding(request, claims);
            String verification = "verified:"
                    + sha256(payload, submitted.evidenceSignatureBase64Url());
            return new VerifiedStageCompletion(
                    request.stageNumber(), request.expectedPlanVersion(),
                    request.expectedStageVersion(), StageState.valueOf(claims.outcome()),
                    Map.copyOf(claims.result()), claims.evidenceSha256(), claims.completedAt(),
                    trusted.issuer(), trusted.keyId(), verification);
        } catch (IncidentRejected rejected) {
            throw rejected;
        } catch (Exception invalid) {
            throw forbidden();
        }
    }

    VerifiedStageCompletion unknown(
            ExecutionRequest request,
            Instant completedAt,
            String reason) {
        Map<String, Object> result = Map.of(
                "outcome", "UNKNOWN_REMOTE_OUTCOME",
                "reason", reason);
        String evidence = canonical.fingerprint(Map.of(
                "request", request,
                "outcome", "UNKNOWN_REMOTE_OUTCOME",
                "result", result,
                "completedAt", completedAt));
        return new VerifiedStageCompletion(
                request.stageNumber(), request.expectedPlanVersion(),
                request.expectedStageVersion(), StageState.UNKNOWN_REMOTE_OUTCOME,
                result, evidence, completedAt, null, null, null);
    }

    private void requireExactBinding(ExecutionRequest request, Claims claims) {
        Instant now = clock.instant();
        if (request == null || claims == null
                || !trusted.issuer().equals(claims.issuer())
                || !trusted.identity().equals(claims.executorIdentity())
                || !trusted.keyId().equals(claims.keyId())
                || !AUDIENCE.equals(claims.audience())
                || !PURPOSE.equals(claims.purpose())
                || request.tenantId() != claims.tenantId()
                || !request.resourceSetKey().equals(claims.resourceSetKey())
                || !request.incidentId().equals(claims.incidentId())
                || !request.planId().equals(claims.planId())
                || request.stageNumber() != claims.stageNumber()
                || request.expectedPlanVersion() != claims.expectedPlanVersion()
                || request.expectedStageVersion() != claims.expectedStageVersion()
                || !request.actionKind().name().equals(claims.actionKind())
                || !request.targetType().name().equals(claims.targetType())
                || !request.targetId().equals(claims.targetId())
                || request.expectedTargetVersion() != claims.expectedTargetVersion()
                || !request.executionKey().equals(claims.executionKey())
                || !terminal(claims.outcome())
                || claims.result() == null || claims.result().isEmpty()
                || claims.completedAt() == null
                || claims.completedAt().isAfter(now.plusSeconds(30))
                || !expectedEvidence(request, claims).equals(claims.evidenceSha256())
                || claims.issuedAt() == null
                || claims.expiresAt() == null
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

    String expectedEvidence(ExecutionRequest request, Claims claims) {
        return canonical.fingerprint(Map.of(
                "request", request,
                "outcome", claims.outcome(),
                "result", claims.result(),
                "completedAt", claims.completedAt()));
    }

    private boolean terminal(String outcome) {
        if (outcome == null) return false;
        try {
            return switch (StageState.valueOf(outcome)) {
                case SUCCEEDED, FAILED, UNKNOWN_REMOTE_OUTCOME -> true;
                default -> false;
            };
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private byte[] payload(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,12000}")) throw forbidden();
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (decoded.length == 0 || decoded.length > 8_192) throw forbidden();
        return decoded;
    }

    private byte[] signature(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{86}")) throw forbidden();
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (decoded.length != 64) throw forbidden();
        return decoded;
    }

    private static TrustedExecutor configured(
            String publicKey,
            String issuer,
            String identity,
            String keyId) {
        if (publicKey.isBlank() && issuer.isBlank() && identity.isBlank() && keyId.isBlank()) {
            return null;
        }
        if (publicKey.isBlank() || issuer.isBlank() || identity.isBlank() || keyId.isBlank()
                || !validIdentity(issuer, 160)
                || !validIdentity(identity, 160)
                || !validIdentity(keyId, 120)) {
            throw new IllegalArgumentException(
                    "Incident-recovery executor configuration must be complete and valid.");
        }
        try {
            PublicKey parsed = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            return new TrustedExecutor(issuer, identity, keyId, parsed);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "Incident-recovery executor public key is invalid.", invalid);
        }
    }

    private static boolean validIdentity(String value, int limit) {
        return value.equals(value.strip()) && !value.isBlank() && value.length() <= limit;
    }

    private static String sha256(byte[] payload, String signature) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(payload);
        digest.update((byte) ':');
        digest.update(signature.getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static IncidentRejected forbidden() {
        return IncidentRejected.forbidden(
                "The recovery execution receipt is not trusted or exactly bound.");
    }
}
