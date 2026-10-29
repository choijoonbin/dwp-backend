package com.dwp.services.messaging.later;

import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingLaterServiceTest {

    private final MessagingLaterRepository repository = mock(MessagingLaterRepository.class);
    private final MessagingLaterService service = new MessagingLaterService(repository);

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void materializesDueItemsBeforeReturningTheUserScopedQueue() {
        subject();
        when(repository.items(7, 100, "DUE", "incident", 0, 30)).thenReturn(List.of());
        when(repository.count(7, 100, "DUE", "incident")).thenReturn(0L);

        var page = service.items("due", " Incident ", 0, 30);

        assertThat(page.items()).isEmpty();
        verify(repository).promoteDue(7, 100);
        verify(repository).items(7, 100, "DUE", "incident", 0, 30);
    }

    @Test
    void requiresAFutureSnoozeAndDoesNotWriteInvalidState() {
        subject();
        UUID messageId = UUID.randomUUID();
        when(repository.item(7, 100, messageId)).thenReturn(Optional.of(item(messageId, 4)));

        var request = new MessagingLaterDtos.UpdateLaterRequest(
                "SNOOZED", null, OffsetDateTime.now().minusMinutes(1), 4L);

        assertThatThrownBy(() -> service.update(messageId, request, "correlation"))
                .isInstanceOf(BaseException.class);
        verify(repository, never()).update(
                eq(7L), eq(100L), eq(messageId), any(), any(), any(), any(), eq(4L));
    }

    @Test
    void rejectsStaleMutationsInsteadOfOverwritingAnotherClient() {
        subject();
        UUID messageId = UUID.randomUUID();
        when(repository.item(7, 100, messageId)).thenReturn(Optional.of(item(messageId, 8)));
        when(repository.update(
                eq(7L), eq(100L), eq(messageId),
                eq(MessagingLaterDtos.LaterState.COMPLETED),
                eq(null), eq(null), any(), eq(7L)))
                .thenReturn(0);

        var request = new MessagingLaterDtos.UpdateLaterRequest(
                "COMPLETED", null, null, 7L);

        assertThatThrownBy(() -> service.update(messageId, request, "correlation"))
                .isInstanceOf(BaseException.class);
        verify(repository, never()).audit(any(Long.class), any(Long.class), any(), any(), any(), any());
    }

    private MessagingLaterDtos.LaterItem item(UUID messageId, long version) {
        return new MessagingLaterDtos.LaterItem(
                messageId, UUID.randomUUID(), "Team", "CHANNEL", "Sender", "Preview",
                false, false, OffsetDateTime.now(), MessagingLaterDtos.LaterState.SAVED,
                null, null, null, version);
    }

    private void subject() {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                100, 7, null, "Member", Set.of(), Set.of("APP.MESSAGING:VIEW"), Set.of()));
    }
}
