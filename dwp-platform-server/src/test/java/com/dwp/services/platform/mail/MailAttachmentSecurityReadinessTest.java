package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailAttachmentSecurityReadinessTest {

    @Test
    void productionAttachmentFeatureFailsStartupWithoutOwner() {
        MailAttachmentSecurityReadiness readiness =
                new MailAttachmentSecurityReadiness(List.of(), true, "production");

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted scanner owner");
    }

    @Test
    void localDisabledFeatureRemainsCompatible() {
        MailAttachmentSecurityReadiness readiness =
                new MailAttachmentSecurityReadiness(List.of(), false, "local");

        readiness.validateProduction();
    }
}
