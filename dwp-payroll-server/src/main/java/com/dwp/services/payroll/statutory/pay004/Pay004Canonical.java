package com.dwp.services.payroll.statutory.pay004;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAY_RETIREMENT_WORKBENCH.enabled",
        havingValue = "true", matchIfMissing = false)
final class Pay004Canonical {

    private final ObjectMapper mapper;
    private final byte[] cursorSecret;

    Pay004Canonical(
            ObjectMapper mapper,
            @Value("${dwp.payroll.pay004.cursor-secret:}") String cursorSecret) {
        this.mapper = mapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.cursorSecret = cursorSecret == null ? new byte[0]
                : cursorSecret.getBytes(StandardCharsets.UTF_8);
    }

    String digest(Object value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(mapper.writeValueAsBytes(value)));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "PAY-004 canonicalization failed", exception);
        }
    }

    String queryBinding(
            Pay004Authority.Actor actor,
            Pay004Models.QueryRequest query,
            Instant asOf,
            Map<String, Pay004Models.Decision> decisions) {
        LinkedHashMap<String, Object> binding = new LinkedHashMap<>();
        binding.put("schema", "Pay004EntryQueryCursorBinding.v1");
        binding.put("projectionVersion", "1");
        binding.put("variantKey", Pay004Models.VARIANT);
        binding.put("tenantId", actor.subject().tenantId());
        binding.put("legalEntityId", actor.scope().legalEntityId());
        binding.put("payrollGroupId", actor.scope().payrollGroupId());
        binding.put("scopeRevision", actor.scope().projectionRevision());
        binding.put("scopeDigest", actor.scope().scopeDigest());
        binding.put("authorizationRevision",
                actor.subject().authorizationRevision());
        binding.put("decisionRevision", actor.subject().decisionRevision());
        binding.put("fieldPolicyRevision",
                actor.subject().fieldPolicyRevision());
        binding.put("fieldPolicyKey", actor.subject().fieldPolicyKey());
        binding.put("populationKind", actor.subject().populationKind());
        binding.put("asOf", asOf);
        binding.put("filters", query.filter());
        binding.put("sort", query.sort());
        binding.put("limit", query.limit());
        binding.put("locale", query.locale());
        binding.put("timeZone", query.timeZone());
        binding.put("decisions", decisions);
        return digest(binding);
    }

    String cursor(int offset, String bindingDigest, Instant asOf) {
        requireCursorAuthority();
        try {
            CursorState state = new CursorState(offset, bindingDigest, asOf);
            byte[] payload = mapper.writeValueAsBytes(state);
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(hmac(payload));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mapper.writeValueAsBytes(
                            new CursorEnvelope(state, signature)));
        } catch (Exception exception) {
            throw new IllegalStateException("PAY-004 cursor creation failed",
                    exception);
        }
    }

    CursorState cursorState(String token) {
        if (token == null) return null;
        requireCursorAuthority();
        try {
            CursorEnvelope envelope = mapper.readValue(
                    Base64.getUrlDecoder().decode(token), CursorEnvelope.class);
            byte[] payload = mapper.writeValueAsBytes(envelope.state());
            byte[] supplied = Base64.getUrlDecoder().decode(envelope.signature());
            if (!MessageDigest.isEqual(hmac(payload), supplied)
                    || envelope.state().offset() < 0) {
                throw invalidCursor();
            }
            Pay004Models.digest(
                    envelope.state().bindingDigest(), "cursor bindingDigest");
            return envelope.state();
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    private void requireCursorAuthority() {
        if (cursorSecret.length < 32) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "PAY-004 cursor authority is unavailable.");
        }
    }

    private byte[] hmac(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(cursorSecret, "HmacSHA256"));
        return mac.doFinal(payload);
    }

    private BaseException invalidCursor() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "Cursor does not belong to this governed PAY-004 query.");
    }

    record CursorState(int offset, String bindingDigest, Instant asOf) { }
    record CursorEnvelope(CursorState state, String signature) { }
}
