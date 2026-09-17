package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MailRuleBackfillService {

    private final MailRuleBackfillTransactions transactions;

    public MailRuleBackfillService(MailRuleBackfillTransactions transactions) {
        this.transactions = transactions;
    }

    public MailRuleBackfillDtos.Preview preview(Long tenantId, Long userId, UUID accountId) {
        return preview(tenantId, userId, accountId, null);
    }

    public MailRuleBackfillDtos.Preview preview(
            Long tenantId, Long userId, UUID accountId, String continuationToken) {
        return transactions.preview(tenantId, userId, accountId, continuationToken);
    }

    public MailRuleBackfillDtos.Result run(
            Long tenantId,
            Long userId,
            UUID accountId,
            String correlationId,
            MailRuleBackfillDtos.Request request) {
        transactions.preview(tenantId, userId, accountId, request.continuationToken());
        MailRuleBackfillRepository.Claim claim =
                transactions.claim(tenantId, userId, accountId, request);
        if (claim.replayed()) return claim.replay();
        try {
            return transactions.execute(
                    tenantId, userId, correlationId, claim, request);
        } catch (RuntimeException exception) {
            String errorCode = exception instanceof BaseException baseException
                    ? baseException.getErrorCode().getCode()
                    : "MAIL_RULE_BACKFILL_FAILED";
            try {
                transactions.fail(tenantId, userId, claim, errorCode);
            } catch (RuntimeException ignored) {
                // Preserve the original command failure. The expired lease remains reclaimable.
            }
            throw exception;
        }
    }
}
