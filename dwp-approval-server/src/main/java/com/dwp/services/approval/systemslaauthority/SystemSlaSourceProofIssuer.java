package com.dwp.services.approval.systemslaauthority;

import static com.dwp.services.approval.systemslaauthority.SystemSlaJson.*;
import static com.dwp.services.approval.systemslaauthority.SystemSlaSourceProtocol.*;
import com.dwp.services.approval.domain.ApprovalSystemSlaNativeSource;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.*;
import java.util.*;

public final class SystemSlaSourceProofIssuer {
    private final SystemSlaJson json;
    private final SystemSlaSourceKeys keys;
    private final Clock clock;
    public SystemSlaSourceProofIssuer(SystemSlaJson json, SystemSlaSourceKeys keys, Clock clock) { this.json = json; this.keys = keys; this.clock = clock; }
    public Exchange issue(ApprovalSystemSlaNativeSource.Seal seal) {
        if (seal == null) throw denied(); var bindings = seal.bindings(); long now = clock.instant().getEpochSecond(), exp = seal.validUntil().getEpochSecond();
        if (seal.delivery()) new SystemSlaSourceWitnessVerifier(json,keys).verify(seal.originalWitness(),bindings);
        if (exp <= now || exp > now + 30 || !seal.validUntil().toString().equals(text(bindings, "authorityValidUntil", 40))) throw denied();
        String ownerId = UUID.randomUUID().toString(), transportId = UUID.randomUUID().toString(), bindingHash = json.digest(bindings);
        var owner = standard(OWNER_ISSUER, OWNER_AUDIENCE, OWNER_PURPOSE, now, exp, ownerId);
        owner.put("bindingsSha256", bindingHash); owner.put("sourceDigest", hash(bindings, "sourceDigest"));
        String proof = sign(owner, keys.owner(), json); if (proof.length() > 16384) throw denied();
        byte[] body = json.bytes(Map.of("sourceProof", proof, "bindings", bindings)); if (body.length > BODY_LIMIT) throw denied();
        var transport = standard(TRANSPORT_ISSUER, TRANSPORT_AUDIENCE, TRANSPORT_PURPOSE, now, exp, transportId);
        transport.put("method", "POST"); transport.put("path", PATH); transport.put("sourceProofJti", ownerId);
        transport.put("bodySha256", sha(body)); transport.put("bindingsSha256", bindingHash);
        String token = sign(transport, keys.transport(), json); if (token.length() > 2048) throw denied();
        return new Exchange(seal, body, token, ownerId, transportId, bindingHash, Instant.ofEpochSecond(now), Instant.ofEpochSecond(exp));
    }
    private static Map<String, Object> standard(String issuer, String audience, String purpose, long now, long exp, String jti) {
        var claims = new LinkedHashMap<String, Object>(); claims.put("iss", issuer); claims.put("aud", audience); claims.put("sub", "dwp-approval-server");
        claims.put("iat", now); claims.put("nbf", now); claims.put("exp", exp); claims.put("jti", jti); claims.put("purpose", purpose); return claims;
    }
    static String sign(Object claims, RSAKey key, SystemSlaJson json) {
        try {
            var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), new Payload(json.bytes(claims)));
            token.sign(new RSASSASigner(key.toRSAPrivateKey())); return token.serialize();
        } catch (Exception invalid) { throw unavailable(); }
    }
    public static final class Exchange {
        private final ApprovalSystemSlaNativeSource.Seal seal;
        private final byte[] body;
        private final String token, ownerId, transportId, bindingHash;
        private final Instant ownerIssuedAt, expiresAt;
        private Exchange(ApprovalSystemSlaNativeSource.Seal seal, byte[] body, String token, String owner, String transport, String hash, Instant issuedAt, Instant expiry) {
            this.seal = seal; this.body = body.clone(); this.token = token; ownerId = owner; transportId = transport; bindingHash = hash; ownerIssuedAt = Objects.requireNonNull(issuedAt); expiresAt = expiry;
        }
        public byte[] body() { return body.clone(); }
        public String transport() { return token; }
        public ApprovalSystemSlaNativeSource.Seal seal() { return seal; }
        String ownerId() { return ownerId; }
        String transportId() { return transportId; }
        String bindingHash() { return bindingHash; }
        Instant ownerIssuedAt() { return ownerIssuedAt; }
        Instant expiresAt() { return expiresAt; }
    }
}
