package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MeetingRecordRetentionService {
    private final MeetingRecordRetentionProperties properties;
    private final MeetingRecordRetentionHealthRepository health;
    private final MeetingRecordDispositionRepository records;
    private final MeetingRecordRetentionTransactions transactions;
    private final AtomicBoolean locallyFailed = new AtomicBoolean();
    public MeetingRecordRetentionService(MeetingRecordRetentionProperties properties,
            MeetingRecordRetentionHealthRepository health, MeetingRecordDispositionRepository records,
            MeetingRecordRetentionTransactions transactions) {
        this.properties = properties; this.health = health; this.records = records; this.transactions = transactions;
    }

    public boolean ready() {
        if (!valid() || locallyFailed.get()) return false;
        try {
            var state = health.read();
            var now = records.now();
            return state != null && state.lastSuccess() != null
                    && state.lastSuccess().isAfter(now.minus(properties.getPollDelay().multipliedBy(3)))
                    && state.lastFailure() == null && !state.blocked()
                    && (state.activeFence() == null || state.leaseUntil() != null && state.leaseUntil().isAfter(now));
        } catch (RuntimeException failure) { return false; }
    }

    public int purgeExpired() {
        if (!valid()) return 0;
        UUID fence = UUID.randomUUID();
        String worker = properties.getWorkerId();
        try {
            if (!transactions.claim(fence, worker, properties.getLeaseDuration())) return 0;
            int deleted = transactions.purgeAndSucceed(fence, worker, properties.getBatchSize());
            locallyFailed.set(false);
            return deleted;
        } catch (RuntimeException failure) {
            locallyFailed.set(true);
            try { transactions.fail(fence, worker); }
            catch (RuntimeException ignored) { /* Expired/lost leases remain durably unready; do not log payloads. */ }
            return -1;
        }
    }

    private boolean valid() {
        Duration delay = properties.getPollDelay(), lease = properties.getLeaseDuration();
        return properties.isEnabled() && properties.getBatchSize() > 0 && properties.getBatchSize() <= 100
                && delay != null && !delay.isZero() && !delay.isNegative() && delay.compareTo(Duration.ofHours(24)) <= 0
                && lease != null && !lease.isZero() && !lease.isNegative() && lease.compareTo(delay) <= 0
                && properties.getWorkerId() != null && properties.getWorkerId().matches("^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$");
    }
}

@Component
@ConditionalOnProperty(prefix = "dwp.meeting.record-retention", name = "enabled", havingValue = "true")
class MeetingRecordRetentionWorker {
    private final MeetingRecordRetentionService service;
    MeetingRecordRetentionWorker(MeetingRecordRetentionService service) { this.service = service; }
    @Scheduled(fixedDelayString = "${dwp.meeting.record-retention.poll-delay:PT5M}")
    void purge() { service.purgeExpired(); }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeetingRecordRetentionProperties.class)
class MeetingRecordRetentionConfiguration { }
