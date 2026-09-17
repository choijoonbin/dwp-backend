package com.dwp.services.platform.workplace.connectorops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Bounded redaction of operator free text and provider result details after policy expiry. */
@Component
class WorkplaceConnectorReplayRetention {
    private static final Logger log = LoggerFactory.getLogger(WorkplaceConnectorReplayRetention.class);

    private final WorkplaceConnectorOpsRepository repository;
    private final boolean enabled;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    WorkplaceConnectorReplayRetention(
            WorkplaceConnectorOpsRepository repository,
            @Value("${dwp.workplace.connector-runtime.retention-enabled:true}") boolean enabled,
            @Value("${dwp.workplace.connector-runtime.retention-batch-size:200}") int batchSize) {
        this(repository, enabled, batchSize, Clock.systemUTC());
    }

    WorkplaceConnectorReplayRetention(
            WorkplaceConnectorOpsRepository repository,
            boolean enabled,
            int batchSize,
            Clock clock) {
        if (batchSize < 1 || batchSize > 2000) {
            throw new IllegalArgumentException("retention batchSize must be between 1 and 2000");
        }
        this.repository = repository;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(cron = "${dwp.workplace.connector-runtime.retention-cron:0 37 2 * * *}")
    @Transactional
    void redactExpiredPayloads() {
        if (!enabled) return;
        int redacted = repository.purgeExpiredSensitivePayloads(
                batchSize, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        if (redacted > 0) log.info("Redacted {} expired connector replay payloads", redacted);
    }
}
