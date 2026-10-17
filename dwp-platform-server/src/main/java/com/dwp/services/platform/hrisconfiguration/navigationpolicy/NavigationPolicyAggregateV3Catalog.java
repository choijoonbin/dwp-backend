package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAuthorityDecisionProvider.*;

/** Closed v46 catalog and semantic verifier for the first six SELF POLICY routes. */
final class NavigationPolicyAggregateV3Catalog {

    static final long REGISTRY_VERSION = 46;
    static final String REGISTRY_CHECKSUM =
            "3499e37653587feed433f84a79fbe7d0e80a1a7c0634d858a13f4e09d4bd015e";
    static final String PROFILE = "full-work";
    static final String TARGET = "SELF";

    static final List<PolicyRouteSpec> ROUTES = List.of(
            route("BASE:my-hr-profile", "/hr/me", "route.hcm.personal.me.page"),
            route("BASE:my-time", "/hr/time", "route.hcm.personal.time.page"),
            route("BASE:my-leave", "/hr/absence", "route.hcm.personal.absence.page"),
            route("BASE:my-pay-tax", "/hr/pay", "route.hcm.personal.pay.page"),
            route("BASE:my-goals-performance", "/hr/talent",
                    "route.hcm.personal.talent.page"),
            route("BASE:my-hr-requests", "/hr/services",
                    "route.hcm.personal.services.page"));

    static final Set<String> NODE_IDS = ROUTES.stream().map(PolicyRouteSpec::nodeId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Map<String, PolicyRouteSpec> BY_NODE = byNode();
    private static final Map<String, PolicyRouteSpec> BY_PATH = byPath();

    static {
        if (ROUTES.size() != 6 || NODE_IDS.size() != 6 || BY_PATH.size() != 6
                || !NODE_IDS.equals(
                ServerNavigationPolicyEligibilityProvider.POLICY_IMPLEMENTATION_NODE_IDS)) {
            throw new IllegalStateException("HRIS_NAV_V3_POLICY_CATALOG_INVALID");
        }
    }

    private NavigationPolicyAggregateV3Catalog() {
    }

    static SealedAuthority seal(
            AuthorityRequest request,
            AuthoritySnapshot snapshot,
            OffsetDateTime now) {
        if (!validRequest(request) || snapshot == null || now == null
                || snapshot.registryVersion() != REGISTRY_VERSION
                || !REGISTRY_CHECKSUM.equals(snapshot.registryChecksum())
                || !snapshotPattern(snapshot.snapshotRevision())
                || !decisionPattern(snapshot.decisionRevision())
                || !authPattern(snapshot.authRevision())
                || !policyPattern(snapshot.policyRevision())
                || !relationshipPattern(snapshot.relationshipRevision())
                || snapshot.freshUntil() == null
                || !snapshot.freshUntil().isAfter(now)
                || request.expectedDecisionRevision() != null
                && !request.expectedDecisionRevision().equals(
                snapshot.decisionRevision())
                || snapshot.decisions().size() != ROUTES.size()) {
            throw unavailable("HRIS_NAV_V3_AUTHORITY_SNAPSHOT_INVALID");
        }

        LinkedHashMap<String, RouteDecision> decisions = new LinkedHashMap<>();
        for (RouteDecision decision : snapshot.decisions()) {
            if (decision == null || decisions.putIfAbsent(
                    decision.nodeId(), decision) != null) {
                throw unavailable("HRIS_NAV_V3_AUTHORITY_DECISION_DUPLICATE");
            }
        }
        if (!decisions.keySet().equals(NODE_IDS)) {
            throw unavailable("HRIS_NAV_V3_AUTHORITY_DECISION_SET_INVALID");
        }

        ArrayList<RouteDecision> admitted = new ArrayList<>();
        LinkedHashMap<String, List<String>> admittedPaths = new LinkedHashMap<>();
        for (PolicyRouteSpec spec : ROUTES) {
            RouteDecision decision = decisions.get(spec.nodeId());
            validateTuple(request, snapshot, spec, decision);
            if ("ALLOWED".equals(decision.decision())) {
                admitted.add(decision);
                admittedPaths.put(spec.nodeId(), List.of(spec.path()));
            }
        }
        return new SealedAuthority(
                snapshot, List.copyOf(admitted),
                Collections.unmodifiableMap(admittedPaths));
    }

    static Map<String, List<String>> sealPolicyPaths(
            Map<String, List<String>> candidatePaths) {
        if (candidatePaths == null || candidatePaths.isEmpty()) return Map.of();
        if (!NODE_IDS.containsAll(candidatePaths.keySet())) {
            throw new IllegalArgumentException("HRIS_NAV_V3_POLICY_NODE_UNKNOWN");
        }
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        for (PolicyRouteSpec spec : ROUTES) {
            List<String> paths = candidatePaths.get(spec.nodeId());
            if (paths == null) continue;
            if (!paths.equals(List.of(spec.path()))) {
                throw new IllegalArgumentException("HRIS_NAV_V3_POLICY_PATH_INVALID");
            }
            result.put(spec.nodeId(), paths);
        }
        return Collections.unmodifiableMap(result);
    }

    static PolicyRouteSpec routeFor(String nodeId, String path) {
        PolicyRouteSpec spec = BY_PATH.get(path);
        return spec != null && spec.nodeId().equals(nodeId) ? spec : null;
    }

    private static void validateTuple(
            AuthorityRequest request,
            AuthoritySnapshot snapshot,
            PolicyRouteSpec spec,
            RouteDecision decision) {
        if (decision == null || !spec.path().equals(decision.path())
                || !spec.routeContractKey().equals(decision.routeContractKey())
                || !PROFILE.equals(decision.profileKey())
                || !TARGET.equals(decision.targetBinding())
                || !Set.of("ALLOWED", "ROUTE_DENIED").contains(
                decision.decision())
                || !canonical(decision.reasonCode(), 240)
                || !snapshot.decisionRevision().equals(
                decision.decisionRevision())
                || !snapshot.authRevision().equals(decision.authRevision())
                || !snapshot.policyRevision().equals(decision.policyRevision())
                || !snapshot.relationshipRevision().equals(
                decision.relationshipRevision())
                || decision.freshUntil() == null
                || !snapshot.freshUntil().equals(decision.freshUntil())) {
            throw unavailable("HRIS_NAV_V3_AUTHORITY_DECISION_INVALID");
        }
        if ("ALLOWED".equals(decision.decision())) {
            ScopeBinding scope = decision.scopeBinding();
            if (!"ALLOWED".equals(decision.reasonCode())
                    || decision.routeGrantRef() == null
                    || !decision.routeGrantRef().matches("^grant-[0-9a-f]{32}$")
                    || scope == null || !TARGET.equals(scope.kind())
                    || !request.expectedScopeKey().equals(scope.key())
                    || !request.expectedPersonPublicId().equals(
                    scope.personPublicId())) {
                throw unavailable("HRIS_NAV_V3_AUTHORITY_SELF_BINDING_INVALID");
            }
        } else if (decision.routeGrantRef() != null
                || decision.scopeBinding() != null
                || "ALLOWED".equals(decision.reasonCode())) {
            throw unavailable("HRIS_NAV_V3_DENIED_DECISION_OPEN");
        }
    }

    private static boolean validRequest(AuthorityRequest request) {
        return request != null && request.tenantId() > 0 && request.actorId() > 0
                && request.activeAccessMode() != null
                && request.expectedPersonPublicId() != null
                && request.expectedScopeKey() != null
                && request.expectedScopeKey().matches("^scope-[0-9a-f]{32}$")
                && request.expectedRegistryVersion() == REGISTRY_VERSION
                && REGISTRY_CHECKSUM.equals(request.expectedRegistryChecksum())
                && (request.expectedDecisionRevision() == null
                || decisionPattern(request.expectedDecisionRevision()));
    }

    private static boolean snapshotPattern(String value) {
        return value != null && value.matches("^snapshot-[0-9a-f]{64}$");
    }

    private static boolean decisionPattern(String value) {
        return value != null && value.matches("^decision-[0-9a-f]{64}$");
    }

    private static boolean authPattern(String value) {
        return value != null && value.matches("^auth-[0-9a-f]{64}$");
    }

    private static boolean policyPattern(String value) {
        return value != null && value.matches(
                "^policy-46-[1-9][0-9]*-[0-9a-f]{64}$");
    }

    private static boolean relationshipPattern(String value) {
        return value != null && value.matches("^relationship-[0-9a-f]{64}$");
    }

    private static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static PolicyRouteSpec route(
            String nodeId, String path, String routeContractKey) {
        return new PolicyRouteSpec(
                nodeId, path, routeContractKey, PROFILE, TARGET);
    }

    private static Map<String, PolicyRouteSpec> byNode() {
        LinkedHashMap<String, PolicyRouteSpec> result = new LinkedHashMap<>();
        ROUTES.forEach(value -> {
            if (result.putIfAbsent(value.nodeId(), value) != null) {
                throw new IllegalStateException("HRIS_NAV_V3_POLICY_NODE_DUPLICATE");
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, PolicyRouteSpec> byPath() {
        LinkedHashMap<String, PolicyRouteSpec> result = new LinkedHashMap<>();
        ROUTES.forEach(value -> {
            if (result.putIfAbsent(value.path(), value) != null) {
                throw new IllegalStateException("HRIS_NAV_V3_POLICY_PATH_DUPLICATE");
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    record PolicyRouteSpec(
            String nodeId,
            String path,
            String routeContractKey,
            String profileKey,
            String targetBinding) {
    }

    record SealedAuthority(
            AuthoritySnapshot snapshot,
            List<RouteDecision> admittedDecisions,
            Map<String, List<String>> admittedPaths) {
    }
}
