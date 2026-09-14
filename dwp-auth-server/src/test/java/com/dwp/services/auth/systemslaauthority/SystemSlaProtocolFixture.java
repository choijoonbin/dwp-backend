package com.dwp.services.auth.systemslaauthority;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SystemSlaProtocolFixture {
    static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");
    final Instant now;
    final Clock clock;
    SystemSlaProtocolFixture() { this(NOW); }
    SystemSlaProtocolFixture(Instant now) { this.now = now; this.clock = Clock.fixed(now, ZoneOffset.UTC); }
    final SystemSlaJson json = new SystemSlaJson(new com.fasterxml.jackson.databind.ObjectMapper());
    final RSAKey owner = key("system_sla_owner"), transport = key("system_sla_transport"), attestation = key("system_sla_attestation");
    static RSAKey key(String id) { try { return new RSAKeyGenerator(2048).keyID(id).generate(); } catch (Exception error) { throw new AssertionError(error); } }
    static String uuid(int id) { return String.format("00000000-0000-0000-0000-%012d", id); }
    static String jwks(RSAKey key) { return new com.nimbusds.jose.jwk.JWKSet(key.toPublicJWK()).toString(); }
    SystemSlaKeys keys() { return new SystemSlaKeys(json, jwks(owner), jwks(transport), attestation.toJSONString(), jwks(attestation), List.of()); }
    SystemSlaProofVerifier verifier() { return new SystemSlaProofVerifier(json, keys(), clock); }
    JsonNode binding() {
        var definition = Map.of("schemaContract", "DWP_APPROVAL_WORKFLOW_QUORUM_V2", "schemaVersion", 2, "slaMinutes", 60,
                "stages", List.of(Map.of("key", "REVIEW", "name", "Review", "candidateRole", "APPROVAL_OPERATOR", "quorum", Map.of("mode", "ANY"), "slaMinutes", 60, "predecessors", List.of())));
        var refs = new java.util.ArrayList<Object>(); var reviews = new java.util.ArrayList<Object>();
        var policyKeys = List.of("BLOCK_SELF_APPROVAL", "REQUIRE_REJECT_REASON", "SLA_ESCALATION");
        for (int index = 0; index < 3; index++) {
            Map<String, Object> rule = index == 0 ? Map.of("enabled", true) : index == 1 ? Map.of("minimumLength", 4) : Map.of("warningPercent", 80, "breachPercent", 100);
            refs.add(Map.of("policyId", uuid(20 + index), "key", policyKeys.get(index), "rowVersion", 1, "enforcement", "BLOCK", "rule", rule));
            reviews.add(Map.of("policyVersionId", uuid(30 + index), "policyId", uuid(20 + index), "versionNumber", 2,
                    "makerId", 90, "publisherId", 91, "submittedAt", now.minusSeconds(100).toString(), "publishedAt", now.minusSeconds(50).toString(),
                    "ruleSha256", json.digest(rule), "provenance", "REVIEWED_PUBLISH"));
        }
        var source = Map.of("request", Map.of("requestId", uuid(1), "requestVersion", 3, "requesterUserId", 99,
                        "requesterPersonPublicId", uuid(99), "dataClassification", "INTERNAL", "resourceSetKey", "RS_APPROVALS"),
                "workflow", Map.of("workflowVersionId", uuid(2), "workflowVersion", 1, "definitionSha256", json.digest(definition), "definition", definition),
                "form", Map.of("formVersionId", uuid(3), "schemaSha256", "a".repeat(64)), "payload", Map.of("revision", 1, "sha256", "b".repeat(64)),
                "policy", Map.of("version", 2, "sha256", json.digest(Map.of("references", refs)), "references", refs, "reviewedHistory", reviews),
                "stage", Map.of("stepId", uuid(4), "generation", 1, "version", 0, "stageKey", "REVIEW", "candidateRole", "APPROVAL_OPERATOR", "frozenPoolSha256", "c".repeat(64)),
                "timer", Map.of("timerId", uuid(5), "version", 1, "kind", "WARNING", "dueAt", now.minusSeconds(10).toString(), "leaseEpoch", 1,
                        "leaseOwner", "worker_1", "leaseUntil", now.plusSeconds(60).toString(), "policyVersion", 2), "event", com.fasterxml.jackson.databind.node.NullNode.instance);
        var audience = List.of(Map.of("userId", 100, "personPublicId", uuid(100), "taskId", uuid(101), "taskVersion", 0));
        return json.tree(Map.of("tenantId", 42, "operation", "PRODUCE", "source", source, "audience", audience,
                "sourceDigest", json.digest(Map.of("tenantId", 42, "operation", "PRODUCE", "source", source, "audience", audience)), "authorityValidUntil", now.plusSeconds(30).toString()));
    }
    JsonNode redigest(JsonNode binding) {
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) binding.deepCopy();
        value.put("sourceDigest", json.digest(Map.of("tenantId", value.get("tenantId"), "operation", value.get("operation"), "source", value.get("source"), "audience", value.get("audience")))); return value;
    }
    Exchange exchange(JsonNode binding) {
        String ownerJti = UUID.randomUUID().toString(), transportJti = UUID.randomUUID().toString();
        var ownerClaims = claims(SystemSlaProtocol.OWNER_ISSUER, SystemSlaProtocol.OWNER_AUDIENCE, SystemSlaProtocol.OWNER_PURPOSE, ownerJti);
        ownerClaims.put("bindingsSha256", json.digest(binding)); ownerClaims.put("sourceDigest", binding.get("sourceDigest").textValue());
        byte[] body = json.bytes(Map.of("sourceProof", token(owner, ownerClaims), "bindings", binding));
        var transportClaims = claims(SystemSlaProtocol.TRANSPORT_ISSUER, SystemSlaProtocol.TRANSPORT_AUDIENCE, SystemSlaProtocol.TRANSPORT_PURPOSE, transportJti);
        transportClaims.put("method", "POST"); transportClaims.put("path", SystemSlaProtocol.PATH); transportClaims.put("sourceProofJti", ownerJti);
        transportClaims.put("bodySha256", SystemSlaJson.sha(body)); transportClaims.put("bindingsSha256", json.digest(binding));
        return new Exchange(body, token(transport, transportClaims), ownerClaims, transportClaims);
    }
    Map<String, Object> claims(String issuer, String audience, String purpose, String jti) {
        var result = new LinkedHashMap<String, Object>(); result.put("iss", issuer); result.put("aud", audience); result.put("sub", "dwp-approval-server");
        result.put("iat", now.getEpochSecond()); result.put("nbf", now.getEpochSecond()); result.put("exp", now.plusSeconds(30).getEpochSecond());
        result.put("jti", jti); result.put("purpose", purpose); return result;
    }
    String token(RSAKey key, Object claims) {
        try { var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(com.nimbusds.jose.JOSEObjectType.JWT)
                    .keyID(key.getKeyID()).build(), new Payload(json.bytes(claims))); token.sign(new RSASSASigner(key)); return token.serialize(); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    record Exchange(byte[] body, String token, Map<String, Object> ownerClaims, Map<String, Object> transportClaims) { }
}
