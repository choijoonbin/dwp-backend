package com.dwp.services.provider.support;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderSupportActivationGateTest {

    private final ProviderSupportSessionRepository repository =
            mock(ProviderSupportSessionRepository.class);

    @Test
    void requiresBothDeploymentAndDatabaseControls() {
        when(repository.activationState()).thenReturn(
                new ProviderSupportSessionRepository.SupportActivationState(true, 2));

        ProviderSupportActivationGate deploymentOff =
                new ProviderSupportActivationGate(repository, false);
        ProviderSupportActivationGate bothOn =
                new ProviderSupportActivationGate(repository, true);

        assertThat(deploymentOff.enabled()).isFalse();
        assertThat(bothOn.enabled()).isTrue();
        assertThatThrownBy(deploymentOff::requireEnabled)
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("operational safety control");
    }

    @Test
    void missingOrDisabledDatabaseControlFailsClosed() {
        when(repository.activationState()).thenReturn(
                new ProviderSupportSessionRepository.SupportActivationState(false, -1));

        ProviderSupportActivationGate gate =
                new ProviderSupportActivationGate(repository, true);

        assertThat(gate.enabled()).isFalse();
        assertThatThrownBy(gate::requireEnabled).isInstanceOf(BaseException.class);
    }
}
