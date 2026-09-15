package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.dwp.core.security.ScopedAuthorityToken;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only the sealed Planning DATA expression contributes two independent native authorities. */
final class ApprovalPep10AuthorityKeys {
    static final String ROUTE = "route.approvals.admin.workflow-planning-simulation.data";
    static final String SELECTION_ROUTE =
            "route.approvals.admin.workflow-planning-selection.data";
    private static final Set<String> KEYS = Set.of("approvals.admin.workflow-planning-form.read",
            "approvals.admin.workflow-planning-simulation.read");
    private ApprovalPep10AuthorityKeys() { }
    static boolean isPlanningRoute(String route) {
        return ROUTE.equals(route) || SELECTION_ROUTE.equals(route);
    }
    static boolean sameResourceSet(String roles, Map<String, JsonNode> capabilities) {
        var duties = Arrays.stream(roles.split(",")).map(String::trim).toList();
        Set<String> common = null;
        for (String key : KEYS) {
            JsonNode capability = capabilities.get(key);
            if (capability == null || !capability.path("resolvedCapabilityCode").isTextual()) return false;
            var sets = ScopedAuthorityToken.matchingResourceSetKeys(duties, key,
                    capability.path("resolvedCapabilityCode").asText());
            if (common == null) common = new HashSet<>(sets); else common.retainAll(sets);
        }
        if (common == null) return false;
        return common.stream().anyMatch(set -> duties.contains("APP_CONFIG_ADMIN@" + set));
    }
    static List<String> planning(String kind, String profile, boolean readOnly, JsonNode access) {
        var keys = new ArrayList<String>();
        access.path("capabilityContractKeys").forEach(value -> {
            if (!value.isTextual()) throw new IllegalStateException("Planning authority key must be textual");
            keys.add(value.asText());
        });
        if (!"DATA".equals(kind) || !"full-management".equals(profile) || !readOnly
                || !"CAPABILITY_EXPRESSION".equals(access.path("type").asText())
                || !"ALL".equals(access.path("mode").asText())
                || keys.size() != 2 || !Set.copyOf(keys).equals(KEYS)
                || access.has("capabilityContractKey") || access.has("accessPolicyKey")) {
            throw new IllegalStateException("Planning requires two independent exact native authorities");
        }
        return List.copyOf(keys);
    }
}
