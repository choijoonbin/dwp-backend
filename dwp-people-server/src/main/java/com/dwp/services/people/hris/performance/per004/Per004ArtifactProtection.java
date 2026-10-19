package com.dwp.services.people.hris.performance.per004;

import com.dwp.core.crypto.EnvelopeCiphertext;
import com.dwp.core.crypto.EnvelopeCiphertextCodec;
import com.dwp.core.crypto.EnvelopeEncryptionService;
import com.dwp.core.crypto.KeyContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** No plaintext fallback and no key material owned by this module. */
@Component
final class Per004ArtifactProtection {
    private final ObjectProvider<EnvelopeEncryptionService> service;
    private final String environment;
    private final EnvelopeCiphertextCodec codec = new EnvelopeCiphertextCodec();

    Per004ArtifactProtection(ObjectProvider<EnvelopeEncryptionService> service,
            @Value("${dwp.environment:local}") String environment) {
        this.service = service;
        String value = environment == null ? "local" : environment.toLowerCase(Locale.ROOT).trim();
        this.environment = value.startsWith("prod") ? "prod"
                : value.startsWith("qa") || value.startsWith("stage") ? "qa"
                : value.startsWith("dev") ? "dev" : "local";
    }

    Protected protect(long tenantId, UUID artifactId, String artifactType, byte[] plaintext) {
        EnvelopeCiphertext ciphertext = require().encrypt(plaintext,
                context(tenantId, artifactId, artifactType));
        return new Protected(codec.encode(ciphertext).getBytes(StandardCharsets.US_ASCII), keyId(ciphertext));
    }

    byte[] reveal(long tenantId, UUID artifactId, String artifactType, byte[] ciphertext,
            UUID expectedKeyId) {
        EnvelopeCiphertext envelope = codec.decode(new String(ciphertext, StandardCharsets.US_ASCII));
        if (!keyId(envelope).equals(expectedKeyId)) throw unavailable();
        return require().decrypt(envelope, context(tenantId, artifactId, artifactType));
    }

    private EnvelopeEncryptionService require() {
        EnvelopeEncryptionService resolved = service.getIfAvailable();
        if (resolved == null) throw unavailable();
        return resolved;
    }
    private KeyContext context(long tenantId, UUID artifactId, String artifactType) {
        return new KeyContext(environment, "dwp-people-server", "HRIS_PER_TYPED_ARTIFACT",
                tenantId, artifactType, artifactId.toString(), "canonical-content");
    }
    private UUID keyId(EnvelopeCiphertext envelope) {
        String identity = envelope.keySlot().provider() + ':' + envelope.keySlot().immutableKeyId()
                + ':' + String.valueOf(envelope.keySlot().keyVersion());
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("PER004_ENCRYPTION_UNAVAILABLE");
    }
    record Protected(byte[] bytes, UUID keyVersionId) {
        Protected { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
