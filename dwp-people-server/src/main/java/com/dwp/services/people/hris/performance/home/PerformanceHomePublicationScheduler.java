package com.dwp.services.people.hris.performance.home;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Drains one locked owner change into XCON-012 on each default-off publication tick. */
@Component
@ConditionalOnProperty(
        prefix = "dwp.people.hris.performance-home",
        name = {"enabled", "event-publication-enabled"},
        havingValue = "true",
        matchIfMissing = false)
final class PerformanceHomePublicationScheduler {

    private final PerformanceHomePublicationCandidates candidates;
    private final PerformanceHomeEventPublisher publisher;

    PerformanceHomePublicationScheduler(
            PerformanceHomePublicationCandidates candidates,
            PerformanceHomeEventPublisher publisher) {
        this.candidates = candidates;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString =
            "${dwp.people.hris.performance-home.publication-delay-ms:1000}")
    @Transactional
    void publishNext() {
        candidates.lockNext().ifPresent(candidate -> {
            PerformanceHomeEventPublisher.Publication publication = publisher.publish(
                            candidate.authority(), candidate.correlationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Performance Home publication was disabled after scheduler activation."));
            var materialization = publication.materialization();
            if (materialization.sourceVersion() != candidate.expectedSourceVersion()) {
                throw new IllegalStateException(
                        "Performance Home candidate changed while publication was locked.");
            }
            candidates.markPublished(new PerformanceHomePublicationCandidates.PublicationReceipt(
                    candidate.candidateId(), materialization.sourceVersion(),
                    materialization.payloadRef(), materialization.payloadDigest()));
        });
    }
}
