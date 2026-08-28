package com.dwp.services.provider.provisioning;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class ProviderOperationProjectionCoordinator {

    private final TransactionTemplate transactionTemplate;
    private final ProviderOperationLeaseRepository leaseRepository;
    private final ProviderOperationEvidenceRepository evidenceRepository;

    public ProviderOperationProjectionCoordinator(
            TransactionTemplate transactionTemplate,
            ProviderOperationLeaseRepository leaseRepository,
            ProviderOperationEvidenceRepository evidenceRepository) {
        this.transactionTemplate = transactionTemplate;
        this.leaseRepository = leaseRepository;
        this.evidenceRepository = evidenceRepository;
    }

    public ProjectionResult succeed(
            UUID operationId,
            UUID leaseToken,
            Duration leaseDuration,
            Long operationStepId,
            int attemptNumber,
            Supplier<ProjectionResult> projection) {
        ProjectionResult committed = transactionTemplate.execute(status -> {
            leaseRepository.renewOwned(operationId, leaseToken, leaseDuration);
            ProjectionResult result = Objects.requireNonNull(
                    projection.get(), "Provider operation projection returned no result.");
            evidenceRepository.succeedAttempt(
                    operationId, leaseToken, leaseDuration, operationStepId, attemptNumber,
                    result.externalReference(), result.redactedResult());
            return result;
        });
        return Objects.requireNonNull(committed, "Provider operation projection transaction returned no result.");
    }

    public void fail(
            UUID operationId,
            UUID leaseToken,
            Duration leaseDuration,
            Long operationStepId,
            int attemptNumber,
            String errorCode,
            String errorMessage,
            Runnable failureProjection,
            Runnable failureAudit) {
        transactionTemplate.executeWithoutResult(status -> {
            leaseRepository.renewOwned(operationId, leaseToken, leaseDuration);
            evidenceRepository.failAttempt(
                    operationId, leaseToken, leaseDuration, operationStepId, attemptNumber,
                    errorCode, errorMessage);
            failureProjection.run();
            failureAudit.run();
            leaseRepository.markPartial(operationId, leaseToken, errorCode, errorMessage);
        });
    }

    public void complete(
            UUID operationId,
            UUID leaseToken,
            Duration leaseDuration,
            Runnable successAudit) {
        transactionTemplate.executeWithoutResult(status -> {
            leaseRepository.renewOwned(operationId, leaseToken, leaseDuration);
            successAudit.run();
            leaseRepository.complete(operationId, leaseToken);
        });
    }

    public record ProjectionResult(String externalReference, String redactedResult) {
    }
}
