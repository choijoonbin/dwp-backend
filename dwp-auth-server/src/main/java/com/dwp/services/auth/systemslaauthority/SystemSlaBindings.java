package com.dwp.services.auth.systemslaauthority;

import static com.dwp.services.auth.systemslaauthority.SystemSlaJson.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owner-sealed workload, never an end-user permission or a client-selected recipient pool. */
public final class SystemSlaBindings {
    private final long tenantId;
    private final String operation, sourceDigest;
    private final JsonNode source, audience;
    private final Instant validUntil;
    private SystemSlaBindings(long tenantId, String operation, String sourceDigest, JsonNode source, JsonNode audience, Instant validUntil) {
        this.tenantId = tenantId; this.operation = operation; this.sourceDigest = sourceDigest;
        this.source = source.deepCopy(); this.audience = audience.deepCopy(); this.validUntil = validUntil;
    }
    public static SystemSlaBindings parse(JsonNode value, SystemSlaJson json) {
        keys(value, Set.of("tenantId", "operation", "source", "audience", "sourceDigest", "authorityValidUntil"));
        long tenant = integer(value, "tenantId", true); String operation = text(value, "operation", 7);
        if (!Set.of("PRODUCE", "DELIVER").contains(operation)) throw denied();
        var source = value.get("source"); keys(source, Set.of("request", "workflow", "form", "payload", "policy", "stage", "timer", "event"));
        var request = source.get("request"); keys(request, Set.of("requestId", "requestVersion", "requesterUserId", "requesterPersonPublicId", "dataClassification", "resourceSetKey"));
        uuid(request, "requestId"); integer(request, "requestVersion", false); integer(request, "requesterUserId", true); uuid(request, "requesterPersonPublicId");
        if (!Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED").contains(text(request, "dataClassification", 20))) throw denied();
        if (!text(request, "resourceSetKey", 80).matches("[A-Z][A-Z0-9_]{2,79}")) throw denied();
        var workflow = source.get("workflow"); keys(workflow, Set.of("workflowVersionId", "workflowVersion", "definitionSha256", "definition"));
        uuid(workflow, "workflowVersionId"); integer(workflow, "workflowVersion", true); hash(workflow, "definitionSha256");
        if (!workflow.get("definition").isObject() || !hash(workflow, "definitionSha256").equals(json.digest(workflow.get("definition")))) throw denied();
        var form = source.get("form"); keys(form, Set.of("formVersionId", "schemaSha256")); uuid(form, "formVersionId"); hash(form, "schemaSha256");
        var payload = source.get("payload"); keys(payload, Set.of("revision", "sha256")); integer(payload, "revision", true); hash(payload, "sha256");
        var stage = source.get("stage"); keys(stage, Set.of("stepId", "generation", "version", "stageKey", "candidateRole", "frozenPoolSha256"));
        uuid(stage, "stepId"); integer(stage, "generation", true); integer(stage, "version", false); hash(stage, "frozenPoolSha256");
        if (!text(stage, "candidateRole", 80).matches("[A-Z][A-Z0-9_]{2,79}") || text(stage, "candidateRole", 80).startsWith("PROVIDER_")) throw denied();
        text(stage, "stageKey", 100);
        var definition = workflow.get("definition"); keys(definition, Set.of("schemaContract", "schemaVersion", "slaMinutes", "stages"));
        if (!"DWP_APPROVAL_WORKFLOW_QUORUM_V2".equals(text(definition, "schemaContract", 80)) || integer(definition, "schemaVersion", true) != 2
                || !definition.get("stages").isArray() || definition.get("stages").isEmpty() || definition.get("stages").size() > 64) throw denied();
        var selected = new java.util.ArrayList<JsonNode>();
        definition.get("stages").forEach(item -> { if (item.isObject() && text(stage, "stageKey", 100).equals(item.path("key").asText())) selected.add(item); });
        if (selected.size() != 1 || !text(stage, "candidateRole", 80).equals(text(selected.getFirst(), "candidateRole", 80))) throw denied();
        var timer = source.get("timer"); keys(timer, Set.of("timerId", "version", "kind", "dueAt", "leaseEpoch", "leaseOwner", "leaseUntil", "policyVersion"));
        uuid(timer, "timerId"); integer(timer, "version", false); integer(timer, "leaseEpoch", true); integer(timer, "policyVersion", true);
        if (!Set.of("WARNING", "BREACH").contains(text(timer, "kind", 10))) throw denied(); instant(timer, "dueAt");
        Instant validUntil = instant(value, "authorityValidUntil");
        if ("PRODUCE".equals(operation)) {
            text(timer, "leaseOwner", 160); if (validUntil.isAfter(instant(timer, "leaseUntil")) || !source.get("event").isNull()) throw denied();
        } else {
            if (!timer.get("leaseOwner").isNull() || !timer.get("leaseUntil").isNull()) throw denied();
            var event = source.get("event"); keys(event, Set.of("eventId", "eventType", "originalEnvelopeSha256", "canonicalEnvelopeSha256")); uuid(event, "eventId");
            if (!Set.of("Approval.Quorum.SlaWarning", "Approval.Quorum.SlaBreached").contains(text(event, "eventType", 40))) throw denied();
            hash(event, "originalEnvelopeSha256"); hash(event, "canonicalEnvelopeSha256");
        }
        validatePolicy(source.get("policy"), timer, json);
        var audience = value.get("audience"); if (!audience.isArray() || audience.isEmpty() || audience.size() > SystemSlaProtocol.AUDIENCE_LIMIT) throw denied();
        long previous = 0; var people = new java.util.HashSet<UUID>(); var tasks = new java.util.HashSet<UUID>();
        for (var seat : audience) {
            keys(seat, Set.of("userId", "personPublicId", "taskId", "taskVersion")); long user = integer(seat, "userId", true);
            if (user <= previous || !people.add(uuid(seat, "personPublicId")) || !tasks.add(uuid(seat, "taskId"))) throw denied();
            previous = user; integer(seat, "taskVersion", false);
        }
        String digest = hash(value, "sourceDigest");
        if (!digest.equals(json.digest(Map.of("tenantId", tenant, "operation", operation, "source", source, "audience", audience)))) throw denied();
        return new SystemSlaBindings(tenant, operation, digest, source, audience, validUntil);
    }
    private static void validatePolicy(JsonNode policy, JsonNode timer, SystemSlaJson json) {
        keys(policy, Set.of("version", "sha256", "references", "reviewedHistory")); long version = integer(policy, "version", true);
        if (version != integer(timer, "policyVersion", true)) throw denied();
        var refs = policy.get("references"); var history = policy.get("reviewedHistory");
        if (!refs.isArray() || refs.size() != 3 || !history.isArray() || history.size() != 3) throw notReviewed();
        if (!hash(policy, "sha256").equals(json.digest(Map.of("references", refs)))) throw denied();
        var expected = List.of("BLOCK_SELF_APPROVAL", "REQUIRE_REJECT_REASON", "SLA_ESCALATION");
        for (int index = 0; index < 3; index++) {
            var ref = refs.get(index); var review = history.get(index);
            keys(ref, Set.of("policyId", "key", "rowVersion", "enforcement", "rule"));
            keys(review, Set.of("policyVersionId", "policyId", "versionNumber", "makerId", "publisherId", "submittedAt", "publishedAt", "ruleSha256", "provenance"));
            if (!expected.get(index).equals(text(ref, "key", 40)) || !uuid(ref, "policyId").equals(uuid(review, "policyId"))
                    || Math.addExact(integer(ref, "rowVersion", false), 1) != integer(review, "versionNumber", true)
                    || !Set.of("BLOCK", "WARN", "MONITOR").contains(text(ref, "enforcement", 10)) || !ref.get("rule").isObject()) throw denied();
            uuid(review, "policyVersionId");
            if (!"REVIEWED_PUBLISH".equals(text(review, "provenance", 40)) || review.get("makerId").isNull() || review.get("publisherId").isNull()) throw notReviewed();
            if (integer(review, "makerId", true) == integer(review, "publisherId", true)
                    || instant(review, "submittedAt").isAfter(instant(review, "publishedAt"))) throw notReviewed();
            if (!hash(review, "ruleSha256").equals(json.digest(ref.get("rule")))) throw denied();
            if (index == 2 && Math.addExact(integer(ref, "rowVersion", false), 1) != version) throw denied();
        }
    }
    public long tenantId() { return tenantId; }
    public String operation() { return operation; }
    public String sourceDigest() { return sourceDigest; }
    public JsonNode source() { return source.deepCopy(); }
    public JsonNode audience() { return audience.deepCopy(); }
    public Instant validUntil() { return validUntil; }
}
