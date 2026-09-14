package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Crypto fixture only: production authority positives use actual Auth and PostgreSQL separately. */
public final class WorkflowRuntimeProofFixture {
    public static final WorkflowRuntimeJson JSON = new WorkflowRuntimeJson(new ObjectMapper().findAndRegisterModules());
    public static final RSAKey OWNER = key("runtime-owner"), TRANSPORT = key("runtime-transport"), ATTESTATION = key("runtime-attestation");
    public static final UUID REQUEST = new UUID(0, 200), WORKFLOW = new UUID(0, 201), FORM = new UUID(0, 202), TASK = new UUID(0, 203);
    private WorkflowRuntimeProofFixture() { }
    private static RSAKey key(String kid) {
        try { return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    public static String publicKeys(RSAKey key) { return new JWKSet(key.toPublicJWK()).toString(); }
    public static WorkflowRuntimeKeys keys() {
        return new WorkflowRuntimeKeys(JSON, publicKeys(OWNER), publicKeys(TRANSPORT), ATTESTATION.toJSONString(), publicKeys(ATTESTATION));
    }
    public static String definition(int stages, String role) {
        return JSON.canonical(JSON.tree(Map.of("schemaContract", "DWP_APPROVAL_WORKFLOW_QUORUM_V2", "schemaVersion", 2,
                "slaMinutes", 30, "stages", java.util.stream.IntStream.range(0, stages).mapToObj(index -> Map.of(
                    "key", "REVIEW_" + index, "name", "Review " + index, "candidateRole", role, "quorum", Map.of("mode", "ALL"),
                    "slaMinutes", 30, "predecessors", List.of())).toList())));
    }
    public static ObjectNode owner(long tenant, long actor, UUID person, String role, int stages) {
        String definition = definition(stages, role);
        var value = new TreeMap<String, Object>();
        value.put("tenantId", tenant); value.put("actorId", actor); value.put("personPublicId", person.toString());
        value.put("requestId", REQUEST.toString()); value.put("requestVersion", 4); value.put("workflowVersionId", WORKFLOW.toString());
        value.put("workflowVersion", 1); value.put("workflowDefinitionSha256", sha(definition)); value.put("publishedDefinition", definition);
        value.put("formVersionId", FORM.toString()); value.put("formSchemaSha256", "a".repeat(64)); value.put("payloadRevision", 1);
        value.put("payloadSha256", "b".repeat(64)); value.put("policyVersion", 1); value.put("policySha256", "c".repeat(64));
        value.put("contextKey", "ctx-runtime"); value.put("contextScopeKey", "scope-runtime"); value.put("decisionRevision", "psr-" + "d".repeat(64));
        value.put("accessMode", "NORMAL"); value.put("routeContractKey", "route.approvals.work.task-decision.action");
        value.put("managementResourceSetKey", "RS_APPROVALS"); value.put("roleCodes", List.of(role));
        value.put("method", "POST"); value.put("path", "/v1/tasks/" + TASK + "/decisions"); value.put("idempotencyKey", "runtime-original-key");
        return (ObjectNode) JSON.tree(value);
    }
    public static ObjectNode stage(PoolMode mode, long requester, UUID person, String role) {
        var value = new TreeMap<String, Object>(); value.put("poolMode", mode.name()); value.put("stepKey", "REVIEW_0");
        value.put("generation", mode == PoolMode.INITIAL || mode == PoolMode.SEALED ? 1 : 2);
        value.put("requesterUserId", requester); value.put("requesterPersonPublicId", person.toString()); value.put("candidateRole", role);
        value.put("sourceStageRevision", 4); value.put("rejectCommentMinLength", 4);
        boolean frozen = mode == PoolMode.RETAINED || mode == PoolMode.SEALED;
        value.put("snapshotSha256", frozen ? "e".repeat(64) : null); value.put("candidateSetSha256", frozen ? "f".repeat(64) : null);
        value.put("candidateCount", frozen ? 2 : null); value.put("requiredVotes", frozen ? 2 : null);
        value.put("admissionAttestationSha256", null); value.put("admissionAttestation", null); value.put("commandRawBodySha256", null);
        return (ObjectNode) JSON.tree(value);
    }
    public static ObjectNode candidate(long tenant, long actor, UUID person, long requester, UUID requesterPerson, String role, PoolMode mode, int stages) {
        return (ObjectNode) JSON.tree(Map.of("owner", owner(tenant, actor, person, role, stages), "stage", stage(mode, requester, requesterPerson, role)));
    }
    public static ObjectNode target(long actor, UUID person, long principal, UUID principalPerson, long roleId) {
        var value = new TreeMap<String, Object>(); value.put("use", "CAST"); value.put("stepKey", "REVIEW_0"); value.put("taskId", TASK.toString());
        value.put("evidenceId", null); value.put("evidenceSha256", null); value.put("sourceGeneration", 1);
        value.put("actorId", actor); value.put("actorPersonPublicId", person.toString()); value.put("principalId", principal);
        value.put("principalPersonPublicId", principalPerson.toString());
        value.put("delegation", actor == principal ? null : Map.of("id", new UUID(0, 204).toString(), "delegatorUserId", principal,
                "delegateUserId", actor, "delegatePersonPublicId", person.toString(), "workflowVersionId", WORKFLOW.toString(),
                "authorityRoleId", roleId, "startsAt", Instant.now().minusSeconds(60).getEpochSecond(), "endsAt", Instant.now().plusSeconds(60).getEpochSecond()));
        return (ObjectNode) JSON.tree(value);
    }
    public static ObjectNode command(long tenant, long actor, UUID person) {
        var owner = owner(tenant, actor, person, "REVIEWER", 1); var value = new TreeMap<String, Object>();
        for (String field : List.of("tenantId", "actorId", "personPublicId", "routeContractKey", "method", "path", "idempotencyKey", "contextKey", "contextScopeKey", "decisionRevision", "accessMode")) value.put(field, owner.get(field));
        value.put("commandPurpose", "TASK_INFORMATION"); value.put("targetId", TASK.toString()); value.put("rawBodySha256", "1".repeat(64));
        value.put("rolloutState", "110"); value.put("authorityValidUntil", Instant.now().plusSeconds(25).getEpochSecond());
        value.put("expectedVersion", 4); value.put("sourceGeneration", null);
        return (ObjectNode) JSON.tree(Map.of("command", value));
    }
    public static Envelope proof(Operation operation, ObjectNode bindings) {
        long now = Instant.now().getEpochSecond(); var owner = standard(operation.ownerIssuer(), operation.ownerAudience(), operation.ownerPurpose(), operation, bindings, now);
        owner.put("bindings", bindings); String sourceProof = sign(owner, OWNER);
        var body = (ObjectNode) JSON.tree(Map.of("operation", operation.name(), "bindings", bindings, "sourceProof", sourceProof));
        String bodyText = JSON.canonical(body);
        var transport = standard(TRANSPORT_ISSUER, TRANSPORT_AUDIENCE, TRANSPORT_PURPOSE, operation, bindings, now);
        transport.put("httpMethod", "POST"); transport.put("httpPath", PATH); transport.put("bodySha256", sha(bodyText)); transport.put("sourceProofSha256", sha(sourceProof));
        return new Envelope(sign(transport, TRANSPORT), bodyText.getBytes(StandardCharsets.UTF_8), sourceProof, body, owner, transport);
    }
    private static TreeMap<String, Object> standard(String issuer, String audience, String purpose, Operation op, ObjectNode bindings, long now) {
        var value = new TreeMap<String, Object>(); value.put("iss", issuer); value.put("aud", audience); value.put("purpose", purpose);
        value.put("operation", op.name()); value.put("sub", bindings.get(op == Operation.INFORMATION_ADMISSION ? "command" : "owner").get("actorId").asText());
        value.put("iat", now); value.put("nbf", now); value.put("exp", now + 25); value.put("jti", UUID.randomUUID().toString()); return value;
    }
    public static String sign(Map<String, Object> claims, RSAKey key) {
        try {
            var jwt = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), new Payload(JSON.canonical(JSON.tree(claims))));
            jwt.sign(new RSASSASigner(key)); return jwt.serialize();
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
    public record Envelope(String transport, byte[] body, String sourceProof, ObjectNode json, Map<String, Object> ownerClaims, Map<String, Object> transportClaims) { }
}
