package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Releases one quarantined Performance event for another local durable handoff attempt.
 * Broker publication remains owned by the shared outbox relay.
 */
@Service
class CompensationPlanningEventHandoffReplayService {

    private static final int MAXIMUM_REASON_LENGTH = 500;

    private final CompensationPlanningEventHandoffRepository repository;
    private final AuditOutboxRecorder audit;
    private final TransactionTemplate transaction;

    CompensationPlanningEventHandoffReplayService(
            CompensationPlanningEventHandoffRepository repository,
            AuditOutboxRecorder audit,
            PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(repository);
        this.audit = Objects.requireNonNull(audit);
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
    }

    public boolean release(UUID eventId, UUID correlationId, String reason) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        String normalizedReason = normalizeReason(reason);
        PeopleRequestContext.Actor actor = authorizedActor();
        return Boolean.TRUE.equals(transaction.execute(ignored -> repository
                .releaseQuarantined(
                        actor.tenantId(), eventId,
                        CompensationPlanningEventHandoffWorker
                                .supportedCycleEventNames(),
                        CompensationPlanningEventHandoffWorker
                                .supportedProposalEventNames())
                .map(event -> {
                    repository.recordReplayRequest(
                            actor.tenantId(), event.eventId(), actor.userId(),
                            normalizedReason);
                    recordAudit(actor, event, correlationId, normalizedReason);
                    return Boolean.TRUE;
                })
                .orElse(Boolean.FALSE)));
    }

    private void recordAudit(
            PeopleRequestContext.Actor actor,
            CompensationPlanningEventHandoffRepository.ReleasedEvent event,
            UUID correlationId,
            String reason) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("SYSTEM_EVENT")
                .action("compensation.event-handoff.quarantine-released")
                .outcome("SUCCESS")
                .severity("HIGH")
                .riskScore(80)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("hris-performance-compensation")
                .targetType("COMPENSATION_EVENT_HANDOFF")
                .targetId(event.eventId().toString())
                .reason(reason)
                .correlationId(correlationId.toString())
                .beforeState(Map.of(
                        "deliveryState", "QUARANTINED",
                        "deliveryAttempts", event.previousDeliveryAttempts()))
                .afterState(Map.of(
                        "deliveryState", "PENDING",
                        "deliveryAttempts", 0))
                .metadata(Map.of(
                        "aggregateType", event.aggregateType(),
                        "aggregateId", event.aggregateId(),
                        "aggregateVersion", event.aggregateVersion(),
                        "eventName", event.eventName()))
                .retentionClass("EXTENDED")
                .build());
    }

    private static PeopleRequestContext.Actor authorizedActor() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.userId() == null || actor.userId() <= 0L
                || actor.tenantId() == null || actor.tenantId() <= 0L
                || !actor.hasAnyRole("TENANT_ADMIN", "PLATFORM_ADMIN")
                || !actor.hasPermission(
                        CompensationPlanningAuthorization.DATA_RESOURCE, "UPDATE")) {
            throw new SecurityException(
                    "Tenant administration and compensation update authority are required "
                            + "to release a quarantined event.");
        }
        return actor;
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String normalized = reason.trim();
        if (normalized.length() > MAXIMUM_REASON_LENGTH) {
            throw new IllegalArgumentException("reason is too long");
        }
        return normalized;
    }
}
