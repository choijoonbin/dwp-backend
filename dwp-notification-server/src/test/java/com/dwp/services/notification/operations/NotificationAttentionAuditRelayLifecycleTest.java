package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationAttentionAuditRelayService.RelayResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationAttentionAuditRelayLifecycleTest {

    @Test
    void isolatesTenantFailureAndRecoversHealthAfterACompleteSuccessfulCycle() {
        NotificationMaintenanceTenantRepository tenants =
                mock(NotificationMaintenanceTenantRepository.class);
        NotificationAttentionAuditRelayService relay =
                mock(NotificationAttentionAuditRelayService.class);
        when(tenants.activeTenantIdsAfter(Long.MIN_VALUE, 500))
                .thenReturn(List.of(41L, 42L));
        when(relay.relayTenant(41L, Instant.parse("2026-09-17T00:00:00Z")))
                .thenReturn(result());
        when(relay.relayTenant(42L, Instant.parse("2026-09-17T00:00:00Z")))
                .thenThrow(new IllegalStateException("tenant transport failure"));
        when(relay.relayTenant(41L, Instant.parse("2026-09-17T00:01:00Z")))
                .thenReturn(result());
        when(relay.relayTenant(42L, Instant.parse("2026-09-17T00:01:00Z")))
                .thenReturn(result());
        NotificationAttentionAuditRelayLifecycle lifecycle =
                new NotificationAttentionAuditRelayLifecycle(
                        tenants, relay, true,
                        "https://audit.example.com/ingest",
                        "canonical-test-ingest-token",
                        Duration.ofHours(1));

        try {
            lifecycle.start();
            lifecycle.runOnce(Instant.parse("2026-09-17T00:00:00Z"));
            assertThat(lifecycle.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(lifecycle.health().getDetails())
                    .containsEntry("lastFailure", "IllegalStateException");

            lifecycle.runOnce(Instant.parse("2026-09-17T00:01:00Z"));
            assertThat(lifecycle.health().getStatus()).isEqualTo(Status.UP);
            assertThat(lifecycle.health().getDetails())
                    .containsEntry("lastSuccess", Instant.parse("2026-09-17T00:01:00Z"))
                    .doesNotContainKey("lastFailure");
        } finally {
            lifecycle.stop();
        }
    }

    @Test
    void refusesToStartWithoutCanonicalAuditTransport() {
        NotificationAttentionAuditRelayLifecycle lifecycle =
                new NotificationAttentionAuditRelayLifecycle(
                        mock(NotificationMaintenanceTenantRepository.class),
                        mock(NotificationAttentionAuditRelayService.class),
                        true, "", "", Duration.ofSeconds(2));

        lifecycle.start();

        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(lifecycle.health().getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(lifecycle.health().getDetails())
                .containsEntry("reason", "AUDIT_TRANSPORT_NOT_CONFIGURED");
    }

    @Test
    void reportsPersistedPoisonBacklogAfterProcessRestart() {
        NotificationMaintenanceTenantRepository tenants =
                mock(NotificationMaintenanceTenantRepository.class);
        NotificationAttentionAuditRelayService relay =
                mock(NotificationAttentionAuditRelayService.class);
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        when(tenants.activeTenantIdsAfter(Long.MIN_VALUE, 500))
                .thenReturn(List.of(42L));
        when(relay.relayTenant(42L, now))
                .thenReturn(new RelayResult(0, 0, 0, 0, 0, 0, 3));
        NotificationAttentionAuditRelayLifecycle lifecycle =
                new NotificationAttentionAuditRelayLifecycle(
                        tenants, relay, true,
                        "https://audit.example.com/ingest",
                        "canonical-test-ingest-token",
                        Duration.ofHours(1));

        try {
            lifecycle.start();
            lifecycle.runOnce(now);

            assertThat(lifecycle.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(lifecycle.health().getDetails()).containsEntry("deadEvents", 3L);
        } finally {
            lifecycle.stop();
        }
    }

    private RelayResult result() {
        return new RelayResult(0, 0, 0, 0, 0, 0, 0);
    }
}
