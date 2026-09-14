package com.dwp.services.auth.workflowplanning;

import static com.dwp.services.auth.workflowplanning.PlanningJson.*;
import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record PlanningBindings(long tenantId, long actorId, UUID personPublicId, String contextKey,
        String contextScopeKey, String decisionRevision, String accessMode, String resourceSetKey,
        UUID workflowId, UUID workflowVersionId, UUID formVersionId, Instant authorityValidUntil, JsonNode source) {
    public PlanningBindings { source = source.deepCopy(); }
    @Override public JsonNode source() { return source.deepCopy(); }
    static PlanningBindings parse(PlanningJson json, JsonNode bindings) {
        exact(bindings, Set.of("owner", "source")); var owner = bindings.get("owner"); exact(owner, OWNER_FIELDS);
        var source = bindings.get("source"); exact(source, Set.of("snapshot", "snapshotSha256", "definition", "roleCodes", "topology"));
        long tenant = integer(owner, "tenantId", 1), actor = integer(owner, "actorId", 1);
        UUID workflow = uuid(owner, "workflowId"), version = uuid(owner, "workflowVersionId"), form = uuid(owner, "formVersionId");
        String mode = text(owner, "accessMode", 20), rs = text(owner, "managementResourceSetKey", 80);
        if (!Set.of("NORMAL", "ELEVATED").contains(mode) || !Set.of("110", "111").contains(text(owner, "rolloutState", 3))
                || !rs.matches("RS_[A-Z0-9_]{1,76}") || !"POST".equals(text(owner, "method", 4))
                || !ROUTE.equals(text(owner, "routeContractKey", 160))
                || !("/v1/admin/workflows/" + workflow + "/versions/" + version + "/simulation").equals(text(owner, "path", 200))
                || !hash(owner, "sourceSnapshotSha256").equals(hash(source, "snapshotSha256"))
                || !json.digest(source.get("snapshot")).equals(hash(source, "snapshotSha256"))) throw denied();
        PlanningDefinition.validate(json, source, tenant, workflow, version, form, rs);
        return new PlanningBindings(tenant, actor, uuid(owner, "personPublicId"), text(owner, "contextKey", 200),
                text(owner, "contextScopeKey", 200), text(owner, "decisionRevision", 200), mode, rs, workflow, version, form,
                Instant.ofEpochSecond(integer(owner, "authorityValidUntil", 1)), source);
    }
}
