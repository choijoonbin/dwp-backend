package com.dwp.services.payroll.home;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayrollHomeProducerPropertiesTest {

    @Test
    void remainsDefaultOffWithEventPublicationAlsoOff() {
        var properties = new PayrollHomeProducerProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isEventPublicationEnabled()).isFalse();
        properties.validateForActivation();
    }

    @Test
    void activationFailsClosedWithoutSecretsAndTrustMaterial() {
        var properties = new PayrollHomeProducerProperties();
        properties.setEnabled(true);

        assertThatThrownBy(properties::validateForActivation)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activationRejectsUnorderedFreshnessWindows() {
        var properties = new PayrollHomeProducerProperties();
        properties.setEnabled(true);
        properties.setPayloadReferenceSecret("x".repeat(32));
        properties.setSigningKeyId("payroll-home-key-1");
        properties.setSigningPublicKeyPem("present-for-order-validation");
        properties.setFreshFor(Duration.ofMinutes(10));
        properties.setStaleAfter(Duration.ofMinutes(2));

        assertThatThrownBy(properties::validateForActivation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("freshness");
    }
}
