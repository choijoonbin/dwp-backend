package com.dwp.services.auth.informationreplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Ephemeral cryptographic fixtures, not installed Approval DB, Auth profile, or activation evidence. */
final class InformationReplayProofFixture {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final InformationReplayJson json = new InformationReplayJson(mapper);
    final RSAKey owner, transport, signer;
    final InformationReplayKeys keys;
    InformationReplayProofFixture() throws Exception {
        owner = key("replay-owner"); transport = key("replay-transport"); signer = key("replay-attestation");
        keys = new InformationReplayKeys(json, publicKeys(owner), publicKeys(transport), signer.toJSONString(), publicKeys(signer));
    }
    static RSAKey key(String kid) throws Exception {
        return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
    }
    static String publicKeys(RSAKey key) { return new JWKSet(key.toPublicJWK()).toString(); }
    static String id(long number) { return new UUID(0, number).toString(); }
    ObjectNode bindings(String operation) {
        long now = Instant.now().getEpochSecond(); boolean reply = operation.equals("REPLY");
        var definition = mapper.createObjectNode(); definition.put("schemaContract", "DWP_APPROVAL_WORKFLOW_QUORUM_V2");
        definition.put("schemaVersion", 2); definition.put("slaMinutes", 60);
        var stage = definition.putArray("stages").addObject(); stage.put("key", "REVIEW"); stage.put("name", "Review");
        stage.put("candidateRole", "REVIEWER"); stage.putObject("quorum").put("mode", "ALL"); stage.put("slaMinutes", 15); stage.putArray("predecessors");
        var owner = mapper.createObjectNode(); owner.put("tenantId", 1); owner.put("actorId", reply ? 99 : 200); owner.put("personPublicId", id(reply ? 99 : 200));
        owner.put("requestId", id(300)); owner.put("requestVersion", 20); owner.put("workflowVersionId", id(301)); owner.put("workflowVersion", 1);
        owner.put("workflowDefinitionSha256", json.digest(definition)); owner.put("formVersionId", id(302)); owner.put("formSchemaSha256", "a".repeat(64));
        owner.put("payloadRevision", 10); owner.put("payloadSha256", "b".repeat(64)); owner.put("policyVersion", 1); owner.put("policySha256", "c".repeat(64));
        owner.put("contextKey", "psc-current"); owner.put("contextScopeKey", "scope-current"); owner.put("decisionRevision", "psr-" + "d".repeat(64));
        owner.put("accessMode", "NORMAL"); owner.put("routeContractKey", InformationReplayProtocol.ROUTE); owner.put("managementResourceSetKey", "RS_APPROVALS");
        owner.putArray("roleCodes").add("REVIEWER"); owner.put("publishedDefinition", json.canonical(definition)); owner.put("method", "POST");
        owner.put("path", "/v1/requests/" + id(300) + "/information-commands/receipt-key/receipt"); owner.put("idempotencyKey", "receipt-key");
        var source = mapper.createObjectNode(); source.put("stepKey", "REVIEW"); source.put("generation", 1); source.put("sourceStageRevision", 2);
        source.put("snapshotSha256", "e".repeat(64)); source.put("candidateSetSha256", "f".repeat(64)); source.put("candidateCount", 2); source.put("requiredVotes", 2);
        source.put("candidateRole", "REVIEWER"); source.put("requesterUserId", 99); source.put("requesterPersonPublicId", id(99)); source.put("rejectCommentMinLength", 4);
        source.put("originalPayloadRevision", 1); source.put("originalPayloadSha256", "0".repeat(64));
        var target = mapper.createObjectNode(); target.put("use", "RECEIPT_ORIGINAL_INFORMATION"); target.put("stepKey", "REVIEW"); target.put("taskId", id(400));
        target.put("evidenceId", id(401)); target.put("evidenceSha256", "1".repeat(64)); target.put("sourceGeneration", 1);
        target.put("actorId", 200); target.put("actorPersonPublicId", id(200)); target.put("principalId", 100); target.put("principalPersonPublicId", id(100));
        var delegation = target.putObject("delegation"); delegation.put("id", id(402)); delegation.put("delegatorUserId", 100); delegation.put("delegateUserId", 200);
        delegation.put("delegatePersonPublicId", id(200)); delegation.put("workflowVersionId", id(301)); delegation.put("roleCode", "REVIEWER");
        delegation.put("startsAt", now - 60); delegation.put("endsAt", now + 60);
        var admission = mapper.createObjectNode(); admission.put("operation", operation); admission.put("commandSha256", "2".repeat(64));
        admission.put("rawBodySha256", "3".repeat(64)); admission.put("receiptSha256", "4".repeat(64)); admission.put("roundId", id(401)); admission.put("taskId", id(400));
        admission.put("stepKey", "REVIEW"); admission.put("sourceGeneration", 1); admission.put("receiptGeneration", reply ? 2 : 1); admission.put("receiptRequestVersion", 10);
        admission.put("receiptPayloadRevision", 2); admission.put("receiptPayloadSha256", "5".repeat(64)); admission.put("materialChange", reply);
        admission.put("commandActorId", reply ? 99 : 200); admission.put("commandActorPersonPublicId", id(reply ? 99 : 200));
        admission.put("admissionSha256", "6".repeat(64)); admission.put("admissionJti", id(500)); admission.put("admissionIssuer", "dwp-auth-server:workflow-runtime:information-admission:v1");
        admission.put("admissionKeyId", "historical-key"); admission.put("admissionSourceRevision", "awr-" + "7".repeat(64));
        admission.put("admissionSourceVectorSha256", "8".repeat(64)); admission.put("admissionOwnerAuthRevision", "auth-historical"); admission.put("admissionOwnerPolicyRevision", "policy-historical");
        admission.put("admissionEvaluatedAt", now - 600); admission.put("admissionExpiresAt", now - 575); admission.put("acceptedAt", now - 580);
        admission.put("ownerProofJti", id(501)); admission.put("transportProofJti", id(502)); admission.put("originalExpectedVersion", reply ? 9 : 3); admission.put("completedAt", now - 581);
        var bindings = mapper.createObjectNode(); bindings.set("owner", owner); bindings.set("source", source); bindings.set("target", target); bindings.set("admission", admission);
        return bindings;
    }
    record Exchange(byte[] body, String token) { }
    Exchange exchange(ObjectNode bindings) throws Exception {
        long now = Instant.now().getEpochSecond(); String actor = bindings.get("owner").get("actorId").asText();
        var ownerClaims = claims(InformationReplayProtocol.OWNER_ISSUER, InformationReplayProtocol.OWNER_AUDIENCE,
                InformationReplayProtocol.OWNER_PURPOSE, actor, now);
        ownerClaims.put("version", 1); ownerClaims.set("sealed", bindings); String source = sign(owner, ownerClaims);
        var body = mapper.createObjectNode(); body.put("operation", InformationReplayProtocol.OPERATION); body.put("sourceProof", source); body.set("bindings", bindings);
        var transportClaims = claims(InformationReplayProtocol.TRANSPORT_ISSUER, InformationReplayProtocol.TRANSPORT_AUDIENCE,
                InformationReplayProtocol.TRANSPORT_PURPOSE, actor, now);
        transportClaims.put("sourceProofJti", ownerClaims.get("jti").textValue()); transportClaims.put("sourceProofSha256", InformationReplayJson.sha(source));
        transportClaims.put("bodySha256", json.digest(body)); transportClaims.put("contextKey", bindings.get("owner").get("contextKey").textValue());
        transportClaims.put("routeContractKey", InformationReplayProtocol.ROUTE);
        return new Exchange(json.canonical(body).getBytes(java.nio.charset.StandardCharsets.UTF_8), sign(transport, transportClaims));
    }
    ObjectNode claims(String iss, String aud, String purpose, String subject, long now) {
        return json.tree(Map.of("iss", iss, "aud", aud, "purpose", purpose, "sub", subject, "iat", now, "nbf", now, "exp", now + 25, "jti", UUID.randomUUID().toString())).deepCopy();
    }
    String sign(RSAKey key, ObjectNode claims) throws Exception {
        var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), new Payload(json.canonical(claims)));
        token.sign(new RSASSASigner(key)); return token.serialize();
    }
}
