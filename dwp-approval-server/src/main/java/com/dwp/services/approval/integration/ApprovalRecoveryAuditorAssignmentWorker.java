package com.dwp.services.approval.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "dwp.approval.recovery-auditor-assignment.enabled",
        havingValue = "true")
public class ApprovalRecoveryAuditorAssignmentWorker {

    private static final Logger log = LoggerFactory.getLogger(
            ApprovalRecoveryAuditorAssignmentWorker.class);

    private final ApprovalRecoveryAuditorAssignmentRepository repository;
    private final ApprovalRecoveryAuditorResolver resolver;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maximumAttempts;
    private final long probeCooldownSeconds;
    private final long maximumProbeCooldownSeconds;
    private final String workerId = "approval-recovery-auditor-" + UUID.randomUUID();

    public ApprovalRecoveryAuditorAssignmentWorker(
            ApprovalRecoveryAuditorAssignmentRepository repository,
            ApprovalRecoveryAuditorResolver resolver,
            @Value("${dwp.approval.recovery-auditor-assignment.batch-size:50}") int batchSize,
            @Value("${dwp.approval.recovery-auditor-assignment.lease-seconds:30}")
                    int leaseSeconds,
            @Value("${dwp.approval.recovery-auditor-assignment.maximum-attempts:10}")
                    int maximumAttempts,
            @Value("${dwp.approval.recovery-auditor-assignment.probe-cooldown-seconds:86400}")
                    long probeCooldownSeconds,
            @Value("${dwp.approval.recovery-auditor-assignment."
                    + "maximum-probe-cooldown-seconds:604800}")
                    long maximumProbeCooldownSeconds) {
        this.repository = repository;
        this.resolver = resolver;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.leaseSeconds = Math.max(5, Math.min(leaseSeconds, 300));
        this.maximumAttempts = Math.max(1, Math.min(maximumAttempts, 100));
        this.probeCooldownSeconds = Math.max(
                3600L, Math.min(probeCooldownSeconds, 604800L));
        this.maximumProbeCooldownSeconds = Math.max(
                this.probeCooldownSeconds,
                Math.min(maximumProbeCooldownSeconds, 2592000L));
    }

    @Scheduled(fixedDelayString =
            "${dwp.approval.recovery-auditor-assignment.poll-delay-ms:2000}")
    public void assignPending() {
        for (ApprovalRecoveryAuditorAssignmentRepository.AssignmentTarget target
                : repository.claim(
                        batchSize, workerId, leaseSeconds, maximumAttempts,
                        probeCooldownSeconds)) {
            assign(target);
        }
    }

    private void assign(
            ApprovalRecoveryAuditorAssignmentRepository.AssignmentTarget target) {
        try {
            ApprovalRecoveryAuditorResolver.Assignment assignment = resolver.resolve(
                    target.tenantId(), target.outboxId(), target.originatorUserId(),
                    target.managementResourceSetKey());
            validate(target, assignment);
            if (!repository.markAssigned(target, workerId, assignment)) {
                log.info(
                        "Approval recovery auditor assignment lease was no longer owned for "
                                + "outbox {}",
                        target.outboxId());
            }
        } catch (RuntimeException exception) {
            boolean retained = repository.markUnavailable(
                    target, workerId, maximumAttempts,
                    probeCooldownSeconds, maximumProbeCooldownSeconds,
                    exception.getMessage());
            if (retained) {
                log.warn(
                        "Approval recovery auditor assignment unavailable for outbox {} "
                                + "on attempt {} of {}",
                        target.outboxId(), target.attemptCount(), maximumAttempts);
            } else {
                log.info(
                        "Approval recovery auditor retry lease was no longer owned for outbox {}",
                        target.outboxId());
            }
        }
    }

    private void validate(
            ApprovalRecoveryAuditorAssignmentRepository.AssignmentTarget target,
            ApprovalRecoveryAuditorResolver.Assignment assignment) {
        if (assignment == null
                || assignment.selectedUserId() <= 0
                || assignment.selectedUserId() == target.originatorUserId()
                || !target.managementResourceSetKey().equals(assignment.resourceSetKey())
                || assignment.assignmentRevision() == null
                || assignment.assignmentRevision().isBlank()
                || assignment.assignmentRevision().length() > 240) {
            throw new IllegalStateException(
                    "Approval recovery auditor resolution returned invalid evidence.");
        }
    }
}
