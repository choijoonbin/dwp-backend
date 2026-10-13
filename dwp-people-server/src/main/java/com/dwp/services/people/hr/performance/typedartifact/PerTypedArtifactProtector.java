package com.dwp.services.people.hr.performance.typedartifact;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.crypto.EnvelopeCiphertext;
import com.dwp.core.crypto.EnvelopeCiphertextCodec;
import com.dwp.core.crypto.EnvelopeEncryptionService;
import com.dwp.core.crypto.KeyContext;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Envelope encryption boundary. Missing key authority fails closed. */
@Component
final class PerTypedArtifactProtector {

    private final ObjectProvider<EnvelopeEncryptionService> encryption;
    private final String environment;
    private final EnvelopeCiphertextCodec codec = new EnvelopeCiphertextCodec();

    PerTypedArtifactProtector(
            ObjectProvider<EnvelopeEncryptionService> encryption,
            @Value("${dwp.environment:local}") String environment) {
        this.encryption = encryption;
        this.environment = canonicalEnvironment(environment);
    }

    ProtectedContent protect(
            long tenantId,
            UUID artifactPublicId,
            PerArtifactType type,
            byte[] plaintext) {
        EnvelopeCiphertext envelope = requireEncryption().encrypt(
                plaintext, context(tenantId, artifactPublicId, type));
        String encoded = codec.encode(envelope);
        return new ProtectedContent(
                encoded.getBytes(StandardCharsets.US_ASCII),
                keyVersionPublicId(envelope));
    }

    byte[] reveal(
            long tenantId,
            UUID artifactPublicId,
            PerArtifactType type,
            UUID expectedKeyVersionPublicId,
            byte[] encrypted) {
        String encoded = new String(encrypted, StandardCharsets.US_ASCII);
        EnvelopeCiphertext envelope = codec.decode(encoded);
        if (!Objects.equals(
                expectedKeyVersionPublicId, keyVersionPublicId(envelope))) {
            throw new IllegalArgumentException(
                    "Persisted typed-artifact key version does not match its envelope.");
        }
        return requireEncryption().decrypt(
                envelope, context(tenantId, artifactPublicId, type));
    }

    private EnvelopeEncryptionService requireEncryption() {
        EnvelopeEncryptionService service = encryption.getIfAvailable();
        if (service == null) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "PER typed-artifact encryption authority is unavailable.");
        }
        return service;
    }

    private KeyContext context(
            long tenantId,
            UUID artifactPublicId,
            PerArtifactType type) {
        return new KeyContext(
                environment,
                "dwp-people-server",
                "HRIS_PER_TYPED_ARTIFACT",
                tenantId,
                type.name(),
                artifactPublicId.toString(),
                "canonical-content");
    }

    private String canonicalEnvironment(String value) {
        String normalized = value == null ? "local" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("prod")) return "prod";
        if (normalized.startsWith("qa") || normalized.startsWith("stage")) return "qa";
        if (normalized.startsWith("dev")) return "dev";
        return "local";
    }

    private UUID keyVersionPublicId(EnvelopeCiphertext envelope) {
        String keyIdentity = envelope.keySlot().provider() + ':'
                + envelope.keySlot().immutableKeyId() + ':'
                + String.valueOf(envelope.keySlot().keyVersion());
        return UUID.nameUUIDFromBytes(keyIdentity.getBytes(StandardCharsets.UTF_8));
    }

    record ProtectedContent(byte[] bytes, UUID keyVersionPublicId) {
        ProtectedContent {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
