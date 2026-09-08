package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.audit.MeetingRecordRetentionAuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.UUID;

@Service
public class MeetingRecordRetentionTransactions {
    private final MeetingRecordRetentionHealthRepository health;
    private final MeetingRecordDispositionRepository records;
    private final MeetingRecordRetentionGuard guard;
    private final MeetingRecordPurgeRepository purge;
    private final MeetingRecordRetentionAuditRecorder audit;
    public MeetingRecordRetentionTransactions(MeetingRecordRetentionHealthRepository health,
            MeetingRecordDispositionRepository records, MeetingRecordRetentionGuard guard,
            MeetingRecordPurgeRepository purge, MeetingRecordRetentionAuditRecorder audit) {
        this.health = health; this.records = records; this.guard = guard; this.purge = purge; this.audit = audit;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID fence, String worker, Duration lease) { return health.claim(fence, worker, lease); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeAndSucceed(UUID fence, String worker, int batchSize) {
        health.assertLease(fence, worker);
        int deleted = 0;
        for (var scope : records.candidates(batchSize)) {
            records.lockScope(scope.tenant(), scope.meeting());
            var control = records.control(scope.tenant(), scope.meeting(), true);
            if (control == null || !control.authorized() || control.hold() || control.purgedAt() != null) continue;
            var snapshot = records.snapshot(scope.tenant(), scope.meeting(), true);
            records.evaluated(scope.tenant(), scope.meeting());
            if (!guard.reasons(scope.tenant(), scope.meeting(), snapshot, control, true).isEmpty()) continue;
            // Assert again after potentially waiting for owner/content locks. clock_timestamp fences long TXs.
            health.assertLease(fence, worker);
            UUID deletion = UUID.randomUUID();
            UUID event = audit.purged(scope.tenant(), scope.meeting(), worker, fence, deletion);
            purge.purge(control, deletion, event, fence, worker);
            deleted++;
        }
        health.finish(fence, worker, !records.candidates(1).isEmpty());
        return deleted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID fence, String worker) { health.fail(fence, worker); }
}
