package com.dwp.services.approval.connectors;

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
import java.util.UUID;

import static com.dwp.services.approval.connectors.ConnectorModels.*;

@Component
final class ConnectorProbeAttestationVerifier {
    static final String AUDIENCE = "dwp-approval-server";
    static final String PURPOSE = "APPROVAL_CONNECTOR_PROBE_ATTESTATION_V1";
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
            UUID connectorId,
            UUID probeId,
            UUID revisionId,
            long expectedProbeVersion,
            String requestSha256,
            String outcome,
            String evidenceRevision,
            String evidenceSha256,
            String diagnosticsSha256,
            Instant completedAt,
            Instant validUntil,
            Instant issuedAt,
            Instant expiresAt,
            String nonce) {
    }

    private final ObjectMapper mapper;
    private final ApprovalDocumentCanonical canonical;
    private final Clock clock;
    private final TrustedAttestor trusted;

    @Autowired
    ConnectorProbeAttestationVerifier(
            ObjectMapper mapper,
            ApprovalDocumentCanonical canonical,
            @Value("${approval.connectors.probe-attestation.trusted-public-key-x509-base64:}")
            String publicKey,
            @Value("${approval.connectors.probe-attestation.trusted-issuer:}") String issuer,
            @Value("${approval.connectors.probe-attestation.trusted-attestor-identity:}")
            String identity,
            @Value("${approval.connectors.probe-attestation.trusted-key-id:}") String keyId) {
        this(mapper, canonical, Clock.systemUTC(),
                configured(publicKey, issuer, identity, keyId));
    }

    ConnectorProbeAttestationVerifier(
            ObjectMapper mapper,
            ApprovalDocumentCanonical canonical,
            Clock clock,
            TrustedAttestor trusted) {
        this.mapper = mapper.copy()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.canonical = canonical;
        this.clock = clock;
        this.trusted = trusted;
    }

    String verify(
            Context context,
            UUID connectorId,
            ProbeView probe,
            ProbeCompletion submitted) {
        if (trusted == null) {
            throw ConnectorRejected.unavailable(
                    "No trusted connector-probe attestor is configured.");
        }
        try {
            byte[] payload = payload(submitted.evidencePayloadBase64Url());
            byte[] signatureBytes = signature(submitted.evidenceSignatureBase64Url());
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(trusted.publicKey());
            signature.update(payload);
            if (!signature.verify(signatureBytes)) throw forbidden();
            Claims claims = mapper.readValue(payload, Claims.class);
            requireExactBinding(context, connectorId, probe, submitted, claims);
            return "verified:" + sha256(payload, submitted.evidenceSignatureBase64Url());
        } catch (ConnectorRejected rejected) {
            throw rejected;
        } catch (Exception invalid) {
            throw forbidden();
        }
    }

    private void requireExactBinding(
            Context context,
            UUID connectorId,
            ProbeView probe,
            ProbeCompletion submitted,
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
                || !connectorId.equals(claims.connectorId())
                || !probe.probeId().equals(claims.probeId())
                || !probe.revisionId().equals(claims.revisionId())
                || submitted.expectedProbeVersion() != claims.expectedProbeVersion()
                || !probe.requestSha256().equals(claims.requestSha256())
                || !submitted.state().name().equals(claims.outcome())
                || !submitted.evidenceRevision().equals(claims.evidenceRevision())
                || !submitted.evidenceSha256().equals(claims.evidenceSha256())
                || !canonical.fingerprint(submitted.diagnostics())
                        .equals(claims.diagnosticsSha256())
                || !submitted.completedAt().equals(claims.completedAt())
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
                    "Connector-probe attestor configuration must be complete and valid.");
        }
        try {
            PublicKey parsed = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            return new TrustedAttestor(issuer, identity, keyId, parsed);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "Connector-probe attestor public key is invalid.", invalid);
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

    private static ConnectorRejected forbidden() {
        return ConnectorRejected.forbidden(
                "The connector-probe attestation is not trusted or exactly bound.");
    }
}
