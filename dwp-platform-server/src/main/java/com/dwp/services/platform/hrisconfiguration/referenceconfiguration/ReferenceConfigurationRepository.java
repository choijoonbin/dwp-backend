package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;

interface ReferenceConfigurationRepository {

    void lockIdempotency(
            long tenantId,
            long subjectId,
            String originatingAction,
            String idempotencyKey);

    Optional<StoredReceipt> findReceipt(
            long tenantId,
            long subjectId,
            String originatingAction,
            String idempotencyKey);

    Optional<StoredReceipt> findReceiptById(long tenantId, UUID receiptId);

    void saveReceipt(
            long tenantId,
            long subjectId,
            String originatingAction,
            String idempotencyKey,
            String requestDigest,
            CommandReceipt receipt);

    long createVersion(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long actorId,
            CreateVersionCommand command,
            OffsetDateTime now);

    Optional<VersionDetail> findVersion(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long version);

    CatalogPage queryCatalog(
            long tenantId,
            PopulationScope scope,
            OffsetDateTime asOf,
            CatalogFilter filter,
            UUID seekAfter,
            int limit);

    WorkbenchPage queryWorkbench(
            long tenantId,
            PopulationScope scope,
            WorkbenchQuery query,
            OffsetDateTime asOf,
            UUID seekAfter,
            int limit);

    boolean transition(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long version,
            long expectedAggregateVersion,
            LifecycleState expectedState,
            LifecycleState targetState,
            TransitionEvidence evidence,
            OffsetDateTime now);

    long rollback(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long currentVersion,
            long targetVersion,
            long expectedAggregateVersion,
            long actorId,
            RollbackCommand command,
            String ownerImpactDigest,
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

    record CatalogPage(List<CatalogItem> items, boolean hasNext, boolean seekFound) {
    }

    record WorkbenchRow(CatalogItem item, VersionDetail detail) {
    }

    record WorkbenchPage(List<WorkbenchRow> rows, boolean hasNext, boolean seekFound) {
    }

    record TransitionEvidence(
            Long actorId,
            ValidationFact validation,
            SimulationFact simulation,
            SubmissionFact submission,
            ApprovalFact approval,
            OffsetDateTime scheduledAt,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            Long replacementVersion,
            String reasonCode) {

        static TransitionEvidence validation(long actorId, ValidationFact fact) {
            return new TransitionEvidence(
                    actorId, fact, null, null, null, null, null, null, null, null);
        }

        static TransitionEvidence simulation(long actorId, SimulationFact fact) {
            return new TransitionEvidence(
                    actorId, null, fact, null, null, null, null, null, null, null);
        }

        static TransitionEvidence submission(long actorId, SubmissionFact fact) {
            return new TransitionEvidence(
                    actorId, null, null, fact, null, null, null, null, null, null);
        }

        static TransitionEvidence approval(long actorId, ApprovalFact fact) {
            return new TransitionEvidence(
                    actorId, null, null, null, fact, null, null, null, null, null);
        }

        static TransitionEvidence schedule(
                long actorId,
                OffsetDateTime scheduledAt,
                OffsetDateTime effectiveFrom,
                OffsetDateTime effectiveTo) {
            return new TransitionEvidence(
                    actorId, null, null, null, null,
                    scheduledAt, effectiveFrom, effectiveTo, null, null);
        }

        static TransitionEvidence publish(
                long actorId,
                OffsetDateTime effectiveFrom,
                OffsetDateTime effectiveTo) {
            return new TransitionEvidence(
                    actorId, null, null, null, null,
                    null, effectiveFrom, effectiveTo, null, null);
        }

        static TransitionEvidence retire(
                long actorId,
                OffsetDateTime effectiveTo,
                Long replacementVersion,
                String reasonCode) {
            return new TransitionEvidence(
                    actorId, null, null, null, null,
                    null, null, effectiveTo, replacementVersion, reasonCode);
        }
    }
}
