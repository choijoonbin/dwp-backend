package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;

final class NavigationPolicyCanonical {

    static final String WORKBENCH_ID = "HRIS-WB-HR-OPERATIONS";

    static final Map<String, List<String>> GROUP_MEMBERS = canonicalGroups();
    static final Set<String> GROUP_IDS = Set.copyOf(GROUP_MEMBERS.keySet());
    static final Set<String> NODE_IDS = GROUP_MEMBERS.values().stream()
            .flatMap(List::stream)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private NavigationPolicyCanonical() {
    }

    static void validate(PolicyDocument policy) {
        if (policy == null || !WORKBENCH_ID.equals(policy.workbenchId())) {
            throw new IllegalArgumentException("HRIS_NAV_POLICY_WORKBENCH_INVALID");
        }
        if (policy.scopeKind() == ScopeKind.TENANT && policy.legalEntityPublicId() != null
                || policy.scopeKind() == ScopeKind.LEGAL_ENTITY
                && policy.legalEntityPublicId() == null) {
            throw new IllegalArgumentException("HRIS_NAV_POLICY_SCOPE_INVALID");
        }
        if (policy.groups().size() != 7) {
            throw new IllegalArgumentException("HRIS_NAV_POLICY_CANONICAL_GROUPS_REQUIRED");
        }
        Set<String> groupIds = policy.groups().stream().map(GroupPolicy::groupId)
                .collect(java.util.stream.Collectors.toSet());
        if (groupIds.size() != 7 || !groupIds.equals(GROUP_IDS)) {
            throw new IllegalArgumentException("HRIS_NAV_POLICY_CANONICAL_GROUPS_INVALID");
        }
        requireDistinctOrders(policy.groups().stream().map(GroupPolicy::displayOrder).toList(),
                "HRIS_NAV_POLICY_GROUP_ORDER_DUPLICATE");
        for (GroupPolicy group : policy.groups()) {
            List<String> canonical = GROUP_MEMBERS.get(group.groupId());
            Set<String> members = group.nodes().stream().map(NodePolicy::nodeId)
                    .collect(java.util.stream.Collectors.toSet());
            if (members.size() != canonical.size()
                    || !members.equals(Set.copyOf(canonical))) {
                throw new IllegalArgumentException("HRIS_NAV_POLICY_CANONICAL_MEMBERS_INVALID");
            }
            requireDistinctOrders(group.nodes().stream().map(NodePolicy::displayOrder).toList(),
                    "HRIS_NAV_POLICY_NODE_ORDER_DUPLICATE");
            if (!members.contains(group.defaultNodeId())) {
                throw new IllegalArgumentException("HRIS_NAV_POLICY_DEFAULT_NODE_INVALID");
            }
            if (group.enabled() && group.nodes().stream().noneMatch(NodePolicy::enabled)) {
                throw new IllegalArgumentException("HRIS_NAV_POLICY_ENABLED_GROUP_EMPTY");
            }
            if (group.enabled() && group.nodes().stream()
                    .filter(node -> node.nodeId().equals(group.defaultNodeId()))
                    .noneMatch(NodePolicy::enabled)) {
                throw new IllegalArgumentException("HRIS_NAV_POLICY_DEFAULT_NODE_DISABLED");
            }
        }
        long memberCount = policy.groups().stream().mapToLong(group -> group.nodes().size()).sum();
        if (memberCount != 17) {
            throw new IllegalArgumentException("HRIS_NAV_POLICY_CANONICAL_MEMBER_COUNT_INVALID");
        }
    }

    static void validateRestrictiveOverlay(PolicyDocument tenant, PolicyDocument overlay) {
        validate(tenant);
        validate(overlay);
        if (overlay.scopeKind() != ScopeKind.LEGAL_ENTITY) {
            throw new IllegalArgumentException("HRIS_NAV_POLICY_OVERLAY_SCOPE_INVALID");
        }
        if (overlay.rolloutMode() != tenant.rolloutMode()) {
            throw new IllegalArgumentException("HRIS_NAV_POLICY_OVERLAY_ROLLOUT_MISMATCH");
        }
        Map<String, GroupPolicy> tenantGroups = tenant.groups().stream()
                .collect(java.util.stream.Collectors.toMap(GroupPolicy::groupId, group -> group));
        for (GroupPolicy overlayGroup : overlay.groups()) {
            GroupPolicy tenantGroup = tenantGroups.get(overlayGroup.groupId());
            if (overlayGroup.enabled() && !tenantGroup.enabled()) {
                throw new IllegalArgumentException("HRIS_NAV_POLICY_OVERLAY_REENABLES_GROUP");
            }
            Map<String, NodePolicy> tenantNodes = tenantGroup.nodes().stream()
                    .collect(java.util.stream.Collectors.toMap(NodePolicy::nodeId, node -> node));
            for (NodePolicy overlayNode : overlayGroup.nodes()) {
                if (overlayNode.enabled() && !tenantNodes.get(overlayNode.nodeId()).enabled()) {
                    throw new IllegalArgumentException("HRIS_NAV_POLICY_OVERLAY_REENABLES_NODE");
                }
            }
        }
    }

    private static void requireDistinctOrders(List<Integer> orders, String error) {
        if (Set.copyOf(orders).size() != orders.size()) throw new IllegalArgumentException(error);
    }

    private static Map<String, List<String>> canonicalGroups() {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        result.put("HROPS-01", List.of("BASE:hr-operations-home", "BASE:hr-people"));
        result.put("HROPS-02", List.of(
                "BASE:hr-employment-assignments", "BASE:hr-appointments",
                "BASE:hr-contracts-compensation-basis"));
        result.put("HROPS-03", List.of(
                "BASE:hr-organization-positions", "MODERN:hcm.operations.workforce-planning"));
        result.put("HROPS-04", List.of(
                "BASE:hr-employee-change-requests", "BASE:hr-certificates-documents",
                "MODERN:hcm.operations.hr-services", "MODERN:hcm.operations.benefits"));
        result.put("HROPS-05", List.of(
                "BASE:hr-exit-return", "MODERN:hcm.operations.onboarding"));
        result.put("HROPS-06", List.of(
                "MODERN:hcm.operations.recruiting",
                "MODERN:hcm.operations.contingent-workforce"));
        result.put("HROPS-07", List.of(
                "BASE:hr-data-quality", "MODERN:hcm.operations.people-analytics"));
        return Collections.unmodifiableMap(result);
    }
}
