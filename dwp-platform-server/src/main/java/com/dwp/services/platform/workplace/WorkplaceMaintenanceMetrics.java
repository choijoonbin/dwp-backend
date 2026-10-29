package com.dwp.services.platform.workplace;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

/** Low-cardinality lifecycle and retention health signals for Workplace. */
@Component
final class WorkplaceMaintenanceMetrics {

    static final String BACKLOG = "dwp.workplace.maintenance.backlog";
    static final String OLDEST_EXPIRED_AGE =
            "dwp.workplace.maintenance.oldest.expired.age";
    static final String PROCESSED = "dwp.workplace.maintenance.processed";
    static final String RUNS = "dwp.workplace.maintenance.runs";

    private final MeterRegistry registry;
    private final BacklogGauge bookingNoShow;
    private final BacklogGauge bookingCompletion;
    private final BacklogGauge privacyBooking;
    private final BacklogGauge privacyReleaseWindow;
    private final BacklogGauge privacyAudit;
    private final BacklogGauge facilityRequest;
    private final BacklogGauge facilityClosure;
    private final BacklogGauge collaborationWorkPlan;

    WorkplaceMaintenanceMetrics(MeterRegistry registry) {
        this.registry = registry;
        bookingNoShow = register("booking-lifecycle", "no-show");
        bookingCompletion = register("booking-lifecycle", "completion");
        privacyBooking = register("privacy-retention", "booking");
        privacyReleaseWindow = register("privacy-retention", "release-window");
        privacyAudit = register("privacy-retention", "audit-replica");
        facilityRequest = register("facility-retention", "request");
        facilityClosure = register("facility-retention", "closure");
        collaborationWorkPlan = register("collaboration-retention", "work-plan");
    }

    void booking(WorkplaceBookingRepository.LifecycleBacklog backlog, OffsetDateTime now) {
        bookingNoShow.update(backlog.noShows(), backlog.oldestNoShowDueAt(), now);
        bookingCompletion.update(
                backlog.completions(), backlog.oldestCompletionDueAt(), now);
    }

    void privacy(WorkplacePrivacyRepository.RetentionBacklog backlog, OffsetDateTime now) {
        privacyBooking.update(
                backlog.bookings(), backlog.oldestBookingExpiredAt(), now);
        privacyReleaseWindow.update(
                backlog.releaseWindows(), backlog.oldestReleaseWindowExpiredAt(), now);
        privacyAudit.update(
                backlog.auditReplicas(), backlog.oldestAuditExpiredAt(), now);
    }

    void facilities(
            WorkplaceExperienceFacilitiesRetention.RetentionBacklog backlog,
            OffsetDateTime now) {
        facilityRequest.update(
                backlog.requests(), backlog.oldestRequestExpiredAt(), now);
        facilityClosure.update(
                backlog.closures(), backlog.oldestClosureExpiredAt(), now);
    }

    void collaboration(
            WorkplaceExperienceCollaborationRepository.RetentionBacklog backlog,
            OffsetDateTime now) {
        collaborationWorkPlan.update(
                backlog.workPlans(), backlog.oldestWorkPlanExpiredAt(), now);
    }

    void processed(String job, String kind, long count) {
        if (count > 0) {
            registry.counter(PROCESSED, "job", job, "kind", kind).increment(count);
        }
    }

    void run(String job, String outcome) {
        registry.counter(RUNS, "job", job, "outcome", outcome).increment();
    }

    private BacklogGauge register(String job, String kind) {
        AtomicLong backlog = new AtomicLong();
        AtomicLong oldestAgeSeconds = new AtomicLong();
        Gauge.builder(BACKLOG, backlog, AtomicLong::get)
                .description("Number of eligible Workplace lifecycle or retention records")
                .tag("job", job)
                .tag("kind", kind)
                .register(registry);
        Gauge.builder(OLDEST_EXPIRED_AGE, oldestAgeSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest eligible Workplace record")
                .baseUnit("seconds")
                .tag("job", job)
                .tag("kind", kind)
                .register(registry);
        return new BacklogGauge(backlog, oldestAgeSeconds);
    }

    private record BacklogGauge(AtomicLong backlog, AtomicLong oldestAgeSeconds) {
        void update(long count, OffsetDateTime oldestExpiredAt, OffsetDateTime now) {
            if (count < 0) {
                throw new IllegalArgumentException("maintenance backlog cannot be negative");
            }
            backlog.set(count);
            long age = oldestExpiredAt == null
                    ? 0L
                    : Math.max(0L, Duration.between(oldestExpiredAt, now).toSeconds());
            oldestAgeSeconds.set(age);
        }
    }
}
