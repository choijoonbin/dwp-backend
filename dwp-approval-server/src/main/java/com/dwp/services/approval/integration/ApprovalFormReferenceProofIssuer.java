package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormReferenceDirectory.MutationPins;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.dwp.services.approval.integration.ApprovalFormUserProofIssuer.SignedProof;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Separate closed 26-claim profile seals the actual ACTION, never a relabeled DATA decision. */
@Component
public final class ApprovalFormReferenceProofIssuer {
    private final ObjectMapper mapper;
    private final RSAKey key;
    private final Clock clock;

    @Autowired
    public ApprovalFormReferenceProofIssuer(ObjectMapper mapper,
            @Value("${dwp.approval.form-user-source-private-jwk:}") String privateJwk) {
        this(mapper, privateJwk, Clock.systemUTC());
    }

    public ApprovalFormReferenceProofIssuer(ObjectMapper mapper, String privateJwk, Clock clock) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).disable(SerializationFeature.INDENT_OUTPUT);
        this.clock = clock;
        this.key = privateKey(privateJwk);
    }

    public SignedProof resolve(Authority authority, MutationPins pins, List<UUID> ids) {
        if (authority == null || pins == null || ids == null || ids.isEmpty() || ids.size() > 30
                || ids.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw invalid();
        pins.requireRoute(authority.routeContractKey());
        requireAuthority(authority);
        if (key == null) throw unavailable();
        long issued = clock.instant().getEpochSecond();
        long expires = Math.min(issued + 30, authority.validUntil().toEpochSecond());
        if (expires <= issued) throw unavailable();
        UUID jti = UUID.randomUUID();
        try {
            var envelope = new TreeMap<String, Object>();
            envelope.put("operation", "RESOLVE");
            envelope.put("request", Map.of("personPublicIds", ids.stream().map(UUID::toString).toList()));
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(envelope)));
            var claims = new TreeMap<String, Object>();
            claims.put("iss", ApprovalFormReferenceDirectory.ISSUER);
            claims.put("aud", ApprovalFormReferenceDirectory.AUDIENCE);
            claims.put("purpose", ApprovalFormReferenceDirectory.PURPOSE);
            claims.put("jti", jti.toString());
            claims.put("iat", issued);
            claims.put("nbf", issued);
            claims.put("exp", expires);
            claims.put("tenantId", authority.form().tenantId());
            claims.put("actorId", authority.form().actorId());
            claims.put("formId", authority.form().formId().toString());
            claims.put("formVersionId", authority.form().formVersionId().toString());
            claims.put("schemaSha256", authority.form().schemaSha256());
            claims.put("contextKey", authority.contextKey());
            claims.put("contextScopeKey", authority.contextScopeKey());
            claims.put("decisionRevision", authority.decisionRevision());
            claims.put("routeContractKey", authority.routeContractKey());
            claims.put("accessMode", authority.accessMode());
            claims.put("referencePurpose", "MUTATION_REFERENCE");
            claims.put("operation", "RESOLVE");
            claims.put("requestDigest", digest);
            claims.put("targetRequestId", pins.targetRequestId().toString());
            claims.put("targetRequestVersion", pins.targetRequestVersion());
            claims.put("mutationPayloadSha256", pins.mutationPayloadSha256());
            claims.put("idempotencyKey", pins.idempotencyKey());
            claims.put("mutationMethod", pins.mutationMethod());
            claims.put("mutationPath", pins.mutationPath());
            var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                    new Payload(mapper.writeValueAsString(claims)));
            token.sign(new RSASSASigner(key));
            return new SignedProof(token.serialize(), jti, digest);
        } catch (Exception exception) { throw unavailable(); }
    }

    private void requireAuthority(Authority value) {
        var form = value.form();
        if (form == null || form.tenantId() <= 0 || form.actorId() <= 0 || form.formId() == null || form.formVersionId() == null
                || form.schemaSha256() == null || !form.schemaSha256().matches("[a-f0-9]{64}")
                || !ApprovalFormUserDirectory.SOURCE_POLICY.equals(value.sourcePolicyKey())
                || !text(value.contextKey()) || !text(value.contextScopeKey()) || value.decisionRevision() == null
                || !value.decisionRevision().matches("psr-[a-f0-9]{64}") || !"MUTATION_REFERENCE".equals(value.referencePurpose())
                || !Set.of("NORMAL", "ELEVATED").contains(value.accessMode() == null ? "" : value.accessMode())
                || value.validUntil() == null || !clock.instant().isBefore(value.validUntil().toInstant())) throw unavailable();
    }
    private RSAKey privateKey(String json) {
        if (json == null || json.isBlank() || json.length() > 16384) return null;
        try {
            mapper.readTree(json);
            var value = RSAKey.parse(json);
            if (!value.isPrivate() || value.size() < 2048 || !JWSAlgorithm.RS256.equals(value.getAlgorithm())
                    || !KeyUse.SIGNATURE.equals(value.getKeyUse()) || value.getKeyID() == null
                    || !value.getKeyID().matches("[A-Za-z0-9._-]{1,80}")) return null;
            return value;
        } catch (Exception exception) { return null; }
    }
    private boolean text(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip()) && value.length() <= 512
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Exact bounded mutation person references are required."); }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Approval reference proof signing is unavailable."); }
}
