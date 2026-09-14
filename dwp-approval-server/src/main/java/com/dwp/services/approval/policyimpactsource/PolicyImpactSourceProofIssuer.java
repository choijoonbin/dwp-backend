package com.dwp.services.approval.policyimpactsource;

import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceProtocol.*;
import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class PolicyImpactSourceProofIssuer {
    private final PolicyImpactSourceKeys keys;
    private final PolicyImpactSourceJson json;
    private final Clock clock;
    public PolicyImpactSourceProofIssuer(PolicyImpactSourceKeys keys, PolicyImpactSourceJson json, Clock clock) {
        this.keys = keys; this.json = json; this.clock = clock;
    }
    public static final class Exchange {
        private final byte[] body;
        private final String transport, sourceJti, transportJti, bodyHash, bindingsHash;
        private final JsonNode bindings;
        private final Instant expiry;
        private Exchange(byte[] body, String transport, String sourceJti, String transportJti, String bindingsHash, JsonNode bindings, Instant expiry) {
            this.body = body.clone(); this.transport = transport; this.sourceJti = sourceJti; this.transportJti = transportJti;
            this.bodyHash = sha(body); this.bindingsHash = bindingsHash; this.bindings = bindings.deepCopy(); this.expiry = expiry;
        }
        public byte[] body() { return body.clone(); }
        public String transport() { return transport; }
        public String sourceJti() { return sourceJti; }
        public String transportJti() { return transportJti; }
        public String bodyHash() { return bodyHash; }
        public String bindingsHash() { return bindingsHash; }
        public JsonNode bindings() { return bindings.deepCopy(); }
        public Instant expiresAt() { return expiry; }
    }
    public Exchange issue(PolicyImpactSourceHeadReader.Seal head, Instant deadline) {
        if (head == null) throw unavailable();
        var installed = head.installed(); Instant now = Instant.ofEpochSecond(clock.instant().getEpochSecond());
        if (deadline == null || deadline.isAfter(installed.validUntil()) || !deadline.isAfter(now) || deadline.isAfter(now.plusSeconds(30))) throw denied();
        deadline = Instant.ofEpochSecond(deadline.getEpochSecond());
        var bindings = new java.util.LinkedHashMap<String, Object>();
        bindings.put("tenantId", installed.tenantId()); bindings.put("actorId", installed.actorId()); bindings.put("personPublicId", installed.personPublicId());
        bindings.put("policyId", installed.policyId()); bindings.put("expectedVersion", installed.expectedVersion()); bindings.put("sourceDigest", head.digest());
        bindings.put("method", "GET"); bindings.put("path", "/v1/admin/policies/" + installed.policyId() + "/impact");
        bindings.put("rawQuerySha256", sha("expectedVersion=" + installed.expectedVersion())); bindings.put("routeContractKey", ROUTE);
        bindings.put("contextKey", installed.contextKey()); bindings.put("contextScopeKey", installed.contextScopeKey()); bindings.put("resourceSetKey", installed.resourceSetKey());
        bindings.put("decisionRevision", installed.decisionRevision()); bindings.put("rolloutState", installed.rolloutState()); bindings.put("accessMode", installed.accessMode());
        bindings.put("authorityValidUntil", installed.validUntil().toString());
        JsonNode bindingJson = json.tree(bindings); keys(bindingJson, BINDINGS); String bindingHash = json.digest(bindingJson);
        String sourceJti = UUID.randomUUID().toString(), transportJti = UUID.randomUUID().toString();
        var source = claims(OWNER_ISSUER, OWNER_AUDIENCE, OWNER_PURPOSE, sourceJti, now, deadline);
        source.put("bindings", bindingJson); source.put("bindingsSha256", bindingHash); String ownerToken = sign(source, keys.owner());
        if (ownerToken.length() > OWNER_LIMIT) throw denied();
        byte[] body = json.bytes(Map.of("sourceProof", ownerToken, "bindings", bindingJson));
        var transport = claims(TRANSPORT_ISSUER, TRANSPORT_AUDIENCE, TRANSPORT_PURPOSE, transportJti, now, deadline);
        transport.put("method", "POST"); transport.put("path", PATH); transport.put("sourceProofJti", sourceJti);
        transport.put("bodySha256", sha(body)); transport.put("bindingsSha256", bindingHash);
        String token = sign(transport, keys.transport()); if (token.length() > TRANSPORT_LIMIT || body.length > BODY_LIMIT) throw denied();
        return new Exchange(body, token, sourceJti, transportJti, bindingHash, bindingJson, deadline);
    }
    private Map<String, Object> claims(String issuer, String audience, String purpose, String jti, Instant at, Instant expiry) {
        var claims = new java.util.LinkedHashMap<String, Object>();
        claims.put("iss", issuer); claims.put("aud", audience); claims.put("sub", "dwp-approval-server"); claims.put("jti", jti);
        claims.put("iat", at.getEpochSecond()); claims.put("nbf", at.getEpochSecond()); claims.put("exp", expiry.getEpochSecond()); claims.put("purpose", purpose); return claims;
    }
    private String sign(Map<String, Object> claims, RSAKey key) {
        try {
            var signed = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), new Payload(json.bytes(claims)));
            signed.sign(new RSASSASigner(key)); return signed.serialize();
        } catch (Exception error) { throw unavailable(); }
    }
}
