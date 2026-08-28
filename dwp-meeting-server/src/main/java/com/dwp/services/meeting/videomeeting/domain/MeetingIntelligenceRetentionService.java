package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MeetingIntelligenceRetentionService {

    private final VideoMeetingIntelligenceRepository repository;
    private final MeetingIntelligenceRetentionTransactions transactions;
    private final MeetingIntelligenceRetentionProperties properties;
    private final Clock clock;
    private final AtomicReference<OffsetDateTime> localFailureAt = new AtomicReference<>();

    @Autowired
    public MeetingIntelligenceRetentionService(
            VideoMeetingIntelligenceRepository repository,
            MeetingIntelligenceRetentionTransactions transactions,
            MeetingIntelligenceRetentionProperties properties) {
        this(repository, transactions, properties, Clock.systemUTC());
    }

    MeetingIntelligenceRetentionService(
            VideoMeetingIntelligenceRepository repository,
            MeetingIntelligenceRetentionTransactions transactions,
            MeetingIntelligenceRetentionProperties properties,
            Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean ready() {
        Duration delay = properties.getPollDelay();
        if (!settingsValid()) return false;
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);
            var health = repository.retentionHealth().orElse(null);
            if (health == null || health.lastSuccessAt() == null) return false;
            Duration staleAfter = delay.multipliedBy(3);
            boolean stale = !health.lastSuccessAt().isAfter(now.minus(staleAfter));
            boolean failedAfterSuccess = health.lastFailureAt() != null
                    && health.lastFailureAt().isAfter(health.lastSuccessAt());
            OffsetDateTime localFailure = localFailureAt.get();
            boolean locallyFailedAfterSuccess = localFailure != null
                    && localFailure.isAfter(health.lastSuccessAt());
            boolean expiredActiveWorker = health.activeFence() != null
                    && (health.activeLeaseExpiresAt() == null
                        || !health.activeLeaseExpiresAt().isAfter(now));
            return !stale && !failedAfterSuccess && !locallyFailedAfterSuccess
                    && health.lastFailureAt() == null && !expiredActiveWorker;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public int purgeExpired() {
        if (!settingsValid()) return 0;
        UUID fence = UUID.randomUUID();
        OffsetDateTime attemptedAt = OffsetDateTime.now(clock);
        try {
            boolean acquired = transactions.attempt(
                    attemptedAt, attemptedAt.plus(properties.getLeaseDuration()), fence);
            if (!acquired) return 0;
            VideoMeetingIntelligenceModels.RetentionPurgeResult result =
                    transactions.purgeAndSucceed(
                    attemptedAt, properties.getBatchSize(), properties.getWorkerId(), fence);
            localFailureAt.set(null);
            return result.deletedCount();
        } catch (RuntimeException exception) {
            localFailureAt.set(OffsetDateTime.now(clock));
            try {
                transactions.fail(OffsetDateTime.now(clock), fence);
            } catch (RuntimeException ignored) {
                // The missing failure marker itself keeps readiness fail-closed.
            }
            return -1;
        }
    }

    private boolean settingsValid() {
        Duration delay = properties.getPollDelay();
        Duration lease = properties.getLeaseDuration();
        String workerId = properties.getWorkerId();
        return properties.isEnabled()
                && properties.getBatchSize() > 0 && properties.getBatchSize() <= 1_000
                && delay != null && !delay.isNegative() && !delay.isZero()
                && delay.compareTo(Duration.ofHours(24)) <= 0
                && lease != null && !lease.isNegative() && !lease.isZero()
                && lease.compareTo(delay) <= 0
                && workerId != null
                && workerId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$");
    }
}

@Component
@ConditionalOnProperty(
        prefix = "dwp.meeting.intelligence.retention",
        name = "enabled",
        havingValue = "true")
class MeetingIntelligenceRetentionWorker {

    private final MeetingIntelligenceRetentionService retention;

    MeetingIntelligenceRetentionWorker(MeetingIntelligenceRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${dwp.meeting.intelligence.retention.poll-delay:PT5M}")
    void purge() {
        retention.purgeExpired();
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(MeetingIntelligenceRetentionProperties.class)
class MeetingIntelligenceRetentionConfiguration {
}
