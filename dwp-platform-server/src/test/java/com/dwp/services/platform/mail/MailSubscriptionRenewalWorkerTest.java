package com.dwp.services.platform.mail;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailSubscriptionRenewalWorkerTest {

    @Test
    void scannerClaimsDurableRenewalAndRecordsLowCardinalityOutcome() {
        MailSubscriptionLedger ledger = mock(MailSubscriptionLedger.class);
        MailSubscriptionCoordinator coordinator = mock(MailSubscriptionCoordinator.class);
        MailSubscriptionLedger.Renewal renewal = new MailSubscriptionLedger.Renewal(
                UUID.randomUUID(), 7, UUID.randomUUID(), UUID.randomUUID(), -3, 1);
        when(ledger.releaseExpiredLeases(5)).thenReturn(
                new MailSubscriptionLedger.LeaseRecovery(0, 0));
        when(ledger.claim(anyString(), org.mockito.ArgumentMatchers.eq(10),
                org.mockito.ArgumentMatchers.eq(30))).thenReturn(List.of(renewal));
        when(coordinator.renew(
                org.mockito.ArgumentMatchers.eq(renewal), anyString(),
                org.mockito.ArgumentMatchers.eq(5)))
                .thenReturn(MailSubscriptionCoordinator.Outcome.RENEWED);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MailSubscriptionRenewalWorker worker = new MailSubscriptionRenewalWorker(
                ledger, coordinator, new MailMetrics(registry),
                10, 30, 5, "test-subscription");

        worker.renewDue();

        verify(ledger).scheduleMissingSubscriptions();
        verify(ledger).expireDue();
        verify(coordinator).renew(
                org.mockito.ArgumentMatchers.eq(renewal), anyString(),
                org.mockito.ArgumentMatchers.eq(5));
        assertThat(registry.get("dwp.mail.subscriptions")
                .tag("outcome", "renewed").counter().count()).isOne();
    }
}
