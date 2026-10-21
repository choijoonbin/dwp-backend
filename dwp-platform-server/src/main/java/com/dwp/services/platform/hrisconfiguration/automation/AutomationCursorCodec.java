package com.dwp.services.platform.hrisconfiguration.automation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/** Authenticated, query-bound opaque cursors for the two SYS-006 owner reads. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.automation-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class AutomationCursorCodec {

    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper json;
    private final byte[] key;

    AutomationCursorCodec(
            ObjectMapper json,
            @Value("${dwp.hris.automation-control-plane.cursor-key:}") String key) {
        this.json = json;
        if (key == null || key.length() < 32) {
            throw new IllegalStateException(
                    "SYS-006 cursor key must contain at least 32 characters when enabled");
        }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    String encode(Binding binding) {
        try {
            byte[] payload = json.writeValueAsBytes(binding);
            return ENCODER.encodeToString(payload) + "."
                    + ENCODER.encodeToString(mac(payload));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("AUTOMATION_CURSOR_INVALID", invalid);
        }
    }

    Binding decode(String cursor, Binding expected) {
        try {
            String[] parts = cursor == null ? new String[0] : cursor.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] payload = DECODER.decode(parts[0]);
            byte[] supplied = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(mac(payload), supplied)) {
                throw new IllegalArgumentException();
            }
            Binding actual = json.readValue(payload, Binding.class);
            if (!actual.sameQuery(expected)
                    || actual.expiresAt() == null
                    || !actual.expiresAt().isAfter(OffsetDateTime.now())) {
                throw new IllegalArgumentException();
            }
            return actual;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("AUTOMATION_CURSOR_INVALID", invalid);
        }
    }

    private byte[] mac(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(payload);
    }

    @JsonPropertyOrder({
            "tenantId", "subjectPrincipalPublicId", "queryId", "queryDigest",
            "purposeCode", "authorizationRevision", "fieldPolicyRevision",
            "asOf", "collectionKind", "sortTuple", "pageLimit", "expiresAt"})
    record Binding(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String queryId,
            String queryDigest,
            String purposeCode,
            long authorizationRevision,
            Long fieldPolicyRevision,
            OffsetDateTime asOf,
            String collectionKind,
            String sortTuple,
            int pageLimit,
            OffsetDateTime expiresAt) {

        Binding {
            if (tenantId < 1 || subjectPrincipalPublicId == null
                    || queryId == null || queryId.isBlank()
                    || queryDigest == null || !queryDigest.matches("^[a-f0-9]{64}$")
                    || purposeCode == null || purposeCode.isBlank()
                    || authorizationRevision < 1 || asOf == null
                    || collectionKind == null || collectionKind.isBlank()
                    || sortTuple == null || sortTuple.isBlank()
                    || pageLimit < 1 || pageLimit > 100 || expiresAt == null) {
                throw new IllegalArgumentException("Invalid SYS-006 cursor binding");
            }
        }

        private boolean sameQuery(Binding expected) {
            return expected != null
                    && tenantId == expected.tenantId
                    && subjectPrincipalPublicId.equals(expected.subjectPrincipalPublicId)
                    && queryId.equals(expected.queryId)
                    && queryDigest.equals(expected.queryDigest)
                    && purposeCode.equals(expected.purposeCode)
                    && authorizationRevision == expected.authorizationRevision
                    && java.util.Objects.equals(
                    fieldPolicyRevision, expected.fieldPolicyRevision)
                    && asOf.equals(expected.asOf)
                    && collectionKind.equals(expected.collectionKind)
                    && pageLimit == expected.pageLimit;
        }
    }
}
