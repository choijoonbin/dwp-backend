package com.dwp.services.platform.home;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeLaunchpadPolicyTest {

    private final HomeLaunchpadPolicy policy = new HomeLaunchpadPolicy();

    @Test
    void defaultConfigurationSeparatesTenantDefaultsFromPersonalPlacement() {
        HomeExperienceDtos.HomeLaunchpadConfiguration result = policy.defaultConfiguration();

        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.groups()).extracting(HomeExperienceDtos.HomeLaunchpadGroup::groupKey)
                .containsExactly("work", "connect", "services", "systems");
        assertThat(result.placements())
                .extracting(HomeExperienceDtos.HomeAppPlacement::resourceKey)
                .contains("APP.WORK", "APP.HCM", "APP.ADMINISTRATION");
    }

    @Test
    void normalizesOrderingAndRequiresBilingualGroupLabels() {
        HomeExperienceDtos.HomeLaunchpadConfiguration requested =
                new HomeExperienceDtos.HomeLaunchpadConfiguration(
                        1,
                        List.of(
                                group("services", 20),
                                group("work", 10)),
                        List.of(
                                new HomeExperienceDtos.HomeAppPlacement(
                                        "app.hris", "services", 20),
                                new HomeExperienceDtos.HomeAppPlacement(
                                        "APP.WORK", "work", 10)));

        HomeExperienceDtos.HomeLaunchpadConfiguration result = policy.normalize(requested);

        assertThat(result.groups()).extracting(HomeExperienceDtos.HomeLaunchpadGroup::groupKey)
                .containsExactly("work", "services");
        assertThat(result.placements())
                .extracting(HomeExperienceDtos.HomeAppPlacement::resourceKey)
                .containsExactly("APP.WORK", "APP.HCM");
    }

    @Test
    void rejectsDuplicateResourcesAndUnknownGroups() {
        HomeExperienceDtos.HomeLaunchpadConfiguration duplicate =
                new HomeExperienceDtos.HomeLaunchpadConfiguration(
                        1,
                        List.of(group("work", 10)),
                        List.of(
                                new HomeExperienceDtos.HomeAppPlacement(
                                        "APP.WORK", "work", 10),
                                new HomeExperienceDtos.HomeAppPlacement(
                                        "APP.WORK", "missing", 20)));

        assertThatThrownBy(() -> policy.normalize(duplicate)).isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsDisablingAGroupThatStillOwnsDefaultAppPlacements() {
        HomeExperienceDtos.HomeLaunchpadGroup disabledGroup =
                new HomeExperienceDtos.HomeLaunchpadGroup(
                        "work",
                        Map.of("ko", "업무", "en", "Work"),
                        Map.of("ko", "설명", "en", "Description"),
                        10,
                        false);
        HomeExperienceDtos.HomeLaunchpadConfiguration requested =
                new HomeExperienceDtos.HomeLaunchpadConfiguration(
                        1,
                        List.of(disabledGroup, group("services", 20)),
                        List.of(new HomeExperienceDtos.HomeAppPlacement(
                                "APP.WORK", "work", 10)));

        assertThatThrownBy(() -> policy.normalize(requested))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Move apps out");
    }

    private HomeExperienceDtos.HomeLaunchpadGroup group(String key, int sortOrder) {
        return new HomeExperienceDtos.HomeLaunchpadGroup(
                key,
                Map.of("ko", key + " 한글", "en", key + " English"),
                Map.of("ko", "설명", "en", "Description"),
                sortOrder,
                true);
    }
}
