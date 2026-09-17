package com.dwp.services.platform.workplace.workplaceassistant;

import org.springframework.stereotype.Service;

import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;

@Service
public class WorkplaceAssistantService {
    private final WorkplaceAssistantRequestService requests;
    private final WorkplaceAssistantBookingService bookings;
    private final WorkplaceAssistantGovernanceService governance;

    public WorkplaceAssistantService(
            WorkplaceAssistantRequestService requests,
            WorkplaceAssistantBookingService bookings,
            WorkplaceAssistantGovernanceService governance) {
        this.requests = requests;
        this.bookings = bookings;
        this.governance = governance;
    }

    public AssistantCommandResult create(
            long tenantId,
            long actorId,
            UUID actorPersonPublicId,
            String actorDisplayName,
            String verifiedGroupRefs,
            String locale,
            String idempotencyKey,
            String correlationId,
            CreateAssistantRequest request) {
        return requests.create(tenantId, actorId, actorPersonPublicId, actorDisplayName,
                verifiedGroupRefs, locale, idempotencyKey, correlationId, request);
    }

    public AssistantRequest request(long tenantId, long actorId, UUID requestId) {
        return requests.request(tenantId, actorId, requestId);
    }

    public AssistantCommandResult validate(
            long tenantId,
            long actorId,
            UUID actorPersonPublicId,
            String actorDisplayName,
            String verifiedGroupRefs,
            String locale,
            UUID requestId,
            String idempotencyKey,
            String correlationId,
            ValidateAssistantRequest request) {
        return bookings.validate(tenantId, actorId, actorPersonPublicId, actorDisplayName,
                verifiedGroupRefs, locale, requestId, idempotencyKey, correlationId, request);
    }

    public AssistantCommandResult confirm(
            long tenantId,
            long actorId,
            String verifiedGroupRefs,
            String locale,
            UUID requestId,
            String idempotencyKey,
            String correlationId,
            ConfirmAssistantRequest request) {
        return bookings.confirm(tenantId, actorId, verifiedGroupRefs, locale, requestId,
                idempotencyKey, correlationId, request);
    }

    public AssistantExecution execution(long tenantId, long actorId, UUID requestId) {
        return bookings.execution(tenantId, actorId, requestId);
    }

    public FeedbackReceipt feedback(
            long tenantId,
            long actorId,
            UUID requestId,
            String idempotencyKey,
            String correlationId,
            FeedbackRequest request) {
        return governance.feedback(
                tenantId, actorId, requestId, idempotencyKey, correlationId, request);
    }

    public AssistantGovernance governance(long tenantId) {
        return governance.governance(tenantId);
    }

    public GovernanceCommandResult updateGovernance(
            long tenantId,
            long actorId,
            String idempotencyKey,
            String correlationId,
            GovernanceUpdateRequest request) {
        return governance.updateGovernance(
                tenantId, actorId, idempotencyKey, correlationId, request);
    }

    public AuditEvents auditEvents(long tenantId, UUID requestId, Integer limit) {
        return governance.auditEvents(tenantId, requestId, limit);
    }
}
