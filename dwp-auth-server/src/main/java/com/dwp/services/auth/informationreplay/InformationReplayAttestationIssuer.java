package com.dwp.services.auth.informationreplay;

import static com.dwp.services.auth.informationreplay.InformationReplayJson.*;
import static com.dwp.services.auth.informationreplay.InformationReplayProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class InformationReplayAttestationIssuer {
    private final InformationReplayKeys keys;
    private final InformationReplayJson json;
    public InformationReplayAttestationIssuer(InformationReplayKeys keys, InformationReplayJson json) { this.keys = keys; this.json = json; }
    public void requireReady() { keys.signer(); }
    public String issue(InformationReplayProofVerifier.Verified proof, InformationReplayAuthorityPort.Current current, Instant deadline) {
        var result = current.result(); validateResult(proof, result);
        long issued = Instant.now().getEpochSecond(), expires = deadline.getEpochSecond();
        if (expires <= issued || expires - issued > 30 || deadline.isAfter(proof.expiresAt()) || deadline.isAfter(current.expiresAt())) throw denied();
        var claims = new TreeMap<String, Object>();
        claims.putAll(Map.of("iss", OWNER_AUDIENCE, "aud", OWNER_ISSUER, "sub", Long.toString(proof.caller().actorId()),
                "iat", issued, "nbf", issued, "exp", expires, "jti", UUID.randomUUID().toString(), "purpose", ATTESTATION_PURPOSE));
        claims.put("operation", OPERATION); claims.put("sourceProofJti", proof.sourceJti()); claims.put("transportProofJti", proof.transportJti());
        claims.put("bodySha256", proof.bodySha256()); claims.put("bindingsSha256", proof.bindingsSha256());
        String vector = json.digest(current.vector());
        claims.put("authority", Map.of("ownerAuthRevision", current.ownerAuthRevision(), "ownerPolicyRevision", current.ownerPolicyRevision(),
                "sourceRevision", "air-" + vector, "sourceVectorSha256", vector, "evaluatedAt", issued, "expiresAt", expires));
        claims.put("result", result); exact(json.tree(claims), ATTESTATION_CLAIMS);
        try {
            var signer = keys.signer();
            var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(signer.getKeyID()).build(),
                    new Payload(json.canonical(json.tree(claims))));
            token.sign(new RSASSASigner(signer)); String compact = token.serialize();
            if (compact.length() > ATTESTATION_LIMIT) throw denied(); return compact;
        } catch (com.dwp.core.exception.BaseException error) { throw error; }
        catch (Exception error) { throw unavailable(); }
    }
    static void validateResult(InformationReplayProofVerifier.Verified proof, JsonNode result) {
        exact(result, RESULT_FIELDS); var caller = proof.caller(); var admission = caller.bindings().get("admission");
        for (String field : Set.of("receiptSha256", "commandSha256", "admissionSha256")) if (!hash(result, field).equals(hash(admission, field))) throw denied();
        var role = result.get("role"); exact(role, Set.of("roleCode", "roleId", "roleVersion"));
        if (!caller.roleCode().equals(text(role, "roleCode", 50))) throw denied();
        long roleId = number(role, "roleId", 1, SAFE_INTEGER); number(role, "roleVersion", 0, SAFE_INTEGER);
        subject(result.get("originalActor"), caller.tenantId(), caller.originalActorId(), caller.originalActorPersonPublicId(), roleId, false);
        subject(result.get("principal"), caller.tenantId(), caller.principalId(), caller.principalPersonPublicId(), roleId, true);
    }
    private static void subject(JsonNode subject, long tenant, long user, UUID person, long role, boolean requireRole) {
        exact(subject, Set.of("tenantId", "userId", "personPublicId", "identityPlane", "status", "roleIds", "canApprove"));
        if (number(subject, "tenantId", 1, SAFE_INTEGER) != tenant || number(subject, "userId", 1, SAFE_INTEGER) != user
                || !person.equals(uuid(subject, "personPublicId")) || !"TENANT".equals(text(subject, "identityPlane", 10))
                || !"ACTIVE".equals(text(subject, "status", 10)) || !subject.get("canApprove").isBoolean() || !subject.get("canApprove").booleanValue()) throw denied();
        var roles = subject.get("roleIds");
        if (!roles.isArray() || roles.size() > 1 || requireRole && roles.size() != 1
                || roles.size() == 1 && (!roles.get(0).isIntegralNumber() || !roles.get(0).canConvertToLong() || roles.get(0).longValue() != role)) throw denied();
    }
}
