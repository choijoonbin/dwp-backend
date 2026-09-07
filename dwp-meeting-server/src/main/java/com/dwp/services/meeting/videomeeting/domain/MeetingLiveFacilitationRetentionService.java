package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.audit.MeetingLiveFacilitationAuditRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class MeetingLiveFacilitationRetentionService {

    private final MeetingLiveFacilitationRepository repository;
    private final MeetingLiveFacilitationAuditRecorder audit;
    private final Clock clock;

    @Autowired
    public MeetingLiveFacilitationRetentionService(
            MeetingLiveFacilitationRepository repository,
            MeetingLiveFacilitationAuditRecorder audit) {
        this(repository, audit, Clock.systemUTC());
    }

    MeetingLiveFacilitationRetentionService(
            MeetingLiveFacilitationRepository repository,
            MeetingLiveFacilitationAuditRecorder audit,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${dwp.meeting.facilitation.retention.poll-delay:PT5M}")
    @Transactional
    public void purgeExpired() {
        UUID executionId = UUID.randomUUID();
        int deleted = 0;
        for (MeetingLiveFacilitationModels.ExpiredFacilitation item : repository.expired(50)) {
            if (repository.delete(item) == 0) continue;
            audit.retention(item.tenantId(), item.meetingId(), executionId);
            deleted++;
        }
        repository.saveRetentionEvidence(executionId, deleted, OffsetDateTime.now(clock));
    }
}
