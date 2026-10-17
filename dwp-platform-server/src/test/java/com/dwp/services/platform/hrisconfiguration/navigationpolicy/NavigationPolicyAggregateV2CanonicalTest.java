package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.AggregatePolicyDocument;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.WorkbenchPolicy;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.RolloutMode;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationPolicyAggregateV2CanonicalTest {

    @Test
    void sealsExactSevenWorkbenchFortySixGroupNinetySevenNodeTopology() {
        assertThat(NavigationPolicyAggregateV2Canonical.WORKBENCHES)
                .extracting(NavigationPolicyAggregateV2Canonical.Workbench::id)
                .containsExactly(
                        "HRIS-WB-MY-HR", "HRIS-WB-TEAM",
                        "HRIS-WB-HR-OPERATIONS", "HRIS-WB-TIME",
                        "HRIS-WB-PAYROLL", "HRIS-WB-PERFORMANCE",
                        "HRIS-WB-SETTINGS");
        assertThat(NavigationPolicyAggregateV2Canonical.WORKBENCHES)
                .extracting(workbench -> workbench.groups().size())
                .containsExactly(6, 5, 7, 6, 7, 7, 8);
        assertThat(NavigationPolicyAggregateV2Canonical.GROUP_IDS).hasSize(46);
        assertThat(NavigationPolicyAggregateV2Canonical.NODE_IDS).hasSize(97);
        assertThat(NavigationPolicyAggregateV2Canonical.CANONICAL_ROUTES)
                .hasSize(97)
                .doesNotContainValue("/hr/explore")
                .doesNotContainValue("/hr/benefits")
                .doesNotContainValue("/hr/operations/benefits");
        assertThat(NavigationPolicyAggregateV2Canonical.COMPATIBILITY_ROUTES)
                .hasSize(17)
                .containsEntry("/hr/me", "BASE:my-hr-profile")
                .containsEntry("/hr/team", "BASE:team-home")
                .containsEntry("/hr/operations", "BASE:hr-operations-home")
                .containsEntry("/hr/operations/assignments",
                        "BASE:hr-employment-assignments")
                .containsEntry("/hr/design/organization",
                        "BASE:hr-organization-positions")
                .containsEntry("/hr/operations/pay", "BASE:pay-command-center")
                .doesNotContainKeys("/hr/data/integrations", "/hr/explore",
                        "/hr/benefits", "/hr/operations/benefits");
        assertThat(NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256)
                .isEqualTo("ab506a2bb2f80c8ab81a4b9d575374d6bda83b312d11dce07495d4812bdf4686");
        assertThat(NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256)
                .isEqualTo("f0b1ebb316e4d5944aac90002e144f25d8c1318216b6733e674cdc8c5042872b");
        assertThat(NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256)
                .isEqualTo("584cc32f845720ad06fb1bdcea7c967026c0e3a648f70e0215fd8a95a6b055bd");
        assertThat(NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION)
                .isEqualTo("HRIS-IA-98-WB7-G46-N97-R5");
        assertThat(NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256)
                .isEqualTo("4d27b3b8958d591968ed42e7ccd6387664fff66a364adfc83a8a5b7f223a6d8a");
        assertThat(sha256(NavigationPolicyAggregateV2Canonical.topologyHashMaterial()))
                .isEqualTo(NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256);
    }

    @Test
    void policyCannotInventWorkbenchGroupOrNode() {
        AggregatePolicyDocument canonical = policy(ScopeKind.TENANT, null);
        NavigationPolicyAggregateV2Canonical.validate(canonical);

        List<WorkbenchPolicy> inventedWorkbench = new ArrayList<>(canonical.workbenches());
        inventedWorkbench.set(0, new WorkbenchPolicy(
                "HRIS-WB-INVENTED", true,
                inventedWorkbench.getFirst().enabledGroupIds(),
                inventedWorkbench.getFirst().enabledNodeIds()));
        assertThatThrownBy(() -> NavigationPolicyAggregateV2Canonical.validate(
                copy(canonical, inventedWorkbench)))
                .hasMessageContaining("WORKBENCH_SET_INVALID");

        List<WorkbenchPolicy> inventedNode = new ArrayList<>(canonical.workbenches());
        WorkbenchPolicy first = inventedNode.getFirst();
        List<String> hostileNodes = new ArrayList<>(first.enabledNodeIds());
        hostileNodes.add("BASE:invented");
        inventedNode.set(0, new WorkbenchPolicy(
                first.workbenchId(), true, first.enabledGroupIds(), hostileNodes));
        assertThatThrownBy(() -> NavigationPolicyAggregateV2Canonical.validate(
                copy(canonical, inventedNode)))
                .hasMessageContaining("NODE_NOT_CANONICAL");
    }

    @Test
    void listOrderCannotChangeServerOwnedCanonicalOrder() {
        AggregatePolicyDocument canonical = policy(ScopeKind.TENANT, null);
        List<WorkbenchPolicy> reversed = new ArrayList<>(canonical.workbenches());
        Collections.reverse(reversed);

        AggregatePolicyDocument input = copy(canonical, reversed);

        NavigationPolicyAggregateV2Canonical.validate(input);
        assertThat(NavigationPolicyAggregateV2Canonical.WORKBENCHES)
                .extracting(NavigationPolicyAggregateV2Canonical.Workbench::displayOrder)
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void legalOverlayCanOnlyRestrictTenantBaseline() {
        AggregatePolicyDocument tenant = policy(ScopeKind.TENANT, null);
        UUID legalEntity = UUID.randomUUID();
        AggregatePolicyDocument overlay = disableNode(
                policy(ScopeKind.LEGAL_ENTITY, legalEntity), "BASE:my-leave");
        NavigationPolicyAggregateV2Canonical.validateRestrictiveOverlay(tenant, overlay);

        AggregatePolicyDocument restrictedTenant = disableNode(
                tenant, "BASE:my-leave");
        assertThatThrownBy(() -> NavigationPolicyAggregateV2Canonical
                .validateRestrictiveOverlay(restrictedTenant,
                        policy(ScopeKind.LEGAL_ENTITY, legalEntity)))
                .hasMessageContaining("OVERLAY_REENABLES_MEMBER");
    }

    static AggregatePolicyDocument policy(ScopeKind scopeKind, UUID legalEntity) {
        return NavigationPolicyAggregateV2Canonical.canonicalPolicy(
                scopeKind, legalEntity, RolloutMode.ENFORCED);
    }

    static AggregatePolicyDocument disableNode(
            AggregatePolicyDocument source, String nodeId) {
        return copy(source, source.workbenches().stream().map(workbench ->
                workbench.enabledNodeIds().contains(nodeId)
                        ? new WorkbenchPolicy(
                        workbench.workbenchId(), workbench.enabled(),
                        workbench.enabledGroupIds(), workbench.enabledNodeIds().stream()
                        .filter(node -> !node.equals(nodeId)).toList())
                        : workbench).toList());
    }

    private static AggregatePolicyDocument copy(
            AggregatePolicyDocument source, List<WorkbenchPolicy> workbenches) {
        return new AggregatePolicyDocument(
                source.schemaVersion(), source.scopeKind(),
                source.legalEntityPublicId(), source.rolloutMode(), workbenches);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
