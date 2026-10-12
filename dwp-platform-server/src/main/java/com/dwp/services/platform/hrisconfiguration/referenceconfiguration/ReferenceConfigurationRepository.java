package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;

interface ReferenceConfigurationRepository {

    void lockIdempotency(long tenantId, String idempotencyKey);

    Optional<StoredReceipt> findReceipt(long tenantId, String idempotencyKey);

    void saveReceipt(long tenantId, String idempotencyKey, String requestDigest, CommandReceipt receipt);

    long createVersion(
            long tenantId,
            UUID configurationId,
            long actorId,
            CreateVersionCommand command,
            OffsetDateTime now);

    Optional<VersionDetail> findVersion(long tenantId, UUID configurationId, long version);

    List<CatalogItem> list(long tenantId, int limit);

    boolean transition(
            long tenantId,
            UUID configurationId,
            long version,
            long expectedAggregateVersion,
            LifecycleState expectedState,
            LifecycleState targetState,
            TransitionEvidence evidence,
            OffsetDateTime now);

    long rollback(
            long tenantId,
            UUID configurationId,
            long currentVersion,
            long targetVersion,
            long expectedAggregateVersion,
            long actorId,
            RollbackCommand command,
            OffsetDateTime now);

    void appendOutbox(
            long tenantId,
            UUID configurationId,
            long version,
            String eventType,
            String payload,
            OffsetDateTime now);

    void appendAudit(
            long tenantId,
            UUID configurationId,
            long version,
            String action,
            long actorId,
            String correlationId,
            String evidenceDigest,
            OffsetDateTime now);

    record StoredReceipt(String requestDigest, CommandReceipt receipt) {
    }

    record TransitionEvidence(
            Long actorId,
            UUID validationReceiptId,
            UUID simulationReceiptId,
            UUID approvalReceiptId,
            String impactDigest,
            String approvalDecisionDigest,
            OffsetDateTime scheduledAt,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            Long replacementVersion,
            String reasonCode) {
        static TransitionEvidence actor(long actorId) {
            return new TransitionEvidence(
                    actorId, null, null, null, null, null, null, null, null, null, null);
        }
    }
}
