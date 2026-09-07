package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class MeetingScheduleDraftRetentionTransactions {

    private final MeetingScheduleDraftRetentionRepository repository;

    public MeetingScheduleDraftRetentionTransactions(
            MeetingScheduleDraftRetentionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean attempt(
            OffsetDateTime attemptedAt,
            OffsetDateTime leaseExpiresAt,
            UUID fence,
            String workerId) {
        return repository.tryClaim(attemptedAt, leaseExpiresAt, fence, workerId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MeetingScheduleDraftRetentionRepository.PurgeResult purgeAndSucceed(
            OffsetDateTime now,
            int batchSize,
            UUID executionId,
            UUID fence,
            String workerId) {
        var result = repository.purgeExpired(now, batchSize, executionId, fence, workerId);
        repository.markSuccess(now, fence, workerId, result.overdueRemaining());
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(OffsetDateTime failedAt, UUID fence, String workerId) {
        repository.markFailure(failedAt, fence, workerId, "DRAFT_RETENTION_PURGE_FAILED");
    }
}
