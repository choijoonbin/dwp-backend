package com.dwp.services.provider.support;

import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSupportActivationServiceTest {

    private final ProviderSupportSessionRepository repository =
            mock(ProviderSupportSessionRepository.class);
    private final ProviderAuditService auditService = mock(ProviderAuditService.class);
    private final ProviderSupportActivationService service =
            new ProviderSupportActivationService(repository, auditService);

    @BeforeEach
    void setUp() {
        ProviderRequestContext.setForTest(12L, 1L);
    }

    @AfterEach
    void tearDown() {
        ProviderRequestContext.clear();
    }

    @Test
    void disablePathRemainsUsableWithoutConsultingTheActivationGate() {
        when(auditService.canonicalCorrelationId("corr-disable"))
                .thenReturn("sha256:safe-correlation");
        when(repository.disableActivation(
                12L, "Contain active support", "sha256:safe-correlation"))
                .thenReturn(new ProviderSupportSessionRepository.SupportActivationState(false, 7));

        service.disable(
                "corr-disable",
                new ProviderSupportDtos.DisableActivationRequest("Contain active support"));

        verify(repository).disableActivation(
                12L, "Contain active support", "sha256:safe-correlation");
        verify(auditService).success(
                eq("provider.support-activation.disable-requested"),
                eq("SUPPORT_CONTROL"), eq("STANDARD_JIT"),
                isNull(), isNull(), eq("corr-disable"), any());
    }
}
