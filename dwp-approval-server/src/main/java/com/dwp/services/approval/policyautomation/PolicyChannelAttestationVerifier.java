package com.dwp.services.approval.policyautomation;

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
import java.util.UUID;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.ChannelObservation;
import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.Context;

@Component
final class PolicyChannelAttestationVerifier {
    static final String AUDIENCE = "dwp-approval-server";
    static final String PURPOSE = "APPROVAL_NOTIFICATION_CHANNEL_READINESS_V1";
    private static final Duration MAX_PROOF_LIFETIME = Duration.ofMinutes(5);

    record TrustedAttestor(
            String issuer,
            String identity,
            String keyId,
            PublicKey publicKey) {
    }

    record Claims(
            String issuer,
            String attestorIdentity,
            String keyId,
            String audience,
            String purpose,
            long tenantId,
            String resourceSetKey,
            UUID channelId,
            long expectedChannelVersion,
            String outcome,
            String sourceRevision,
            String evidenceSha256,
            Instant observedAt,
            Instant validUntil,
            Instant issuedAt,
            Instant expiresAt,
            String nonce) {
    }

    private final ObjectMapper mapper;
    private final Clock clock;
    private final TrustedAttestor trusted;

    @Autowired
    PolicyChannelAttestationVerifier(
            ObjectMapper mapper,
            @Value("${approval.policy-automation.channel-attestation.trusted-public-key-x509-base64:}")
            String publicKey,
            @Value("${approval.policy-automation.channel-attestation.trusted-issuer:}")
            String issuer,
            @Value("${approval.policy-automation.channel-attestation.trusted-attestor-identity:}")
            String identity,
            @Value("${approval.policy-automation.channel-attestation.trusted-key-id:}")
            String keyId) {
        this(mapper, Clock.systemUTC(), configured(publicKey, issuer, identity, keyId));
    }

    PolicyChannelAttestationVerifier(
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

    String verify(Context context, UUID channelId, ChannelObservation submitted) {
        if (trusted == null) {
            throw PolicyAutomationRejected.unavailable(
                    "No trusted notification-channel attestor is configured.");
        }
        try {
            byte[] payload = payload(submitted.evidencePayloadBase64Url());
            byte[] signatureBytes = signature(submitted.evidenceSignatureBase64Url());
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(trusted.publicKey());
            signature.update(payload);
            if (!signature.verify(signatureBytes)) throw forbidden();
            Claims claims = mapper.readValue(payload, Claims.class);
            requireExactBinding(context, channelId, submitted, claims);
            return "verified:" + sha256(payload, submitted.evidenceSignatureBase64Url());
        } catch (PolicyAutomationRejected rejected) {
            throw rejected;
        } catch (Exception invalid) {
            throw forbidden();
        }
    }

    private void requireExactBinding(
            Context context,
            UUID channelId,
            ChannelObservation submitted,
            Claims claims) {
        Instant now = clock.instant();
        if (claims == null
                || !trusted.issuer().equals(claims.issuer())
                || !trusted.identity().equals(claims.attestorIdentity())
                || !trusted.keyId().equals(claims.keyId())
                || !AUDIENCE.equals(claims.audience())
                || !PURPOSE.equals(claims.purpose())
                || context.tenantId() != claims.tenantId()
                || !context.resourceSetKey().equals(claims.resourceSetKey())
                || !channelId.equals(claims.channelId())
                || submitted.expectedVersion() != claims.expectedChannelVersion()
                || !submitted.readiness().name().equals(claims.outcome())
                || !submitted.sourceRevision().equals(claims.sourceRevision())
                || !submitted.evidenceSha256().equals(claims.evidenceSha256())
                || !submitted.observedAt().equals(claims.observedAt())
                || !submitted.validUntil().equals(claims.validUntil())
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

    private static TrustedAttestor configured(
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
                    "Notification-channel attestor configuration must be complete and valid.");
        }
        try {
            PublicKey parsed = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            return new TrustedAttestor(issuer, identity, keyId, parsed);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "Notification-channel attestor public key is invalid.", invalid);
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

    private static PolicyAutomationRejected forbidden() {
        return PolicyAutomationRejected.forbidden(
                "The notification-channel attestation is not trusted or exactly bound.");
    }
}
