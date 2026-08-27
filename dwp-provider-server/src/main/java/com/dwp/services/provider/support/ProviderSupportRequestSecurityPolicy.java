package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ProviderSupportRequestSecurityPolicy {

    private final ProviderAuditService auditService;

    public ProviderSupportRequestSecurityPolicy(ProviderAuditService auditService) {
        this.auditService = auditService;
    }

    public UUID requireCreationAuthSession(ProviderRequestContext.Actor actor) {
        if (actor.authSessionId() == null) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "A durable authenticated session is required to request support access.");
        }
        return actor.authSessionId();
    }

    public void requireIndependentApproval(
            ProviderSupportRequestRepository.SupportAccessRequestRecord record,
            ProviderRequestContext.Actor actor,
            String correlationId) {
        if (!Objects.equals(record.requesterOperatorId(), actor.operatorId())) return;
        denied(record, correlationId, "provider.support-access.approval-denied",
                "REQUESTER_SELF_APPROVAL");
        throw new BaseException(ErrorCode.FORBIDDEN,
                "Support access requests cannot be self-approved.");
    }

    public void requireActivationPrincipal(
            ProviderSupportRequestRepository.SupportAccessRequestRecord record,
            ProviderRequestContext.Actor actor,
            String correlationId) {
        if (!Objects.equals(record.requesterOperatorId(), actor.operatorId())) {
            denied(record, correlationId, "provider.support-access.activation-denied",
                    "REQUESTER_MISMATCH");
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Only the approved requester can activate this support access.");
        }
        if (actor.authSessionId() != null
                && Objects.equals(record.requesterAuthSessionId(), actor.authSessionId())) return;
        denied(record, correlationId, "provider.support-access.activation-denied",
                "REQUEST_AUTH_SESSION_MISMATCH");
        throw new BaseException(ErrorCode.FORBIDDEN,
                "Support access must be activated from the authenticated session that requested it.");
    }

    public void requireIndependentPostReview(
            ProviderSupportRequestRepository.SupportAccessRequestRecord record,
            ProviderRequestContext.Actor actor,
            String correlationId) {
        if (!Objects.equals(record.requesterOperatorId(), actor.operatorId())) return;
        denied(record, correlationId, "provider.support-access.review-denied",
                "REQUESTER_SELF_POST_REVIEW");
        throw new BaseException(ErrorCode.FORBIDDEN,
                "Requesters cannot complete their own post-access review.");
    }

    public ProviderSupportRequestRepository.SupportAccessRequestRecord requireActivationTarget(
            Optional<ProviderSupportRequestRepository.SupportAccessRequestRecord> candidate,
            UUID requestId,
            ProviderRequestContext.Actor actor,
            String correlationId) {
        ProviderSupportRequestRepository.SupportAccessRequestRecord record = candidate.orElse(null);
        if (record != null && Objects.equals(record.requesterOperatorId(), actor.operatorId())) {
            return record;
        }
        unresolved("provider.support-access.activation-denied",
                "SUPPORT_ACCESS_REQUEST", requestId, correlationId);
        throw unavailableTarget();
    }

    public ProviderSupportRequestRepository.SupportAccessRequestRecord requireCancellationTarget(
            Optional<ProviderSupportRequestRepository.SupportAccessRequestRecord> candidate,
            UUID requestId,
            ProviderRequestContext.Actor actor,
            String correlationId) {
        ProviderSupportRequestRepository.SupportAccessRequestRecord record = candidate.orElse(null);
        if (record != null && (Objects.equals(record.requesterOperatorId(), actor.operatorId())
                || actor.permissions().contains("SUPPORT_ACCESS_REVIEW"))) {
            return record;
        }
        unresolved("provider.support-access.cancel-denied",
                "SUPPORT_ACCESS_REQUEST", requestId, correlationId);
        throw unavailableTarget();
    }

    public ProviderSupportSessionRepository.SupportSessionRecord requireRevocationTarget(
            Optional<ProviderSupportSessionRepository.SupportSessionRecord> candidate,
            UUID sessionId,
            ProviderRequestContext.Actor actor,
            String correlationId) {
        ProviderSupportSessionRepository.SupportSessionRecord record = candidate.orElse(null);
        if (record != null && (Objects.equals(record.operatorId(), actor.operatorId())
                || actor.roles().contains("PROVIDER_ADMIN"))) {
            return record;
        }
        unresolved("provider.support-session.revoke-denied",
                "SUPPORT_SESSION", sessionId, correlationId);
        throw unavailableTarget();
    }

    private void unresolved(
            String action,
            String targetType,
            UUID targetId,
            String correlationId) {
        auditService.denied(
                action, targetType, auditService.opaqueReference(targetId.toString()),
                null, null, correlationId,
                java.util.Map.of("reasonCode", "TARGET_UNAVAILABLE_OR_UNAUTHORIZED"));
    }

    private BaseException unavailableTarget() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The support access target is unavailable.");
    }

    private void denied(
            ProviderSupportRequestRepository.SupportAccessRequestRecord record,
            String correlationId,
            String action,
            String reasonCode) {
        auditService.deniedSupportRequest(
                action, record.requestId(), record.tenantId(), correlationId, reasonCode,
                record.lifecycleState(), record.version());
    }
}
