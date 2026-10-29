package com.dwp.services.platform.calendar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.PollState;

/** Bounded expiry and privacy retention; legal-hold rows are excluded by the repository. */
@Component
class CalendarSchedulingPollRetention {

    private final CalendarSchedulingPollRepository repository;
    private final CalendarSchedulingPollMetrics metrics;
    private final Clock clock;
    private final int retentionDays;
    private final int batchSize;
    private final int maximumRounds;

    @Autowired
    CalendarSchedulingPollRetention(
            CalendarSchedulingPollRepository repository,
            CalendarSchedulingPollMetrics metrics,
            @Value("${dwp.platform.calendar.scheduling-polls.retention-days:365}")
                    int retentionDays,
            @Value("${dwp.platform.calendar.scheduling-polls.maintenance-batch-size:200}")
                    int batchSize,
            @Value("${dwp.platform.calendar.scheduling-polls.maintenance-max-rounds:10}")
                    int maximumRounds) {
        this(repository, metrics, Clock.systemUTC(), retentionDays, batchSize, maximumRounds);
    }

    CalendarSchedulingPollRetention(
            CalendarSchedulingPollRepository repository,
            CalendarSchedulingPollMetrics metrics,
            Clock clock,
            int retentionDays,
            int batchSize,
            int maximumRounds) {
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalArgumentException(
                    "Calendar poll retention must be between 1 and 3650 days.");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                    "Calendar poll maintenance batch must be between 1 and 1000.");
        }
        if (maximumRounds < 1 || maximumRounds > 100) {
            throw new IllegalArgumentException(
                    "Calendar poll maintenance rounds must be between 1 and 100.");
        }
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.maximumRounds = maximumRounds;
    }

    @Scheduled(fixedDelayString =
            "${dwp.platform.calendar.scheduling-polls.maintenance-delay-ms:60000}")
    @Transactional
    void maintain() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int holdsExpired = 0;
        for (int round = 0; round < maximumRounds; round++) {
            int changed = repository.expireStaleHolds(now, batchSize);
            holdsExpired += changed;
            if (changed < batchSize) break;
        }
        int expired = 0;
        for (int round = 0; round < maximumRounds; round++) {
            var due = repository.duePolls(now, batchSize);
            for (CalendarSchedulingPollRepository.PollRow poll : due) {
                repository.releaseHolds(
                        poll.tenantId(), poll.pollId(), poll.organizerUserId(), now);
                if (repository.transition(
                        poll.tenantId(), poll.pollId(), poll.state(), PollState.EXPIRED,
                        poll.version(), poll.organizerUserId(),
                        now.plusDays(retentionDays), now) == 1) {
                    repository.audit(
                            poll.tenantId(), poll.pollId(), poll.organizerUserId(),
                            "calendar.poll.expired",
                            Map.of("state", poll.state().name()),
                            Map.of("state", PollState.EXPIRED.name()), null, now);
                    expired++;
                }
            }
            if (due.size() < batchSize) break;
        }
        int purged = 0;
        for (int round = 0; round < maximumRounds; round++) {
            int removed = repository.purgeGlobal(now, batchSize);
            purged += removed;
            if (removed < batchSize) break;
        }
        metrics.holdsExpired(holdsExpired);
        metrics.expired(expired);
        metrics.purged(purged);
    }
}
