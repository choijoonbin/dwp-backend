package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MeetingPreparationMaterialRetentionService {
    private static final Duration DEFAULT_POLL_DELAY = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_POLL_DELAY = Duration.ofHours(1);
    private static final int BATCH_SIZE = 200;
    private final MeetingPreparationMaterialRetentionTransactions transactions;
    private final Clock clock;
    private final Duration pollDelay;
    private final AtomicReference<OffsetDateTime> localFailureAt = new AtomicReference<>();

    @Autowired
    public MeetingPreparationMaterialRetentionService(
            MeetingPreparationMaterialRetentionTransactions transactions,
            @Value("${dwp.meeting.preparation-material-retention.poll-delay:PT5M}")
            Duration pollDelay) {
        this(transactions, Clock.systemUTC(), pollDelay);
    }

    public MeetingPreparationMaterialRetentionService(
            MeetingPreparationMaterialRetentionTransactions transactions) {
        this(transactions, Clock.systemUTC(), DEFAULT_POLL_DELAY);
    }

    MeetingPreparationMaterialRetentionService(
            MeetingPreparationMaterialRetentionTransactions transactions, Clock clock) {
        this(transactions, clock, DEFAULT_POLL_DELAY);
    }

    MeetingPreparationMaterialRetentionService(
            MeetingPreparationMaterialRetentionTransactions transactions,
            Clock clock,
            Duration pollDelay) {
        this.transactions = transactions;
        this.clock = clock;
        this.pollDelay = pollDelay;
    }

    public boolean ready() {
        if (!pollDelayValid()) return false;
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);
            OffsetDateTime minimumSuccessAt = now.minus(pollDelay.multipliedBy(3));
            OffsetDateTime localFailure = localFailureAt.get();
            if (localFailure != null && localFailure.isAfter(minimumSuccessAt)) {
                minimumSuccessAt = localFailure;
            }
            return transactions.ready(minimumSuccessAt, now);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public int purgeExpired() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            int deleted = transactions.purge(now, BATCH_SIZE);
            if (deleted >= 0) localFailureAt.set(null);
            return deleted;
        } catch (RuntimeException exception) {
            localFailureAt.compareAndSet(null, now);
            try {
                transactions.recordFailure(now);
            } catch (RuntimeException evidenceFailure) {
                exception.addSuppressed(evidenceFailure);
            }
            throw exception;
        }
    }

    private boolean pollDelayValid() {
        return pollDelay != null && !pollDelay.isZero() && !pollDelay.isNegative()
                && pollDelay.compareTo(MAXIMUM_POLL_DELAY) <= 0;
    }

    @Scheduled(fixedDelayString = "${dwp.meeting.preparation-material-retention.poll-delay:PT5M}")
    void scheduledPurge() {
        purgeExpired();
    }
}
