package com.dwp.services.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ApprovalFormUserProofTestSupport {
    public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    public static final Instant NOW = Instant.parse("2026-09-14T02:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final UUID PERSON = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    public static final RSAKey KEY = key("approval-source-test-1");

    private ApprovalFormUserProofTestSupport() { }

    public static RSAKey key(String id) {
        try { return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    public static ApprovalFormUserSourceProofVerifier verifier() {
        return new ApprovalFormUserSourceProofVerifier(MAPPER, new JWKSet(KEY.toPublicJWK()).toString(), CLOCK);
    }

    public static Map<String, Object> claims(String operation, String digest) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", ApprovalFormUserSourceProofVerifier.ISSUER);
        claims.put("aud", ApprovalFormUserSourceProofVerifier.AUDIENCE);
        claims.put("purpose", ApprovalFormUserSourceProofVerifier.PURPOSE);
        claims.put("jti", "cccccccc-cccc-cccc-cccc-cccccccccccc");
        claims.put("iat", NOW.getEpochSecond()); claims.put("nbf", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(30).getEpochSecond());
        claims.put("tenantId", 10L); claims.put("actorId", 20L);
        claims.put("formId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        claims.put("formVersionId", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        claims.put("schemaSha256", "a".repeat(64)); claims.put("contextKey", "approval-context");
        claims.put("contextScopeKey", "approval-scope"); claims.put("decisionRevision", "psr-" + "b".repeat(64));
        claims.put("routeContractKey", ApprovalFormUserCurrentAuthorityAdapter.WORK_ROUTE);
        claims.put("accessMode", "NORMAL"); claims.put("referencePurpose", "CREATE_REFERENCE");
        claims.put("operation", operation); claims.put("requestDigest", digest);
        return claims;
    }

    public static String sign(Map<String, Object> claims) { return sign(claims, KEY); }

    public static Map<String, Object> referenceClaims(String route, long version, String digest) {
        var claims = claims("RESOLVE", digest);
        claims.put("iss", ApprovalFormReferenceProofVerifier.ISSUER);
        claims.put("aud", ApprovalFormReferenceProofVerifier.AUDIENCE);
        claims.put("purpose", ApprovalFormReferenceProofVerifier.PURPOSE);
        claims.put("referencePurpose", "MUTATION_REFERENCE");
        claims.put("routeContractKey", route);
        claims.put("targetRequestId", "dddddddd-dddd-dddd-dddd-dddddddddddd");
        claims.put("targetRequestVersion", version); claims.put("mutationPayloadSha256", "c".repeat(64));
        claims.put("idempotencyKey", "original-command-key");
        claims.put("mutationMethod", route.endsWith("request-draft-update.action") ? "PUT" : "POST");
        String suffix = route.endsWith("request-submit.action") ? "/submit"
                : route.endsWith("request-information-response.action") ? "/information-response"
                : route.endsWith("request-draft-recover.action") ? "/draft/recover" : "/draft";
        claims.put("mutationPath", route.endsWith("request-create.action") ? "/v1/requests"
                : "/v1/requests/dddddddd-dddd-dddd-dddd-dddddddddddd" + suffix);
        return claims;
    }

    public static String sign(Map<String, Object> claims, RSAKey key) {
        try {
            var signed = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                    .keyID(key.getKeyID()).build(), new Payload(MAPPER.writeValueAsString(claims)));
            signed.sign(new RSASSASigner(key)); return signed.serialize();
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
}
