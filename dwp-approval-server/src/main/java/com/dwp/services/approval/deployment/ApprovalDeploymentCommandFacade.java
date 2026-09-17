package com.dwp.services.approval.deployment;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentCommandGuard.Profile;
import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;

@Service
public class ApprovalDeploymentCommandFacade {
    private final ApprovalDeploymentService service;
    private final ApprovalDeploymentHttpAuthority authority;
    private final ApprovalDeploymentCommandGuard guard;

    public ApprovalDeploymentCommandFacade(
            ApprovalDeploymentService service,
            ApprovalDeploymentHttpAuthority authority,
            ApprovalDeploymentCommandGuard guard) {
        this.service = service;
        this.authority = authority;
        this.guard = guard;
    }

    @Transactional
    public PackageRecord createPackage(
            PackageCommand command,
            long expectedVersion,
            String expectedDecisionRevision,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(expectedDecisionRevision);
        requireCreateVersion(expectedVersion);
        var permit = guard.begin(
                current, Profile.PACKAGE_CREATE, command.packageId(),
                expectedVersion, command, headers);
        if (permit.priorResult()) {
            return service.packageById(current.scope(), command.packageId());
        }
        PackageRecord result = service.createPackage(
                current.scope(), command, permit.governed());
        guard.complete(permit);
        return result;
    }

    @Transactional
    public Promotion requestPromotion(
            PromotionCommand command,
            long expectedVersion,
            String expectedDecisionRevision,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(expectedDecisionRevision);
        requireCreateVersion(expectedVersion);
        var permit = guard.begin(
                current, Profile.PROMOTION_CREATE, command.promotionId(),
                expectedVersion, command, headers);
        if (permit.priorResult()) {
            return service.promotionById(current.scope(), command.promotionId());
        }
        Promotion result = service.requestPromotion(
                current.scope(), command, permit.governed());
        guard.complete(permit);
        return result;
    }

    private void requireCreateVersion(long expectedVersion) {
        if (expectedVersion != 0) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.OBJECT_VERSION_CONFLICT,
                    "New deployment resources require expected object version zero.");
        }
    }

    @Transactional
    public Promotion approve(
            UUID promotionId,
            long expectedVersion,
            String reviewComment,
            String expectedDecisionRevision,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(expectedDecisionRevision);
        var permit = guard.begin(
                current, Profile.APPROVE, promotionId, expectedVersion,
                reviewComment, headers);
        if (permit.priorResult()) {
            return service.promotionById(current.scope(), promotionId);
        }
        Promotion result = service.approve(
                current.scope(), promotionId, expectedVersion,
                reviewComment, permit.governed());
        guard.complete(permit);
        return result;
    }

    @Transactional
    public Promotion schedule(
            UUID promotionId,
            long expectedVersion,
            Instant scheduledFor,
            String expectedDecisionRevision,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(expectedDecisionRevision);
        var permit = guard.begin(
                current, Profile.SCHEDULE, promotionId, expectedVersion,
                scheduledFor, headers);
        if (permit.priorResult()) {
            return service.promotionById(current.scope(), promotionId);
        }
        Promotion result = service.schedule(
                current.scope(), promotionId, expectedVersion,
                scheduledFor, permit.governed());
        guard.complete(permit);
        return result;
    }

    @Transactional
    public Promotion beginActivation(
            UUID promotionId,
            long expectedVersion,
            String expectedDecisionRevision,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(expectedDecisionRevision);
        var permit = guard.begin(
                current, Profile.ACTIVATE, promotionId, expectedVersion,
                java.util.Map.of("operation", "BEGIN_ACTIVATION"), headers);
        if (permit.priorResult()) {
            return service.promotionById(current.scope(), promotionId);
        }
        Promotion result = service.beginActivation(
                current.scope(), promotionId, expectedVersion,
                permit.governed());
        guard.complete(permit);
        return result;
    }

    @Transactional
    public Promotion recordActivationEvidence(
            UUID promotionId,
            long expectedVersion,
            ExternalHealthEvidenceSubmission evidence,
            String expectedDecisionRevision,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(expectedDecisionRevision);
        var permit = guard.begin(
                current, Profile.ACTIVATION_EVIDENCE, promotionId,
                expectedVersion, evidence, headers);
        if (permit.priorResult()) {
            return service.promotionById(current.scope(), promotionId);
        }
        Promotion result = service.recordActivationEvidence(
                current.scope(), promotionId, expectedVersion,
                evidence, permit.governed());
        guard.complete(permit);
        return result;
    }

    @Transactional
    public Promotion requestRollback(
            UUID promotionId,
            long expectedVersion,
            String reason,
            String expectedDecisionRevision,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(expectedDecisionRevision);
        var permit = guard.begin(
                current, Profile.ROLLBACK, promotionId, expectedVersion,
                reason, headers);
        if (permit.priorResult()) {
            return service.promotionById(current.scope(), promotionId);
        }
        Promotion result = service.requestRollback(
                current.scope(), promotionId, expectedVersion,
                reason, permit.governed());
        guard.complete(permit);
        return result;
    }

    @Transactional
    public Promotion recordRollbackEvidence(
            UUID promotionId,
            long expectedVersion,
            ExternalHealthEvidenceSubmission evidence,
            String expectedDecisionRevision,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(expectedDecisionRevision);
        var permit = guard.begin(
                current, Profile.ROLLBACK_EVIDENCE, promotionId,
                expectedVersion, evidence, headers);
        if (permit.priorResult()) {
            return service.promotionById(current.scope(), promotionId);
        }
        Promotion result = service.recordRollbackEvidence(
                current.scope(), promotionId, expectedVersion,
                evidence, permit.governed());
        guard.complete(permit);
        return result;
    }
}
