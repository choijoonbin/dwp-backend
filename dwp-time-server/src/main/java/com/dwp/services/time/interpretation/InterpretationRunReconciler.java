package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.InterpretationComputation;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationRun;

/** Rebases a speculative run onto the latest effective run while holding the DB scope lock. */
final class InterpretationRunReconciler {

    static InterpretationRun reconcile(InterpretationRun candidate, InterpretationRun latest) {
        InterpretationComputation computation = new InterpretationComputation(
                candidate.inputDigest(),
                candidate.ruleDigest(),
                candidate.outputDigest(),
                candidate.results(),
                candidate.traceSteps());
        InterpretationLedgerPlanner.Plan plan = InterpretationLedgerPlanner.plan(
                candidate.runPublicId(), computation, latest);
        return new InterpretationRun(
                candidate.runPublicId(),
                candidate.tenantId(),
                candidate.subjectPrincipalPublicId(),
                candidate.periodStart(),
                candidate.periodEnd(),
                candidate.asOf(),
                candidate.ruleVersionPublicId(),
                candidate.ruleVersionRevision(),
                candidate.inputDigest(),
                candidate.ruleDigest(),
                candidate.outputDigest(),
                latest == null ? 1 : latest.attemptNo() + 1,
                candidate.status(),
                plan.ledgerDeltaApplied(),
                latest == null ? null : latest.runPublicId(),
                candidate.correlationId(),
                candidate.createdAt(),
                candidate.results(),
                plan.entries(),
                candidate.traceSteps());
    }

    private InterpretationRunReconciler() {}
}
