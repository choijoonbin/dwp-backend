package com.dwp.services.platform.workplace.workplaceassistant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
public class WorkplaceAssistantRetentionService {
    private final WorkplaceAssistantAuditRepository repository;
    private final Clock clock;

    @Autowired
    public WorkplaceAssistantRetentionService(WorkplaceAssistantAuditRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WorkplaceAssistantRetentionService(
            WorkplaceAssistantAuditRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${dwp.workplace.assistant.retention-delay-ms:3600000}")
    @Transactional
    public int redactExpiredContent() {
        return repository.redactExpired(OffsetDateTime.now(clock));
    }
}
