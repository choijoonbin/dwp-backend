package com.dwp.services.platform.hrisconfiguration.explorer;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;
import static org.assertj.core.api.Assertions.assertThat;

class HrisExplorerCatalogTest {

    @Test
    void canonicalRegistryIsExactlySeventySixPlusTwentyTwoUniqueIdentities() {
        assertThat(HrisExplorerCatalog.nodes()).hasSize(98);
        assertThat(HrisExplorerCatalog.nodes())
                .filteredOn(node -> node.sourceSet().equals("BASE76")).hasSize(76);
        assertThat(HrisExplorerCatalog.nodes())
                .filteredOn(node -> node.sourceSet().equals("MODERN22")).hasSize(22);
        assertThat(HrisExplorerCatalog.nodes())
                .extracting(HrisExplorerCatalog.CatalogNode::routeKey)
                .doesNotHaveDuplicates()
                .allSatisfy(key -> assertThat(key).matches("^[a-z][a-z0-9.-]{2,95}$"));
        assertThat(HrisExplorerCatalog.nodes())
                .extracting(HrisExplorerCatalog.CatalogNode::labelKey)
                .doesNotHaveDuplicates()
                .allSatisfy(key -> assertThat(key).startsWith("hris.explorer.routes."));
        HrisExplorerCatalog.nodes().forEach(node -> {
            String normalized = node.sourceNodeKey().replace('.', '-');
            assertThat(node.routeKey()).isEqualTo("hris.explorer." + normalized);
            assertThat(node.labelKey()).isEqualTo("hris.explorer.routes." + normalized);
        });
    }

    @Test
    void workbenchModuleAndLiteralTargetClosureMatchesTheSealedIa() {
        Map<Workbench, Long> workbenches = HrisExplorerCatalog.nodes().stream()
                .collect(Collectors.groupingBy(
                        HrisExplorerCatalog.CatalogNode::workbench,
                        Collectors.counting()));
        assertThat(workbenches).containsExactlyInAnyOrderEntriesOf(Map.of(
                Workbench.MY_HR, 15L,
                Workbench.TEAM, 7L,
                Workbench.HR_OPERATIONS, 17L,
                Workbench.TIME, 11L,
                Workbench.PAYROLL, 14L,
                Workbench.PERFORMANCE, 12L,
                Workbench.SETTINGS, 22L));
        assertThat(HrisExplorerCatalog.nodes())
                .filteredOn(node -> node.target().kind() == TargetKind.DWP_CONTROL)
                .hasSize(6)
                .extracting(node -> node.target().path())
                .containsExactlyInAnyOrder(
                        "/admin/experience/branding?product=HCM",
                        "/admin/identity/app-governance?product=HCM",
                        "/admin/platform/catalog?product=HCM",
                        "/admin/integrations/productivity?product=HCM",
                        "/admin/governance/audit-overview?product=HCM",
                        "/admin/platform/registry?namespace=hris-extension");
        assertThat(HrisExplorerCatalog.nodes())
                .filteredOn(node -> node.target().kind() == TargetKind.HRIS_INTERNAL)
                .allSatisfy(node -> assertThat(node.target().path()).matches("^/hr(?:/|$).*$"));
    }

    @Test
    void v43AdapterExposesOnlyFourteenCapabilityFormPageDecisions() {
        assertThat(HrisExplorerV43RouteAdapter.deployedRouteKeys()).hasSize(14);
        assertThat(HrisExplorerV43RouteAdapter.deployedRouteKeys())
                .doesNotContain(
                        "hris.explorer.my-hr-home",
                        "hris.explorer.my-time",
                        "hris.explorer.my-pay-tax",
                        "hris.explorer.my-goals-performance");
        assertThat(HrisExplorerV43RouteAdapter.deployedRouteKeys().stream()
                .map(HrisExplorerV43RouteAdapter::authorization))
                .allSatisfy(authorization -> {
                    assertThat(authorization.capabilityMode()).isEqualTo(CapabilityMode.SINGLE);
                    assertThat(authorization.capabilityKeys()).hasSize(1);
                    assertThat(authorization.routeContractKey())
                            .startsWith("route.hcm.").endsWith(".page");
                });
        assertThat(HrisExplorerV43RouteAdapter.deployedRouteKeys().stream()
                .filter(key -> HrisExplorerCatalog.node(key).target().kind()
                        == TargetKind.DWP_CONTROL))
                .containsExactly(HrisExplorerV43RouteAdapter.OPERATIONAL_DWP_CONTROL_ROUTE_KEY);
    }
}
