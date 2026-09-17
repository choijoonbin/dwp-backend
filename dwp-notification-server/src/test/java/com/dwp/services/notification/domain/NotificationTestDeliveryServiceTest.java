package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDelivery;
import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDeliveryRequest;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationTestDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");

    private final NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
    private final NotificationTestDeliveryRepository repository =
            mock(NotificationTestDeliveryRepository.class);
    private final NotificationTestDeliveryService service = new NotificationTestDeliveryService(
            databaseScope, repository, 1, 10, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void deduplicatesChannelsAndNeverClaimsExternalDeliverySucceeded() {
        when(repository.activePushEndpoints(ACTOR)).thenReturn(Set.of("WEB_PUSH"));
        when(repository.create(eq(ACTOR), any(), eq(1), eq(10), eq("test-1")))
                .thenAnswer(invocation -> invocation.getArgument(1));

        TestDelivery result = service.create(
                ACTOR,
                new TestDeliveryRequest(List.of("IN_APP", "WEB_PUSH", "IN_APP")),
                "test-1");

        assertThat(result.requestedChannels()).containsExactly("IN_APP", "WEB_PUSH");
        assertThat(result.state()).isEqualTo("PARTIAL");
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(86_400));
        assertThat(result.stages())
                .filteredOn(stage -> stage.stage().equals("ENDPOINT_DELIVERY"))
                .singleElement()
                .satisfies(stage -> {
                    assertThat(stage.state()).isEqualTo("DISABLED");
                    assertThat(stage.detail()).contains("external delivery is disabled");
                });
        verify(databaseScope).applyUser(ACTOR);
        verify(repository).create(eq(ACTOR), any(), eq(1), eq(10), eq("test-1"));
    }
}
