package com.dwp.services.payroll.input.compensationplan;

import com.dwp.core.event.DomainEventEnvelope;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface CompensationPlanInputRepository {

    Registration register(CompensationPlanInputContracts.Publication publication, Instant now);

    Optional<CompensationPlanInputContracts.Receipt> receipt(
            long tenantId, UUID sourceEventId);

    Optional<Attempt> latestAttempt(long tenantId, UUID sourceEventId);

    ApplyResult apply(
            CompensationPlanInputContracts.Publication publication,
            CompensationPlanInputContracts.MaterializedSnapshot snapshot,
            String attemptKind,
            String actorType,
            String actorId,
            Instant now);

    CompensationPlanInputContracts.ConsumptionResult quarantine(
            CompensationPlanInputContracts.Publication publication,
            String attemptKind,
            String actorType,
            String actorId,
            String failureCode,
            String failureDetail,
            Instant now);

    CompensationPlanInputContracts.ConsumptionResult quarantineEventIdReuse(
            CompensationPlanInputContracts.Publication publication,
            String actorType,
            String actorId,
            Instant now);

    DomainEventEnvelope quarantinedEvent(long tenantId, UUID sourceEventId);

    void auditReplay(
            long tenantId,
            UUID sourceEventId,
            CompensationPlanInputContracts.ReplayAuthority authority,
            Instant now);

    enum Registration {
        NEW,
        IDENTICAL,
        EVENT_ID_REUSED
    }

    record Attempt(String outcome, String failureCode, int attemptNumber) {
    }

    record ApplyResult(
            CompensationPlanInputContracts.ConsumptionResult result,
            boolean newlyApplied) {
    }
}
