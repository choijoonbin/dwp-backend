package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollHighRiskCommandGuard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ActionCommand;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.QuantizationBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.Category;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.EvaluatedLine;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.QuantizationStep;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.RoundingStage;
import static com.dwp.services.payroll.calculation.PayrollCalculationTestSupport.NOW;
import static com.dwp.services.payroll.calculation.PayrollCalculationTestSupport.RUN_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PayrollCalculationSemanticMappingTest {

    @Test
    void opaqueComponentCodeUsesInjectedExactCategoryMapping() {
        PayrollCalculationCanonical canonical = PayrollCalculationTestSupport.canonical();
        PayrollCalculationEngine engine = new PayrollCalculationEngine(canonical,
                PayrollCalculationTestSupport.exactSemanticMappings(
                        Map.of("X7Q9", new BigDecimal("125.0000"))));
        var snapshot = PayrollCalculationTestSupport.preparation(
                1, RUN_ID, 1, NOW).snapshot();

        assertThat(snapshot.inputs().getLast().approvedAmount())
                .isEqualByComparingTo("100.0000");
        var outcome = engine.calculate(snapshot);

        assertThat(outcome.lines()).extracting(
                PayrollCalculationModels.CalculatedLine::componentCode)
                .containsExactly("BASE", "ALLOWANCE", "X7Q9");
        assertThat(outcome.controlTotals()).extracting(
                PayrollCalculationModels.ControlTotal::code,
                PayrollCalculationModels.ControlTotal::amount)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "GROSS", new BigDecimal("3200.0000")),
                        org.assertj.core.groups.Tuple.tuple(
                                "DEDUCTIONS", new BigDecimal("125.0000")),
                        org.assertj.core.groups.Tuple.tuple(
                                "NET", new BigDecimal("3075.0000")));
        assertThat(outcome.lines()).allSatisfy(line -> {
            assertThat(line.elementId()).isNotNull();
            assertThat(line.elementVersion()).isEqualTo(1);
            assertThat(line.formulaId()).isNotNull();
            assertThat(line.formulaVersion()).isEqualTo(1);
            assertThat(line.formulaAstDigest())
                    .isEqualTo(PayrollCalculationTestSupport.FORMULA_DIGEST);
            assertThat(line.formulaInputDigest()).hasSize(64);
            assertThat(line.formulaOutputDigest()).hasSize(64)
                    .isNotEqualTo(line.formulaInputDigest());
            assertThat(line.traceNodes())
                    .extracting(PayrollCalculationModels.CalculatedTraceNode::stage)
                    .containsExactly(
                            RoundingStage.FORMULA_DECLARED,
                            RoundingStage.ELEMENT);
            assertThat(line.traceNodes().getFirst().parentTraceId()).isNull();
            assertThat(line.traceNodes().getLast().parentTraceId())
                    .isEqualTo(line.traceNodes().getFirst().traceId());
            assertThat(line.terminalTraceId())
                    .isEqualTo(line.traceNodes().getLast().traceId());
            assertThat(line.traceNodes()).allSatisfy(trace -> {
                assertThat(trace.roundingScale()).isEqualTo(4);
                assertThat(trace.unroundedAmount())
                        .isEqualByComparingTo(line.amount());
                assertThat(trace.roundedAmount())
                        .isEqualByComparingTo(line.amount());
            });
        });
        assertThat(outcome.lines().getLast().category())
                .isEqualTo(Category.DEDUCTION);
        assertThat(outcome.workers()).singleElement().satisfies(worker -> {
            assertThat(worker.gross()).isEqualByComparingTo("3200.0000");
            assertThat(worker.deductions()).isEqualByComparingTo("125.0000");
            assertThat(worker.net()).isEqualByComparingTo("3075.0000");
            assertThat(worker.employerCost()).isEqualByComparingTo("0.0000");
            assertThat(worker.lineCount()).isEqualTo(3);
        });
    }

    @Test
    void roundingEvidenceIsBoundedAndVerifiesTheActualQuantization() {
        assertThatThrownBy(() -> new QuantizationStep(
                1, null, RoundingStage.ELEMENT, null,
                "b".repeat(64), "c".repeat(64),
                new BigDecimal("12.50000000"), new BigDecimal("12.50000000"),
                9, RoundingMode.HALF_UP,
                new BigDecimal("0.00000001"), "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuantizationStep(
                1, null, RoundingStage.ELEMENT, null,
                "b".repeat(64), "c".repeat(64),
                new BigDecimal("12.50500000"), new BigDecimal("12.50000000"),
                2, RoundingMode.UP,
                new BigDecimal("0.01"), "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void disconnectedQuantizationTraceIsRejected() {
        var snapshot = PayrollCalculationTestSupport.preparation(
                1, RUN_ID, 1, NOW).snapshot();
        var input = snapshot.inputs().getFirst();
        EvaluatedLine exact = PayrollCalculationTestSupport.exactSemanticMappings()
                .resolve(snapshot, input).evaluatedLine();
        QuantizationStep first = exact.traceSteps().getFirst();
        QuantizationStep second = exact.traceSteps().getLast();
        QuantizationStep disconnected = new QuantizationStep(
                second.sequence(), second.parentSequence(), second.stage(),
                second.formula(), "f".repeat(64), second.outputDigest(),
                second.unroundedAmount(), second.roundedAmount(), second.scale(),
                second.mode(), second.increment(), second.roundingPolicyDigest());

        assertThatThrownBy(() -> new EvaluatedLine(
                exact.sourcePublicId(), exact.sourcePayloadDigest(),
                exact.componentCode(), exact.elementId(), exact.elementVersion(),
                exact.elementSemanticDigest(), exact.valueCurrencyMappingId(),
                exact.valueCurrencyMappingDigest(), exact.formula(),
                exact.formulaDependencies(), exact.functionSetVersion(),
                exact.category(), exact.evaluatedAmount(), exact.currency(),
                List.of(first, disconnected)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ordered pipeline");
    }

    @Test
    void quantizationIncrementKeepsDeclaredScaleAcrossCanonicalJsonRoundTrip()
            throws Exception {
        QuantizationBinding binding = new QuantizationBinding(
                1, null, RoundingStage.ELEMENT, null,
                4, RoundingMode.HALF_EVEN, new BigDecimal("10.0000"),
                "a".repeat(64));
        var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .findAndAddModules().build();

        String json = mapper.writeValueAsString(binding);
        QuantizationBinding restored = mapper.readValue(
                json, QuantizationBinding.class);

        assertThat(json).contains("\"increment\":10.0000")
                .doesNotContain("E+");
        assertThat(restored).isEqualTo(binding);
        assertThat(restored.increment().scale()).isEqualTo(4);
        assertThat(restored.increment().toPlainString()).isEqualTo("10.0000");
    }

    @Test
    void zeroValuedRunResponseUsesCanonicalPostedMoneyStrings() throws Exception {
        var response = PayrollCalculationTestSupport.preparedRun(1, 101, RUN_ID)
                .publicView();

        assertThat(response.gross().amount()).isEqualTo("0.0000");
        assertThat(response.net().amount()).isEqualTo("0.0000");
        assertThat(com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .findAndAddModules().build().writeValueAsString(response))
                .contains("\"gross\":{\"amount\":\"0.0000\",\"currency\":\"TST\"}")
                .contains("\"net\":{\"amount\":\"0.0000\",\"currency\":\"TST\"}");
    }

    @Test
    void unavailableProductionDefaultFailsBeforeResultFactsOrCalculatedEvent() {
        PayrollCalculationCanonical canonical = PayrollCalculationTestSupport.canonical();
        PayrollCalculationTestSupport.InMemoryStore store =
                new PayrollCalculationTestSupport.InMemoryStore();
        var prepared = PayrollCalculationTestSupport.preparedRun(1, 101, RUN_ID);
        store.seedPreparedRun(prepared);
        var preparedSnapshot = store.snapshot(1, RUN_ID, 1);
        PayrollCalculationService service = new PayrollCalculationService(
                store, canonical, mock(PayrollHighRiskCommandGuard.class),
                new PayrollCalculationSemanticBinder(
                        (snapshot, input) ->
                                PayrollCalculationSemanticMappingPort.Resolution.unavailable(),
                        canonical),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.calculate(
                PayrollCalculationTestSupport.calculator(1, 101), RUN_ID,
                "semantic-unavailable", prepared.revision(), UUID.randomUUID(),
                new ActionCommand("REGULAR_RUN", null, null)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        assertThat(store.snapshotCount()).isEqualTo(1);
        assertThat(store.snapshot(1, RUN_ID, 1)).isEqualTo(preparedSnapshot);
        assertThat(store.prepareCalls()).isZero();
        assertThat(store.freezeCalls()).isZero();
        assertThat(store.cloneCalls()).isZero();
        assertThat(store.receiptCount()).isZero();
        assertThat(store.auditCount()).isZero();
        assertThat(store.attemptCount(1, RUN_ID)).isZero();
        assertThat(store.runRevisionCount(1, RUN_ID)).isEqualTo(1);
        assertThat(store.lineCount(1, RUN_ID)).isZero();
        assertThat(store.outboxCount(1, RUN_ID, "PayrollCalculated")).isZero();
    }
}
