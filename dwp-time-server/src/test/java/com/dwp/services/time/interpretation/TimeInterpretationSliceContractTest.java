package com.dwp.services.time.interpretation;

import static com.dwp.services.time.interpretation.InterpretationTestFixtures.AS_OF;
import static com.dwp.services.time.interpretation.InterpretationTestFixtures.NOW;
import static com.dwp.services.time.interpretation.InterpretationTestFixtures.TENANT;
import static com.dwp.services.time.interpretation.InterpretationTestFixtures.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationInputs;
import com.dwp.services.time.interpretation.InterpretationModels.PopulationKind;
import com.dwp.services.time.interpretation.InterpretationModels.RuleOperator;
import com.dwp.services.time.interpretation.InterpretationModels.RuleValueType;
import com.dwp.services.time.interpretation.InterpretationModels.TypedRuleAst;
import com.dwp.services.time.interpretation.InterpretationModels.TypedRuleNode;
import com.dwp.services.time.interpretation.TypedRuleEngine.RuleError;
import com.dwp.services.time.interpretation.TypedRuleEngine.RuleInput;
import com.dwp.services.time.interpretation.TypedRuleEngine.RuleValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TimeInterpretationSliceContractTest {

    private final TypedRuleEngine engine = new TypedRuleEngine();
    private final DeterministicTimeInterpreter interpreter = new DeterministicTimeInterpreter();

    @Test
    void timPRuleUsesExactRegistryFixtureAndReturnsOvertimePredicate() {
        TypedRuleAst rule = InterpretationTestFixtures.timPRule();

        TypedRuleEngine.Evaluation evaluated = engine.evaluate(
                rule,
                Map.of("worked", RuleInput.quantityMinutes(
                        540, InterpretationTestFixtures.workedReference())));

        assertThat(rule.nodes()).hasSize(3);
        assertThat(evaluated.root().type()).isEqualTo(RuleValueType.BOOLEAN);
        assertThat(evaluated.root().bool()).isTrue();
        assertThat(rule.nodes().getFirst().inputRef().publicId()).isEqualTo(
                UUID.fromString("30000000-0000-4000-8000-000000000001"));
        assertThat(rule.nodes().getFirst().inputRef().revision()).isEqualTo(7);
    }

    @Test
    void unknownOperatorIsRejectedAtClosedJacksonEnumBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String canonical = mapper.writeValueAsString(InterpretationTestFixtures.timPRule());
        String hostile = canonical.replace("COMPARE_LT", "EXECUTE_SCRIPT");

        assertThatThrownBy(() -> mapper.readValue(hostile, TypedRuleAst.class))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("EXECUTE_SCRIPT");
    }

    @Test
    void cyclicAstReturnsCycleBeforeArityAndIncludesUnusedSubgraphs() {
        TypedRuleAst fixtureMutation = new TypedRuleAst(
                TypedRuleEngine.REGISTRY_ID,
                1,
                "isOvertime",
                List.of(
                        InterpretationTestFixtures.node(
                                "worked", RuleOperator.INPUT, List.of("isOvertime"),
                                RuleValueType.QUANTITY_MINUTE, null,
                                InterpretationTestFixtures.workedReference()),
                        InterpretationTestFixtures.node(
                                "threshold", RuleOperator.QUANTITY_LITERAL, List.of(),
                                RuleValueType.QUANTITY_MINUTE, "480", null),
                        InterpretationTestFixtures.node(
                                "isOvertime", RuleOperator.COMPARE_LT,
                                List.of("threshold", "worked"),
                                RuleValueType.BOOLEAN, null, null)));

        assertRuleError(fixtureMutation, RuleError.CYCLE);

        TypedRuleAst unusedCycle = new TypedRuleAst(
                TypedRuleEngine.REGISTRY_ID,
                1,
                "root",
                List.of(
                        InterpretationTestFixtures.node(
                                "root", RuleOperator.QUANTITY_LITERAL, List.of(),
                                RuleValueType.QUANTITY_MINUTE, "1", null),
                        InterpretationTestFixtures.node(
                                "unusedA", RuleOperator.ADD, List.of("unusedB", "unusedB"),
                                RuleValueType.QUANTITY_MINUTE, null, null),
                        InterpretationTestFixtures.node(
                                "unusedB", RuleOperator.ADD, List.of("unusedA", "unusedA"),
                                RuleValueType.QUANTITY_MINUTE, null, null)));
        assertRuleError(unusedCycle, RuleError.CYCLE);
    }

    @Test
    void multiplyRejectsUnregisteredQuantityByQuantitySignature() {
        TypedRuleAst invalid = new TypedRuleAst(
                TypedRuleEngine.REGISTRY_ID,
                1,
                "product",
                List.of(
                        InterpretationTestFixtures.node(
                                "left", RuleOperator.QUANTITY_LITERAL, List.of(),
                                RuleValueType.QUANTITY_MINUTE, "2", null),
                        InterpretationTestFixtures.node(
                                "right", RuleOperator.QUANTITY_LITERAL, List.of(),
                                RuleValueType.QUANTITY_MINUTE, "3", null),
                        InterpretationTestFixtures.node(
                                "product", RuleOperator.MULTIPLY, List.of("left", "right"),
                                RuleValueType.QUANTITY_MINUTE, null, null)));

        assertRuleError(invalid, RuleError.UNIT_MISMATCH);
    }

    @Test
    void goldenMinutesAndDigestsAreDeterministicAcrossInputOrder() {
        var rule = InterpretationTestFixtures.minuteRule();
        var eightHours = interpreter.interpret(
                TENANT,
                List.of(WORKER),
                InterpretationTestFixtures.WORK_DATE,
                InterpretationTestFixtures.WORK_DATE,
                AS_OF,
                InterpretationTestFixtures.facts(18),
                rule);
        var replay = interpreter.interpret(
                TENANT,
                List.of(WORKER),
                InterpretationTestFixtures.WORK_DATE,
                InterpretationTestFixtures.WORK_DATE,
                AS_OF,
                InterpretationTestFixtures.facts(18).reversed(),
                rule);
        var nineHours = interpreter.interpret(
                TENANT,
                List.of(WORKER),
                InterpretationTestFixtures.WORK_DATE,
                InterpretationTestFixtures.WORK_DATE,
                AS_OF,
                InterpretationTestFixtures.facts(19),
                rule);

        assertThat(eightHours).isEqualTo(replay);
        assertThat(eightHours.results()).singleElement().satisfies(result -> {
            assertThat(result.workedMinutes()).isEqualTo(480);
            assertThat(result.regularMinutes()).isEqualTo(480);
            assertThat(result.overtimeMinutes()).isZero();
            assertThat(result.unpaidBreakMinutes()).isEqualTo(60);
        });
        assertThat(nineHours.results()).singleElement().satisfies(result -> {
            assertThat(result.workedMinutes()).isEqualTo(540);
            assertThat(result.regularMinutes()).isEqualTo(480);
            assertThat(result.overtimeMinutes()).isEqualTo(60);
        });
        assertThat(nineHours.outputDigest()).isNotEqualTo(eightHours.outputDigest());
    }

    @Test
    void semanticReplayIsZeroDeltaAndCorrectionReversesEachEffectiveEntryOnce() {
        InMemoryInterpretationRepository repository = repository(18);
        InterpretationService service = service(repository);
        UUID firstKey = UUID.fromString("41000000-0000-4000-8000-000000000001");
        UUID replayKey = UUID.fromString("41000000-0000-4000-8000-000000000002");

        var first = service.run(
                InterpretationTestFixtures.authority(), firstKey,
                InterpretationTestFixtures.command());
        var sameKey = service.run(
                InterpretationTestFixtures.authority(), firstKey,
                InterpretationTestFixtures.command());
        var semanticReplay = service.run(
                InterpretationTestFixtures.authority(), replayKey,
                InterpretationTestFixtures.command());

        assertThat(sameKey.receiptId()).isEqualTo(first.receiptId());
        assertThat(sameKey.replayed()).isTrue();
        assertThat(semanticReplay.outputDigest()).isEqualTo(first.outputDigest());
        assertThat(repository.runs()).hasSize(2);
        assertThat(repository.runs().getFirst().ledgerDeltaApplied()).isTrue();
        assertThat(repository.runs().get(1).ledgerDeltaApplied()).isFalse();
        assertThat(repository.runs().get(1).entries()).isEmpty();
        assertThat(repository.runs().get(1).results()).isEqualTo(
                repository.runs().getFirst().results());
        assertThat(service.trace(
                InterpretationTestFixtures.authority(), semanticReplay.runPublicId()).steps())
                .isNotEmpty();

        repository.inputs(new InterpretationInputs(
                InterpretationTestFixtures.facts(19),
                InterpretationTestFixtures.minuteRule()));
        var corrected = service.run(
                InterpretationTestFixtures.authority(),
                UUID.fromString("41000000-0000-4000-8000-000000000003"),
                InterpretationTestFixtures.command());
        var correctedRun = repository.findRun(TENANT, corrected.runPublicId()).orElseThrow();
        var firstPositive = repository.runs().getFirst().entries().stream()
                .filter(entry -> entry.reversesEntryPublicId() == null).toList();

        assertThat(correctedRun.entries().stream()
                .filter(entry -> entry.reversesEntryPublicId() != null).toList())
                .hasSameSizeAs(firstPositive)
                .extracting(InterpretationModels.InterpretationEntry::reversesEntryPublicId)
                .containsExactlyInAnyOrderElementsOf(firstPositive.stream()
                        .map(InterpretationModels.InterpretationEntry::entryPublicId).toList());
    }

    @Test
    void concurrentDistinctKeysPostOnlyOneSemanticLedgerDelta() throws Exception {
        InMemoryInterpretationRepository repository = repository(18);
        InterpretationService service = service(repository);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> runAfterBarrier(
                    service, ready, start,
                    UUID.fromString("41000000-0000-4000-8000-000000000011")));
            var second = executor.submit(() -> runAfterBarrier(
                    service, ready, start,
                    UUID.fromString("41000000-0000-4000-8000-000000000012")));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).outputDigest())
                    .isEqualTo(second.get(5, TimeUnit.SECONDS).outputDigest());
        }

        assertThat(repository.runs()).hasSize(2);
        assertThat(repository.runs()).filteredOn(InterpretationModels.InterpretationRun::ledgerDeltaApplied)
                .hasSize(1);
        assertThat(repository.runs().stream().flatMap(run -> run.entries().stream())
                .filter(entry -> entry.reversesEntryPublicId() == null).toList())
                .hasSize(2);
    }

    @Test
    void tenantPopulationPermissionAndIdempotencyBoundariesFailClosed() {
        InterpretationService service = service(repository(18));

        assertThatThrownBy(() -> service.run(
                InterpretationTestFixtures.authority(
                        TENANT,
                        Set.of(InterpretationService.SELF_RUN_PERMISSION),
                        PopulationKind.ORG_UNIT,
                        Set.of(WORKER)),
                UUID.randomUUID(),
                InterpretationTestFixtures.command()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.run(
                InterpretationTestFixtures.authority(
                        TENANT,
                        Set.of(InterpretationService.RUN_PERMISSION),
                        PopulationKind.ORG_UNIT,
                        Set.of(InterpretationTestFixtures.OTHER_WORKER)),
                UUID.randomUUID(),
                InterpretationTestFixtures.command()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.run(
                InterpretationTestFixtures.authority(
                        TENANT + 1,
                        Set.of(InterpretationService.RUN_PERMISSION),
                        PopulationKind.ORG_UNIT,
                        Set.of(WORKER)),
                UUID.randomUUID(),
                InterpretationTestFixtures.command()))
                .isInstanceOf(AssertionError.class);
    }

    private static InterpretationModels.InterpretationCommandReceipt runAfterBarrier(
            InterpretationService service,
            CountDownLatch ready,
            CountDownLatch start,
            UUID key) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
        return service.run(
                InterpretationTestFixtures.authority(), key,
                InterpretationTestFixtures.command());
    }

    private static InMemoryInterpretationRepository repository(int outHour) {
        return new InMemoryInterpretationRepository(new InterpretationInputs(
                InterpretationTestFixtures.facts(outHour),
                InterpretationTestFixtures.minuteRule()));
    }

    private static InterpretationService service(InMemoryInterpretationRepository repository) {
        return new InterpretationService(
                repository,
                new DeterministicTimeInterpreter(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void assertRuleError(TypedRuleAst rule, RuleError expected) {
        assertThatThrownBy(() -> engine.validate(rule))
                .isInstanceOfSatisfying(RuleValidationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(expected));
    }
}
