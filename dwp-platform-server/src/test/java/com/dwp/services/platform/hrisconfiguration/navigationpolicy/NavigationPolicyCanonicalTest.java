package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationPolicyCanonicalTest {

    @Test
    void acceptsExactlySevenGroupsAndSeventeenCanonicalMembers() {
        NavigationPolicyCanonical.validate(policy(ScopeKind.TENANT, null, true));
    }

    @Test
    void rejectsUnknownMembersAndOverlayReenable() {
        PolicyDocument valid = policy(ScopeKind.TENANT, null, true);
        List<GroupPolicy> hostileGroups = new ArrayList<>(valid.groups());
        GroupPolicy first = hostileGroups.getFirst();
        List<NodePolicy> hostileNodes = new ArrayList<>(first.nodes());
        hostileNodes.set(0, new NodePolicy("HOSTILE:invented-authority", 0, true));
        hostileGroups.set(0, new GroupPolicy(
                first.groupId(), first.displayOrder(), true,
                "HOSTILE:invented-authority", hostileNodes));
        assertThatThrownBy(() -> NavigationPolicyCanonical.validate(new PolicyDocument(
                ScopeKind.TENANT, null, RolloutMode.ENFORCED,
                NavigationPolicyCanonical.WORKBENCH_ID, hostileGroups)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CANONICAL_MEMBERS_INVALID");

        PolicyDocument restrictiveTenant = policy(ScopeKind.TENANT, null, false);
        PolicyDocument overlay = policy(
                ScopeKind.LEGAL_ENTITY,
                UUID.fromString("00000000-0000-0000-0000-000000000042"), true);
        assertThatThrownBy(() -> NavigationPolicyCanonical.validateRestrictiveOverlay(
                restrictiveTenant, overlay))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REENABLES_NODE");
    }

    static PolicyDocument policy(
            ScopeKind scopeKind, UUID legalEntityPublicId, boolean firstNodeEnabled) {
        List<GroupPolicy> groups = new ArrayList<>();
        int groupOrder = 0;
        for (var entry : NavigationPolicyCanonical.GROUP_MEMBERS.entrySet()) {
            List<NodePolicy> nodes = new ArrayList<>();
            int nodeOrder = 0;
            for (String nodeId : entry.getValue()) {
                boolean enabled = firstNodeEnabled || groupOrder != 0 || nodeOrder != 0;
                nodes.add(new NodePolicy(nodeId, nodeOrder, enabled));
                nodeOrder++;
            }
            String defaultNode = nodes.stream().filter(NodePolicy::enabled)
                    .findFirst().orElseThrow().nodeId();
            groups.add(new GroupPolicy(
                    entry.getKey(), groupOrder, true, defaultNode, nodes));
            groupOrder++;
        }
        return new PolicyDocument(
                scopeKind, legalEntityPublicId, RolloutMode.ENFORCED,
                NavigationPolicyCanonical.WORKBENCH_ID, groups);
    }
}
