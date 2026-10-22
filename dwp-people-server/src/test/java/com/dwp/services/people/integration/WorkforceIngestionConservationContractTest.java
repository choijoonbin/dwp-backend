package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkforceIngestionConservationContractTest {
    @Test
    void applyConservationIsDerivedOnlyFromImmutableItemOutcomes() {
        List<Hrm012WorkforceIngestionContract.ItemEvidence> evidence = List.of(
                evidence(1, Hrm012WorkforceIngestionContract.ItemOutcome.APPLIED),
                evidence(2, Hrm012WorkforceIngestionContract.ItemOutcome
                        .QUARANTINED_AFTER_ACCEPTANCE),
                evidence(3, Hrm012WorkforceIngestionContract.ItemOutcome.REJECTED),
                evidence(4, Hrm012WorkforceIngestionContract.ItemOutcome.DUPLICATE));

        Hrm012WorkforceIngestionContract.Counts counts =
                Hrm012WorkforceIngestionContract.Counts.prove(
                        Hrm012WorkforceIngestionContract.Mode.APPLY, evidence);

        assertThat(counts).isEqualTo(
                new Hrm012WorkforceIngestionContract.Counts(4, 2, 1, 1, 1, 1));
        counts.requireConservation(Hrm012WorkforceIngestionContract.Mode.APPLY, 0);
        assertThat(counts.sourceCount())
                .isEqualTo(counts.acceptedCount()
                        + counts.rejectedCount() + counts.duplicateCount());
        assertThat(counts.acceptedCount())
                .isEqualTo(counts.appliedCount() + counts.quarantinedCount());
    }

    @Test
    void validateOnlyCountsValidatedRecordsWithoutCallingThemAppliedOrAccepted() {
        List<Hrm012WorkforceIngestionContract.ItemEvidence> evidence = List.of(
                evidence(1, Hrm012WorkforceIngestionContract.ItemOutcome.VALIDATED),
                evidence(2, Hrm012WorkforceIngestionContract.ItemOutcome.REJECTED),
                evidence(3, Hrm012WorkforceIngestionContract.ItemOutcome.DUPLICATE));

        Hrm012WorkforceIngestionContract.Counts counts =
                Hrm012WorkforceIngestionContract.Counts.prove(
                        Hrm012WorkforceIngestionContract.Mode.VALIDATE_ONLY, evidence);

        assertThat(counts).isEqualTo(
                new Hrm012WorkforceIngestionContract.Counts(3, 0, 1, 1, 0, 0));
        counts.requireConservation(
                Hrm012WorkforceIngestionContract.Mode.VALIDATE_ONLY, 1);
    }

    @Test
    void modeSpecificOutcomeMismatchAndInvalidEvidenceShapesFailClosed() {
        assertThatThrownBy(() -> Hrm012WorkforceIngestionContract.Counts.prove(
                Hrm012WorkforceIngestionContract.Mode.APPLY,
                List.of(evidence(1,
                        Hrm012WorkforceIngestionContract.ItemOutcome.VALIDATED))))
                .isInstanceOf(Hrm012Problem.class);
        assertThatThrownBy(() -> Hrm012WorkforceIngestionContract.Counts.prove(
                Hrm012WorkforceIngestionContract.Mode.VALIDATE_ONLY,
                List.of(evidence(1,
                        Hrm012WorkforceIngestionContract.ItemOutcome.APPLIED))))
                .isInstanceOf(Hrm012Problem.class);
        assertThatThrownBy(() -> new Hrm012WorkforceIngestionContract.ItemEvidence(
                1, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                Hrm012WorkforceIngestionContract.ItemOutcome.VALIDATED,
                "d".repeat(64), "UNEXPECTED", "must not exist", null,
                null, null, null))
                .isInstanceOf(Hrm012Problem.class);
    }

    @Test
    void nonFindingOutcomesPermitNullFindingEvidenceExactlyAsV60Does() {
        for (Hrm012WorkforceIngestionContract.ItemOutcome outcome : List.of(
                Hrm012WorkforceIngestionContract.ItemOutcome.VALIDATED,
                Hrm012WorkforceIngestionContract.ItemOutcome.DUPLICATE,
                Hrm012WorkforceIngestionContract.ItemOutcome.APPLIED)) {
            assertThat(evidence(1, outcome).findingCode()).isNull();
            assertThat(evidence(1, outcome).redactedSummary()).isNull();
        }
    }

    private Hrm012WorkforceIngestionContract.ItemEvidence evidence(
            int sequence, Hrm012WorkforceIngestionContract.ItemOutcome outcome) {
        boolean rejected = outcome == Hrm012WorkforceIngestionContract.ItemOutcome.REJECTED;
        boolean quarantined = outcome == Hrm012WorkforceIngestionContract.ItemOutcome
                .QUARANTINED_AFTER_ACCEPTANCE;
        boolean applied = outcome == Hrm012WorkforceIngestionContract.ItemOutcome.APPLIED;
        return new Hrm012WorkforceIngestionContract.ItemEvidence(
                sequence, "a".repeat(64), "b".repeat(64), "c".repeat(64), outcome,
                "d".repeat(64), rejected || quarantined ? "POLICY_REJECTED" : null,
                rejected || quarantined ? "Redacted finding." : null,
                applied ? "e".repeat(64) : null,
                quarantined ? new byte[]{1} : null,
                quarantined ? "kms://hrm012/key/1" : null,
                quarantined ? "f".repeat(64) : null);
    }
}
