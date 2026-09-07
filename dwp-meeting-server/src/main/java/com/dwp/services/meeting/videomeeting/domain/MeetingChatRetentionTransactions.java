package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.audit.MeetingChatRetentionAuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class MeetingChatRetentionTransactions {

    private final MeetingChatRetentionRepository repository;
    private final MeetingChatRetentionAuditRecorder audit;

    public MeetingChatRetentionTransactions(
            MeetingChatRetentionRepository repository,
            MeetingChatRetentionAuditRecorder audit) {
        this.repository = repository;
        this.audit = audit;
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
    public MeetingChatRetentionRepository.PurgeResult purgeAndSucceed(
            OffsetDateTime now,
            int batchSize,
            UUID executionId,
            UUID fence,
            String workerId) {
        MeetingChatRetentionRepository.PurgeResult result = repository.purgeExpired(
                now, batchSize, executionId, fence, workerId);
        result.messages().forEach(message -> audit.purged(
                message.tenantId(), message.meetingId(), message.messageId(),
                executionId, fence, workerId));
        repository.markSuccess(
                OffsetDateTime.now(ZoneOffset.UTC), fence, workerId,
                result.overdueRemaining());
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            OffsetDateTime failedAt,
            UUID fence,
            String workerId) {
        repository.markFailure(
                failedAt, fence, workerId, "CHAT_RETENTION_PURGE_FAILED");
    }
}
