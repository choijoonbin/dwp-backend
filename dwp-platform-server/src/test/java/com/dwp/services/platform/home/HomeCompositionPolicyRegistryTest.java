package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeCompositionPolicyRegistryTest {

    private final HomeCompositionPolicyRegistry registry = new HomeCompositionPolicyRegistry();

    @Test
    void restoresMissingGovernedZonesFromTheVersionedContract() {
        HomeExperienceDtos.HomeCompositionPolicy result = registry.normalize(
                new HomeExperienceDtos.HomeCompositionPolicy(
                        1,
                        true,
                        List.of(new HomeExperienceDtos.GovernedHomeZone(
                                "announcements", "CANVAS", false, "large", null, 30))));

        assertThat(result.schemaVersion()).isEqualTo(4);
        assertThat(result.experienceVariant()).isEqualTo("CLASSIC");
        assertThat(result.governedZones())
                .extracting(HomeExperienceDtos.GovernedHomeZone::zoneKey)
                .containsExactly("announcements");
        assertThat(result.governedZones().getFirst().visible()).isFalse();
        assertThat(result.governedZones().getFirst().size()).isEqualTo("large");
        assertThat(result.governedZones().getFirst().height()).isEqualTo("short");
    }

    @Test
    void rejectsUnknownZonesAndContractBreakingPlacementOrSize() {
        assertInvalid(new HomeExperienceDtos.GovernedHomeZone(
                "unknown", "CANVAS", true, "compact", "short", 10));
        assertInvalid(new HomeExperienceDtos.GovernedHomeZone(
                "announcements", "CANVAS", true, "fifth", "short", 10));
        assertInvalid(new HomeExperienceDtos.GovernedHomeZone(
                "announcements", "CANVAS", true, "compact", "expanded", 10));
    }

    @Test
    void retiresTheLegacyWorkspaceToolsGovernedShellWithoutDisablingPersonalization() {
        HomeExperienceDtos.HomeCompositionPolicy result = registry.normalize(
                new HomeExperienceDtos.HomeCompositionPolicy(
                        1,
                        true,
                        List.of(
                                new HomeExperienceDtos.GovernedHomeZone(
                                        "workspace-tools", "HERO", false, "full", null, 10),
                                new HomeExperienceDtos.GovernedHomeZone(
                                        "announcements", "CANVAS", true, "compact", "standard", 20))));

        assertThat(result.personalCustomizationEnabled()).isTrue();
        assertThat(result.governedZones())
                .extracting(HomeExperienceDtos.GovernedHomeZone::zoneKey)
                .containsExactly("announcements");
    }

    @Test
    void requiresTheV3VariantAndCombinesItWithTheServiceKillSwitch() {
        HomeExperienceDtos.HomeCompositionPolicy flow = registry.normalize(
                new HomeExperienceDtos.HomeCompositionPolicy(
                        3, "FLOW_V1", true, List.of()));

        assertThat(flow.experienceVariant()).isEqualTo("FLOW_V1");
        assertThat(flow.schemaVersion()).isEqualTo(4);
        assertThat(flow.modeLayouts().keySet()).containsExactlyInAnyOrder("CLASSIC", "FLOW_V1");
        assertThat(registry.effectiveVariant(flow, false)).isEqualTo("CLASSIC");
        assertThat(registry.effectiveVariant(flow, true)).isEqualTo("FLOW_V1");

        assertThatThrownBy(() -> registry.normalize(
                new HomeExperienceDtos.HomeCompositionPolicy(
                        3, "UNREGISTERED", true, List.of())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void promotesTheV2PolicyToClassicWithBothModeDescriptors() {
        HomeExperienceDtos.HomeCompositionPolicy result = registry.normalize(
                new HomeExperienceDtos.HomeCompositionPolicy(2, true, List.of()));

        assertThat(result.schemaVersion()).isEqualTo(4);
        assertThat(result.experienceVariant()).isEqualTo("CLASSIC");
        assertThat(result.modeLayouts().keySet()).containsExactly("CLASSIC", "FLOW_V1");
    }

    @Test
    void acceptsOnlyTheCompleteV4ModeAndDeviceContract() {
        var devices = List.of(
                "DESKTOP_WIDE", "DESKTOP_STANDARD", "MOBILE_STANDARD", "MOBILE_COMPACT");
        var contract = new HomeExperienceDtos.HomeModeLayoutContract(
                "MODE_SCOPED_VIEW", devices);
        HomeExperienceDtos.HomeCompositionPolicy result = registry.normalize(
                new HomeExperienceDtos.HomeCompositionPolicy(
                        4, "FLOW_V1", true, List.of(),
                        Map.of("CLASSIC", contract, "FLOW_V1", contract)));

        assertThat(result.schemaVersion()).isEqualTo(4);
        assertThat(result.experienceVariant()).isEqualTo("FLOW_V1");
        assertThat(result.modeLayouts().get("CLASSIC").deviceClasses()).isEqualTo(devices);
        assertThatThrownBy(() -> registry.normalize(
                new HomeExperienceDtos.HomeCompositionPolicy(
                        4, "CLASSIC", true, List.of(), Map.of("CLASSIC", contract))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> registry.normalize(
                new HomeExperienceDtos.HomeCompositionPolicy(
                        4, "CLASSIC", true, List.of(), Map.of(
                        "CLASSIC", new HomeExperienceDtos.HomeModeLayoutContract(
                                "MODE_SCOPED_VIEW", List.of("DESKTOP")),
                        "FLOW_V1", contract))))
                .isInstanceOf(BaseException.class);
    }

    private void assertInvalid(HomeExperienceDtos.GovernedHomeZone zone) {
        assertThatThrownBy(() -> registry.normalize(
                        new HomeExperienceDtos.HomeCompositionPolicy(2, true, List.of(zone))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
    }
}
