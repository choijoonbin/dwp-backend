package com.dwp.services.platform.hrisconfiguration.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;

/** Atomic execution/item/attempt persistence boundary. */
interface IntegrationExecutionRepository {

    record AttemptRecord(
            UUID attemptId,
            UUID itemId,
            UUID itemPublicId,
            int attemptNumber,
            long attemptFence,
            String providerIdempotencyKey,
            String requestDigest) {}

    record CompensationClaim(
            ExecutionRecord execution,
            String claimDigest,
            boolean owner) {}

    ExecutionRecord beginDispatch(
            ExecutionRecord current, long expectedFence, OffsetDateTime now);

    List<AttemptRecord> currentAttempts(ExecutionRecord current);

    default AttemptRecord currentAttempt(ExecutionRecord current) {
        List<AttemptRecord> attempts = currentAttempts(current);
        if (attempts.isEmpty()) {
            throw new IllegalStateException("SYS008_CURRENT_ATTEMPT_REQUIRED");
        }
        return attempts.getLast();
    }

    ExecutionRecord transitionExecution(
            ExecutionRecord current,
            ExecutionState target,
            OffsetDateTime now);

    ExecutionRecord lockExecutionAuthorized(
            long tenantId,
            UUID executionId,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId);

    CompensationClaim claimCompensation(
            ExecutionRecord current,
            String claimDigest,
            Long expectedRevision,
            OffsetDateTime now);

    ExecutionRecord markCompensationResultUnknown(
            ExecutionRecord current,
            String operationsExceptionRef,
            OffsetDateTime now);

    ExecutionRecord beginReconciliation(
            ExecutionRecord current,
            String providerReceiptRef,
            String providerReceiptDigest,
            OffsetDateTime now);

    ExecutionRecord applyProviderEvidence(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence,
            ExecutionState target,
            OffsetDateTime now);

    List<UUID> retryableItemIds(ExecutionRecord current);

    ExecutionRecord createRetryAttempts(
            ExecutionRecord current,
            List<UUID> exactFailedItemIds,
            String failedItemSetDigest,
            long actorId,
            OffsetDateTime now);

    ExecutionRecord deadLetter(
            ExecutionRecord current,
            long actorId,
            OffsetDateTime now);

    ExecutionRecord cancel(
            ExecutionRecord current,
            String compensationPolicyRef,
            String compensationPolicyDigest,
            String compensationReceiptRef,
            String compensationReceiptDigest,
            long actorId,
            OffsetDateTime now);
}
