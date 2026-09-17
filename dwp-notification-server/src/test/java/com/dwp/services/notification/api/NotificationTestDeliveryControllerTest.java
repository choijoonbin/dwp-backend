package com.dwp.services.notification.api;

import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDelivery;
import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDeliveryRequest;
import com.dwp.services.notification.domain.NotificationTestDeliveryService;
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

class NotificationTestDeliveryControllerTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");

    private final NotificationTestDeliveryService service =
            mock(NotificationTestDeliveryService.class);
    private final NotificationTestDeliveryController controller =
            new NotificationTestDeliveryController(service);

    @AfterEach
    void clearContext() {
        NotificationRequestContext.clear();
    }

    @Test
    void exposesCreateAndOwnerScopedReceiptRead() {
        UUID testId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-16T00:00:00Z");
        TestDeliveryRequest request = new TestDeliveryRequest(List.of("IN_APP"));
        TestDelivery delivery = new TestDelivery(
                testId, "COMPLETED", List.of("IN_APP"), List.of(),
                now, now.plusSeconds(86_400), null);
        when(service.create(ACTOR, request, "test-1")).thenReturn(delivery);
        when(service.get(ACTOR, testId)).thenReturn(delivery);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.createTestDelivery("test-1", request).data()).isSameAs(delivery);
        assertThat(controller.getTestDelivery(testId).data()).isSameAs(delivery);
        verify(service).create(ACTOR, request, "test-1");
        verify(service).get(ACTOR, testId);
    }
}
