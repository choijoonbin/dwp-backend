package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.EntryCode;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationComputation;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationEntry;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationRun;
import com.dwp.services.time.interpretation.InterpretationModels.WorkerMinuteResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Builds an immutable ledger delta from the latest committed interpretation snapshot. */
final class InterpretationLedgerPlanner {

    record Plan(boolean ledgerDeltaApplied, List<InterpretationEntry> entries) {
        Plan {
            entries = List.copyOf(entries);
            if (!ledgerDeltaApplied && !entries.isEmpty()) {
                throw new IllegalArgumentException("zero-delta replay cannot post ledger entries");
            }
        }
    }

    static Plan plan(
            UUID runId,
            InterpretationComputation computation,
            InterpretationRun previous) {
        if (previous != null && sameSemanticSnapshot(previous, computation)) {
            return new Plan(false, List.of());
        }
        List<InterpretationEntry> result = new ArrayList<>();
        if (previous != null) {
            previous.entries().stream()
                    .filter(entry -> entry.reversesEntryPublicId() == null)
                    .filter(entry -> entry.minutes() > 0)
                    .sorted(Comparator.comparing(InterpretationEntry::entryPublicId))
                    .forEach(entry -> result.add(new InterpretationEntry(
                            InterpretationDigests.stableUuid(
                                    "InterpretationReversal.v1", runId, entry.entryPublicId()),
                            entry.workerPublicId(),
                            entry.entryCode(),
                            -entry.minutes(),
                            entry.entryPublicId())));
        }
        for (WorkerMinuteResult worker : computation.results()) {
            add(result, runId, worker.workerPublicId(), EntryCode.REGULAR_MINUTES,
                    worker.regularMinutes());
            add(result, runId, worker.workerPublicId(), EntryCode.OVERTIME_MINUTES,
                    worker.overtimeMinutes());
            add(result, runId, worker.workerPublicId(), EntryCode.UNPAID_BREAK_MINUTES,
                    worker.unpaidBreakMinutes());
        }
        return new Plan(true, result);
    }

    static boolean sameSemanticSnapshot(
            InterpretationRun run, InterpretationComputation computation) {
        return run.inputDigest().equals(computation.inputDigest())
                && run.ruleDigest().equals(computation.ruleDigest())
                && run.outputDigest().equals(computation.outputDigest());
    }

    private static void add(
            List<InterpretationEntry> entries,
            UUID runId,
            UUID workerId,
            EntryCode code,
            int minutes) {
        if (minutes == 0) return;
        entries.add(new InterpretationEntry(
                InterpretationDigests.stableUuid(
                        "InterpretationEntry.v1", runId, workerId, code),
                workerId,
                code,
                minutes,
                null));
    }

    private InterpretationLedgerPlanner() {}
}
