package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class MeetingIntelligenceRetentionTransactions {

    private final VideoMeetingIntelligenceRepository repository;

    public MeetingIntelligenceRetentionTransactions(
            VideoMeetingIntelligenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean attempt(
            OffsetDateTime attemptedAt,
            OffsetDateTime leaseExpiresAt,
            UUID fence) {
        return repository.tryMarkRetentionAttempt(attemptedAt, leaseExpiresAt, fence);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VideoMeetingIntelligenceModels.RetentionPurgeResult purgeAndSucceed(
            OffsetDateTime now, int batchSize, String workerId, UUID fence) {
        VideoMeetingIntelligenceModels.RetentionPurgeResult result =
                repository.purgeExpiredReports(now, batchSize, workerId, fence);
        repository.markRetentionSuccess(
                OffsetDateTime.now(java.time.ZoneOffset.UTC),
                fence, !result.overdueRemaining());
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(OffsetDateTime failedAt, UUID fence) {
        repository.markRetentionFailure(failedAt, fence, "RETENTION_PURGE_FAILED");
    }
}
