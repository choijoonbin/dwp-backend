package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.Health;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class MeetingTranscriptDeletionReadiness {

    private final MeetingTranscriptDeletionRepository repository;
    private final MeetingTranscriptDeletionProperties properties;
    private final MeetingTranscriptHttpProperties transcriptProperties;
    private final Clock clock;
    private final AtomicReference<OffsetDateTime> localFailureAt = new AtomicReference<>();

    @Autowired
    public MeetingTranscriptDeletionReadiness(
            MeetingTranscriptDeletionRepository repository,
            MeetingTranscriptDeletionProperties properties,
            MeetingTranscriptHttpProperties transcriptProperties) {
        this(repository, properties, transcriptProperties, Clock.systemUTC());
    }

    MeetingTranscriptDeletionReadiness(
            MeetingTranscriptDeletionRepository repository,
            MeetingTranscriptDeletionProperties properties,
            MeetingTranscriptHttpProperties transcriptProperties,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.transcriptProperties = transcriptProperties;
        this.clock = clock;
    }

    public boolean ready() {
        return snapshotIfReady() != null;
    }

    public RetentionSnapshot requireSnapshot() {
        RetentionSnapshot snapshot = snapshotIfReady();
        if (snapshot == null) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Governed transcript retention is not ready.");
        }
        return snapshot;
    }

    public RetentionSnapshot requireSnapshot(
            MeetingTranscriptSource.RetentionCapability capability) {
        RetentionSnapshot snapshot = requireSnapshot();
        if (capability == null || !capability.available()
                || !capability.deletionAvailable() || !capability.cryptoShredAvailable()
                || !capability.customerManagedStorage()
                || !capability.providerRetentionDisabled()
                || !capability.orphanCleanupAvailable()
                || capability.maximumOrphanTtlSeconds() < 30
                || capability.maximumOrphanTtlSeconds() > 3_600
                || !snapshot.providerCode().equals(capability.providerCode())
                || !snapshot.storageProviderCode().equals(
                        capability.storageProviderCode())) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Governed transcript retention provider is not ready.");
        }
        return snapshot;
    }

    private RetentionSnapshot snapshotIfReady() {
        if (!validConfiguration()) return null;
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (localFailureAt.get() != null) return null;
            Health health = repository.health().orElse(null);
            if (health == null || health.lastSuccessAt() == null
                    || !health.lastSuccessAt().isAfter(now.minus(staleAfter()))
                    || health.lastProviderCode() == null
                    || !health.lastProviderCode().matches(
                            "^[A-Z][A-Z0-9_-]{2,47}$")
                    || health.lastStorageProviderCode() == null
                    || !health.lastStorageProviderCode().matches(
                            "^[A-Z][A-Z0-9_-]{1,31}$")) {
                return null;
            }
            if (health.lastFailureAt() != null
                    && !health.lastFailureAt().isBefore(health.lastSuccessAt())) {
                return null;
            }
            if (health.activeFence() != null
                    && (health.activeLeaseExpiresAt() == null
                        || !health.activeLeaseExpiresAt().isAfter(now))) {
                return null;
            }
            return repository.overdueLocatorCount(now) == 0
                    ? new RetentionSnapshot(
                            health.lastProviderCode(), health.lastStorageProviderCode(),
                            health.lastSuccessAt())
                    : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public void requireReady() {
        requireSnapshot();
    }

    void markLocalFailure() {
        localFailureAt.compareAndSet(null, OffsetDateTime.now(clock));
    }

    void markLocalSuccess() {
        localFailureAt.set(null);
    }

    boolean validConfiguration() {
        Duration poll = properties.getPollDelay();
        Duration lease = properties.getLeaseDuration();
        Duration requestTimeout = transcriptProperties.getRequestTimeout();
        String worker = properties.getWorkerId();
        return properties.isEnabled()
                && "http".equals(transcriptProperties.getProvider())
                && properties.getBatchSize() >= 1 && properties.getBatchSize() <= 500
                && poll != null && poll.compareTo(Duration.ofSeconds(1)) >= 0
                && poll.compareTo(Duration.ofHours(1)) <= 0
                && lease != null && lease.compareTo(Duration.ofSeconds(30)) >= 0
                && lease.compareTo(Duration.ofMinutes(10)) <= 0
                && requestTimeout != null
                && requestTimeout.compareTo(Duration.ofMillis(250)) >= 0
                && requestTimeout.compareTo(Duration.ofSeconds(30)) <= 0
                && lease.compareTo(requestTimeout.plusSeconds(5)) >= 0
                && worker != null
                && worker.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$");
    }

    private Duration staleAfter() {
        Duration value = properties.getPollDelay().multipliedBy(3);
        return value.compareTo(Duration.ofMinutes(1)) < 0
                ? Duration.ofMinutes(1) : value;
    }

    public record RetentionSnapshot(
            String providerCode,
            String storageProviderCode,
            OffsetDateTime verifiedAt) {
    }
}
