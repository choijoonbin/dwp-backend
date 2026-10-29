package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.RuleMatchMode.ALL;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailIncomingRuleWorkerTest {

    @Test
    void oneRuleFailureIsPersistedAndDoesNotStopTheRemainingBatch() {
        MailIncomingRuleRepository runs = mock(MailIncomingRuleRepository.class);
        MailIncomingRuleTransactions transactions = mock(MailIncomingRuleTransactions.class);
        MailIncomingRuleRepository.IncomingRun first = run(10);
        MailIncomingRuleRepository.IncomingRun second = run(20);
        when(runs.claim(anyString(), eq(25), eq(30))).thenReturn(List.of(first, second));
        doThrow(new MailIncomingRuleTransactions.IncomingRuleFailure("INJECTED_FAILURE"))
                .when(transactions).execute(eq(first), anyString());

        new MailIncomingRuleWorker(
                runs, transactions, true, 25, 30, 5, "mail-test").executePending();

        verify(runs).releaseExpiredLeases(5);
        verify(transactions).fail(eq(first), anyString(), eq(5), eq("INJECTED_FAILURE"));
        verify(transactions).execute(eq(second), anyString());
    }

    @Test
    void disabledWorkerNeverTouchesTheLedger() {
        MailIncomingRuleRepository runs = mock(MailIncomingRuleRepository.class);
        MailIncomingRuleTransactions transactions = mock(MailIncomingRuleTransactions.class);

        new MailIncomingRuleWorker(
                runs, transactions, false, 25, 30, 5, "mail-test").executePending();

        verify(runs, never()).releaseExpiredLeases(5);
        verify(runs, never()).claim(anyString(), eq(25), eq(30));
    }

    private MailIncomingRuleRepository.IncomingRun run(int priority) {
        return new MailIncomingRuleRepository.IncomingRun(
                UUID.randomUUID(), 7L, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 91L, UUID.randomUUID(), 0L, priority, ALL,
                List.of(), List.of(), false, 1);
    }
}
