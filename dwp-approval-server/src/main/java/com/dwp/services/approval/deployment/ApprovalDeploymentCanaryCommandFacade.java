package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentCanaryModels.*;
import static com.dwp.services.approval.deployment.ApprovalDeploymentCommandGuard.Profile;

@Service
class ApprovalDeploymentCanaryCommandFacade {
    private final ApprovalDeploymentCanaryService service;
    private final ApprovalDeploymentHttpAuthority authority;
    private final ApprovalDeploymentCommandGuard guard;

    ApprovalDeploymentCanaryCommandFacade(
            ApprovalDeploymentCanaryService service,
            ApprovalDeploymentHttpAuthority authority,
            ApprovalDeploymentCommandGuard guard) {
        this.service = service;
        this.authority = authority;
        this.guard = guard;
    }

    @Transactional
    CanaryView control(
            UUID promotionId,
            ControlCommand command,
            long expectedVersion,
            String expectedDecisionRevision,
            ApprovalStepUpHeaders headers) {
        requireVersion(command == null ? -1 : command.expectedPromotionVersion(),
                expectedVersion);
        var current = authority.command(expectedDecisionRevision);
        var permit = guard.begin(current, Profile.CANARY_CONTROL, promotionId,
                expectedVersion, command, headers);
        if (permit.priorResult()) return service.canary(current.scope(), promotionId);
        CanaryView result = service.control(
                current.scope(), promotionId, command, permit.governed());
        guard.complete(permit);
        return result;
    }

    @Transactional
    CanaryView telemetry(
            UUID promotionId,
            TelemetryCommand command,
            long expectedVersion,
            String expectedDecisionRevision,
            ApprovalStepUpHeaders headers) {
        requireVersion(command == null ? -1 : command.expectedPromotionVersion(),
                expectedVersion);
        var current = authority.command(expectedDecisionRevision);
        var permit = guard.begin(current, Profile.CANARY_TELEMETRY, promotionId,
                expectedVersion, command, headers);
        if (permit.priorResult()) return service.canary(current.scope(), promotionId);
        CanaryView result = service.recordTelemetry(
                current.scope(), promotionId, command, permit.governed());
        guard.complete(permit);
        return result;
    }

    private void requireVersion(long bodyVersion, long headerVersion) {
        if (bodyVersion < 0 || bodyVersion != headerVersion) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "Payload and expected promotion versions must match.");
        }
    }
}
