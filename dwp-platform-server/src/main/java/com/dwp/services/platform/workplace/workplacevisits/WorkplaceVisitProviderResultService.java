package com.dwp.services.platform.workplace.workplacevisits;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitProviderPort.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitRepository.*;

@Service
public class WorkplaceVisitProviderResultService {
    private final WorkplaceVisitRepository repository;
    private final Clock clock;

    @Autowired
    public WorkplaceVisitProviderResultService(WorkplaceVisitRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WorkplaceVisitProviderResultService(WorkplaceVisitRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void apply(long tenantId, UUID outboxId, ProviderOutcome outcome) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OutboxRow current = repository.outbox(tenantId, outboxId)
                .filter(row -> "PROCESSING".equals(row.deliveryState()))
                .orElseThrow(() -> conflict("The provider operation is not awaiting a result."));
        OutboxRow governed = current;
        if ("CHECK_PROVIDER_STATUS".equals(current.operationType())) {
            governed = repository.outbox(tenantId, current.relatedOutboxId())
                    .orElseThrow(() -> conflict("The original uncertain operation was not found."));
        }
        if (governed != current && outcome.state() == OutcomeState.RESULT_UNKNOWN) {
            long delaySeconds = Math.min(300L,
                    5L << Math.min(6, Math.max(0, current.attemptCount() - 1)));
            requireChanged(repository.retryOutbox(tenantId, current.id(),
                    outcome.evidenceReference(), now.plusSeconds(delaySeconds), now));
            return;
        }
        VisitRow visit = repository.adminVisit(tenantId, current.visitId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        applyVisitOutcome(tenantId, visit, governed, outcome, now);
        String outboxState = switch (outcome.state()) {
            case SUCCEEDED -> "DELIVERED";
            case FAILED -> "DEAD_LETTER";
            case RESULT_UNKNOWN -> "RESULT_UNKNOWN";
        };
        requireChanged(repository.completeOutbox(
                tenantId, current.id(), outboxState, outcome.evidenceReference(), now));
        if (governed != current) {
            requireChanged(repository.reconcileOutbox(
                    tenantId, governed.id(), outboxState, outcome.evidenceReference(), now));
        } else if (outcome.state() == OutcomeState.RESULT_UNKNOWN) {
            repository.outbox(tenantId, current.visitId(), "CHECK_PROVIDER_STATUS",
                    current.visitId() + ":provider-status:" + current.id(), current.id(),
                    null, "PENDING", now);
        }
    }

    private void applyVisitOutcome(long tenantId, VisitRow visit, OutboxRow operationRow,
                                   ProviderOutcome outcome, OffsetDateTime now) {
        String operation = operationRow.operationType();
        if (List.of(VisitState.CANCELLED, VisitState.REJECTED, VisitState.CHECKED_OUT)
                .contains(visit.state())) {
            String detail = outcome.detailCode() == null
                    ? "PROVIDER_RESULT_AFTER_TERMINAL_STATE" : outcome.detailCode();
            repository.timeline(tenantId, visit.visitId(), 0,
                    "PROVIDER_RESULT_AFTER_TERMINAL_STATE", visit.state(), detail, now);
            if ("REQUEST_ACCESS".equals(operation)
                    && outcome.state() == OutcomeState.SUCCEEDED) {
                repository.outboxWithProviderSnapshot(tenantId, visit.visitId(),
                        "REVOKE_ACCESS", visit.visitId() + ":revoke:late-provider-result:"
                                + operationRow.id(), outcome.evidenceReference(), "PENDING", now,
                        operationRow);
            }
            return;
        }
        if (outcome.state() == OutcomeState.SUCCEEDED
                && !reservationCurrent(tenantId, visit)) {
            requireChanged(repository.transition(tenantId, visit.visitId(), visit.version(),
                    List.of(VisitState.INVITED, VisitState.ACCESS_PENDING, VisitState.RESULT_UNKNOWN),
                    VisitState.CANCELLED, outcome.evidenceReference(),
                    "RESERVATION_CHANGED_AFTER_PROVIDER_REQUEST", now));
            repository.timeline(tenantId, visit.visitId(), 0, "RESERVATION_CHANGED",
                    VisitState.CANCELLED, "RESERVATION_CHANGED_AFTER_PROVIDER_REQUEST", now);
            if ("REQUEST_ACCESS".equals(operation)) {
                repository.outboxWithProviderSnapshot(tenantId, visit.visitId(),
                        "REVOKE_ACCESS", visit.visitId() + ":revoke:source-change:"
                                + visit.version(), outcome.evidenceReference(), "PENDING", now,
                        operationRow);
            } else {
                repository.outbox(tenantId, visit.visitId(), "REVOKE_ACCESS",
                        visit.visitId() + ":revoke:source-change:" + visit.version(),
                        null, "PENDING", now);
            }
            return;
        }
        if ("SEND_INVITATION".equals(operation)) {
            VisitState target = switch (outcome.state()) {
                case SUCCEEDED -> visit.approvalRequired()
                        ? VisitState.APPROVAL_PENDING : VisitState.APPROVED;
                case FAILED -> VisitState.INVITED;
                case RESULT_UNKNOWN -> VisitState.RESULT_UNKNOWN;
            };
            String limitation = switch (outcome.state()) {
                case SUCCEEDED -> null;
                case FAILED -> outcome.detailCode() == null
                        ? "INVITATION_PROVIDER_FAILED" : outcome.detailCode();
                case RESULT_UNKNOWN -> "INVITATION_PROVIDER_RESULT_UNKNOWN";
            };
            requireChanged(repository.transition(tenantId, visit.visitId(), visit.version(),
                    List.of(VisitState.INVITED, VisitState.RESULT_UNKNOWN), target,
                    outcome.evidenceReference(), limitation, now));
            repository.timeline(tenantId, visit.visitId(), 0,
                    event("INVITATION", outcome.state()), target, limitation, now);
            return;
        }
        if ("REQUEST_ACCESS".equals(operation)) {
            VisitState target = switch (outcome.state()) {
                case SUCCEEDED -> VisitState.READY;
                case FAILED -> VisitState.ACCESS_FAILED;
                case RESULT_UNKNOWN -> VisitState.RESULT_UNKNOWN;
            };
            String limitation = switch (outcome.state()) {
                case SUCCEEDED -> null;
                case FAILED -> outcome.detailCode() == null
                        ? "ACCESS_PROVIDER_FAILED" : outcome.detailCode();
                case RESULT_UNKNOWN -> "ACCESS_PROVIDER_RESULT_UNKNOWN";
            };
            requireChanged(repository.transition(tenantId, visit.visitId(), visit.version(),
                    List.of(VisitState.ACCESS_PENDING, VisitState.RESULT_UNKNOWN), target,
                    outcome.evidenceReference(), limitation, now));
            repository.timeline(tenantId, visit.visitId(), 0,
                    event("ACCESS", outcome.state()), target, limitation, now);
        }
    }

    private boolean reservationCurrent(long tenantId, VisitRow visit) {
        return repository.reservationSource(tenantId, visit.authority(), visit.reservationId())
                .filter(source -> source.active()
                        && source.version() == visit.reservationVersion())
                .isPresent();
    }

    private static String event(String prefix, OutcomeState state) {
        return prefix + switch (state) {
            case SUCCEEDED -> "_SUCCEEDED";
            case FAILED -> "_FAILED";
            case RESULT_UNKNOWN -> "_RESULT_UNKNOWN";
        };
    }

    private static void requireChanged(boolean changed) {
        if (!changed) throw conflict("The provider result raced with another state transition.");
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
