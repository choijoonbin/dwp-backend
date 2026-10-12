package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.crypto.EnvelopeCiphertext;
import com.dwp.core.crypto.EnvelopeCiphertextCodec;
import com.dwp.core.crypto.EnvelopeEncryptionService;
import com.dwp.core.crypto.KeyContext;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Encrypts the frozen patch using the platform envelope and trusted row AAD. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangePatchProtector {
    private final ObjectProvider<EnvelopeEncryptionService> encryption;
    private final String environment;
    private final EnvelopeCiphertextCodec codec = new EnvelopeCiphertextCodec();

    EmployeeChangePatchProtector(
            ObjectProvider<EnvelopeEncryptionService> encryption,
            @Value("${dwp.environment:local}") String environment) {
        this.encryption = encryption;
        this.environment = canonicalEnvironment(environment);
    }

    ProtectedPatch protect(long tenantId, UUID requestPublicId, byte[] plaintext) {
        EnvelopeEncryptionService service = requireEncryption();
        EnvelopeCiphertext envelope = service.encrypt(
                plaintext, context(tenantId, requestPublicId));
        String encoded = codec.encode(envelope);
        String keyReference = envelope.keySlot().provider() + ':'
                + envelope.keySlot().immutableKeyId()
                + (envelope.keySlot().keyVersion() == null
                ? "" : ':' + envelope.keySlot().keyVersion());
        if (keyReference.length() > 255) {
            throw new IllegalStateException("Employee-change key reference is too long.");
        }
        return new ProtectedPatch(encoded.getBytes(StandardCharsets.US_ASCII), keyReference);
    }

    byte[] reveal(long tenantId, UUID requestPublicId, byte[] ciphertext) {
        String encoded = new String(ciphertext, StandardCharsets.US_ASCII);
        return requireEncryption().decrypt(codec.decode(encoded), context(tenantId, requestPublicId));
    }

    private EnvelopeEncryptionService requireEncryption() {
        EnvelopeEncryptionService service = encryption.getIfAvailable();
        if (service == null) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Employee-change encryption authority is unavailable.");
        }
        return service;
    }

    private KeyContext context(long tenantId, UUID requestPublicId) {
        return new KeyContext(
                environment, "dwp-people-server", "HRIS_EMPLOYEE_CHANGE",
                tenantId, "EMPLOYEE_CHANGE_REQUEST", requestPublicId.toString(), "patch");
    }

    private String canonicalEnvironment(String value) {
        String normalized = value == null ? "local" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("prod")) return "prod";
        if (normalized.startsWith("qa") || normalized.startsWith("stage")) return "qa";
        if (normalized.startsWith("dev")) return "dev";
        return "local";
    }

    record ProtectedPatch(byte[] bytes, String keyReference) {
        ProtectedPatch {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
