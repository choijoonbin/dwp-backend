package com.dwp.services.notification.operations;

import com.dwp.services.notification.security.NotificationDatabaseScope;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationOutboxRelayTransactionTest {

    @Test
    void appliesTheTenantWorkerScopeBeforeLeasingEvents() {
        NotificationDatabaseScope scope = mock(NotificationDatabaseScope.class);
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationOutboxRelayTransaction transaction =
                new NotificationOutboxRelayTransaction(scope, repository);
        Instant now = Instant.parse("2026-08-20T09:00:00Z");
        when(repository.lease(7L, "lease-token", now, now.plusSeconds(30), 50))
                .thenReturn(List.of());

        var events = transaction.lease(7L, "lease-token", now, now.plusSeconds(30), 50);

        assertThat(events).isEmpty();
        InOrder order = inOrder(scope, repository);
        order.verify(scope).applyWorker(7L);
        order.verify(repository).lease(7L, "lease-token", now, now.plusSeconds(30), 50);
    }
}
