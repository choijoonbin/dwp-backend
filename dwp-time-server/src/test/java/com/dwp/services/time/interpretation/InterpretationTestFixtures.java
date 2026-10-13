package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.ClockFactType;
import com.dwp.services.time.interpretation.InterpretationModels.ClockInputFact;
import com.dwp.services.time.interpretation.InterpretationModels.EntryCode;
import com.dwp.services.time.interpretation.InterpretationModels.InputReference;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretCommand;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAction;
import com.dwp.services.time.interpretation.InterpretationModels.PopulationKind;
import com.dwp.services.time.interpretation.InterpretationModels.PublishedRuleVersion;
import com.dwp.services.time.interpretation.InterpretationModels.RuleOperator;
import com.dwp.services.time.interpretation.InterpretationModels.RuleOutput;
import com.dwp.services.time.interpretation.InterpretationModels.RuleValueType;
import com.dwp.services.time.interpretation.InterpretationModels.TypedRuleAst;
import com.dwp.services.time.interpretation.InterpretationModels.TypedRuleNode;
import com.dwp.services.time.interpretation.InterpretationModels.VerifiedInterpretationRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class InterpretationTestFixtures {

    static final long TENANT = 41L;
    static final UUID WORKER = uuid("30000000-0000-4000-8000-000000000041");
    static final UUID OTHER_WORKER = uuid("30000000-0000-4000-8000-000000000042");
    static final UUID SUBJECT = uuid("30000000-0000-4000-8000-000000000043");
    static final UUID POPULATION = uuid("30000000-0000-4000-8000-000000000044");
    static final UUID CORRELATION = uuid("30000000-0000-4000-8000-000000000045");
    static final UUID RULE_ID = uuid("30000000-0000-4000-8000-000000000046");
    static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    static final Instant AS_OF = Instant.parse("2026-10-12T12:00:00Z");
    static final LocalDate WORK_DATE = LocalDate.parse("2026-10-12");
    static final String DIGEST = "a".repeat(64);

    static InputReference workedReference() {
        return new InputReference(
                "TIM",
                "TIM.WorkedMinutes.v1",
                uuid("30000000-0000-4000-8000-000000000001"),
                7,
                DIGEST,
                null,
                null);
    }

    static TypedRuleAst timPRule() {
        return new TypedRuleAst(
                TypedRuleEngine.REGISTRY_ID,
                TypedRuleEngine.REGISTRY_VERSION,
                "isOvertime",
                List.of(
                        node("worked", RuleOperator.INPUT, List.of(),
                                RuleValueType.QUANTITY_MINUTE, null, workedReference()),
                        node("threshold", RuleOperator.QUANTITY_LITERAL, List.of(),
                                RuleValueType.QUANTITY_MINUTE, "480", null),
                        node("isOvertime", RuleOperator.COMPARE_LT,
                                List.of("threshold", "worked"),
                                RuleValueType.BOOLEAN, null, null)));
    }

    static PublishedRuleVersion minuteRule() {
        InputReference reference = workedReference();
        TypedRuleAst regular = new TypedRuleAst(
                TypedRuleEngine.REGISTRY_ID,
                1,
                "regular",
                List.of(
                        node("worked", RuleOperator.INPUT, List.of(),
                                RuleValueType.QUANTITY_MINUTE, null, reference),
                        node("cap", RuleOperator.QUANTITY_LITERAL, List.of(),
                                RuleValueType.QUANTITY_MINUTE, "480", null),
                        node("regular", RuleOperator.MIN, List.of("worked", "cap"),
                                RuleValueType.QUANTITY_MINUTE, null, null)));
        TypedRuleAst overtime = new TypedRuleAst(
                TypedRuleEngine.REGISTRY_ID,
                1,
                "overtime",
                List.of(
                        node("worked", RuleOperator.INPUT, List.of(),
                                RuleValueType.QUANTITY_MINUTE, null, reference),
                        node("cap", RuleOperator.QUANTITY_LITERAL, List.of(),
                                RuleValueType.QUANTITY_MINUTE, "480", null),
                        node("zero", RuleOperator.QUANTITY_LITERAL, List.of(),
                                RuleValueType.QUANTITY_MINUTE, "0", null),
                        node("above", RuleOperator.SUBTRACT, List.of("worked", "cap"),
                                RuleValueType.QUANTITY_MINUTE, null, null),
                        node("overtime", RuleOperator.MAX, List.of("above", "zero"),
                                RuleValueType.QUANTITY_MINUTE, null, null)));
        List<RuleOutput> outputs = List.of(
                new RuleOutput(EntryCode.REGULAR_MINUTES, regular),
                new RuleOutput(EntryCode.OVERTIME_MINUTES, overtime));
        return new PublishedRuleVersion(
                TENANT,
                RULE_ID,
                7,
                LocalDate.parse("2026-01-01"),
                LocalDate.parse("2027-01-01"),
                TypedRuleEngine.REGISTRY_ID,
                TypedRuleEngine.REGISTRY_VERSION,
                TypedRuleEngine.digest(outputs),
                outputs);
    }

    static List<ClockInputFact> facts(int outHour) {
        return List.of(
                fact(1, ClockFactType.IN, "2026-10-12T00:00:00Z"),
                fact(2, ClockFactType.BREAK_START, "2026-10-12T03:00:00Z"),
                fact(3, ClockFactType.BREAK_END, "2026-10-12T04:00:00Z"),
                fact(4, ClockFactType.OUT, "2026-10-12T" + String.format("%02d", outHour - 9)
                        + ":00:00Z"));
    }

    static InterpretCommand command() {
        return new InterpretCommand(List.of(WORKER), WORK_DATE, WORK_DATE, AS_OF);
    }

    static VerifiedInterpretationRequest authority() {
        return authority(TENANT, Set.of(InterpretationService.RUN_PERMISSION),
                PopulationKind.ORG_UNIT, Set.of(WORKER));
    }

    static VerifiedInterpretationRequest authority(
            long tenantId,
            Set<String> permissions,
            PopulationKind populationKind,
            Set<UUID> workers) {
        return new VerifiedInterpretationRequest(
                tenantId,
                72L,
                SUBJECT,
                permissions,
                Set.of(
                        InterpretationAction.VIEW,
                        InterpretationAction.RUN,
                        InterpretationAction.TRACE),
                populationKind,
                POPULATION,
                9,
                11,
                DIGEST,
                13,
                InterpretationService.PURPOSE,
                37,
                "psr-" + "b".repeat(64),
                NOW.plusSeconds(300),
                CORRELATION,
                workers,
                Set.of(),
                Set.of());
    }

    static TypedRuleNode node(
            String id,
            RuleOperator operator,
            List<String> arguments,
            RuleValueType type,
            Object value,
            InputReference input) {
        return new TypedRuleNode(id, operator, arguments, type, value, input, null);
    }

    private static ClockInputFact fact(long order, ClockFactType type, String occurredAt) {
        return new ClockInputFact(
                order,
                InterpretationDigests.stableUuid("clock", order, occurredAt),
                TENANT,
                WORKER,
                type,
                Instant.parse(occurredAt),
                "Asia/Seoul",
                540,
                "2026a",
                InterpretationDigests.sha256("clock-payload", order, occurredAt));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private InterpretationTestFixtures() {}
}
