package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailRuleExecutionFenceTest {

    @Test
    void truncatedPreviewIsRejectedBeforeAClaimOrWrite() {
        MailRuleBackfillTransactions transactions = mock(MailRuleBackfillTransactions.class);
        MailRuleBackfillService service = new MailRuleBackfillService(transactions);
        UUID accountId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        when(transactions.preview(1L, 7L, accountId)).thenReturn(
                new MailRuleBackfillDtos.Preview(
                        accountId, fingerprint, 2, 500, 40, 42,
                        true, OffsetDateTime.now()));

        assertThatThrownBy(() -> service.run(
                1L, 7L, accountId, "corr-truncated",
                new MailRuleBackfillDtos.Request(requestId, fingerprint)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("truncated");

        verify(transactions, never()).claim(
                1L, 7L, accountId,
                new MailRuleBackfillDtos.Request(requestId, fingerprint));
    }

    @Test
    void legacyDirectRuleRunIsExplicitlyDisabled() {
        MailOrganizationService service = new MailOrganizationService(
                mock(MailQueryRepository.class),
                mock(MailOrganizationQueryRepository.class),
                mock(MailOrganizationCommandRepository.class),
                mock(MailCommandRepository.class),
                new MailRuleEvaluator());

        assertThatThrownBy(() -> service.runRule(
                1L, 7L, UUID.randomUUID(), "corr-legacy"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("preview-bound backfill");
    }
}
