package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.AggregatePolicyDocument;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.WorkbenchPolicy;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;

/**
 * Append-only R7-C1 topology successor. The generated IA and the V329 canonical remain historical
 * evidence; runtime composition adds one dedicated People-owned controlled-export node and retires
 * only the erroneous route ownership alias.
 */
final class NavigationPolicyTopologySuccessor {

    static final String TOPOLOGY_REVISION = "HRIS-IA-99-WB7-G46-N98-R6";
    static final String TOPOLOGY_SHA256 =
            "4ac4b5411d804888ff1a4bd0373c70419b698a0abe43d0de43bd646966cfe166";
    static final String CONTROLLED_EXPORT_NODE_ID =
            "MODERN:hcm.management.controlled-export";
    static final String CONTROLLED_EXPORT_ROUTE = "/hr/data/exports";
    static final String CONTROLLED_EXPORT_WORKBENCH_ID = "HRIS-WB-SETTINGS";
    static final String CONTROLLED_EXPORT_GROUP_ID = "SET-08";
    static final int CONTROLLED_EXPORT_DISPLAY_ORDER = 5;

    static final List<NavigationPolicyAggregateV2Canonical.Workbench> WORKBENCHES =
            successorWorkbenches();
    static final Map<String, NavigationPolicyAggregateV2Canonical.Workbench> BY_WORKBENCH =
            indexWorkbenches();
    static final Set<String> WORKBENCH_IDS = Set.copyOf(BY_WORKBENCH.keySet());
    static final Set<String> GROUP_IDS = allGroups();
    static final Set<String> NODE_IDS = allNodes();
    static final Map<String, String> CANONICAL_ROUTES = successorCanonicalRoutes();
    static final Map<String, String> COMPATIBILITY_ROUTES = successorCompatibilityRoutes();
    static final Set<String> EXCLUDED_PATHS = NavigationPolicyAggregateV2Canonical.EXCLUDED_PATHS;

    static final PageAuthorityContract CONTROLLED_EXPORT_PAGE_AUTHORITY =
            new PageAuthorityContract(
                    "route.hcm.management.controlled-export.page",
                    "hcm.controlled-export.read",
                    "ACTION.WORKFORCE_CONTROLLED_EXPORT:VIEW",
                    "full-management",
                    List.of("NORMAL", "ELEVATED"),
                    List.of("TARGET_POPULATION", "OBJECT"),
                    List.of("predicate.hcm-export-population.v1"),
                    List.of(
                            new FirstReadBinding(
                                    "GET", "/api/people/v1/workforce/exports/datasets",
                                    "people", "/v1/workforce/exports/datasets"),
                            new FirstReadBinding(
                                    "GET", "/api/people/v1/workforce/exports",
                                    "people", "/v1/workforce/exports")),
                    new FirstReadBinding(
                            "GET", "/api/people/v1/workforce/exports/{requestId}/attempts",
                            "people", "/v1/workforce/exports/{requestId}/attempts"));

    record FirstReadBinding(
            String method, String gatewayPath, String serviceKey, String servicePath) {
    }

    record PageAuthorityContract(
            String routeContractKey,
            String capabilityContractKey,
            String permission,
            String profileKey,
            List<String> activeAccessModes,
            List<String> targetBindingKinds,
            List<String> predicatePolicyKeys,
            List<FirstReadBinding> initialReads,
            FirstReadBinding conditionalRead) {
        PageAuthorityContract {
            activeAccessModes = List.copyOf(activeAccessModes);
            targetBindingKinds = List.copyOf(targetBindingKinds);
            predicatePolicyKeys = List.copyOf(predicatePolicyKeys);
            initialReads = List.copyOf(initialReads);
        }
    }

    static {
        if (WORKBENCHES.size() != 7 || GROUP_IDS.size() != 46 || NODE_IDS.size() != 98
                || !CANONICAL_ROUTES.keySet().equals(NODE_IDS)
                || COMPATIBILITY_ROUTES.containsKey(CONTROLLED_EXPORT_ROUTE)
                || !CONTROLLED_EXPORT_ROUTE.equals(
                CANONICAL_ROUTES.get(CONTROLLED_EXPORT_NODE_ID))) {
            throw new IllegalStateException("HRIS_NAV_TOPOLOGY_SUCCESSOR_INVALID");
        }
        if (!TOPOLOGY_SHA256.equals(sha256(topologyHashMaterial()))) {
            throw new IllegalStateException("HRIS_NAV_TOPOLOGY_SUCCESSOR_DIGEST_INVALID");
        }
    }

    private NavigationPolicyTopologySuccessor() {
    }

    static String topologyHashMaterial() {
        return String.join("\n",
                "schema=hris-navigation-topology-successor.v1",
                "revision=" + TOPOLOGY_REVISION,
                "predecessorRevision="
                        + NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                "predecessorSha256="
                        + NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256,
                "workbenchCount=7",
                "groupCount=46",
                "descendantNodeCount=98",
                "append.workbenchId=" + CONTROLLED_EXPORT_WORKBENCH_ID,
                "append.groupId=" + CONTROLLED_EXPORT_GROUP_ID,
                "append.displayOrder=" + CONTROLLED_EXPORT_DISPLAY_ORDER,
                "append.nodeId=" + CONTROLLED_EXPORT_NODE_ID,
                "append.ownerSession=HRIS-HRM",
                "append.module=HRM",
                "append.runtimeOwner=dwp-people-server",
                "append.inventoryGroup=DWP_CONTROL",
                "append.navigationSurface=SETTINGS",
                "append.canonicalRoute=" + CONTROLLED_EXPORT_ROUTE,
                "append.label.ko=통제형 반출",
                "append.label.en=Governed exports",
                "retiredAlias.nodeId=BASE:dwp-governance",
                "retiredAlias.path=" + CONTROLLED_EXPORT_ROUTE);
    }

    static boolean supportsTopology(String revision, String sha256) {
        return NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION.equals(revision)
                && NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256.equals(sha256)
                || TOPOLOGY_REVISION.equals(revision) && TOPOLOGY_SHA256.equals(sha256);
    }

    static void validate(AggregatePolicyDocument policy) {
        if (policy == null || !NavigationPolicyAggregateV2Dtos.SCHEMA_VERSION.equals(
                policy.schemaVersion())) {
            throw new IllegalArgumentException("HRIS_NAV_V2_SCHEMA_INVALID");
        }
        if (policy.scopeKind() == ScopeKind.TENANT && policy.legalEntityPublicId() != null
                || policy.scopeKind() == ScopeKind.LEGAL_ENTITY
                && policy.legalEntityPublicId() == null) {
            throw new IllegalArgumentException("HRIS_NAV_V2_SCOPE_INVALID");
        }
        if (policy.workbenches().size() != 7) {
            throw new IllegalArgumentException("HRIS_NAV_V2_SEVEN_WORKBENCHES_REQUIRED");
        }
        Map<String, WorkbenchPolicy> configured = uniquePolicies(policy);
        if (!configured.keySet().equals(WORKBENCH_IDS)) {
            throw new IllegalArgumentException("HRIS_NAV_V2_WORKBENCH_SET_INVALID");
        }
        for (NavigationPolicyAggregateV2Canonical.Workbench canonical : WORKBENCHES) {
            WorkbenchPolicy selected = configured.get(canonical.id());
            Set<String> canonicalGroups = canonical.groups().stream()
                    .map(NavigationPolicyAggregateV2Canonical.Group::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> enabledGroups = distinct(
                    selected.enabledGroupIds(), "HRIS_NAV_V2_GROUP_DUPLICATE");
            if (!canonicalGroups.containsAll(enabledGroups)) {
                throw new IllegalArgumentException("HRIS_NAV_V2_GROUP_NOT_CANONICAL");
            }
            Set<String> canonicalNodes = canonical.groups().stream()
                    .flatMap(group -> group.nodeIds().stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> enabledNodes = distinct(
                    selected.enabledNodeIds(), "HRIS_NAV_V2_NODE_DUPLICATE");
            if (!canonicalNodes.containsAll(enabledNodes)) {
                throw new IllegalArgumentException("HRIS_NAV_V2_NODE_NOT_CANONICAL");
            }
            for (NavigationPolicyAggregateV2Canonical.Group group : canonical.groups()) {
                if (!enabledGroups.contains(group.id()) && group.nodeIds().stream()
                        .anyMatch(enabledNodes::contains)) {
                    throw new IllegalArgumentException("HRIS_NAV_V2_DISABLED_GROUP_HAS_NODE");
                }
                if (enabledGroups.contains(group.id()) && group.nodeIds().stream()
                        .noneMatch(enabledNodes::contains)) {
                    throw new IllegalArgumentException("HRIS_NAV_V2_ENABLED_GROUP_EMPTY");
                }
            }
            if (!selected.enabled()
                    && (!enabledGroups.isEmpty() || !enabledNodes.isEmpty())) {
                throw new IllegalArgumentException(
                        "HRIS_NAV_V2_DISABLED_WORKBENCH_HAS_MEMBERS");
            }
            if (selected.enabled() && enabledGroups.isEmpty()) {
                throw new IllegalArgumentException("HRIS_NAV_V2_ENABLED_WORKBENCH_EMPTY");
            }
        }
    }

    static void validateRestrictiveOverlay(
            AggregatePolicyDocument tenant, AggregatePolicyDocument overlay) {
        validate(tenant);
        validate(overlay);
        if (tenant.scopeKind() != ScopeKind.TENANT
                || overlay.scopeKind() != ScopeKind.LEGAL_ENTITY) {
            throw new IllegalArgumentException("HRIS_NAV_V2_OVERLAY_SCOPE_INVALID");
        }
        if (tenant.rolloutMode() != overlay.rolloutMode()) {
            throw new IllegalArgumentException("HRIS_NAV_V2_OVERLAY_ROLLOUT_MISMATCH");
        }
        Map<String, WorkbenchPolicy> baseline = uniquePolicies(tenant);
        for (WorkbenchPolicy restricted : overlay.workbenches()) {
            WorkbenchPolicy allowed = baseline.get(restricted.workbenchId());
            if (restricted.enabled() && !allowed.enabled()
                    || !Set.copyOf(allowed.enabledGroupIds()).containsAll(
                    restricted.enabledGroupIds())
                    || !Set.copyOf(allowed.enabledNodeIds()).containsAll(
                    restricted.enabledNodeIds())) {
                throw new IllegalArgumentException("HRIS_NAV_V2_OVERLAY_REENABLES_MEMBER");
            }
        }
    }

    static AggregatePolicyDocument canonicalPolicy(
            ScopeKind scopeKind,
            java.util.UUID legalEntityPublicId,
            NavigationPolicyDtos.RolloutMode rolloutMode) {
        return new AggregatePolicyDocument(
                NavigationPolicyAggregateV2Dtos.SCHEMA_VERSION,
                scopeKind,
                legalEntityPublicId,
                rolloutMode,
                WORKBENCHES.stream().map(workbench -> new WorkbenchPolicy(
                        workbench.id(), true,
                        workbench.groups().stream()
                                .map(NavigationPolicyAggregateV2Canonical.Group::id).toList(),
                        workbench.groups().stream()
                                .flatMap(group -> group.nodeIds().stream()).toList()))
                        .toList());
    }

    private static List<NavigationPolicyAggregateV2Canonical.Workbench>
    successorWorkbenches() {
        return NavigationPolicyAggregateV2Canonical.WORKBENCHES.stream().map(workbench -> {
            if (!CONTROLLED_EXPORT_WORKBENCH_ID.equals(workbench.id())) return workbench;
            List<NavigationPolicyAggregateV2Canonical.Group> groups =
                    workbench.groups().stream().map(group -> {
                        if (!CONTROLLED_EXPORT_GROUP_ID.equals(group.id())) return group;
                        ArrayList<String> nodes = new ArrayList<>(group.nodeIds());
                        nodes.add(CONTROLLED_EXPORT_NODE_ID);
                        return new NavigationPolicyAggregateV2Canonical.Group(
                                group.id(), group.defaultNodeId(), nodes);
                    }).toList();
            return new NavigationPolicyAggregateV2Canonical.Workbench(
                    workbench.id(), workbench.displayOrder(),
                    workbench.defaultNodeId(), groups);
        }).toList();
    }

    private static Map<String, NavigationPolicyAggregateV2Canonical.Workbench>
    indexWorkbenches() {
        LinkedHashMap<String, NavigationPolicyAggregateV2Canonical.Workbench> result =
                new LinkedHashMap<>();
        WORKBENCHES.forEach(workbench -> result.put(workbench.id(), workbench));
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> allGroups() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        WORKBENCHES.forEach(workbench -> workbench.groups().forEach(
                group -> result.add(group.id())));
        return Set.copyOf(result);
    }

    private static Set<String> allNodes() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        WORKBENCHES.forEach(workbench -> workbench.groups().forEach(
                group -> result.addAll(group.nodeIds())));
        return Set.copyOf(result);
    }

    private static Map<String, String> successorCanonicalRoutes() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(
                NavigationPolicyAggregateV2Canonical.CANONICAL_ROUTES);
        if (result.putIfAbsent(CONTROLLED_EXPORT_NODE_ID, CONTROLLED_EXPORT_ROUTE) != null) {
            throw new IllegalStateException("HRIS_NAV_TOPOLOGY_SUCCESSOR_NODE_COLLISION");
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> successorCompatibilityRoutes() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(
                NavigationPolicyAggregateV2Canonical.COMPATIBILITY_ROUTES);
        if (!"BASE:dwp-governance".equals(result.remove(CONTROLLED_EXPORT_ROUTE))) {
            throw new IllegalStateException("HRIS_NAV_TOPOLOGY_SUCCESSOR_ALIAS_DRIFT");
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, WorkbenchPolicy> uniquePolicies(
            AggregatePolicyDocument policy) {
        LinkedHashMap<String, WorkbenchPolicy> result = new LinkedHashMap<>();
        for (WorkbenchPolicy workbench : policy.workbenches()) {
            if (workbench == null || workbench.workbenchId() == null
                    || result.putIfAbsent(workbench.workbenchId(), workbench) != null) {
                throw new IllegalArgumentException("HRIS_NAV_V2_WORKBENCH_DUPLICATE");
            }
        }
        return Map.copyOf(result);
    }

    private static Set<String> distinct(List<String> values, String error) {
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(error);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>(values);
        if (result.size() != values.size()) throw new IllegalArgumentException(error);
        return Set.copyOf(result);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
