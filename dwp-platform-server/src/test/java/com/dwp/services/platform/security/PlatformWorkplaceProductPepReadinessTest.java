package com.dwp.services.platform.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformWorkplaceProductPepReadinessTest {

    @Test
    void productionFailsClosedWhenWorkplacePepIsMissingOrDisabled() {
        assertThatThrownBy(() -> new PlatformWorkplaceProductPepReadiness(
                "production", false).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("product-authorization-workplace-v4-enabled=true");
    }

    @Test
    void productionStartsOnlyWithExactWorkplacePepEnabled() {
        assertThatCode(() -> new PlatformWorkplaceProductPepReadiness(
                "prod", true).validate()).doesNotThrowAnyException();
    }

    @Test
    void localAndTestRemainExplicitlyCompatibleWithPepDisabled() {
        assertThatCode(() -> new PlatformWorkplaceProductPepReadiness(
                "local", false).validate()).doesNotThrowAnyException();
        assertThatCode(() -> new PlatformWorkplaceProductPepReadiness(
                "test", false).validate()).doesNotThrowAnyException();
    }
}
