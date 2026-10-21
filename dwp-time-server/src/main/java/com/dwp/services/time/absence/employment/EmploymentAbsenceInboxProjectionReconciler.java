package com.dwp.services.time.absence.employment;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;

/** Crash-safe bounded reconciler; active leases are observed but never stolen. */
public final class EmploymentAbsenceInboxProjectionReconciler {

    private final EmploymentAbsenceInboxSource inbox;
    private final EmploymentAbsenceConsumerCoordinator coordinator;
    private final Clock clock;

    public EmploymentAbsenceInboxProjectionReconciler(
            EmploymentAbsenceInboxSource inbox,
            EmploymentAbsenceConsumerCoordinator coordinator,
            Clock clock) {
        this.inbox = inbox;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    public ReconciliationResult reconcile(int maximumRows) {
        EnumMap<EmploymentAbsenceConsumerCoordinator.DeliveryOutcome, Integer> outcomes =
                new EnumMap<>(EmploymentAbsenceConsumerCoordinator.DeliveryOutcome.class);
        int scanned = 0;
        int liveLeases = 0;
        int malformedRows = 0;
        for (EmploymentAbsenceInboxSource.InboxCandidate candidate
                : inbox.unresolved(maximumRows)) {
            scanned++;
            if (candidate.activeProcessing(clock.instant())) {
                liveLeases++;
                continue;
            }
            var outcome = candidate.malformed()
                    ? coordinator.reconcileMalformedStoredEnvelope(
                            candidate.eventId(), candidate.payloadSha256(),
                            candidate.tenantId(), candidate.aggregateId())
                    : coordinator.consume(candidate.event());
            if (candidate.malformed()) malformedRows++;
            outcomes.merge(outcome, 1, Integer::sum);
        }
        return new ReconciliationResult(scanned, liveLeases, malformedRows, outcomes);
    }

    public record ReconciliationResult(
            int scanned,
            int activeLeases,
            int malformedRows,
            Map<EmploymentAbsenceConsumerCoordinator.DeliveryOutcome, Integer> outcomes) {
        public ReconciliationResult {
            outcomes = Map.copyOf(outcomes);
        }
    }
}
