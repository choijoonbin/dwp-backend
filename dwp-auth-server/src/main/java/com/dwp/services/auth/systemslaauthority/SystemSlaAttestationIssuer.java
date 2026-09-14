package com.dwp.services.auth.systemslaauthority;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class SystemSlaAttestationIssuer {
    private final SystemSlaJson json;
    private final Supplier<SystemSlaKeys> keys;
    private final Clock clock;
    public SystemSlaAttestationIssuer(SystemSlaJson json, Supplier<SystemSlaKeys> keys, Clock clock) { this.json = json; this.keys = keys; this.clock = clock; }
    public String issue(SystemSlaProofVerifier.Verified proof, SystemSlaAuthorityPort.Current current) {
        Instant now = clock.instant(); long exp = current.expiresAt().getEpochSecond();
        if (exp <= now.getEpochSecond() || current.expiresAt().isAfter(proof.expiresAt()) || exp > now.getEpochSecond() + 30
                || current.recipients().size() != proof.bindings().audience().size()
                || !current.authorityRevision().matches("asla-[a-f0-9]{64}") || !current.sourceVectorSha256().matches("[a-f0-9]{64}")) throw SystemSlaJson.denied();
        for (int index = 0; index < current.recipients().size(); index++) {
            var seat = current.recipients().get(index); var expected = proof.bindings().audience().get(index);
            if (seat.userId() != SystemSlaJson.integer(expected, "userId", true) || !seat.personPublicId().equals(SystemSlaJson.uuid(expected, "personPublicId"))
                    || !seat.taskId().equals(SystemSlaJson.uuid(expected, "taskId")) || seat.taskVersion() != SystemSlaJson.integer(expected, "taskVersion", false)
                    || seat.expiresAt().isBefore(current.expiresAt()) || seat.eligible() != "ELIGIBLE".equals(seat.reason())) throw SystemSlaJson.denied();
        }
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", SystemSlaProtocol.ATTESTATION_ISSUER); claims.put("aud", SystemSlaProtocol.ATTESTATION_AUDIENCE);
        claims.put("sub", "dwp-approval-server"); claims.put("iat", now.getEpochSecond()); claims.put("nbf", now.getEpochSecond()); claims.put("exp", exp);
        claims.put("jti", UUID.randomUUID().toString()); claims.put("purpose", SystemSlaProtocol.ATTESTATION_PURPOSE);
        claims.put("sourceProofJti", proof.ownerJti()); claims.put("transportProofJti", proof.transportJti());
        claims.put("bodySha256", proof.bodyHash()); claims.put("bindingsSha256", proof.bindingsHash()); claims.put("sourceDigest", proof.bindings().sourceDigest());
        claims.put("authority", Map.of("authorityRevision", current.authorityRevision(), "sourceVectorSha256", current.sourceVectorSha256(),
                "evaluatedAt", current.evaluatedAt().toString(), "expiresAt", current.expiresAt().toString()));
        claims.put("recipients", current.recipients());
        try {
            var key = keys.get().signer(); var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(com.nimbusds.jose.JOSEObjectType.JWT).keyID(key.getKeyID()).build(), new Payload(json.bytes(claims)));
            token.sign(new RSASSASigner(key.toRSAPrivateKey())); String result = token.serialize();
            if (result.length() > SystemSlaProtocol.BODY_LIMIT) throw SystemSlaJson.unavailable(); return result;
        } catch (Exception error) { throw SystemSlaJson.unavailable(); }
    }
}
