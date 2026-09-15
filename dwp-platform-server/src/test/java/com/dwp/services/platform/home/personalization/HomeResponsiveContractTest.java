package com.dwp.services.platform.home.personalization;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeResponsiveContractTest {

    @Test
    void legacyDeviceAliasesHaveOneCanonicalProjection() {
        assertThat(HomeDeviceClasses.canonical("DESKTOP")).isEqualTo("DESKTOP_STANDARD");
        assertThat(HomeDeviceClasses.canonical("MOBILE")).isEqualTo("MOBILE_STANDARD");
        assertThat(HomeDeviceClasses.canonical("desktop_wide")).isEqualTo("DESKTOP_WIDE");
        assertThat(HomeDeviceClasses.canonical("mobile_compact")).isEqualTo("MOBILE_COMPACT");
        assertThatThrownBy(() -> HomeDeviceClasses.canonical("TABLET"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void absentModeRemainsClassicForExistingClients() {
        assertThat(HomeModeKeys.canonical(null)).isEqualTo("CLASSIC");
        assertThat(HomeModeKeys.canonical("flow_v1")).isEqualTo("FLOW_V1");
        assertThatThrownBy(() -> HomeModeKeys.canonical("FLOW_V2"))
                .isInstanceOf(BaseException.class);
    }
}
