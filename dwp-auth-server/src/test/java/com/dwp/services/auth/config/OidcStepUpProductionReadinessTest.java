package com.dwp.services.auth.config;

import com.dwp.services.auth.service.OidcService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OidcStepUpProductionReadinessTest {

    @Test
    void productionRejectsAnyPartiallyConfiguredStepUpProvider() {
        OidcService service = mock(OidcService.class);
        when(service.incompleteConfiguredStepUpProviderKeys("urn:dwp:acr:mfa"))
                .thenReturn(List.of("7:partial"));
        OidcStepUpProductionReadiness readiness = new OidcStepUpProductionReadiness(
                service, "production", "urn:dwp:acr:mfa");

        assertThatIllegalStateException().isThrownBy(() -> readiness.run(null))
                .withMessageContaining("7:partial");
    }

    @Test
    void localDoesNotReadTenantProviderInventory() throws Exception {
        OidcService service = mock(OidcService.class);
        OidcStepUpProductionReadiness readiness = new OidcStepUpProductionReadiness(
                service, "local", "");

        readiness.run(null);

        verifyNoInteractions(service);
    }
}
