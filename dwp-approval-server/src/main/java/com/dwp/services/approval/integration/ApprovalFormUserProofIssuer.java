package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.dwp.services.approval.security.ApprovalFormUserCurrentAuthority;
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

/** Signs only the fixed tenant coworker source protocol after owner-side reference validation. */
@Component
public final class ApprovalFormUserProofIssuer {
    public static final String ISSUER = "dwp-approval-server:form-user-source:v1";
    public static final String AUDIENCE = "dwp-auth-server:approval-form-user-directory:v1";
    private final ObjectMapper mapper;
    private final Clock clock;
    private final RSAKey key;

    @Autowired
    public ApprovalFormUserProofIssuer(ObjectMapper mapper,
            @Value("${dwp.approval.form-user-source-private-jwk:}") String privateJwk) {
        this(mapper, privateJwk, Clock.systemUTC());
    }

    public ApprovalFormUserProofIssuer(ObjectMapper mapper, String privateJwk, Clock clock) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(SerializationFeature.INDENT_OUTPUT);
        this.clock = clock;
        this.key = privateKey(privateJwk);
    }

    public SignedProof search(Authority authority, String query, int size) {
        if (query == null || !query.equals(query.strip()) || query.length() < 2 || query.length() > 100
                || query.codePoints().anyMatch(Character::isISOControl) || size < 1 || size > 30) throw invalid();
        return sign(authority, "SEARCH", Map.of("query", query, "size", size));
    }

    public SignedProof resolve(Authority authority, List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 30 || ids.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(ids).size() != ids.size()) throw invalid();
        return sign(authority, "RESOLVE", Map.of("personPublicIds", ids.stream().map(UUID::toString).toList()));
    }

    public String requestDigest(String operation, Map<String, Object> request) {
        if (request == null || !("SEARCH".equals(operation) && request.keySet().equals(Set.of("query", "size")))
                && !("RESOLVE".equals(operation) && request.keySet().equals(Set.of("personPublicIds")))) throw invalid();
        try {
            var envelope = new TreeMap<String, Object>();
            envelope.put("operation", operation);
            envelope.put("request", new TreeMap<>(request));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(envelope)));
        } catch (Exception exception) { throw unavailable(); }
    }

    private SignedProof sign(Authority authority, String operation, Map<String, Object> request) {
        requireAuthority(authority);
        if (key == null) throw unavailable();
        long issued = clock.instant().getEpochSecond();
        long expires = Math.min(issued + 30, authority.validUntil().toEpochSecond());
        if (expires <= issued) throw unavailable();
        UUID id = UUID.randomUUID();
        String digest = requestDigest(operation, request);
        var claims = new TreeMap<String, Object>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("purpose", ApprovalFormUserDirectory.SOURCE_POLICY);
        claims.put("jti", id.toString());
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
        claims.put("referencePurpose", authority.referencePurpose());
        claims.put("operation", operation);
        claims.put("requestDigest", digest);
        try {
            var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                    .keyID(key.getKeyID()).build(), new Payload(mapper.writeValueAsString(claims)));
            token.sign(new RSASSASigner(key));
            return new SignedProof(token.serialize(), id, digest);
        } catch (Exception exception) { throw unavailable(); }
    }

    private void requireAuthority(Authority value) {
        if (value == null || value.form() == null || value.form().tenantId() <= 0 || value.form().actorId() <= 0
                || value.form().formId() == null || value.form().formVersionId() == null
                || !hex(value.form().schemaSha256()) || !ApprovalFormUserDirectory.SOURCE_POLICY.equals(value.sourcePolicyKey())
                || !text(value.contextKey()) || !text(value.contextScopeKey()) || value.decisionRevision() == null
                || !value.decisionRevision().matches("psr-[a-f0-9]{64}")
                || !Set.of("NORMAL", "ELEVATED").contains(value.accessMode() == null ? "" : value.accessMode())
                || value.validUntil() == null || !clock.instant().isBefore(value.validUntil().toInstant())) throw unavailable();
        String purpose = ApprovalFormUserCurrentAuthority.WORK_ROUTE.equals(value.routeContractKey()) ? "CREATE_REFERENCE"
                : ApprovalFormUserCurrentAuthority.ADMIN_ROUTE.equals(value.routeContractKey()) ? "FORM_PREVIEW" : "";
        if (purpose.isEmpty() || !purpose.equals(value.referencePurpose())) throw unavailable();
    }

    private RSAKey privateKey(String json) {
        if (json == null || json.isBlank() || json.length() > 16384) return null;
        try {
            mapper.readTree(json);
            RSAKey value = RSAKey.parse(json);
            if (!value.isPrivate() || value.size() < 2048 || !KeyUse.SIGNATURE.equals(value.getKeyUse())
                    || !JWSAlgorithm.RS256.equals(value.getAlgorithm()) || value.getKeyID() == null
                    || !value.getKeyID().matches("[A-Za-z0-9._-]{1,80}")) return null;
            return value;
        } catch (Exception exception) { return null; }
    }

    private boolean hex(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private boolean text(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip()) && value.length() <= 512
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "An exact bounded person source request is required."); }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Approval person source signing is unavailable."); }

    public record SignedProof(String token, UUID proofId, String requestDigest) { }
}
