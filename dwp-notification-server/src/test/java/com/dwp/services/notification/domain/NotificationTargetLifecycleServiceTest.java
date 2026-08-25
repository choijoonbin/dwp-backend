package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationModels.ChangeSignal;
import com.dwp.services.notification.realtime.NotificationChangeCause;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationTargetLifecycleServiceTest {

    private final NotificationDatabaseScope scope = mock(NotificationDatabaseScope.class);
    private final NotificationProducerOwnershipPolicy ownership =
            mock(NotificationProducerOwnershipPolicy.class);
    private final NotificationTargetLifecycleRepository repository =
            mock(NotificationTargetLifecycleRepository.class);
    private final NotificationChangePublisher publisher = mock(NotificationChangePublisher.class);
    private final NotificationTargetLifecycleService service = new NotificationTargetLifecycleService(
            scope, ownership, repository, publisher);

    @Test
    void appliesOwnedTargetChangesAndPublishesContentFreeSyncHints() {
        NotificationRequestContext.Actor actor = actor();
        UUID notificationId = UUID.randomUUID();
        String target = "/messages/direct?conversation=42&message=7";
        List<ChangeSignal> signals = List.of(new ChangeSignal(1L, 9L, 12L, notificationId));
        when(repository.markUnavailable(
                1L, "messaging", target, "DELETED", "SOURCE_DELETED"))
                .thenReturn(signals);

        service.apply(actor, new NotificationTargetLifecycleService.TargetChange(
                "MESSAGING", target, "deleted", "SOURCE_DELETED"));

        verify(scope).applyWorker(1L);
        verify(ownership).requireAppOwnership(actor, "messaging");
        verify(publisher).publishAfterCommit(signals, NotificationChangeCause.TARGET_LIFECYCLE);
    }

    @Test
    void rejectsProducerControlledArbitraryLifecycleStates() {
        assertThatThrownBy(() -> service.apply(
                actor(),
                new NotificationTargetLifecycleService.TargetChange(
                        "messaging", "/messages/42", "AVAILABLE", "SOURCE_RESTORED")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private NotificationRequestContext.Actor actor() {
        return new NotificationRequestContext.Actor(
                1L, null, Set.of(), Set.of(), true, "dwp-messaging-server");
    }
}
