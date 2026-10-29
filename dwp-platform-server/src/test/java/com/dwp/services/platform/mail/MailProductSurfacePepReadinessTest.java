package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailProductSurfacePepReadinessTest {

    @Test
    void productionFailsClosedWhenMailPepIsDisabled() {
        assertThatThrownBy(() -> new MailProductSurfacePepReadiness(
                "production", false).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("product-authorization-mail-v4-enabled=true");
    }

    @Test
    void productionStartsWhenMailPepIsEnabled() {
        assertThatCode(() -> new MailProductSurfacePepReadiness(
                "prod", true).validate()).doesNotThrowAnyException();
    }

    @Test
    void localAndTestRemainCompatibleWithPepDisabled() {
        assertThatCode(() -> new MailProductSurfacePepReadiness(
                "local", false).validate()).doesNotThrowAnyException();
        assertThatCode(() -> new MailProductSurfacePepReadiness(
                "test", false).validate()).doesNotThrowAnyException();
    }
}
