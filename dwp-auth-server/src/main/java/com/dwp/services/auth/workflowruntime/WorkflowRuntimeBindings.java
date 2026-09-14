package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;

public final class WorkflowRuntimeBindings {
    public static final Set<String> COMMAND_FIELDS = Set.of("tenantId", "actorId", "personPublicId", "commandPurpose", "targetId",
            "routeContractKey", "method", "path", "idempotencyKey", "rawBodySha256", "contextKey", "contextScopeKey",
            "decisionRevision", "accessMode", "rolloutState", "authorityValidUntil", "expectedVersion", "sourceGeneration");
    public static final Set<String> OWNER_FIELDS = Set.of("tenantId", "actorId", "personPublicId", "requestId", "requestVersion",
            "workflowVersionId", "workflowVersion", "workflowDefinitionSha256", "formVersionId", "formSchemaSha256", "payloadRevision",
            "payloadSha256", "policyVersion", "policySha256", "contextKey", "contextScopeKey", "decisionRevision", "accessMode",
            "routeContractKey", "managementResourceSetKey", "roleCodes", "publishedDefinition", "method", "path", "idempotencyKey");
    public static final Set<String> STAGE_FIELDS = Set.of("poolMode", "stepKey", "generation", "requesterUserId", "requesterPersonPublicId",
            "candidateRole", "sourceStageRevision", "snapshotSha256", "candidateSetSha256", "candidateCount", "requiredVotes",
            "rejectCommentMinLength", "admissionAttestationSha256", "admissionAttestation", "commandRawBodySha256");
    public static final Set<String> TARGET_FIELDS = Set.of("use", "stepKey", "taskId", "evidenceId", "evidenceSha256", "sourceGeneration", "actorId",
            "actorPersonPublicId", "principalId", "principalPersonPublicId", "delegation");
    public static final Set<String> DELEGATION_FIELDS = Set.of("id", "delegatorUserId", "delegateUserId", "delegatePersonPublicId",
            "workflowVersionId", "authorityRoleId", "startsAt", "endsAt");
    public static final Set<String> INFO_RESULT_FIELDS = Set.of("tenantId", "personPublicId", "commandPurpose", "targetId", "idempotencyKey",
            "rawBodySha256", "expectedVersion", "sourceGeneration", "contextKey", "contextScopeKey", "decisionRevision", "routeContractKey",
            "accessMode", "rolloutState", "authorityValidUntil");
    private WorkflowRuntimeBindings() { }

    public static Caller parse(Operation operation, JsonNode bindings) {
        exact(bindings, operation == Operation.INFORMATION_ADMISSION ? Set.of("command")
                : operation == Operation.CANDIDATES ? Set.of("owner", "stage") : Set.of("owner", "stage", "target"));
        var owner = bindings.get(operation == Operation.INFORMATION_ADMISSION ? "command" : "owner");
        exact(owner, operation == Operation.INFORMATION_ADMISSION ? COMMAND_FIELDS : OWNER_FIELDS);
        long tenant = number(owner, "tenantId", 1, Long.MAX_VALUE), actor = number(owner, "actorId", 1, Long.MAX_VALUE);
        UUID person = uuid(owner, "personPublicId");
        if (!text(owner, "idempotencyKey", 120).matches("[A-Za-z0-9._:-]{1,120}")
                || !text(owner, "decisionRevision", 68).matches("psr-[a-f0-9]{64}")
                || !Set.of("NORMAL", "ELEVATED").contains(text(owner, "accessMode"))) throw denied();
        text(owner, "contextKey"); text(owner, "contextScopeKey");
        if (operation == Operation.INFORMATION_ADMISSION) command(owner); else source(operation, bindings);
        return new Caller(tenant, actor, person, text(owner, "routeContractKey"), text(owner, "contextKey"),
                text(owner, "contextScopeKey"), text(owner, "accessMode"), bindings.deepCopy());
    }
    private static void command(JsonNode command) {
        String purpose = text(command, "commandPurpose"); UUID target = uuid(command, "targetId");
        String route = text(command, "routeContractKey"), path = text(command, "path");
        if (!"POST".equals(text(command, "method")) || !Set.of("110", "111").contains(text(command, "rolloutState"))) throw denied();
        hash(command, "rawBodySha256"); number(command, "expectedVersion", 0, 9007199254740991L);
        number(command, "authorityValidUntil", 1, Long.MAX_VALUE);
        if (purpose.equals("TASK_INFORMATION")) {
            if (!route.equals("route.approvals.work.task-decision.action") || !path.equals("/v1/tasks/" + target + "/decisions")
                    || !command.get("sourceGeneration").isNull()) throw denied();
        } else if (purpose.equals("REQUEST_REPLY")) {
            if (!route.equals("route.approvals.work.request-information-response.action") || !path.equals("/v1/requests/" + target + "/information-response")) throw denied();
            number(command, "sourceGeneration", 1, Long.MAX_VALUE);
        } else throw denied();
    }
    private static void source(Operation operation, JsonNode bindings) {
        var owner = bindings.get("owner"); var stage = bindings.get("stage"); exact(stage, STAGE_FIELDS);
        UUID request = uuid(owner, "requestId"); uuid(owner, "workflowVersionId"); uuid(owner, "formVersionId");
        for (String field : Set.of("workflowDefinitionSha256", "formSchemaSha256", "payloadSha256", "policySha256")) hash(owner, field);
        number(owner, "requestVersion", 0, 9007199254740991L);
        for (String field : Set.of("workflowVersion", "payloadRevision", "policyVersion")) number(owner, field, 1, 9007199254740991L);
        text(owner, "managementResourceSetKey"); text(owner, "publishedDefinition", 131072);
        String route = text(owner, "routeContractKey"), path = text(owner, "path");
        if (!"POST".equals(text(owner, "method"))) throw denied();
        boolean valid = switch (route) {
            case "route.approvals.work.request-submit.action" -> path.equals("/v1/requests/" + request + "/submit");
            case "route.approvals.work.request-information-response.action" -> path.equals("/v1/requests/" + request + "/information-response");
            case "route.approvals.work.task-decision.action" -> canonicalTaskPath(path);
            default -> false;
        };
        if (!valid) throw denied();
        long generation = number(stage, "generation", 1, Long.MAX_VALUE);
        number(stage, "requesterUserId", 1, Long.MAX_VALUE); uuid(stage, "requesterPersonPublicId");
        for (String field : Set.of("stepKey", "candidateRole")) if (!text(stage, field).matches("[A-Z][A-Z0-9_]{1,49}")) throw denied();
        number(stage, "sourceStageRevision", 0, Long.MAX_VALUE); number(stage, "rejectCommentMinLength", 4, 2000);
        PoolMode mode;
        try { mode = PoolMode.valueOf(text(stage, "poolMode")); } catch (IllegalArgumentException exception) { throw denied(); }
        if (operation == Operation.VOTER && mode != PoolMode.SEALED
                || mode == PoolMode.INITIAL && generation != 1 || (mode == PoolMode.REBUILD || mode == PoolMode.RETAINED) && generation <= 1) throw denied();
        if (mode == PoolMode.INITIAL || mode == PoolMode.REBUILD) {
            for (String field : Set.of("snapshotSha256", "candidateSetSha256", "candidateCount", "requiredVotes")) if (!stage.get(field).isNull()) throw denied();
        } else {
            hash(stage, "snapshotSha256"); hash(stage, "candidateSetSha256");
            long count = number(stage, "candidateCount", 1, 1000); number(stage, "requiredVotes", 1, count);
        }
        boolean info = !stage.get("admissionAttestation").isNull();
        if (info) { text(stage, "admissionAttestation", MAX_ATTESTATION); hash(stage, "admissionAttestationSha256"); hash(stage, "commandRawBodySha256"); }
        else for (String field : Set.of("admissionAttestationSha256", "commandRawBodySha256")) if (!stage.get(field).isNull()) throw denied();
        if (route.equals("route.approvals.work.request-information-response.action") && !info) throw denied();
        if (operation == Operation.VOTER) target(bindings);
    }
    private static void target(JsonNode bindings) {
        var target = bindings.get("target"); var stage = bindings.get("stage"); exact(target, TARGET_FIELDS);
        if (!text(target, "stepKey").equals(text(stage, "stepKey"))) throw denied();
        uuid(target, "taskId"); uuid(target, "actorPersonPublicId"); uuid(target, "principalPersonPublicId");
        long actor = number(target, "actorId", 1, Long.MAX_VALUE), principal = number(target, "principalId", 1, Long.MAX_VALUE);
        if (number(target, "sourceGeneration", 1, Long.MAX_VALUE) != number(stage, "generation", 1, Long.MAX_VALUE)) throw denied();
        TargetUse use;
        try { use = TargetUse.valueOf(text(target, "use")); } catch (IllegalArgumentException exception) { throw denied(); }
        if (use == TargetUse.CAST || use == TargetUse.SEAT_RECHECK) {
            if (!target.get("evidenceId").isNull() || !target.get("evidenceSha256").isNull()) throw denied();
        } else { uuid(target, "evidenceId"); hash(target, "evidenceSha256"); }
        if (use == TargetUse.CAST && actor != number(bindings.get("owner"), "actorId", 1, Long.MAX_VALUE)
                || use == TargetUse.SEAT_RECHECK && actor != principal) throw denied();
        if (use == TargetUse.CAST && text(bindings.get("owner"), "routeContractKey").equals("route.approvals.work.task-decision.action")
                && !text(bindings.get("owner"), "path").equals("/v1/tasks/" + uuid(target, "taskId") + "/decisions")) throw denied();
        var delegation = target.get("delegation");
        if (actor == principal) {
            if (!delegation.isNull() || !uuid(target, "actorPersonPublicId").equals(uuid(target, "principalPersonPublicId"))) throw denied();
        } else {
            exact(delegation, DELEGATION_FIELDS); uuid(delegation, "id");
            if (number(delegation, "delegatorUserId", 1, Long.MAX_VALUE) != principal || number(delegation, "delegateUserId", 1, Long.MAX_VALUE) != actor
                    || !uuid(delegation, "delegatePersonPublicId").equals(uuid(target, "actorPersonPublicId"))
                    || !uuid(delegation, "workflowVersionId").equals(uuid(bindings.get("owner"), "workflowVersionId"))) throw denied();
            number(delegation, "authorityRoleId", 1, Long.MAX_VALUE);
            long starts = number(delegation, "startsAt", 1, Long.MAX_VALUE - 1); number(delegation, "endsAt", starts + 1, Long.MAX_VALUE);
        }
        if (use == TargetUse.INFORMATION_RECHECK && stage.get("admissionAttestation").isNull()) throw denied();
    }
    private static boolean canonicalTaskPath(String path) {
        if (!path.startsWith("/v1/tasks/") || !path.endsWith("/decisions")) return false;
        String value = path.substring("/v1/tasks/".length(), path.length() - "/decisions".length());
        try { return UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException exception) { return false; }
    }
    public record Caller(long tenantId, long actorId, UUID personPublicId, String routeContractKey, String contextKey,
            String contextScopeKey, String accessMode, JsonNode sealed) {
        public Caller { sealed = sealed.deepCopy(); }
        @Override public JsonNode sealed() { return sealed.deepCopy(); }
    }
}
