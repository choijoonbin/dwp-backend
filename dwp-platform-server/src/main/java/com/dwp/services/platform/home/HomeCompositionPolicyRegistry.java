package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HomeCompositionPolicyRegistry {

    public static final int SCHEMA_VERSION = 3;
    public static final String CLASSIC = "CLASSIC";
    public static final String FLOW_V1 = "FLOW_V1";
    private static final Set<Integer> READABLE_SCHEMA_VERSIONS = Set.of(1, 2, SCHEMA_VERSION);
    private static final Set<String> EXPERIENCE_VARIANTS = Set.of(CLASSIC, FLOW_V1);
    private static final Set<String> LEGACY_PERSONAL_ZONE_KEYS = Set.of("workspace-tools");
    private static final Map<String, ZoneContract> ZONES = contracts();

    public HomeExperienceDtos.HomeCompositionPolicy defaultPolicy() {
        return new HomeExperienceDtos.HomeCompositionPolicy(
                SCHEMA_VERSION,
                CLASSIC,
                true,
                ZONES.values().stream()
                        .map(ZoneContract::defaultZone)
                        .sorted(Comparator
                                .comparingInt(HomeExperienceDtos.GovernedHomeZone::sortOrder)
                                .thenComparing(HomeExperienceDtos.GovernedHomeZone::zoneKey))
                        .toList());
    }

    public HomeExperienceDtos.HomeCompositionPolicy failClosedPolicy() {
        HomeExperienceDtos.HomeCompositionPolicy defaults = defaultPolicy();
        return new HomeExperienceDtos.HomeCompositionPolicy(
                defaults.schemaVersion(), CLASSIC, false, defaults.governedZones());
    }

    public HomeExperienceDtos.HomeCompositionPolicy normalize(
            HomeExperienceDtos.HomeCompositionPolicy requested) {
        if (requested == null
                || requested.schemaVersion() == null
                || !READABLE_SCHEMA_VERSIONS.contains(requested.schemaVersion())) {
            throw invalid("Unsupported home composition policy schema version.");
        }
        if (requested.personalCustomizationEnabled() == null) {
            throw invalid("The personal customization policy is required.");
        }
        String experienceVariant = requested.schemaVersion() < SCHEMA_VERSION
                ? CLASSIC
                : requested.experienceVariant();
        if (experienceVariant == null || !EXPERIENCE_VARIANTS.contains(experienceVariant)) {
            throw invalid("The home experience variant is not registered.");
        }

        List<HomeExperienceDtos.GovernedHomeZone> requestedZones =
                requested.governedZones() == null ? List.of() : requested.governedZones();
        Set<String> unique = new LinkedHashSet<>();
        Map<String, HomeExperienceDtos.GovernedHomeZone> normalized = new LinkedHashMap<>();
        for (HomeExperienceDtos.GovernedHomeZone zone : requestedZones) {
            if (zone == null || zone.zoneKey() == null) {
                throw invalid("Governed home zone keys must be present and unique.");
            }
            // Version 1 tenant documents may still contain the former governed shell.
            // Its member-owned app layout is now persisted by HomePreferenceService.
            if (LEGACY_PERSONAL_ZONE_KEYS.contains(zone.zoneKey())) continue;
            if (!unique.add(zone.zoneKey())) {
                throw invalid("Governed home zone keys must be present and unique.");
            }
            ZoneContract contract = ZONES.get(zone.zoneKey());
            if (contract == null) {
                throw invalid("The governed home zone is not registered.");
            }
            if (zone.placement() != null && !contract.placement().equals(zone.placement())) {
                throw invalid("The governed home zone placement cannot be changed.");
            }
            String height = zone.height() == null ? contract.defaultHeight() : zone.height();
            if (zone.visible() == null || zone.size() == null
                    || !contract.allowedSizes().contains(zone.size())
                    || !contract.allowedHeights().contains(height)) {
                throw invalid("The governed home zone configuration is invalid.");
            }
            int sortOrder = boundedOrder(zone.sortOrder(), contract.defaultOrder());
            normalized.put(zone.zoneKey(), new HomeExperienceDtos.GovernedHomeZone(
                    zone.zoneKey(), contract.placement(), zone.visible(), zone.size(), height, sortOrder));
        }

        ZONES.forEach((zoneKey, contract) ->
                normalized.putIfAbsent(zoneKey, contract.defaultZone()));
        List<HomeExperienceDtos.GovernedHomeZone> zones = new ArrayList<>(normalized.values());
        zones.sort(Comparator
                .comparingInt(HomeExperienceDtos.GovernedHomeZone::sortOrder)
                .thenComparing(HomeExperienceDtos.GovernedHomeZone::zoneKey));
        return new HomeExperienceDtos.HomeCompositionPolicy(
                SCHEMA_VERSION,
                experienceVariant,
                requested.personalCustomizationEnabled(),
                List.copyOf(zones));
    }

    public String effectiveVariant(
            HomeExperienceDtos.HomeCompositionPolicy policy,
            boolean flowEnabled) {
        return flowEnabled && policy != null && FLOW_V1.equals(policy.experienceVariant())
                ? FLOW_V1
                : CLASSIC;
    }

    private int boundedOrder(Integer value, int defaultOrder) {
        int normalized = value == null ? defaultOrder : value;
        if (normalized < 0 || normalized > 10_000) {
            throw invalid("Governed home zone order must be between 0 and 10000.");
        }
        return normalized;
    }

    private static Map<String, ZoneContract> contracts() {
        Map<String, ZoneContract> zones = new LinkedHashMap<>();
        zones.put("announcements", new ZoneContract(
                "announcements", "CANVAS", true, "compact", "short", 20,
                Set.of("compact", "medium", "large", "full"),
                Set.of("short", "standard")));
        return Map.copyOf(zones);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record ZoneContract(
            String zoneKey,
            String placement,
            boolean defaultVisible,
            String defaultSize,
            String defaultHeight,
            int defaultOrder,
            Set<String> allowedSizes,
            Set<String> allowedHeights) {

        private HomeExperienceDtos.GovernedHomeZone defaultZone() {
            return new HomeExperienceDtos.GovernedHomeZone(
                    zoneKey, placement, defaultVisible, defaultSize, defaultHeight, defaultOrder);
        }
    }
}
