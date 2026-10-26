package com.dwp.services.people.hris.separation.hrm013;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013RuntimeDefaultOffTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Hrm013RuntimeConfiguration.class);

    @Test
    void everyRuntimeLaneIsDefaultOffAndSecretsAreNotProperties() {
        Hrm013RuntimeProperties properties = new Hrm013RuntimeProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isWaiverRelayEnabled()).isFalse();
        assertThat(properties.isTaskDispatchRelayEnabled()).isFalse();
        assertThat(Hrm013RuntimeProperties.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .noneMatch(name -> name.toLowerCase().contains("secret")
                        || name.toLowerCase().contains("privatekey")
                        || name.toLowerCase().contains("certificate"));
    }

    @Test
    void incoherentRelayAndUnboundedTransportSettingsFailClosed() {
        Hrm013RuntimeProperties properties = new Hrm013RuntimeProperties();
        properties.setWaiverRelayEnabled(true);
        assertThatThrownBy(properties::requireCoherent)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties.setAuthorityEndpoint(
                URI.create("http://dwp-auth.internal/unsafe")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setRelayLease(Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultOffContextCreatesNoRuntimeBeans() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).doesNotHaveBean("hrm013Clock");
            assertThat(application).doesNotHaveBean(AuthHrm013ServiceAuthorityAdapter.class);
            assertThat(application).doesNotHaveBean(Hrm013WorkloadProofVerifier.class);
            assertThat(application).doesNotHaveBean(Hrm013CallbackService.class);
            assertThat(application).doesNotHaveBean(Hrm013OwnerService.class);
            assertThat(application).doesNotHaveBean(Hrm013WorkloadCredentialProvider.class);
            assertThat(application).doesNotHaveBean(Hrm013ApprovalClient.class);
            assertThat(application).doesNotHaveBean(Hrm013WaiverRequestRelay.class);
            assertThat(application).doesNotHaveBean(Hrm013TaskDispatchClient.class);
            assertThat(application).doesNotHaveBean(Hrm013TaskDispatchRelay.class);
        });
    }

    @Test
    void relayCannotStartWhileOwnerRuntimeIsDisabled() {
        context.withPropertyValues(
                        "dwp.hris.separation.enabled=false",
                        "dwp.hris.separation.waiver-relay-enabled=true")
                .run(application -> {
                    assertThat(application).hasFailed();
                    assertThat(application.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "HRM-013 relays require the owner runtime");
                });
    }
}
