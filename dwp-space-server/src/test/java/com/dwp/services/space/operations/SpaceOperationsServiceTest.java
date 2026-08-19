package com.dwp.services.space.operations;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.space.integration.SpaceEntitlementPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpaceOperationsServiceTest {

    private final SpaceOperationsRepository repository = mock(SpaceOperationsRepository.class);
    private final SpaceGovernanceRepository governance = mock(SpaceGovernanceRepository.class);
    private final SpaceEntitlementPort entitlements = mock(SpaceEntitlementPort.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private SpaceOperationsService service;

    @BeforeEach
    void setUp() {
        service = new SpaceOperationsService(
                repository, governance, entitlements, audit, transactions);
    }

    @Test
    void leavesTheDurableQueueUntouchedWhenTheIdentityProviderIsNotConfigured() {
        when(entitlements.configured()).thenReturn(false);

        assertThat(service.deliver(50, "worker-1")).isZero();

        verify(repository, never()).claim(any(Integer.class), any(String.class));
    }

    @Test
    void recordsSuccessfulIdentityDelivery() {
        SpaceOperationsRepository.SyncItem item = item("GRANTED", 1);
        when(entitlements.configured()).thenReturn(true);
        when(repository.claim(50, "worker-1")).thenReturn(List.of(item));
        when(entitlements.synchronize(any())).thenReturn(
                new SpaceEntitlementPort.Result("grant-1", "ACTIVE", 0, true));

        assertThat(service.deliver(50, "worker-1")).isOne();

        verify(repository).markSucceeded(item.syncItemId(), "grant-1", "ACTIVE");
        verify(repository, never()).markFailed(any(), any());
    }

    @Test
    void keepsAFailedIdentityDeliveryRecoverable() {
        SpaceOperationsRepository.SyncItem item = item("REVOKED", 3);
        when(entitlements.configured()).thenReturn(true);
        when(repository.claim(50, "worker-1")).thenReturn(List.of(item));
        when(entitlements.synchronize(any())).thenThrow(new IllegalStateException("auth unavailable"));

        assertThat(service.deliver(50, "worker-1")).isOne();

        verify(repository).markFailed(item, "auth unavailable");
        verify(repository, never()).markSucceeded(any(), any(), any());
    }

    private SpaceOperationsRepository.SyncItem item(String desiredState, int attemptCount) {
        return new SpaceOperationsRepository.SyncItem(
                UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
                "USER", "7", "SPACE.PROJECT-ALPHA", "Project Alpha", "VIEW", 12L,
                Instant.now().plusSeconds(3600), desiredState, "IN_PROGRESS",
                "space:membership:view", attemptCount, Instant.now(), null, null,
                null, Instant.now(), null);
    }
}
