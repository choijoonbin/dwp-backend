package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Authenticated cursor bound to all eleven sealed IAQ owner-query axes. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class IntegrationCursorCodec {

    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final ObjectMapper json;

    IntegrationCursorCodec(
            @Value("${dwp.hris.integration-control-plane.cursor-secret:}")
            String secret,
            ObjectMapper json) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "SYS-008 cursor secret must contain at least 32 characters");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    String encode(CursorBinding binding) {
        return encodeValue(binding);
    }

    String encodeCatalog(CatalogCursorBinding binding) {
        return encodeValue(binding);
    }

    private String encodeValue(Object binding) {
        try {
            byte[] payload = json.writeValueAsBytes(binding);
            return ENCODER.encodeToString(payload) + "."
                    + ENCODER.encodeToString(mac(payload));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Unable to encode cursor", invalid);
        }
    }

    CursorBinding decode(String token) {
        return decodeValue(token, CursorBinding.class);
    }

    CatalogCursorBinding decodeCatalog(String token) {
        return decodeValue(token, CatalogCursorBinding.class);
    }

    private <T> T decodeValue(String token, Class<T> type) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 2 || parts[0].length() > 8192
                    || parts[1].length() > 512) {
                throw new IllegalArgumentException("Cursor shape is invalid");
            }
            byte[] payload = DECODER.decode(parts[0]);
            byte[] signature = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(signature, mac(payload))) {
                throw new IllegalArgumentException("Cursor signature is invalid");
            }
            return json.readValue(payload, type);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cursor is invalid", invalid);
        }
    }

    AuthenticatedCursor authenticate(String token) {
        CursorBinding binding = decode(token);
        return new AuthenticatedCursor(
                binding.tenantId(), binding.actorId(), binding.queryVariant(),
                binding.filters(), binding.sort(), binding.asOf(), binding.locale(),
                binding.timeZone(), binding.scopeRevision(),
                binding.fieldPolicyRevision(), binding.authorizationRevision(),
                IntegrationDomainPolicy.sha256(token));
    }

    private byte[] mac(byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    record CursorBinding(
            long tenantId,
            long actorId,
            Projection queryVariant,
            Map<String, String> filters,
            String sort,
            Instant asOf,
            String locale,
            String timeZone,
            long scopeRevision,
            long fieldPolicyRevision,
            long authorizationRevision,
            String lastItemPublicId) {

        CursorBinding {
            filters = Map.copyOf(filters);
            if (tenantId < 1 || actorId < 1 || scopeRevision < 1
                    || fieldPolicyRevision < 1 || authorizationRevision < 1
                    || lastItemPublicId == null || lastItemPublicId.isBlank()) {
                throw new IllegalArgumentException("Cursor binding is incomplete");
            }
        }
    }

    record CatalogCursorBinding(
            long tenantId,
            long actorId,
            List<String> fields,
            String freshness,
            String filter,
            Instant asOf,
            long scopeRevision,
            long fieldPolicyRevision,
            long authorizationRevision,
            UUID lastItemPublicId) {

        CatalogCursorBinding {
            fields = List.copyOf(fields);
            if (tenantId < 1 || actorId < 1 || fields.isEmpty()
                    || freshness == null || freshness.isBlank()
                    || asOf == null || scopeRevision < 1
                    || fieldPolicyRevision < 1 || authorizationRevision < 1
                    || lastItemPublicId == null) {
                throw new IllegalArgumentException(
                        "Catalog cursor binding is incomplete");
            }
        }

        boolean matches(
                IntegrationOwnerAuthorityFilter.Authority authority,
                List<String> currentFields,
                String currentFreshness,
                String currentFilter,
                Instant currentAsOf) {
            return tenantId == authority.tenantId()
                    && actorId == authority.actorId()
                    && fields.equals(currentFields)
                    && freshness.equals(currentFreshness)
                    && java.util.Objects.equals(filter, currentFilter)
                    && asOf.equals(currentAsOf)
                    && scopeRevision == authority.scopeRevision()
                    && fieldPolicyRevision == authority.fieldPolicyRevision()
                    && authorizationRevision == authority.authorizationRevision();
        }
    }
}
