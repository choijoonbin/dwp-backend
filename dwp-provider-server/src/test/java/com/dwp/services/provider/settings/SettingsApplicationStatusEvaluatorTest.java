package com.dwp.services.provider.settings;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsApplicationStatusEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-09-17T05:00:00Z");
    private final SettingsApplicationStatusEvaluator evaluator =
            new SettingsApplicationStatusEvaluator();

    @Test
    void neverClaimsConvergenceWhenTheOwnerHasNoObservationContract() {
        SettingsContracts.ApplicationStatus result = evaluator.evaluate(
                evidence("v2", "v2", false, 0, List.of()), NOW);

        assertThat(result.state())
                .isEqualTo(SettingsContracts.ApplicationState.OBSERVATION_UNSUPPORTED);
        assertThat(result.convergedTargetCount()).isZero();
        assertThat(result.uniformlyObservedVersion()).isNull();
    }

    @Test
    void distinguishesDraftAndPublishPendingFromAppliedState() {
        assertThat(evaluator.evaluate(
                evidence("v2", null, true, 1, List.of()), NOW).state())
                .isEqualTo(SettingsContracts.ApplicationState.DRAFT);
        assertThat(evaluator.evaluate(
                evidence("v3", "v2", true, 1, List.of()), NOW).state())
                .isEqualTo(SettingsContracts.ApplicationState.PUBLISH_PENDING);
    }

    @Test
    void preservesApprovalAndReadyToPublishAsDistinctLifecycleStates() {
        assertThat(evaluator.evaluate(new SettingsContracts.ApplicationEvidence(
                "v3", SettingsContracts.DesiredState.PENDING_APPROVAL,
                "v2", NOW, true, 0, List.of()), NOW).state())
                .isEqualTo(SettingsContracts.ApplicationState.APPROVAL_PENDING);
        assertThat(evaluator.evaluate(new SettingsContracts.ApplicationEvidence(
                "v3", SettingsContracts.DesiredState.APPROVED,
                "v2", NOW, true, 0, List.of()), NOW).state())
                .isEqualTo(SettingsContracts.ApplicationState.READY_TO_PUBLISH);
    }

    @Test
    void reportsConvergedAndStaleEvidenceSeparately() {
        SettingsContracts.TargetObservation current = observation(
                "cell-a", SettingsContracts.ObservationState.APPLIED, "v2",
                NOW.minusSeconds(30));
        SettingsContracts.TargetObservation old = observation(
                "cell-a", SettingsContracts.ObservationState.APPLIED, "v2",
                NOW.minus(Duration.ofHours(1)));

        assertThat(evaluator.evaluate(
                evidence("v2", "v2", true, 1, List.of(current)), NOW).state())
                .isEqualTo(SettingsContracts.ApplicationState.CONVERGED);
        assertThat(evaluator.evaluate(
                evidence("v2", "v2", true, 1, List.of(old)), NOW).state())
                .isEqualTo(SettingsContracts.ApplicationState.OBSERVATION_STALE);
    }

    @Test
    void marksTheWholeResolutionStaleWhenAnyTargetObservationIsStale() {
        SettingsContracts.TargetObservation old = observation(
                "cell-a", SettingsContracts.ObservationState.APPLIED, "v2",
                NOW.minus(Duration.ofHours(1)));
        SettingsContracts.TargetObservation current = observation(
                "cell-b", SettingsContracts.ObservationState.APPLIED, "v2",
                NOW.minusSeconds(30));

        SettingsContracts.ApplicationStatus result = evaluator.evaluate(
                evidence("v2", "v2", true, 2, List.of(old, current)), NOW);

        assertThat(result.state())
                .isEqualTo(SettingsContracts.ApplicationState.OBSERVATION_STALE);
        assertThat(result.stale()).isTrue();
        assertThat(result.latestObservationAt()).isEqualTo(current.observedAt());
    }

    @Test
    void reportsPartialDriftAndFailureWithoutCollapsingThemIntoSuccess() {
        SettingsContracts.TargetObservation converged = observation(
                "cell-a", SettingsContracts.ObservationState.APPLIED, "v2", NOW);
        SettingsContracts.TargetObservation drifted = observation(
                "cell-b", SettingsContracts.ObservationState.APPLIED, "v1", NOW);
        SettingsContracts.TargetObservation failed = observation(
                "cell-c", SettingsContracts.ObservationState.FAILED, null, NOW);

        SettingsContracts.ApplicationStatus mixed = evaluator.evaluate(
                evidence("v2", "v2", true, 3,
                        List.of(converged, drifted, failed)), NOW);
        assertThat(mixed.state()).isEqualTo(SettingsContracts.ApplicationState.PARTIAL);
        assertThat(mixed.convergedTargetCount()).isEqualTo(1);
        assertThat(mixed.driftedTargetCount()).isEqualTo(1);
        assertThat(mixed.failedTargetCount()).isEqualTo(1);

        assertThat(evaluator.evaluate(
                evidence("v2", "v2", true, 1, List.of(drifted)), NOW).state())
                .isEqualTo(SettingsContracts.ApplicationState.DRIFTED);
        assertThat(evaluator.evaluate(
                evidence("v2", "v2", true, 1, List.of(failed)), NOW).state())
                .isEqualTo(SettingsContracts.ApplicationState.FAILED);
        assertThat(evaluator.evaluate(
                evidence("v2", "v2", true, 2, List.of(failed)), NOW).state())
                .isEqualTo(SettingsContracts.ApplicationState.PARTIAL);
    }

    private SettingsContracts.ApplicationEvidence evidence(
            String desired,
            String published,
            boolean supported,
            int targets,
            List<SettingsContracts.TargetObservation> observations) {
        return new SettingsContracts.ApplicationEvidence(
                desired,
                desired == null
                        ? SettingsContracts.DesiredState.NONE
                        : published == null
                        ? SettingsContracts.DesiredState.DRAFT
                        : desired.equals(published)
                        ? SettingsContracts.DesiredState.PUBLISHED
                        : SettingsContracts.DesiredState.PUBLISH_REQUESTED,
                published, published == null ? null : NOW,
                supported, targets, observations);
    }

    private SettingsContracts.TargetObservation observation(
            String target,
            SettingsContracts.ObservationState state,
            String version,
            Instant observedAt) {
        return new SettingsContracts.TargetObservation(
                target, state, version, observedAt,
                state == SettingsContracts.ObservationState.APPLIED ? observedAt : null,
                state == SettingsContracts.ObservationState.FAILED ? "DELIVERY_FAILED" : null);
    }
}
