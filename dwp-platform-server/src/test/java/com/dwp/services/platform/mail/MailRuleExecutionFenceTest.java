package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailRuleExecutionFenceTest {

    @Test
    void boundedPreviewCanBeClaimedAndExecutedWithItsContinuationIdentity() {
        MailRuleBackfillTransactions transactions = mock(MailRuleBackfillTransactions.class);
        MailRuleBackfillService service = new MailRuleBackfillService(transactions);
        UUID accountId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        String continuationToken = "continuation-token";
        MailRuleBackfillDtos.Request request = new MailRuleBackfillDtos.Request(
                requestId, fingerprint, continuationToken);
        MailRuleBackfillRepository.Claim claim = new MailRuleBackfillRepository.Claim(
                UUID.randomUUID(), requestId, accountId, 1L, UUID.randomUUID(), null);
        MailRuleBackfillDtos.Result expected = new MailRuleBackfillDtos.Result(
                claim.executionId(), requestId, accountId, "SUCCEEDED", false,
                500, 40, 42, 40, OffsetDateTime.now(), OffsetDateTime.now());
        when(transactions.preview(1L, 7L, accountId, continuationToken)).thenReturn(
                new MailRuleBackfillDtos.Preview(
                        accountId, continuationToken, "next-token", fingerprint, 2, 500, 40, 42,
                        true, OffsetDateTime.now()));
        when(transactions.claim(1L, 7L, accountId, request)).thenReturn(claim);
        when(transactions.execute(1L, 7L, "corr-batch", claim, request)).thenReturn(expected);

        MailRuleBackfillDtos.Result result = service.run(
                1L, 7L, accountId, "corr-batch", request);

        assertThat(result).isEqualTo(expected);
        verify(transactions).claim(1L, 7L, accountId, request);
        verify(transactions).execute(1L, 7L, "corr-batch", claim, request);
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
