package com.dwp.services.messaging.home;

import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessagingHomeAssetServiceTest {
    private final MessagingHomeAssetRepository repository = mock(MessagingHomeAssetRepository.class);
    private final MessagingHomeAssetService service = new MessagingHomeAssetService(repository);

    @AfterEach
    void clear() { MessagingRequestContext.clear(); }

    @Test
    void validatesLimitBeforeQueryAndUsesAuthenticatedTenantAndUser() {
        for (int limit : List.of(0, -1, 21, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> service.recent(limit)).isInstanceOf(BaseException.class);
        }
        verifyNoInteractions(repository);
        subject();
        when(repository.files(7, 100, 6)).thenReturn(List.of());
        when(repository.linkMessages(7, 100)).thenReturn(List.of());
        assertThat(service.recent(6).items()).isEmpty();
        verify(repository).files(7, 100, 6);
        verify(repository).linkMessages(7, 100);
    }

    @Test
    void mergesByActualShareTimeLimitsAndUsesStableIds() {
        subject();
        UUID conversation = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        var file = new MessagingHomeDtos.SharedAsset("file:one", MessagingHomeDtos.AssetKind.FILE,
                conversation, "Team", message, now.minusDays(1), "Sender", "plan.pdf",
                UUID.randomUUID(), null, "application/pdf", 12L);
        when(repository.files(7, 100, 1)).thenReturn(List.of(file));
        when(repository.linkMessages(7, 100)).thenReturn(List.of(new MessagingHomeAssetRepository.LinkMessage(
                message, conversation, "Team", "https://example.com/plan", now, "Sender")));
        var first = service.recent(1).items();
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().kind()).isEqualTo(MessagingHomeDtos.AssetKind.LINK);
        assertThat(first.getFirst().title()).isEqualTo("example.com");
        assertThat(first.getFirst().url()).isEqualTo("https://example.com/plan");
        assertThat(first.getFirst().attachmentId()).isNull();
        assertThat(service.recent(1).items()).isEqualTo(first);
    }

    private void subject() {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(100, 7, null, "User", Set.of(),
                Set.of("APP.MESSAGING:VIEW"), Set.of()));
    }
}
