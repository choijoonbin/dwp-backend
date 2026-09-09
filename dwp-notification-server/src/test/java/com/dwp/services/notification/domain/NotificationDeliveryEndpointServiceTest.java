package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationDeliveryEndpointModels.DeliveryEndpoint;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryEndpointServiceTest {

    private final NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
    private final NotificationDeliveryEndpointRepository repository =
            mock(NotificationDeliveryEndpointRepository.class);
    private final NotificationDeliveryEndpointService service =
            new NotificationDeliveryEndpointService(databaseScope, repository);
    private final NotificationRequestContext.Actor actor = new NotificationRequestContext.Actor(
            42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");

    @Test
    void listsOnlyAfterApplyingTheRecipientDatabaseScope() {
        DeliveryEndpoint endpoint = endpoint("ACTIVE", "1");
        when(repository.list(actor)).thenReturn(List.of(endpoint));

        assertThat(service.list(actor)).containsExactly(endpoint);

        verify(databaseScope).applyUser(actor);
        verify(repository).list(actor);
    }

    @Test
    void revokesThroughTheRecipientScopedIdempotentRepositoryBoundary() {
        UUID endpointId = UUID.randomUUID();
        DeliveryEndpoint revoked = new DeliveryEndpoint(
                endpointId, "WEB_PUSH", "Chrome", "WEB", "Chrome · Seoul",
                "REVOKED", Instant.now(), Instant.now(), Instant.now(), "2");
        when(repository.revoke(actor, endpointId, 1L, "endpoint-revoke-1"))
                .thenReturn(revoked);

        assertThat(service.revoke(actor, endpointId, 1L, "endpoint-revoke-1"))
                .isSameAs(revoked);

        verify(databaseScope).applyUser(actor);
        verify(repository).revoke(actor, endpointId, 1L, "endpoint-revoke-1");
    }

    private DeliveryEndpoint endpoint(String state, String version) {
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        return new DeliveryEndpoint(
                UUID.randomUUID(), "WEB_PUSH", "Chrome", "WEB", "Chrome · Seoul",
                state, now, now, null, version);
    }
}
