package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationRetentionRepository.PurgeResult;
import com.dwp.services.notification.realtime.NotificationChangeCause;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class NotificationRetentionService {

    private final NotificationDatabaseScope databaseScope;
    private final NotificationRetentionRepository repository;
    private final NotificationChangePublisher changePublisher;
    private final Duration defaultTtl;
    private final Duration admissionReceiptTtl;
    private final int batchSize;

    public NotificationRetentionService(
            NotificationDatabaseScope databaseScope,
            NotificationRetentionRepository repository,
            NotificationChangePublisher changePublisher,
            @Value("${dwp.notification.retention.default-ttl:P0D}") Duration defaultTtl,
            @Value("${dwp.notification.retention.admission-receipt-ttl:P7D}")
            Duration admissionReceiptTtl,
            @Value("${dwp.notification.retention.batch-size:100}") int batchSize) {
        if (defaultTtl.isNegative()
                || admissionReceiptTtl.compareTo(Duration.ofDays(1)) < 0
                || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Notification retention configuration is invalid.");
        }
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.changePublisher = changePublisher;
        this.defaultTtl = defaultTtl;
        this.admissionReceiptTtl = admissionReceiptTtl;
        this.batchSize = batchSize;
    }

    public void applyDefaultExpiry(
            long tenantId,
            UUID notificationId,
            Instant lastActivityAt) {
        if (defaultTtl.isZero()) return;
        repository.extendExpiry(tenantId, notificationId, lastActivityAt.plus(defaultTtl));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PurgeResult purgeTenant(long tenantId, Instant now) {
        databaseScope.applyWorker(tenantId);
        PurgeResult result = repository.purgeExpired(tenantId, now, batchSize);
        repository.cleanupAdmissionHistory(
                tenantId,
                now.minus(admissionReceiptTtl),
                now.minus(Duration.ofDays(2)),
                batchSize);
        repository.cleanupBulkUndoReceipts(tenantId, now, batchSize);
        changePublisher.publishAfterCommit(
                result.signals(), NotificationChangeCause.SYSTEM_RECONCILIATION);
        return result;
    }
}
