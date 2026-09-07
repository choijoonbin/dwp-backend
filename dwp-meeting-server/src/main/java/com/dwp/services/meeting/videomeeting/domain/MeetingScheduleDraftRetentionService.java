package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MeetingScheduleDraftRetentionService {

    private final MeetingScheduleDraftRetentionRepository repository;
    private final MeetingScheduleDraftRetentionTransactions transactions;
    private final MeetingScheduleDraftRetentionProperties properties;
    private final Clock clock;
    private final AtomicReference<OffsetDateTime> localFailureAt = new AtomicReference<>();

    @Autowired
    public MeetingScheduleDraftRetentionService(
            MeetingScheduleDraftRetentionRepository repository,
            MeetingScheduleDraftRetentionTransactions transactions,
            MeetingScheduleDraftRetentionProperties properties) {
        this(repository, transactions, properties, Clock.systemUTC());
    }

    MeetingScheduleDraftRetentionService(
            MeetingScheduleDraftRetentionRepository repository,
            MeetingScheduleDraftRetentionTransactions transactions,
            MeetingScheduleDraftRetentionProperties properties,
            Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean ready() {
        if (!settingsValid()) return false;
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);
            var health = repository.health().orElse(null);
            if (health == null || health.lastSuccessAt() == null) return false;
            boolean stale = !health.lastSuccessAt().isAfter(
                    now.minus(properties.getPollDelay().multipliedBy(3)));
            boolean failedAfterSuccess = health.lastFailureAt() != null
                    && health.lastFailureAt().isAfter(health.lastSuccessAt());
            OffsetDateTime localFailure = localFailureAt.get();
            boolean localFailedAfterSuccess = localFailure != null
                    && localFailure.isAfter(health.lastSuccessAt());
            boolean expiredLease = health.activeFence() != null
                    && (health.activeLeaseExpiresAt() == null
                    || !health.activeLeaseExpiresAt().isAfter(now));
            return !stale && !failedAfterSuccess && !localFailedAfterSuccess
                    && !health.overdueRemaining() && !expiredLease;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public int purgeExpired() {
        if (!settingsValid()) return 0;
        UUID fence = UUID.randomUUID();
        String worker = properties.getWorkerId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            if (!transactions.attempt(now, now.plus(properties.getLeaseDuration()), fence, worker)) {
                return 0;
            }
            var result = transactions.purgeAndSucceed(
                    now, properties.getBatchSize(), UUID.randomUUID(), fence, worker);
            localFailureAt.set(null);
            return result.deletedCount();
        } catch (RuntimeException exception) {
            localFailureAt.set(OffsetDateTime.now(clock));
            try {
                transactions.fail(OffsetDateTime.now(clock), fence, worker);
            } catch (RuntimeException ignored) {
                // A lost/expired fence remains fail-closed in durable health state.
            }
            return -1;
        }
    }

    private boolean settingsValid() {
        Duration delay = properties.getPollDelay();
        Duration lease = properties.getLeaseDuration();
        String worker = properties.getWorkerId();
        return properties.isEnabled()
                && properties.getBatchSize() > 0 && properties.getBatchSize() <= 1_000
                && delay != null && !delay.isZero() && !delay.isNegative()
                && delay.compareTo(Duration.ofHours(24)) <= 0
                && lease != null && !lease.isZero() && !lease.isNegative()
                && lease.compareTo(delay) <= 0
                && worker != null
                && worker.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$");
    }
}

@Component
@ConditionalOnProperty(
        prefix = "dwp.meeting.schedule-draft-retention",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class MeetingScheduleDraftRetentionWorker {

    private final MeetingScheduleDraftRetentionService retention;

    MeetingScheduleDraftRetentionWorker(MeetingScheduleDraftRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${dwp.meeting.schedule-draft-retention.poll-delay:PT5M}")
    void purge() {
        retention.purgeExpired();
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeetingScheduleDraftRetentionProperties.class)
class MeetingScheduleDraftRetentionConfiguration {
}
