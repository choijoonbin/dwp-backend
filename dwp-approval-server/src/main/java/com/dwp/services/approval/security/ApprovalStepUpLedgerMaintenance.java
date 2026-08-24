package com.dwp.services.approval.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bounded retention purge for command challenges and minimal idempotency receipts. */
@Component
public class ApprovalStepUpLedgerMaintenance {

    private final ApprovalStepUpReplayRepository repository;
    private final int batchSize;

    public ApprovalStepUpLedgerMaintenance(
            ApprovalStepUpReplayRepository repository,
            @Value("${dwp.approval.step-up.ledger-purge-batch-size:500}") int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException(
                    "Step-up ledger purge batch size must be between 1 and 10000.");
        }
        this.repository = repository;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${dwp.approval.step-up.ledger-purge-delay-ms:900000}")
    @Transactional
    public void purge() {
        repository.purgeExpired(batchSize);
    }
}
