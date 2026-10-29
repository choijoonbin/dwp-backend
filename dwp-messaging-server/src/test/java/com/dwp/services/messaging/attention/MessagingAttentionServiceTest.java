package com.dwp.services.messaging.attention;

import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessagingAttentionServiceTest {

    private final MessagingAttentionRepository repository = mock(MessagingAttentionRepository.class);
    private final MessagingAttentionService service = new MessagingAttentionService(repository);

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void rejectsUnboundedQueriesBeforeAccessingTheRepository() {
        for (int limit : List.of(-1, 0, 101, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> service.activity(limit)).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> service.threads(limit)).isInstanceOf(BaseException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void scopesActivityAndThreadsToTheVerifiedTenantAndUser() {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                100, 7, null, "Member", Set.of(), Set.of("APP.MESSAGING:VIEW"), Set.of()));
        when(repository.activity(7, 100, 50)).thenReturn(List.of());
        when(repository.threads(7, 100, 25)).thenReturn(List.of());

        assertThat(service.activity(50).items()).isEmpty();
        assertThat(service.threads(25).items()).isEmpty();

        verify(repository).activity(7, 100, 50);
        verify(repository).threads(7, 100, 25);
    }
}
