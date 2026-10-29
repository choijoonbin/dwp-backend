package com.dwp.services.platform.calendar;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Bounded, failure-isolated privacy retention for short-lived scheduling evidence. */
@Component
class CalendarSchedulingV2Retention {

    private final CalendarSchedulingV2Repository repository;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final int graceSeconds;
    private final int batchSize;
    private final int maximumRounds;

    @Autowired
    CalendarSchedulingV2Retention(
            CalendarSchedulingV2Repository repository,
            MeterRegistry metrics,
            @Value("${dwp.platform.calendar.scheduling-evidence-retention.grace-seconds:300}")
                    int graceSeconds,
            @Value("${dwp.platform.calendar.scheduling-evidence-retention.batch-size:1000}")
                    int batchSize,
            @Value("${dwp.platform.calendar.scheduling-evidence-retention.max-rounds:20}")
                    int maximumRounds) {
        this(repository, metrics, Clock.systemUTC(), graceSeconds, batchSize, maximumRounds);
    }

    CalendarSchedulingV2Retention(
            CalendarSchedulingV2Repository repository,
            Clock clock,
            int graceSeconds,
            int batchSize,
            int maximumRounds) {
        this(repository, null, clock, graceSeconds, batchSize, maximumRounds);
    }

    private CalendarSchedulingV2Retention(
            CalendarSchedulingV2Repository repository,
            MeterRegistry metrics,
            Clock clock,
            int graceSeconds,
            int batchSize,
            int maximumRounds) {
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
        this.graceSeconds = bounded(graceSeconds, 0, 3600, "graceSeconds");
        this.batchSize = bounded(batchSize, 1, 5000, "batchSize");
        this.maximumRounds = bounded(maximumRounds, 1, 100, "maximumRounds");
    }

    @Scheduled(fixedDelayString =
            "${dwp.platform.calendar.scheduling-evidence-retention.delay-ms:60000}")
    public void scheduledPurge() {
        try {
            int removed = purgeExpired();
            if (metrics != null) {
                metrics.counter(
                        "dwp.calendar.scheduling.evidence.retention.runs",
                        "outcome", "success").increment();
                metrics.summary("dwp.calendar.scheduling.evidence.retention.deleted")
                        .record(removed);
            }
        } catch (RuntimeException exception) {
            if (metrics != null) {
                metrics.counter(
                        "dwp.calendar.scheduling.evidence.retention.runs",
                        "outcome", "failure").increment();
            }
            throw exception;
        }
    }

    int purgeExpired() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minusSeconds(graceSeconds);
        int total = 0;
        for (int round = 0; round < maximumRounds; round++) {
            int removed = repository.purgeExpiredGlobal(cutoff, batchSize);
            total += removed;
            if (removed < batchSize) break;
        }
        return total;
    }

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is outside its safe range.");
        }
        return value;
    }
}
