package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MailFollowUpDueNotificationWorkerTest {

    @Test
    void disabledWorkerDoesNotClaimFollowUps() {
        MailFollowUpDueNotificationTransactions transactions =
                mock(MailFollowUpDueNotificationTransactions.class);

        new MailFollowUpDueNotificationWorker(transactions, false, 100).publishDue();

        verify(transactions, never()).publishDue(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void enabledWorkerPublishesTheConfiguredBatch() {
        MailFollowUpDueNotificationTransactions transactions =
                mock(MailFollowUpDueNotificationTransactions.class);

        new MailFollowUpDueNotificationWorker(transactions, true, 75).publishDue();

        verify(transactions).publishDue(any(), org.mockito.ArgumentMatchers.eq(75));
    }

    @Test
    void batchSizeIsBounded() {
        assertThatThrownBy(() -> new MailFollowUpDueNotificationWorker(
                mock(MailFollowUpDueNotificationTransactions.class), true, 501))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch size");
    }
}
