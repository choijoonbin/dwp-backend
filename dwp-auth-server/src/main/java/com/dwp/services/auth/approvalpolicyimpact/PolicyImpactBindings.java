package com.dwp.services.auth.approvalpolicyimpact;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record PolicyImpactBindings(long tenantId, long actorId, UUID personPublicId, UUID policyId,
        long expectedVersion, String sourceDigest, String method, String path, String rawQuerySha256,
        String routeContractKey, String contextKey, String contextScopeKey, String resourceSetKey,
        String decisionRevision, String rolloutState, String accessMode, Instant authorityValidUntil) {
    public static PolicyImpactBindings parse(JsonNode value) {
        PolicyImpactJson.keys(value, PolicyImpactProtocol.BINDINGS);
        var result = new PolicyImpactBindings(PolicyImpactJson.integer(value, "tenantId", true),
                PolicyImpactJson.integer(value, "actorId", true), PolicyImpactJson.uuid(value, "personPublicId"),
                PolicyImpactJson.uuid(value, "policyId"), PolicyImpactJson.integer(value, "expectedVersion", false),
                PolicyImpactJson.hash(value, "sourceDigest"), PolicyImpactJson.text(value, "method", 4),
                PolicyImpactJson.text(value, "path", 160), PolicyImpactJson.hash(value, "rawQuerySha256"),
                PolicyImpactJson.text(value, "routeContractKey", 100), PolicyImpactJson.text(value, "contextKey", 500),
                PolicyImpactJson.text(value, "contextScopeKey", 500), PolicyImpactJson.text(value, "resourceSetKey", 80),
                PolicyImpactJson.text(value, "decisionRevision", 68), PolicyImpactJson.text(value, "rolloutState", 3),
                PolicyImpactJson.text(value, "accessMode", 20), PolicyImpactJson.instant(value, "authorityValidUntil"));
        if (!"GET".equals(result.method()) || !("/v1/admin/policies/" + result.policyId() + "/impact").equals(result.path())
                || !PolicyImpactProtocol.ROUTE.equals(result.routeContractKey())
                || !result.rawQuerySha256().equals(PolicyImpactJson.sha("expectedVersion=" + result.expectedVersion()))
                || !result.resourceSetKey().matches("[A-Z][A-Z0-9_]{2,79}")
                || !result.decisionRevision().matches("psr-[a-f0-9]{64}")
                || !Set.of("110", "111").contains(result.rolloutState())
                || !Set.of("NORMAL", "ELEVATED").contains(result.accessMode())) throw PolicyImpactJson.denied();
        return result;
    }
}
