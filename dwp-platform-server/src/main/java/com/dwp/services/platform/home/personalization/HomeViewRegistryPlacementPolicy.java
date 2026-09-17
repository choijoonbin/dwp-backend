package com.dwp.services.platform.home.personalization;

import com.dwp.services.platform.home.preference.HomeLayoutPolicy;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/** Maps the evaluated member catalog into the bounded legacy Home View placement contract. */
@Component
final class HomeViewRegistryPlacementPolicy {
    private final WidgetCatalogService catalog;
    private final HomeLayoutPolicy layoutPolicy;

    HomeViewRegistryPlacementPolicy(
            WidgetCatalogService catalog, HomeLayoutPolicy layoutPolicy) {
        this.catalog = catalog;
        this.layoutPolicy = layoutPolicy;
    }

    Map<String, HomeLayoutPolicy.RegistryWidgetContract> contracts(
            Long tenantId, String surfaceKey, String modeKey, Authority authority) {
        Authority trusted = authority == null ? Authority.NONE : authority;
        return catalog.availablePlacementContracts(
                        tenantId, surfaceKey,
                        trusted.permissions(), trusted.roles(), trusted.groups(), modeKey)
                .entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> contract(entry.getValue())));
    }

    Map<String, HomeLayoutPolicy.RegistryWidgetContract> contracts(
            Long tenantId,
            String surfaceKey,
            String modeKey,
            Authority authority,
            HomePreferenceDtos.HomeLayoutPayload storedLayout) {
        Map<String, HomeLayoutPolicy.RegistryWidgetContract> result =
                new java.util.LinkedHashMap<>(
                        layoutPolicy.contractsForStoredLayout(storedLayout));
        result.putAll(contracts(tenantId, surfaceKey, modeKey, authority));
        return Map.copyOf(result);
    }

    private HomeLayoutPolicy.RegistryWidgetContract contract(
            WidgetCatalogService.PlacementWriteContract source) {
        return new HomeLayoutPolicy.RegistryWidgetContract(
                source.canHide(), source.defaultSize(), source.allowedSizes(),
                source.defaultHeight(), source.allowedHeights(), null);
    }

    record Authority(String permissions, String roles, String groups) {
        static final Authority NONE = new Authority(null, null, null);

        static Authority of(String permissions, String roles, String groups) {
            return new Authority(permissions, roles, groups);
        }
    }
}
