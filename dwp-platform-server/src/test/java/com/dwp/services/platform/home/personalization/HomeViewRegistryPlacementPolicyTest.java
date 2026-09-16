package com.dwp.services.platform.home.personalization;

import com.dwp.services.platform.home.preference.HomeLayoutPolicy;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeViewRegistryPlacementPolicyTest {
    @Mock private WidgetCatalogService catalog;
    @Mock private HomeLayoutPolicy layoutPolicy;

    @Test
    void forwardsTheTrustedTenantAndAuthorityProjectionWithoutActivatingRegistryWrites() {
        var source = new WidgetCatalogService.PlacementWriteContract(
                true, "medium", Set.of("medium", "large"),
                "standard", Set.of("standard", "tall"));
        when(catalog.availablePlacementContracts(
                7L, "workspace-home", "APP.WORK:VIEW", "MEMBER", "team:blue"))
                .thenReturn(Map.of("partner.insights", source));
        var policy = new HomeViewRegistryPlacementPolicy(catalog, layoutPolicy);

        var result = policy.contracts(
                7L, "workspace-home",
                HomeViewRegistryPlacementPolicy.Authority.of(
                        "APP.WORK:VIEW", "MEMBER", "team:blue"));

        assertThat(result).containsOnlyKeys("partner.insights");
        assertThat(result.get("partner.insights").fixedVisibility()).isNull();
        assertThat(result.get("partner.insights").allowedSizes())
                .containsExactlyInAnyOrder("medium", "large");
        verify(catalog).availablePlacementContracts(
                7L, "workspace-home", "APP.WORK:VIEW", "MEMBER", "team:blue");
    }
}
