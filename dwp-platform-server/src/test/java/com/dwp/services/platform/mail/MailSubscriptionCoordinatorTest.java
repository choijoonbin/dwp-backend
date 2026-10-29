package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailSubscriptionCoordinatorTest {

    @Test
    void renewsAccountOutsideTransactionAndPersistsOnlyHashedProviderReference() {
        AdminMailCompletionRepository repository = mock(AdminMailCompletionRepository.class);
        MailSubscriptionLedger ledger = mock(MailSubscriptionLedger.class);
        UUID connectionId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        when(repository.connection(7, connectionId)).thenReturn(java.util.Optional.of(
                new AdminMailCompletionRepository.ConnectionRow(
                        connectionId, "DWP_SANDBOX", "ACTIVE", null,
                        "example.test", 0, null, null,
                        OffsetDateTime.ofInstant(now, ZoneOffset.UTC))));
        when(repository.connectionAccounts(7, connectionId)).thenReturn(List.of(
                new AdminMailCompletionRepository.AccountRow(
                        accountId, "owner@example.test", "provider-account-7", null, 0)));
        AtomicBoolean transactionObserved = new AtomicBoolean(true);
        MailConnectorPort connector = new DwpSandboxMailConnector() {
            @Override
            public SubscriptionReceipt renewSubscription(SubscriptionRequest request) {
                transactionObserved.set(
                        TransactionSynchronizationManager.isActualTransactionActive());
                assertThat(request.providerAccountReference())
                        .isEqualTo("provider-account-7");
                return new SubscriptionReceipt(
                        "raw-provider-subscription", now.plus(Duration.ofDays(2)));
            }
        };
        when(ledger.completeSuccess(any(), anyString(), anyString(), isNull(),
                any(), any(), any())).thenReturn(true);
        MailSubscriptionCoordinator coordinator = new MailSubscriptionCoordinator(
                repository, new MailConnectorRegistry(List.of(connector)), ledger,
                URI.create("https://mail.example.test/"), Duration.ofDays(2),
                Duration.ofMinutes(30), Clock.fixed(now, ZoneOffset.UTC));
        MailSubscriptionLedger.Renewal renewal = new MailSubscriptionLedger.Renewal(
                UUID.randomUUID(), 7, connectionId, accountId, -3, 1);

        MailSubscriptionCoordinator.Outcome outcome = coordinator.renew(
                renewal, "worker-1", 5);

        assertThat(outcome).isEqualTo(MailSubscriptionCoordinator.Outcome.RENEWED);
        assertThat(transactionObserved).isFalse();
        ArgumentCaptor<String> referenceHash = ArgumentCaptor.forClass(String.class);
        verify(ledger).completeSuccess(eq(renewal), eq("worker-1"),
                referenceHash.capture(), isNull(), any(), any(), any());
        assertThat(referenceHash.getValue()).matches("[0-9a-f]{64}")
                .isNotEqualTo("raw-provider-subscription");
    }
}
