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
public class MeetingChatRetentionService {

    private final MeetingChatRetentionRepository repository;
    private final MeetingChatRetentionTransactions transactions;
    private final MeetingChatRetentionProperties properties;
    private final Clock clock;
    private final AtomicReference<OffsetDateTime> localFailureAt = new AtomicReference<>();

    @Autowired
    public MeetingChatRetentionService(
            MeetingChatRetentionRepository repository,
            MeetingChatRetentionTransactions transactions,
            MeetingChatRetentionProperties properties) {
        this(repository, transactions, properties, Clock.systemUTC());
    }

    MeetingChatRetentionService(
            MeetingChatRetentionRepository repository,
            MeetingChatRetentionTransactions transactions,
            MeetingChatRetentionProperties properties,
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
            MeetingChatRetentionRepository.Health health = repository.health().orElse(null);
            if (health == null || health.lastSuccessAt() == null) return false;
            Duration staleAfter = properties.getPollDelay().multipliedBy(3);
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
                    && health.lastFailureAt() == null && !health.overdueRemaining()
                    && !expiredActiveWorker;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public int purgeExpired() {
        if (!settingsValid()) return 0;
        UUID fence = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        OffsetDateTime attemptedAt = OffsetDateTime.now(clock);
        String workerId = properties.getWorkerId();
        try {
            boolean acquired = transactions.attempt(
                    attemptedAt, attemptedAt.plus(properties.getLeaseDuration()),
                    fence, workerId);
            if (!acquired) return 0;
            MeetingChatRetentionRepository.PurgeResult result =
                    transactions.purgeAndSucceed(
                            attemptedAt, properties.getBatchSize(), executionId,
                            fence, workerId);
            localFailureAt.set(null);
            return result.deletedCount();
        } catch (RuntimeException exception) {
            localFailureAt.set(OffsetDateTime.now(clock));
            try {
                transactions.fail(OffsetDateTime.now(clock), fence, workerId);
            } catch (RuntimeException ignored) {
                // An expired or lost fence remains fail-closed in durable readiness state.
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
        prefix = "dwp.meeting.chat-retention",
        name = "enabled",
        havingValue = "true")
class MeetingChatRetentionWorker {

    private final MeetingChatRetentionService retention;

    MeetingChatRetentionWorker(MeetingChatRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${dwp.meeting.chat-retention.poll-delay:PT5M}")
    void purge() {
        retention.purgeExpired();
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeetingChatRetentionProperties.class)
class MeetingChatRetentionConfiguration {
}
