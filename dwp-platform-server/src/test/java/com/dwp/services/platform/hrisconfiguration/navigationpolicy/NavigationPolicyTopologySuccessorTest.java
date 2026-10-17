package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.AggregatePolicyDocument;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.RolloutMode;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationPolicyTopologySuccessorTest {

    @Test
    void appendsDedicatedControlledExportWithoutMutatingHistoricalCanonical() {
        assertThat(NavigationPolicyAggregateV2Canonical.NODE_IDS).hasSize(97);
        assertThat(NavigationPolicyAggregateV2Canonical.COMPATIBILITY_ROUTES)
                .containsEntry("/hr/data/exports", "BASE:dwp-governance");

        assertThat(NavigationPolicyTopologySuccessor.WORKBENCHES).hasSize(7);
        assertThat(NavigationPolicyTopologySuccessor.GROUP_IDS).hasSize(46);
        assertThat(NavigationPolicyTopologySuccessor.NODE_IDS).hasSize(98);
        assertThat(NavigationPolicyTopologySuccessor.CANONICAL_ROUTES)
                .containsEntry(
                        NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID,
                        "/hr/data/exports");
        assertThat(NavigationPolicyTopologySuccessor.COMPATIBILITY_ROUTES)
                .doesNotContainKey("/hr/data/exports");

        NavigationPolicyAggregateV2Canonical.Group settingsControl =
                NavigationPolicyTopologySuccessor.BY_WORKBENCH
                        .get("HRIS-WB-SETTINGS").groups().stream()
                        .filter(group -> group.id().equals("SET-08"))
                        .findFirst().orElseThrow();
        assertThat(settingsControl.nodeIds()).hasSize(6);
        assertThat(settingsControl.nodeIds().get(
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_DISPLAY_ORDER))
                .isEqualTo(NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID);
    }

    @Test
    void sealsSuccessorDigestAndAcceptsOnlyExactOldOrNewTopologyPairs() throws Exception {
        assertThat(sha256(NavigationPolicyTopologySuccessor.topologyHashMaterial()))
                .isEqualTo(NavigationPolicyTopologySuccessor.TOPOLOGY_SHA256);
        assertThat(NavigationPolicyTopologySuccessor.supportsTopology(
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256)).isTrue();
        assertThat(NavigationPolicyTopologySuccessor.supportsTopology(
                NavigationPolicyTopologySuccessor.TOPOLOGY_REVISION,
                NavigationPolicyTopologySuccessor.TOPOLOGY_SHA256)).isTrue();
        assertThat(NavigationPolicyTopologySuccessor.supportsTopology(
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyTopologySuccessor.TOPOLOGY_SHA256)).isFalse();
        assertThat(NavigationPolicyTopologySuccessor.supportsTopology(
                NavigationPolicyTopologySuccessor.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256)).isFalse();
    }

    @Test
    void sealsExactPageAuthorityAndInitialReadsWithoutReusingGovernancePermission() {
        NavigationPolicyTopologySuccessor.PageAuthorityContract authority =
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_PAGE_AUTHORITY;
        assertThat(authority.routeContractKey())
                .isEqualTo("route.hcm.management.controlled-export.page");
        assertThat(authority.capabilityContractKey())
                .isEqualTo("hcm.controlled-export.read");
        assertThat(authority.permission())
                .isEqualTo("ACTION.WORKFORCE_CONTROLLED_EXPORT:VIEW");
        assertThat(authority.profileKey()).isEqualTo("full-management");
        assertThat(authority.activeAccessModes()).containsExactly("NORMAL", "ELEVATED");
        assertThat(authority.targetBindingKinds())
                .containsExactly("TARGET_POPULATION", "OBJECT");
        assertThat(authority.predicatePolicyKeys())
                .containsExactly("predicate.hcm-export-population.v1");
        assertThat(authority.initialReads())
                .extracting(NavigationPolicyTopologySuccessor.FirstReadBinding::gatewayPath)
                .containsExactly(
                        "/api/people/v1/workforce/exports/datasets",
                        "/api/people/v1/workforce/exports");
        assertThat(authority.conditionalRead().gatewayPath())
                .isEqualTo("/api/people/v1/workforce/exports/{requestId}/attempts");
        assertThat(authority.permission())
                .isNotIn("DATA_GOVERNANCE_READ", "ADMIN.AUDIT_VIEW:VIEW");
    }

    @Test
    void historicalPolicyKeepsNewNodeDisabledAndLegalEntityCannotReenableIt() {
        AggregatePolicyDocument historical =
                NavigationPolicyAggregateV2Canonical.canonicalPolicy(
                        ScopeKind.TENANT, null, RolloutMode.ENFORCED);
        NavigationPolicyTopologySuccessor.validate(historical);
        assertThat(enabledNodes(historical))
                .doesNotContain(NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID);

        AggregatePolicyDocument successor =
                NavigationPolicyTopologySuccessor.canonicalPolicy(
                        ScopeKind.TENANT, null, RolloutMode.ENFORCED);
        assertThat(enabledNodes(successor))
                .contains(NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID);

        AggregatePolicyDocument overlay =
                NavigationPolicyTopologySuccessor.canonicalPolicy(
                        ScopeKind.LEGAL_ENTITY, UUID.randomUUID(), RolloutMode.ENFORCED);
        assertThatThrownBy(() -> NavigationPolicyTopologySuccessor
                .validateRestrictiveOverlay(historical, overlay))
                .hasMessageContaining("OVERLAY_REENABLES_MEMBER");
    }

    private static List<String> enabledNodes(AggregatePolicyDocument policy) {
        return policy.workbenches().stream()
                .flatMap(workbench -> workbench.enabledNodeIds().stream()).toList();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
