package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationRetentionRepository.PurgeResult;
import com.dwp.services.notification.operations.NotificationRetentionRepository.RetentionInputs;
import com.dwp.services.notification.realtime.NotificationChangeCause;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Duration qualityFactTtl;
    private final int batchSize;

    @Autowired
    public NotificationRetentionService(
            NotificationDatabaseScope databaseScope,
            NotificationRetentionRepository repository,
            NotificationChangePublisher changePublisher,
            @Value("${dwp.notification.retention.default-ttl:P0D}") Duration defaultTtl,
            @Value("${dwp.notification.retention.admission-receipt-ttl:P7D}")
            Duration admissionReceiptTtl,
            @Value("${dwp.notification.retention.quality-fact-ttl:P180D}")
            Duration qualityFactTtl,
            @Value("${dwp.notification.retention.batch-size:100}") int batchSize) {
        if (defaultTtl.isNegative()
                || (!defaultTtl.isZero()
                    && (defaultTtl.compareTo(Duration.ofDays(1)) < 0
                        || defaultTtl.compareTo(Duration.ofDays(365)) > 0))
                || admissionReceiptTtl.compareTo(Duration.ofDays(1)) < 0
                || qualityFactTtl.compareTo(Duration.ofDays(30)) < 0
                || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Notification retention configuration is invalid.");
        }
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.changePublisher = changePublisher;
        this.defaultTtl = defaultTtl;
        this.admissionReceiptTtl = admissionReceiptTtl;
        this.qualityFactTtl = qualityFactTtl;
        this.batchSize = batchSize;
    }

    public NotificationRetentionService(
            NotificationDatabaseScope databaseScope,
            NotificationRetentionRepository repository,
            NotificationChangePublisher changePublisher,
            Duration defaultTtl,
            Duration admissionReceiptTtl,
            int batchSize) {
        this(databaseScope, repository, changePublisher, defaultTtl,
                admissionReceiptTtl, Duration.ofDays(180), batchSize);
    }

    public void applyEffectiveExpiry(
            long tenantId,
            UUID notificationId,
            UUID typeVersionId,
            Instant lastActivityAt) {
        RetentionDecision decision = decision(
                repository.retentionInputs(tenantId, typeVersionId));
        if (decision == null) return;
        repository.applyExpiry(
                tenantId,
                notificationId,
                lastActivityAt.plus(decision.ttl()),
                decision.source(),
                decision.ttl().toSeconds());
    }

    RetentionDecision decision(RetentionInputs inputs) {
        if (inputs.contractOverrideTtlSeconds() != null) {
            return new RetentionDecision(
                    "CONTRACT_OVERRIDE",
                    Duration.ofSeconds(inputs.contractOverrideTtlSeconds()));
        }
        if (inputs.tenantDefaultTtlSeconds() != null) {
            return new RetentionDecision(
                    "TENANT_DEFAULT",
                    Duration.ofSeconds(inputs.tenantDefaultTtlSeconds()));
        }
        return defaultTtl.isZero()
                ? null
                : new RetentionDecision("PLATFORM_DEFAULT", defaultTtl);
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
        repository.cleanupQualityFacts(
                tenantId, now.minus(qualityFactTtl), batchSize);
        repository.cleanupBulkUndoReceipts(tenantId, now, batchSize);
        changePublisher.publishAfterCommit(
                result.signals(), NotificationChangeCause.SYSTEM_RECONCILIATION);
        return result;
    }

    record RetentionDecision(String source, Duration ttl) {
    }
}
