package com.dwp.services.payroll.home;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Drains one locked owner change into XCON-015 on each default-off publication tick. */
@Component
@ConditionalOnProperty(
        prefix = "dwp.hris.payroll.home-producer",
        name = {"enabled", "event-publication-enabled"},
        havingValue = "true",
        matchIfMissing = false)
final class PayrollHomePublicationScheduler {

    private final PayrollHomePublicationCandidates candidates;
    private final PayrollHomeContributionService service;

    PayrollHomePublicationScheduler(
            PayrollHomePublicationCandidates candidates,
            PayrollHomeContributionService service) {
        this.candidates = candidates;
        this.service = service;
    }

    @Scheduled(fixedDelayString =
            "${dwp.hris.payroll.home-producer.publication-delay-ms:1000}")
    @Transactional
    void publishNext() {
        candidates.lockNext().ifPresent(candidate -> {
            var materialization = service.publishCurrent(candidate.authority());
            if (materialization.sourceVersion() != candidate.expectedSourceVersion()) {
                throw new IllegalStateException(
                        "Payroll Home candidate changed while publication was locked.");
            }
            candidates.markPublished(new PayrollHomePublicationCandidates.PublicationReceipt(
                    candidate.candidateId(), materialization.sourceVersion(),
                    materialization.payloadRef(), materialization.payloadDigest()));
        });
    }
}
