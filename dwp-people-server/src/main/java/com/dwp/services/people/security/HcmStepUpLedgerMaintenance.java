package com.dwp.services.people.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bounded retention purge for consumed People-owned HCM challenges. */
@Component
public class HcmStepUpLedgerMaintenance {

    private final HcmStepUpReplayRepository repository;
    private final int batchSize;

    public HcmStepUpLedgerMaintenance(
            HcmStepUpReplayRepository repository,
            @Value("${dwp.people.step-up.ledger-purge-batch-size:500}") int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException(
                    "HCM step-up ledger purge batch size must be between 1 and 10000.");
        }
        this.repository = repository;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${dwp.people.step-up.ledger-purge-delay-ms:900000}")
    @Transactional
    public void purge() {
        repository.purgeExpired(batchSize);
    }
}
