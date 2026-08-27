package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSupportAuthorityReconciliationServiceTest {

    private final ProviderSupportSessionRepository repository =
            mock(ProviderSupportSessionRepository.class);
    private final ProviderSupportAuthorityReconciliationService service =
            new ProviderSupportAuthorityReconciliationService(repository);

    @Test
    void emitsExactlyOneDatabaseReconciliationPulse() {
        when(repository.pulseAuthorityReconciliation()).thenReturn(1);

        service.reconcile();

        verify(repository).pulseAuthorityReconciliation();
    }

    @Test
    void failsClosedWhenTheSingletonControlRowIsUnavailable() {
        when(repository.pulseAuthorityReconciliation()).thenReturn(0);

        assertUnavailable(() -> service.reconcile());
    }

    @Test
    void mapsDatabaseFailureToAuthorityUnavailable() {
        when(repository.pulseAuthorityReconciliation())
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(service::reconcile)
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
                    assertThat(exception.getCause()).hasMessage("database unavailable");
                });
    }

    @Test
    void runsEachPulseInAnIndependentTransaction() throws NoSuchMethodException {
        Transactional transaction = ProviderSupportAuthorityReconciliationService.class
                .getMethod("reconcile")
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private void assertUnavailable(Runnable command) {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
}
