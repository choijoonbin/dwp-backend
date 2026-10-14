package com.dwp.services.payroll.calculation;

import com.dwp.services.payroll.security.PayrollHighRiskCommandGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ActionCommand;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ReceiptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunState;
import static com.dwp.services.payroll.calculation.PayrollCalculationTestSupport.NOW;
import static com.dwp.services.payroll.calculation.PayrollCalculationTestSupport.RUN_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PayrollWorkerRecoveryTest {

    @Test
    void crashAfterCheckpointResumesWithoutDuplicateFactsOrSecondAttempt() {
        Fixture fixture = fixture();
        UUID receiptId = fixture.calculate();

        assertThatThrownBy(() -> fixture.worker.execute(
                1, RUN_ID, 1, "worker-a",
                PayrollCalculationWorker.FailurePoint.AFTER_FIRST_CHECKPOINT_COMMIT))
                .isInstanceOf(PayrollCalculationWorker.SimulatedWorkerCrash.class);
        assertThat(fixture.store.lineCount(1, RUN_ID)).isEqualTo(1);
        assertThat(fixture.store.outboxCount(1, RUN_ID, "PayrollCalculated")).isZero();

        fixture.clock.advance(Duration.ofSeconds(2));
        assertThat(fixture.worker.execute(1, RUN_ID, 1, "worker-b")).isTrue();

        assertThat(fixture.store.attemptCount(1, RUN_ID)).isEqualTo(1);
        assertThat(fixture.store.lineCount(1, RUN_ID)).isEqualTo(3);
        assertThat(fixture.store.outboxCount(1, RUN_ID, "PayrollCalculated")).isEqualTo(1);
        assertThat(fixture.store.history(1, RUN_ID, 1))
                .extracting(PayrollCalculationModels.AttemptStatus::state)
                .containsExactly(
                        AttemptState.READY, AttemptState.CALCULATING,
                        AttemptState.VALIDATED);
        assertThat(fixture.store.currentRun(1, RUN_ID).orElseThrow().state())
                .isEqualTo(RunState.VALIDATED);
        assertThat(fixture.store.receipt(1, receiptId).orElseThrow().receipt().status())
                .isEqualTo(ReceiptStatus.SUCCEEDED);
    }

    @Test
    void lostCompletionAcknowledgementProvesCommitInsteadOfDuplicatingIt() {
        Fixture fixture = fixture();
        UUID receiptId = fixture.calculate();

        assertThat(fixture.worker.execute(
                1, RUN_ID, 1, "worker-a",
                PayrollCalculationWorker.FailurePoint
                        .AFTER_COMPLETION_COMMIT_UNCERTAIN)).isTrue();

        assertThat(fixture.store.attemptCount(1, RUN_ID)).isEqualTo(1);
        assertThat(fixture.store.lineCount(1, RUN_ID)).isEqualTo(3);
        assertThat(fixture.store.outboxCount(1, RUN_ID, "PayrollCalculated")).isEqualTo(1);
        assertThat(fixture.store.receipt(1, receiptId).orElseThrow().receipt().status())
                .isEqualTo(ReceiptStatus.SUCCEEDED);
    }

    @Test
    void unresolvedCommitCreatesOneLinkedForwardRecoveryAttempt() {
        Fixture fixture = fixture();
        UUID receiptId = fixture.calculate();

        assertThat(fixture.worker.execute(
                1, RUN_ID, 1, "worker-a",
                PayrollCalculationWorker.FailurePoint
                        .BEFORE_COMPLETION_UNCERTAIN)).isFalse();
        assertThat(fixture.store.currentRun(1, RUN_ID).orElseThrow().state())
                .isEqualTo(RunState.RESULT_UNKNOWN);
        assertThat(fixture.store.receipt(1, receiptId).orElseThrow().receipt().status())
                .isEqualTo(ReceiptStatus.RESULT_UNKNOWN);

        assertThat(fixture.worker.reconcile(1, RUN_ID)).isTrue();
        assertThat(fixture.store.attemptCount(1, RUN_ID)).isEqualTo(2);
        assertThat(fixture.store.attempt(1, RUN_ID, 2).predecessorAttemptNo())
                .isEqualTo(1);
        assertThat(fixture.store.history(1, RUN_ID, 1))
                .extracting(PayrollCalculationModels.AttemptStatus::state)
                .containsExactly(
                        AttemptState.READY, AttemptState.CALCULATING,
                        AttemptState.RESULT_UNKNOWN, AttemptState.SUPERSEDED);

        assertThat(fixture.worker.execute(1, RUN_ID, 2, "worker-recovery")).isTrue();
        assertThat(fixture.store.history(1, RUN_ID, 2))
                .extracting(PayrollCalculationModels.AttemptStatus::state)
                .containsExactly(
                        AttemptState.READY, AttemptState.CALCULATING,
                        AttemptState.VALIDATED);
        assertThat(fixture.store.outboxCount(1, RUN_ID, "PayrollCalculated")).isEqualTo(1);
        assertThat(fixture.store.receipt(1, receiptId).orElseThrow().receipt().status())
                .isEqualTo(ReceiptStatus.SUCCEEDED);
    }

    @Test
    void actualTransactionOutcomeUnknownIsProbedBeforeTerminalDecision() {
        Fixture committed = fixture();
        UUID committedReceipt = committed.calculate();
        committed.store.failCompletion(
                PayrollCalculationTestSupport.InMemoryStore.CompletionFailure.AFTER_COMMIT);

        assertThat(committed.worker.execute(1, RUN_ID, 1, "worker-commit-lost"))
                .isTrue();
        assertThat(committed.store.outboxCount(1, RUN_ID, "PayrollCalculated"))
                .isEqualTo(1);
        assertThat(committed.store.receipt(1, committedReceipt).orElseThrow()
                .receipt().status()).isEqualTo(ReceiptStatus.SUCCEEDED);

        Fixture unknown = fixture();
        UUID unknownReceipt = unknown.calculate();
        unknown.store.failCompletion(
                PayrollCalculationTestSupport.InMemoryStore.CompletionFailure.BEFORE_COMMIT);

        assertThat(unknown.worker.execute(1, RUN_ID, 1, "worker-precommit-lost"))
                .isFalse();
        assertThat(unknown.store.outboxCount(1, RUN_ID, "PayrollCalculated")).isZero();
        assertThat(unknown.store.currentRun(1, RUN_ID).orElseThrow().state())
                .isEqualTo(RunState.RESULT_UNKNOWN);
        assertThat(unknown.store.receipt(1, unknownReceipt).orElseThrow()
                .receipt().status()).isEqualTo(ReceiptStatus.RESULT_UNKNOWN);
    }

    @Test
    void expiredStaleWorkerCannotTerminalizeReplacementLease() {
        Fixture fixture = fixture();
        fixture.calculate();
        var stale = fixture.store.claim(
                1, RUN_ID, 1, "worker-stale", NOW, Duration.ofSeconds(1))
                .orElseThrow();
        fixture.clock.advance(Duration.ofSeconds(2));
        var replacement = fixture.store.claim(
                1, RUN_ID, 1, "worker-replacement", fixture.clock.instant(),
                Duration.ofSeconds(1)).orElseThrow();

        assertThatThrownBy(() -> fixture.worker.execute(stale))
                .isInstanceOf(RuntimeException.class);
        assertThat(fixture.store.history(1, RUN_ID, 1))
                .extracting(PayrollCalculationModels.AttemptStatus::state)
                .containsExactly(AttemptState.READY);
        assertThat(fixture.store.outboxCount(1, RUN_ID, "PayrollCalculated")).isZero();

        assertThat(fixture.worker.execute(replacement)).isTrue();
        assertThat(fixture.store.history(1, RUN_ID, 1))
                .extracting(PayrollCalculationModels.AttemptStatus::state)
                .containsExactly(
                        AttemptState.READY, AttemptState.CALCULATING,
                        AttemptState.VALIDATED);
    }

    @Test
    void tamperedCheckpointFailsClosedWithoutSuccessEvent() {
        Fixture fixture = fixture();
        fixture.calculate();
        assertThatThrownBy(() -> fixture.worker.execute(
                1, RUN_ID, 1, "worker-a",
                PayrollCalculationWorker.FailurePoint.AFTER_FIRST_CHECKPOINT_COMMIT))
                .isInstanceOf(PayrollCalculationWorker.SimulatedWorkerCrash.class);
        fixture.store.tamperCheckpointDigest(1, RUN_ID, 1);
        fixture.clock.advance(Duration.ofSeconds(2));

        assertThat(fixture.worker.execute(1, RUN_ID, 1, "worker-b")).isFalse();
        assertThat(fixture.store.currentRun(1, RUN_ID).orElseThrow().state())
                .isEqualTo(RunState.RESULT_UNKNOWN);
        assertThat(fixture.store.outboxCount(1, RUN_ID, "PayrollCalculated")).isZero();
    }

    @Test
    void dispatcherDiscoversResultUnknownAndHonorsPerPollBound() {
        Fixture fixture = fixture();
        fixture.calculate();
        assertThat(fixture.worker.execute(
                1, RUN_ID, 1, "worker-a",
                PayrollCalculationWorker.FailurePoint.BEFORE_COMPLETION_UNCERTAIN))
                .isFalse();
        PayrollCalculationDispatcher dispatcher = new PayrollCalculationDispatcher(
                fixture.store, fixture.worker, fixture.clock, java.util.List.of(1L),
                1, "dispatcher-a", Duration.ofSeconds(1));

        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        assertThat(fixture.store.attemptCount(1, RUN_ID)).isEqualTo(2);
        assertThat(fixture.store.outboxCount(1, RUN_ID, "PayrollCalculated")).isZero();
        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        assertThat(fixture.store.outboxCount(1, RUN_ID, "PayrollCalculated"))
                .isEqualTo(1);
    }

    @Test
    void schedulingConfigurationIsDarkUnlessFeatureIsExplicitlyEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(PayrollCalculationScheduling.class);
        runner.withPropertyValues("dwp.hris.payroll.calculation.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PayrollCalculationScheduling.class));
        runner.withPropertyValues("dwp.hris.payroll.calculation.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(PayrollCalculationScheduling.class));
    }

    private Fixture fixture() {
        PayrollCalculationCanonical canonical = PayrollCalculationTestSupport.canonical();
        PayrollCalculationTestSupport.InMemoryStore store =
                new PayrollCalculationTestSupport.InMemoryStore();
        PayrollCalculationTestSupport.MutableClock clock =
                new PayrollCalculationTestSupport.MutableClock(NOW);
        PayrollCalculationService service = PayrollCalculationTestSupport.service(
                store, canonical, mock(PayrollHighRiskCommandGuard.class), clock);
        PayrollCalculationWorker worker = new PayrollCalculationWorker(
                store, new PayrollCalculationEngine(
                        canonical, PayrollCalculationTestSupport.exactSemanticMappings()),
                canonical,
                new PayrollCalculationTestSupport.NoopTransactionManager(),
                clock, Duration.ofSeconds(1), 1, 100);
        return new Fixture(store, service, worker, clock);
    }

    private record Fixture(
            PayrollCalculationTestSupport.InMemoryStore store,
            PayrollCalculationService service,
            PayrollCalculationWorker worker,
            PayrollCalculationTestSupport.MutableClock clock) {

        UUID calculate() {
            store.seedPreparedRun(
                    PayrollCalculationTestSupport.preparedRun(1, 101, RUN_ID));
            return service.calculate(
                    PayrollCalculationTestSupport.calculator(1, 101), RUN_ID,
                    "pay-gold-003", 1, UUID.randomUUID(),
                    new ActionCommand("REGULAR_RUN", null, null)).receiptId();
        }
    }
}
