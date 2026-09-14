package com.dwp.services.auth.informationreplay;

import static com.dwp.services.auth.informationreplay.InformationReplayJson.*;
import static com.dwp.services.auth.informationreplay.InformationReplayProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;

/** Historical provenance is separate from the current, server-sealed receipt caller and source authority. */
public final class InformationReplayBindings {
    public static final Set<String> OWNER_FIELDS = Set.of("tenantId", "actorId", "personPublicId", "requestId", "requestVersion",
            "workflowVersionId", "workflowVersion", "workflowDefinitionSha256", "formVersionId", "formSchemaSha256", "payloadRevision",
            "payloadSha256", "policyVersion", "policySha256", "contextKey", "contextScopeKey", "decisionRevision", "accessMode",
            "routeContractKey", "managementResourceSetKey", "roleCodes", "publishedDefinition", "method", "path", "idempotencyKey");
    public static final Set<String> SOURCE_FIELDS = Set.of("stepKey", "generation", "sourceStageRevision", "snapshotSha256",
            "candidateSetSha256", "candidateCount", "requiredVotes", "candidateRole", "requesterUserId", "requesterPersonPublicId",
            "rejectCommentMinLength", "originalPayloadRevision", "originalPayloadSha256");
    public static final Set<String> TARGET_FIELDS = Set.of("use", "stepKey", "taskId", "evidenceId", "evidenceSha256", "sourceGeneration",
            "actorId", "actorPersonPublicId", "principalId", "principalPersonPublicId", "delegation");
    public static final Set<String> DELEGATION_FIELDS = Set.of("id", "delegatorUserId", "delegateUserId", "delegatePersonPublicId",
            "workflowVersionId", "roleCode", "startsAt", "endsAt");
    public static final Set<String> ADMISSION_FIELDS = Set.of("operation", "commandSha256", "rawBodySha256", "receiptSha256", "roundId",
            "taskId", "stepKey", "sourceGeneration", "receiptGeneration", "receiptRequestVersion", "receiptPayloadRevision", "receiptPayloadSha256",
            "materialChange", "commandActorId", "commandActorPersonPublicId", "admissionSha256", "admissionJti", "admissionIssuer",
            "admissionKeyId", "admissionSourceRevision", "admissionSourceVectorSha256", "admissionOwnerAuthRevision", "admissionOwnerPolicyRevision",
            "admissionEvaluatedAt", "admissionExpiresAt", "acceptedAt", "ownerProofJti", "transportProofJti", "originalExpectedVersion", "completedAt");
    private InformationReplayBindings() { }

    public static Caller validate(InformationReplayJson json, JsonNode bindings) {
        exact(bindings, Set.of("owner", "source", "target", "admission"));
        var owner = bindings.get("owner"); var source = bindings.get("source");
        var target = bindings.get("target"); var admission = bindings.get("admission");
        exact(owner, OWNER_FIELDS); exact(source, SOURCE_FIELDS); exact(target, TARGET_FIELDS); exact(admission, ADMISSION_FIELDS);
        long tenant = positive(owner, "tenantId"), actor = positive(owner, "actorId");
        UUID person = uuid(owner, "personPublicId"), request = uuid(owner, "requestId");
        nonnegative(owner, "requestVersion"); uuid(owner, "workflowVersionId"); positive(owner, "workflowVersion");
        uuid(owner, "formVersionId"); positive(owner, "payloadRevision"); positive(owner, "policyVersion");
        for (String field : Set.of("workflowDefinitionSha256", "formSchemaSha256", "payloadSha256", "policySha256")) hash(owner, field);
        String route = text(owner, "routeContractKey", 100), key = text(owner, "idempotencyKey", 120);
        if (!ROUTE.equals(route) || !key.matches("[A-Za-z0-9._:-]{1,120}") || Set.of(".", "..").contains(key) || !"POST".equals(text(owner, "method", 4))
                || !("/v1/requests/" + request + "/information-commands/" + key + "/receipt").equals(text(owner, "path", 220))) throw denied();
        String context = text(owner, "contextKey", 500), scope = text(owner, "contextScopeKey", 500);
        String mode = text(owner, "accessMode", 20);
        if (!Set.of("NORMAL", "ELEVATED").contains(mode) || !text(owner, "decisionRevision", 68).matches("psr-[a-f0-9]{64}")
                || !text(owner, "managementResourceSetKey", 80).matches("[A-Z][A-Z0-9_]{2,79}")) throw denied();
        String step = text(source, "stepKey", 50), role = text(source, "candidateRole", 50);
        if (!step.matches("[A-Z][A-Z0-9_]{1,49}") || !role.matches("[A-Z][A-Z0-9_]{1,49}") || role.startsWith("PROVIDER_")) throw denied();
        long generation = number(source, "generation", 1, SAFE_INTEGER - 1);
        nonnegative(source, "sourceStageRevision"); number(source, "rejectCommentMinLength", 4, 2000);
        hash(source, "snapshotSha256"); hash(source, "candidateSetSha256"); positive(source, "originalPayloadRevision"); hash(source, "originalPayloadSha256");
        long count = number(source, "candidateCount", 1, 1000); number(source, "requiredVotes", 1, count);
        long requester = positive(source, "requesterUserId"); UUID requesterPerson = uuid(source, "requesterPersonPublicId");
        if (!"RECEIPT_ORIGINAL_INFORMATION".equals(text(target, "use", 40)) || !step.equals(text(target, "stepKey", 50))
                || generation != positive(target, "sourceGeneration")) throw denied();
        UUID task = uuid(target, "taskId"), round = uuid(target, "evidenceId"); hash(target, "evidenceSha256");
        long originalActor = positive(target, "actorId"), principal = positive(target, "principalId");
        UUID originalPerson = uuid(target, "actorPersonPublicId"), principalPerson = uuid(target, "principalPersonPublicId");
        if (requester == principal || requesterPerson.equals(principalPerson) || requester == originalActor || requesterPerson.equals(originalPerson)) throw denied();
        var delegation = target.get("delegation");
        if (delegation.isNull()) {
            if (originalActor != principal || !originalPerson.equals(principalPerson)) throw denied();
        } else {
            exact(delegation, DELEGATION_FIELDS); uuid(delegation, "id");
            if (positive(delegation, "delegatorUserId") != principal || positive(delegation, "delegateUserId") != originalActor
                    || originalActor == principal || !uuid(delegation, "delegatePersonPublicId").equals(originalPerson)
                    || !uuid(delegation, "workflowVersionId").equals(uuid(owner, "workflowVersionId"))
                    || !role.equals(text(delegation, "roleCode", 50))) throw denied();
            long from = positive(delegation, "startsAt"), to = positive(delegation, "endsAt");
            if (from >= to) throw denied();
        }
        String operation = text(admission, "operation", 20);
        if (!Set.of("REQUEST_INFO", "REPLY").contains(operation) || positive(admission, "commandActorId") != actor
                || !uuid(admission, "commandActorPersonPublicId").equals(person) || !uuid(admission, "roundId").equals(round)
                || !uuid(admission, "taskId").equals(task) || !step.equals(text(admission, "stepKey", 50))
                || generation != positive(admission, "sourceGeneration")) throw denied();
        if (operation.equals("REQUEST_INFO") ? actor != originalActor || !person.equals(originalPerson)
                : actor != requester || !person.equals(requesterPerson)) throw denied();
        long receiptGeneration = positive(admission, "receiptGeneration"), receiptVersion = positive(admission, "receiptRequestVersion");
        long originalExpected = nonnegative(admission, "originalExpectedVersion");
        if (receiptGeneration != generation + (operation.equals("REPLY") ? 1 : 0)
                || operation.equals("REPLY") && originalExpected != receiptVersion - 1
                || nonnegative(owner, "requestVersion") < receiptVersion || positive(owner, "payloadRevision") < positive(admission, "receiptPayloadRevision")) throw denied();
        var material = admission.get("materialChange");
        if (!material.isBoolean() || operation.equals("REQUEST_INFO") && material.booleanValue()) throw denied();
        for (String field : Set.of("commandSha256", "rawBodySha256", "receiptSha256", "receiptPayloadSha256", "admissionSha256", "admissionSourceVectorSha256")) hash(admission, field);
        for (String field : Set.of("admissionJti", "ownerProofJti", "transportProofJti")) uuid(admission, field);
        if (!text(admission, "admissionIssuer", 160).equals("dwp-auth-server:workflow-runtime:information-admission:v1")
                || !text(admission, "admissionKeyId", 80).matches("[A-Za-z0-9._-]{1,80}")
                || !text(admission, "admissionSourceRevision", 68).matches("awr-[a-f0-9]{64}")) throw denied();
        text(admission, "admissionOwnerAuthRevision", 200); text(admission, "admissionOwnerPolicyRevision", 200);
        long evaluated = positive(admission, "admissionEvaluatedAt"), accepted = positive(admission, "acceptedAt"), expired = positive(admission, "admissionExpiresAt");
        long completed = positive(admission, "completedAt");
        if (evaluated > completed || completed > accepted || accepted > expired || expired - evaluated > 30 || expired <= evaluated) throw denied();
        // Historical expiry constrains acceptance, not the current receipt replay lease.
        return new Caller(tenant, actor, person, request, context, scope, mode, role, requester, requesterPerson,
                originalActor, originalPerson, principal, principalPerson, operation, bindings);
    }

    private static long positive(JsonNode value, String field) { return number(value, field, 1, SAFE_INTEGER); }
    private static long nonnegative(JsonNode value, String field) { return number(value, field, 0, SAFE_INTEGER); }
    public record Caller(long tenantId, long actorId, UUID personPublicId, UUID requestId, String contextKey,
            String contextScopeKey, String accessMode, String roleCode, long requesterId, UUID requesterPersonPublicId,
            long originalActorId, UUID originalActorPersonPublicId, long principalId, UUID principalPersonPublicId,
            String historicalOperation, JsonNode bindings) {
        public Caller { bindings = bindings.deepCopy(); }
        @Override public JsonNode bindings() { return bindings.deepCopy(); }
    }
}
