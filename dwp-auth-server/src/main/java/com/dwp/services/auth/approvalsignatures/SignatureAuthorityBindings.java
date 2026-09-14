package com.dwp.services.auth.approvalsignatures;

import static com.dwp.services.auth.approvalsignatures.SignatureAuthorityJson.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record SignatureAuthorityBindings(SignatureAuthorityProtocol.Operation operation, long tenantId, long actorId,
        UUID personPublicId, UUID objectId, Long objectVersion, String idempotencyKey, String bodySha256,
        String contextKey, String contextScopeKey, String resourceSetKey, String decisionRevision, String registrySha256,
        String rolloutState, String accessMode, Instant authorityValidUntil, UUID nonce, JsonNode source, JsonNode commandBody,
        String sourceSha256, String stepUpToken) {
    public SignatureAuthorityBindings { source = source.deepCopy(); commandBody = commandBody.deepCopy(); }
    @Override public JsonNode source() { return source.deepCopy(); }
    @Override public JsonNode commandBody() { return commandBody.deepCopy(); }
    public static SignatureAuthorityBindings parse(JsonNode node, SignatureAuthorityJson json) {
        keys(node, Set.of("operation", "tenantId", "actorId", "personPublicId", "objectId", "objectVersion", "idempotencyKey",
                "bodySha256", "contextKey", "contextScopeKey", "resourceSetKey", "decisionRevision", "registrySha256",
                "rolloutState", "accessMode", "authorityValidUntil", "nonce", "source", "commandBody", "sourceSha256", "stepUpToken"));
        final SignatureAuthorityProtocol.Operation operation;
        try { operation = SignatureAuthorityProtocol.Operation.valueOf(text(node, "operation", 16)); }
        catch (IllegalArgumentException unknown) { throw denied(); }
        long tenant = integer(node, "tenantId"), actor = integer(node, "actorId");
        if (tenant == 0 || actor == 0) throw denied();
        String rs = text(node, "resourceSetKey", 80), revision = text(node, "decisionRevision", 68);
        if (!rs.matches("RS_[A-Z0-9_]{1,76}") || !revision.matches("psr-[a-f0-9]{64}")) throw denied();
        String mode = text(node, "accessMode", 16), rollout = text(node, "rolloutState", 3);
        if (!Set.of("NORMAL", "ELEVATED").contains(mode) || !Set.of("110", "111").contains(rollout)) throw denied();
        Long version = operation.mutation() ? integer(node, "objectVersion") : null;
        boolean receipt=operation==SignatureAuthorityProtocol.Operation.COMMAND_RECEIPT;
        String key = operation.mutation() || receipt ? text(node, "idempotencyKey", 120) : null;
        if (key != null && !key.matches("[A-Za-z0-9._:-]{1,120}")
                || receipt && (key.equals(".") || key.equals(".."))
                || !operation.mutation() && (!node.path("objectVersion").isNull() || !receipt && !node.path("idempotencyKey").isNull())) throw denied();
        JsonNode source = node.get("source"), body = node.get("commandBody");
        UUID objectId = uuid(node, "objectId");
        if(receipt) SignatureReceiptSource.validate(source,body,actor,rs,key,objectId);
        else {
        keys(source, SignatureAuthorityProtocol.SOURCE_FIELDS);
        for (String field : SignatureAuthorityProtocol.SOURCE_FIELDS) {
            if (field.endsWith("Sha256")) hash(source, field);
            else if (field.equals("ownerUserId") || field.endsWith("Version") && !field.equals("rendererVersion") || field.endsWith("Revision")) integer(source, field);
            else if (field.endsWith("Id")) uuid(source, field);
            else text(source, field, 80);
        }
        if (integer(source, "ownerUserId") != actor || !rs.equals(text(source, "resourceSetKey", 80))
                || !"DWP_SELF_ATTESTATION_JSON_V1".equals(text(source, "rendererVersion", 80))
                || body == null || !body.isObject() || !json.digest(body).equals(hash(node, "bodySha256"))
                || !json.digest(source).equals(hash(node, "sourceSha256"))) throw denied();
        if (operation.mutation() && (integer(body, "expectedVersion") != version || !key.equals(text(body, "idempotencyKey", 120)))) throw denied();
        if ((operation == SignatureAuthorityProtocol.Operation.CONTEXT || operation == SignatureAuthorityProtocol.Operation.CREATE)
                && !objectId.equals(uuid(source, "requestId"))) throw denied();
        if (operation == SignatureAuthorityProtocol.Operation.CREATE && integer(source, "requestVersion") != version) throw changed();
        }
        if(body==null || !body.isObject() || !json.digest(body).equals(hash(node,"bodySha256")) || !json.digest(source).equals(hash(node,"sourceSha256"))) throw denied();
        String token = operation == SignatureAuthorityProtocol.Operation.SIGN ? text(node, "stepUpToken", 16384) : null;
        if (operation != SignatureAuthorityProtocol.Operation.SIGN && !node.path("stepUpToken").isNull()) throw denied();
        try {
            return new SignatureAuthorityBindings(operation, tenant, actor, uuid(node, "personPublicId"), objectId, version, key,
                    hash(node, "bodySha256"), text(node, "contextKey", 512), text(node, "contextScopeKey", 512), rs, revision,
                    hash(node, "registrySha256"), rollout, mode, Instant.parse(text(node, "authorityValidUntil", 40)), uuid(node, "nonce"),
                    source, body, hash(node, "sourceSha256"), token);
        } catch (java.time.DateTimeException invalid) { throw denied(); }
    }
    public String path() { return operation==SignatureAuthorityProtocol.Operation.COMMAND_RECEIPT ? "/v1/signature-command-receipts/"+idempotencyKey : operation.path(objectId); }
    public String sourceKind() { return operation==SignatureAuthorityProtocol.Operation.COMMAND_RECEIPT ? "COMMAND_RECEIPT" : "ARTIFACT"; }
}
