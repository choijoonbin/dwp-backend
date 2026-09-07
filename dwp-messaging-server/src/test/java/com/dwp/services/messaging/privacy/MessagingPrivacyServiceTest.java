package com.dwp.services.messaging.privacy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MessagingPrivacyServiceTest {
    private final MessagingPrivacyRepository repository = mock(MessagingPrivacyRepository.class);
    private final MessagingEventRecorder events = mock(MessagingEventRecorder.class);
    private final MessagingPrivacyService service = new MessagingPrivacyService(repository, events);
    private final MessagingRequestContext.Subject subject = new MessagingRequestContext.Subject(
            100, 7, null, "Reader", Set.of(), Set.of("APP.MESSAGING:VIEW"), Set.of());

    @BeforeEach
    void context() { MessagingRequestContext.set(subject); }

    @AfterEach
    void clear() { MessagingRequestContext.clear(); }

    @Test
    void preferenceUsesOnlyVerifiedSelfAndTenant() {
        when(repository.preference(7, 100)).thenReturn(new MessagingPrivacyDtos.PrivacyPreference(true, 0));
        assertThat(service.preference()).isEqualTo(new MessagingPrivacyDtos.PrivacyPreference(true, 0));
    }

    @Test
    void updatePublishesOnlyPrivateVersionInvalidation() {
        when(repository.save(7, 100, false, 0)).thenReturn(1);
        assertThat(service.update(new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(false, 0L)))
                .isEqualTo(new MessagingPrivacyDtos.PrivacyPreference(false, 1));
        verify(repository).audit(7, 100, 1);
        verify(events).privateEvent(subject, "messaging.privacy-preferences.updated", null, null,
                Map.of("version", 1L));
        verifyNoMoreInteractions(events);
    }

    @Test
    void staleVersionDoesNotPublishOrAudit() {
        when(repository.save(7, 100, true, 4)).thenReturn(0);
        assertThatThrownBy(() -> service.update(new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(true, 4L)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verifyNoInteractions(events);
        verify(repository, never()).audit(anyLong(), anyLong(), anyLong());
    }

    @Test
    void requiresExplicitBooleanAndVersion() {
        for (var request : new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest[] {
                null, new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(null, 0L),
                new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(false, null),
                new MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest(true, -1L)}) {
            assertThatThrownBy(() -> service.update(request)).isInstanceOf(BaseException.class);
        }
        verifyNoInteractions(repository, events);
    }
}
