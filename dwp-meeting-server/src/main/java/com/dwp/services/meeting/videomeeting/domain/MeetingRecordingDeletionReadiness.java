package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.Health;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class MeetingRecordingDeletionReadiness {

    private final MeetingRecordingDeletionRepository repository;
    private final MeetingRecordingDeletionProperties properties;
    private final MeetingRecordingHttpProperties recordingProperties;
    private final Clock clock;
    private final AtomicReference<OffsetDateTime> localFailureAt = new AtomicReference<>();

    @Autowired
    public MeetingRecordingDeletionReadiness(
            MeetingRecordingDeletionRepository repository,
            MeetingRecordingDeletionProperties properties,
            MeetingRecordingHttpProperties recordingProperties) {
        this(repository, properties, recordingProperties, Clock.systemUTC());
    }

    MeetingRecordingDeletionReadiness(
            MeetingRecordingDeletionRepository repository,
            MeetingRecordingDeletionProperties properties,
            Clock clock) {
        this(repository, properties, new MeetingRecordingHttpProperties(), clock);
    }

    MeetingRecordingDeletionReadiness(
            MeetingRecordingDeletionRepository repository,
            MeetingRecordingDeletionProperties properties,
            MeetingRecordingHttpProperties recordingProperties,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.recordingProperties = recordingProperties;
        this.clock = clock;
    }

    public boolean ready() {
        if (!validConfiguration()) return false;
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (localFailureAt.get() != null) return false;
            Health health = repository.health().orElse(null);
            if (health == null || health.lastSuccessAt() == null
                    || !health.lastSuccessAt().isAfter(now.minus(staleAfter()))) {
                return false;
            }
            if (health.lastFailureAt() != null
                    && !health.lastFailureAt().isBefore(health.lastSuccessAt())) {
                return false;
            }
            if (health.activeFence() != null
                    && (health.activeLeaseExpiresAt() == null
                        || !health.activeLeaseExpiresAt().isAfter(now))) {
                return false;
            }
            return repository.overdueLocatorCount(now) == 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean ready(MeetingRecordingProvider.Capability capability) {
        return capability != null && capability.available()
                && capability.deletionAvailable() && capability.cryptoShredAvailable()
                && ready();
    }

    public void requireReady() {
        if (!ready()) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Governed recording retention is not ready.");
        }
    }

    void markLocalFailure() {
        localFailureAt.compareAndSet(null, OffsetDateTime.now(clock));
    }

    void markLocalSuccess() {
        localFailureAt.set(null);
    }

    OffsetDateTime localFailureAt() {
        return localFailureAt.get();
    }

    boolean validConfiguration() {
        Duration poll = properties.getPollDelay();
        Duration lease = properties.getLeaseDuration();
        Duration requestTimeout = recordingProperties.getRequestTimeout();
        String worker = properties.getWorkerId();
        return properties.isEnabled()
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
}
