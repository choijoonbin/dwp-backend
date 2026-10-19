package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
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
import java.util.Locale;
import java.util.Map;

@Component
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_STATUTORY_INSURANCE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class Pay003Canonical {

    private final ObjectMapper mapper;
    private final byte[] cursorSecret;

    Pay003Canonical(
            ObjectMapper mapper,
            @Value("${dwp.payroll.pay003.cursor-secret:}") String cursorSecret) {
        this.mapper = mapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.cursorSecret = cursorSecret == null
                ? new byte[0] : cursorSecret.getBytes(StandardCharsets.UTF_8);
    }

    String digest(Object value) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(value);
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("PAY-003 canonicalization failed", exception);
        }
    }

    String activationDigest(
            Pay003Authority.Actor actor,
            Pay003Models.PackSnapshot snapshot,
            Pay003Models.ActivationCommand command,
            long expectedRevision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("effectiveDate", command.effectiveDate());
        body.put("goldenPackDigest", command.goldenPackDigest());
        body.put("officialReferenceSet", command.officialReferenceSet());
        body.put("statutoryApprovalReceiptId",
                command.statutoryApprovalReceiptId());
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("schema", "PayCountryPackActivateRequest.v1");
        binding.put("packId", snapshot.packId());
        binding.put("expectedRevision", expectedRevision);
        binding.put("body", body);
        binding.put("tenantId", actor.subject().tenantId());
        binding.put("legalEntityId", actor.scope().legalEntityId());
        binding.put("payrollGroupId", actor.scope().payrollGroupId());
        binding.put("scopeDigest", actor.scope().scopeDigest());
        binding.put("populationKind", actor.subject().populationKind());
        binding.put("fieldPolicyKey", actor.subject().fieldPolicyKey());
        return digest(binding);
    }

    String policyKey(String raw) {
        String value = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-");
        if (!value.matches("[a-z][a-z0-9._-]{1,127}")) {
            return "statutory." + digest(raw).substring(0, 16);
        }
        return value;
    }

    String queryBinding(
            Pay003Authority.Actor actor,
            Pay003Models.QueryRequest query,
            Instant asOf,
            Map<String, Pay003Models.Decision> decisions,
            String freshness,
            String variantKey) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("schema", "Pay003EntryQueryCursorBinding.v1");
        binding.put("variantKey", variantKey);
        binding.put("tenantId", actor.subject().tenantId());
        binding.put("legalEntityId", actor.scope().legalEntityId());
        binding.put("payrollGroupId", actor.scope().payrollGroupId());
        binding.put("scopeRevision", actor.scope().projectionRevision());
        binding.put("scopeDigest", actor.scope().scopeDigest());
        binding.put("populationKind", actor.subject().populationKind());
        binding.put("fieldPolicyKey", actor.subject().fieldPolicyKey());
        binding.put("fieldPolicyRevision",
                actor.subject().fieldPolicyRevision());
        binding.put("authorizationRevision",
                actor.subject().authorizationRevision());
        binding.put("decisionRevision", actor.subject().decisionRevision());
        binding.put("asOf", asOf);
        binding.put("filter", query.filter());
        binding.put("sort", query.sort());
        binding.put("limit", query.limit());
        binding.put("locale", query.locale());
        binding.put("timeZone", query.timeZone());
        binding.put("decisions", decisions);
        binding.put("freshness", freshness);
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
            throw new IllegalStateException("PAY-003 cursor creation failed", exception);
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
            if (!MessageDigest.isEqual(hmac(payload), supplied)) {
                throw invalidCursor();
            }
            CursorState state = envelope.state();
            if (state.offset() < 0) throw invalidCursor();
            Pay003Models.digest(state.bindingDigest(), "cursor bindingDigest");
            return state;
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    void requireCursorAuthority() {
        if (cursorSecret.length < 32) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "PAY-003 cursor signing authority is unavailable.");
        }
    }

    private byte[] hmac(byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(cursorSecret, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private BaseException invalidCursor() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "Cursor does not belong to this governed PAY-003 query.");
    }

    record CursorState(int offset, String bindingDigest, Instant asOf) {
    }

    record CursorEnvelope(CursorState state, String signature) {
    }
}
