package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.List;

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

        assertThat(result.schemaVersion()).isEqualTo(2);
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

    private void assertInvalid(HomeExperienceDtos.GovernedHomeZone zone) {
        assertThatThrownBy(() -> registry.normalize(
                        new HomeExperienceDtos.HomeCompositionPolicy(2, true, List.of(zone))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
    }
}
