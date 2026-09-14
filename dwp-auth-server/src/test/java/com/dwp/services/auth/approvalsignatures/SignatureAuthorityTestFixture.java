package com.dwp.services.auth.approvalsignatures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.*;
import java.util.*;

final class SignatureAuthorityTestFixture {
    static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    final SignatureAuthorityJson json = new SignatureAuthorityJson(new ObjectMapper());
    final RSAKey owner=key("approval-signature-owner:test"), transport=key("approval-signature-transport:test"),
            authority=key("approval-signature-authority:test"), mfa=key("auth-step-up:test");
    final SignatureAuthorityKeys keys = new SignatureAuthorityKeys(json, jwks(owner), jwks(transport), authority.toJSONString(), jwks(authority), List.of(jwks(mfa)), true);
    final SignatureAuthorityProofVerifier verifier = new SignatureAuthorityProofVerifier(json, keys, CLOCK);
    static RSAKey key(String id) {
        try { return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    static String jwks(RSAKey key) { return new JWKSet(key.toPublicJWK()).toString(); }
    Map<String,Object> binding(SignatureAuthorityProtocol.Operation operation) {
        var source=new LinkedHashMap<String,Object>();
        for (String field:SignatureAuthorityProtocol.SOURCE_FIELDS) {
            if (field.endsWith("Sha256")) source.put(field,"a".repeat(64));
            else if (field.equals("ownerUserId")) source.put(field,99L);
            else if (field.endsWith("Id")) source.put(field,UUID.randomUUID().toString());
            else if (field.endsWith("Version") || field.endsWith("Revision")) source.put(field,5L);
        }
        source.put("resourceSetKey","RS_APPROVALS"); source.put("rendererVersion","DWP_SELF_ATTESTATION_JSON_V1");
        var body=new LinkedHashMap<String,Object>();
        if (operation.mutation()) { body.put("expectedVersion",5L); body.put("idempotencyKey","original-key"); }
        if (operation==SignatureAuthorityProtocol.Operation.CREATE) {
            body.put("signerKind","SELF_ATTESTATION"); body.put("locale","ko"); body.put("sourceDigest","a".repeat(64));
        }
        var b=new LinkedHashMap<String,Object>(); b.put("operation",operation.name()); b.put("tenantId",42L); b.put("actorId",99L);
        b.put("personPublicId",UUID.randomUUID().toString());
        b.put("objectId",operation==SignatureAuthorityProtocol.Operation.CREATE || operation==SignatureAuthorityProtocol.Operation.CONTEXT
                ? source.get("requestId") : UUID.randomUUID().toString());
        b.put("objectVersion",operation.mutation()?5L:null); b.put("idempotencyKey",operation.mutation()?"original-key":null);
        b.put("bodySha256",json.digest(body)); b.put("contextKey","context"); b.put("contextScopeKey","opaque"); b.put("resourceSetKey","RS_APPROVALS");
        b.put("decisionRevision","psr-"+"a".repeat(64)); b.put("registrySha256","b".repeat(64)); b.put("rolloutState","110"); b.put("accessMode","NORMAL");
        b.put("authorityValidUntil",NOW.plusSeconds(30).toString()); b.put("nonce",UUID.randomUUID().toString());
        b.put("source",source); b.put("commandBody",body); b.put("sourceSha256",json.digest(source)); b.put("stepUpToken",operation==SignatureAuthorityProtocol.Operation.SIGN?"pending-real-token":null);
        return b;
    }
    JsonNode node(Object value) { return json.parse(json.bytes(value)); }
    record Exchange(byte[] bytes,String token) { }
    Exchange exchange(Map<String,Object> b) { return exchange(b,SignatureAuthorityProtocol.OWNER,SignatureAuthorityProtocol.TRANSPORT,NOW.plusSeconds(30)); }
    Exchange exchange(Map<String,Object> b,String ownerPurpose,String transportPurpose,Instant expires) {
        String ownerId=UUID.randomUUID().toString();
        String proof=sign(owner,base("dwp-approval-signature-owner","dwp-auth-signature-source",ownerId,expires)
                .claim("purpose",ownerPurpose).claim("bindings",b).claim("bindingsSha256",json.digest(b)).build());
        byte[] bytes=json.bytes(Map.of("sourceProof",proof,"bindings",b));
        String wire=sign(transport,base("dwp-approval-signature-transport","dwp-auth-signature-authority",UUID.randomUUID().toString(),expires)
                .claim("purpose",transportPurpose).claim("method","POST").claim("path",SignatureAuthorityProtocol.PATH)
                .claim("bodySha256",SignatureAuthorityJson.sha(bytes)).claim("bindingsSha256",json.digest(b)).claim("sourceProofJti",ownerId).build());
        return new Exchange(bytes,wire);
    }
    static JWTClaimsSet.Builder base(String issuer,String audience,String id,Instant expiry) {
        return new JWTClaimsSet.Builder().issuer(issuer).claim("aud",audience).subject("dwp-approval-server").jwtID(id)
                .issueTime(Date.from(NOW)).notBeforeTime(Date.from(NOW)).expirationTime(Date.from(expiry));
    }
    static String sign(RSAKey key,JWTClaimsSet c) {
        try { var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),c);
            jwt.sign(new RSASSASigner(key)); return jwt.serialize(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
