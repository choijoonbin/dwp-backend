package com.dwp.services.notification.api;

import com.dwp.services.notification.domain.NotificationDeliveryEndpointModels.DeliveryEndpoint;
import com.dwp.services.notification.domain.NotificationDeliveryEndpointModels.DeliveryEndpointRevokeRequest;
import com.dwp.services.notification.domain.NotificationDeliveryEndpointService;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryEndpointControllerTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");

    private final NotificationDeliveryEndpointService service =
            mock(NotificationDeliveryEndpointService.class);
    private final NotificationDeliveryEndpointController controller =
            new NotificationDeliveryEndpointController(service);

    @AfterEach
    void clearContext() {
        NotificationRequestContext.clear();
    }

    @Test
    void exposesRecipientEndpointInventoryAndVersionedRevocation() {
        UUID endpointId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        DeliveryEndpoint endpoint = new DeliveryEndpoint(
                endpointId, "MOBILE_PUSH", "iPhone", "IOS", "DWP Mobile · iOS",
                "ACTIVE", now, now, null, "1");
        DeliveryEndpoint revoked = new DeliveryEndpoint(
                endpointId, "MOBILE_PUSH", "iPhone", "IOS", "DWP Mobile · iOS",
                "REVOKED", now, now, now, "2");
        when(service.list(ACTOR)).thenReturn(List.of(endpoint));
        when(service.revoke(ACTOR, endpointId, 1L, "revoke-1")).thenReturn(revoked);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.list().data()).containsExactly(endpoint);
        assertThat(controller.revoke(
                endpointId, "revoke-1", new DeliveryEndpointRevokeRequest("1")).data())
                .isSameAs(revoked);
        verify(service).revoke(ACTOR, endpointId, 1L, "revoke-1");
    }
}
