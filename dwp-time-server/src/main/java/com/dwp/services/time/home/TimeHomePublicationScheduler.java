package com.dwp.services.time.home;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Drains one locked owner change into XCON-014 on each default-off publication tick. */
@Component
@ConditionalOnProperty(
        prefix = "dwp.hris.time.home",
        name = {"enabled", "event-publication-enabled"},
        havingValue = "true",
        matchIfMissing = false)
final class TimeHomePublicationScheduler {

    private final TimeHomePublicationCandidates candidates;
    private final TimeHomeContributionService service;

    TimeHomePublicationScheduler(
            TimeHomePublicationCandidates candidates,
            TimeHomeContributionService service) {
        this.candidates = candidates;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${dwp.hris.time.home.publication-delay-ms:1000}")
    @Transactional
    void publishNext() {
        candidates.lockNext().ifPresent(candidate -> {
            var materialization = service.prepare(candidate.authority());
            if (materialization.sourceVersion() != candidate.expectedSourceVersion()) {
                throw new IllegalStateException(
                        "Time Home candidate changed while publication was locked.");
            }
            candidates.markPublished(new TimeHomePublicationCandidates.PublicationReceipt(
                    candidate.candidateId(), materialization.sourceVersion(),
                    materialization.payloadRef(), materialization.payloadDigest()));
        });
    }
}
