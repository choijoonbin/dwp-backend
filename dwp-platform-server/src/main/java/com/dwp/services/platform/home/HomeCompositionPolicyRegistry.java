package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HomeCompositionPolicyRegistry {

    public static final int SCHEMA_VERSION = 4;
    public static final String CLASSIC = "CLASSIC";
    public static final String FLOW_V1 = "FLOW_V1";
    public static final String MZ_V1 = "MZ_V1";
    private static final Set<Integer> READABLE_SCHEMA_VERSIONS = Set.of(1, 2, 3, SCHEMA_VERSION);
    private static final Set<String> EXPERIENCE_VARIANTS = Set.of(CLASSIC, FLOW_V1, MZ_V1);
    private static final Set<String> LEGACY_V4_VARIANTS = Set.of(CLASSIC, FLOW_V1);
    private static final String MODE_SCOPED_VIEW = "MODE_SCOPED_VIEW";
    private static final List<String> DEVICE_CLASSES = List.of(
            "DESKTOP_WIDE", "DESKTOP_STANDARD", "MOBILE_STANDARD", "MOBILE_COMPACT");
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
                        .toList(),
                defaultModeLayouts(),
                List.of(CLASSIC, FLOW_V1, MZ_V1),
                CLASSIC);
    }

    public HomeExperienceDtos.HomeCompositionPolicy failClosedPolicy() {
        HomeExperienceDtos.HomeCompositionPolicy defaults = defaultPolicy();
        return new HomeExperienceDtos.HomeCompositionPolicy(
                defaults.schemaVersion(), CLASSIC, false, defaults.governedZones(),
                defaults.modeLayouts(), defaults.allowedModes(), defaults.defaultMode());
    }

    /**
     * Projection used while pre-Wave1 clients or servers may still be active.
     * It carries the normalized governed state without exposing v4-only mode descriptors.
     */
    public HomeExperienceDtos.HomeCompositionPolicy legacyV3Projection(
            HomeExperienceDtos.HomeCompositionPolicy policy) {
        HomeExperienceDtos.HomeCompositionPolicy normalized = normalize(policy);
        return new HomeExperienceDtos.HomeCompositionPolicy(
                3,
                normalized.experienceVariant(),
                normalized.personalCustomizationEnabled(),
                normalized.governedZones(),
                null,
                null,
                null);
    }

    public HomeExperienceDtos.HomeCompositionPolicy legacyV3DefaultPolicy() {
        return legacyV3Projection(defaultPolicy());
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
        String legacyVariant = requested.schemaVersion() < 3
                ? CLASSIC
                : requested.experienceVariant();
        if (legacyVariant == null || !EXPERIENCE_VARIANTS.contains(legacyVariant)) {
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
        Map<String, HomeExperienceDtos.HomeModeLayoutContract> modeLayouts =
                requested.schemaVersion() < SCHEMA_VERSION
                        ? defaultModeLayouts()
                        : normalizeModeLayouts(requested.modeLayouts());
        List<String> allowedModes = normalizeAllowedModes(
                requested.allowedModes(), requested.schemaVersion(), requested.modeLayouts(),
                legacyVariant);
        String defaultMode = requested.defaultMode() == null
                ? legacyVariant : requested.defaultMode();
        if (!EXPERIENCE_VARIANTS.contains(defaultMode) || !allowedModes.contains(defaultMode)) {
            throw invalid("The default Home mode must be allowed by the tenant policy.");
        }
        if (requested.defaultMode() != null
                && requested.experienceVariant() != null
                && !requested.defaultMode().equals(requested.experienceVariant())) {
            throw invalid("The legacy experience variant must match the default Home mode.");
        }
        return new HomeExperienceDtos.HomeCompositionPolicy(
                SCHEMA_VERSION,
                defaultMode,
                requested.personalCustomizationEnabled(),
                List.copyOf(zones),
                modeLayouts,
                allowedModes,
                defaultMode);
    }

    public String effectiveVariant(
            HomeExperienceDtos.HomeCompositionPolicy policy,
            boolean flowEnabled,
            boolean mzEnabled) {
        if (policy == null) return CLASSIC;
        return switch (policy.experienceVariant()) {
            case FLOW_V1 -> flowEnabled ? FLOW_V1 : CLASSIC;
            case MZ_V1 -> mzEnabled ? MZ_V1 : CLASSIC;
            default -> CLASSIC;
        };
    }

    /** Source-compatible overload for pre-MZ callers. */
    public String effectiveVariant(
            HomeExperienceDtos.HomeCompositionPolicy policy,
            boolean flowEnabled) {
        return effectiveVariant(policy, flowEnabled, false);
    }

    private int boundedOrder(Integer value, int defaultOrder) {
        int normalized = value == null ? defaultOrder : value;
        if (normalized < 0 || normalized > 10_000) {
            throw invalid("Governed home zone order must be between 0 and 10000.");
        }
        return normalized;
    }

    private Map<String, HomeExperienceDtos.HomeModeLayoutContract> normalizeModeLayouts(
            Map<String, HomeExperienceDtos.HomeModeLayoutContract> requested) {
        if (requested == null
                || !(requested.keySet().equals(EXPERIENCE_VARIANTS)
                || requested.keySet().equals(LEGACY_V4_VARIANTS))) {
            throw invalid("Home composition v4 must describe CLASSIC, FLOW_V1 and MZ_V1 layouts.");
        }
        Map<String, HomeExperienceDtos.HomeModeLayoutContract> result = new LinkedHashMap<>();
        for (String mode : List.of(CLASSIC, FLOW_V1, MZ_V1)) {
            HomeExperienceDtos.HomeModeLayoutContract contract = requested.getOrDefault(
                    mode, defaultModeLayout());
            if (contract == null
                    || !MODE_SCOPED_VIEW.equals(contract.layoutScope())
                    || contract.deviceClasses() == null
                    || !contract.deviceClasses().equals(DEVICE_CLASSES)) {
                throw invalid("A Home composition mode layout contract is invalid.");
            }
            result.put(mode, new HomeExperienceDtos.HomeModeLayoutContract(
                    MODE_SCOPED_VIEW, DEVICE_CLASSES));
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<String, HomeExperienceDtos.HomeModeLayoutContract> defaultModeLayouts() {
        Map<String, HomeExperienceDtos.HomeModeLayoutContract> result = new LinkedHashMap<>();
        for (String mode : List.of(CLASSIC, FLOW_V1, MZ_V1)) {
            result.put(mode, defaultModeLayout());
        }
        return Collections.unmodifiableMap(result);
    }

    private List<String> normalizeAllowedModes(
            List<String> requested,
            int schemaVersion,
            Map<String, HomeExperienceDtos.HomeModeLayoutContract> requestedLayouts,
            String legacyVariant) {
        LinkedHashSet<String> modes = new LinkedHashSet<>();
        if (requested != null) {
            modes.addAll(requested);
        } else if (schemaVersion >= SCHEMA_VERSION && requestedLayouts != null) {
            // A pre-MZ v4 document used the layout keys as its implicit allowlist.
            modes.addAll(requestedLayouts.keySet());
        } else {
            modes.add(CLASSIC);
            modes.add(legacyVariant);
        }
        if (modes.isEmpty() || !modes.contains(CLASSIC)
                || !EXPERIENCE_VARIANTS.containsAll(modes)) {
            throw invalid("The tenant Home mode allowlist is invalid.");
        }
        return List.of(CLASSIC, FLOW_V1, MZ_V1).stream()
                .filter(modes::contains)
                .toList();
    }

    private HomeExperienceDtos.HomeModeLayoutContract defaultModeLayout() {
        return new HomeExperienceDtos.HomeModeLayoutContract(MODE_SCOPED_VIEW, DEVICE_CLASSES);
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
