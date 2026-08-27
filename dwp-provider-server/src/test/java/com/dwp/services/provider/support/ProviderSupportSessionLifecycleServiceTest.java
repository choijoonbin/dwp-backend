package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSupportSessionLifecycleServiceTest {

    private final ProviderSupportSessionRepository sessionRepository =
            mock(ProviderSupportSessionRepository.class);
    private final ProviderSupportRequestRepository requestRepository =
            mock(ProviderSupportRequestRepository.class);
    private final ProviderSupportSessionLifecycleService service =
            new ProviderSupportSessionLifecycleService(sessionRepository, requestRepository);

    @Test
    void expiresSessionsBeforeRequestsAndReturnsTheTotalTransitionCount() {
        when(sessionRepository.expireSupportSessions()).thenReturn(2);
        when(requestRepository.expireElapsedRequests()).thenReturn(3);

        assertThat(service.expireElapsedSessions()).isEqualTo(5);
        verify(sessionRepository).expireSupportSessions();
        verify(requestRepository).expireElapsedRequests();
    }

    @Test
    void mapsDatabaseOrAuditFailureToAuthorityUnavailable() {
        when(sessionRepository.expireSupportSessions())
                .thenThrow(new IllegalStateException("outbox write failed"));

        assertThatThrownBy(service::expireElapsedSessions)
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
                    assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(503);
                    assertThat(exception.getCause()).hasMessage("outbox write failed");
                });
    }
}
