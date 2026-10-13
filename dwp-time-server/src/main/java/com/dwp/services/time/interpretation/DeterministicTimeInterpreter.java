package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.ClockFactType;
import com.dwp.services.time.interpretation.InterpretationModels.ClockInputFact;
import com.dwp.services.time.interpretation.InterpretationModels.EntryCode;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationComputation;
import com.dwp.services.time.interpretation.InterpretationModels.PublishedRuleVersion;
import com.dwp.services.time.interpretation.InterpretationModels.RuleOutput;
import com.dwp.services.time.interpretation.InterpretationModels.RuleValueType;
import com.dwp.services.time.interpretation.InterpretationModels.TraceStep;
import com.dwp.services.time.interpretation.InterpretationModels.TypedRuleNode;
import com.dwp.services.time.interpretation.InterpretationModels.WorkerMinuteResult;
import com.dwp.services.time.interpretation.TypedRuleEngine.RuleInput;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure interpreter over frozen clock evidence and a content-addressed typed rule version. */
public final class DeterministicTimeInterpreter {

    private static final String WORKED_SCHEMA = "TIM.WorkedMinutes.v1";
    private static final String BREAK_SCHEMA = "TIM.UnpaidBreakMinutes.v1";

    private final TypedRuleEngine rules;

    public DeterministicTimeInterpreter() {
        this(new TypedRuleEngine());
    }

    DeterministicTimeInterpreter(TypedRuleEngine rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public InterpretationComputation interpret(
            long tenantId,
            List<UUID> requestedWorkers,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant asOf,
            List<ClockInputFact> inputFacts,
            PublishedRuleVersion ruleVersion) {
        validateRequest(tenantId, requestedWorkers, periodStart, periodEnd, asOf, ruleVersion);
        List<ClockInputFact> facts = canonicalFacts(
                tenantId, Set.copyOf(requestedWorkers), asOf, inputFacts);
        validateRuleVersion(ruleVersion, periodStart, periodEnd);

        String inputDigest = inputDigest(
                tenantId, requestedWorkers, periodStart, periodEnd, asOf, facts);
        List<WorkerMinuteResult> results = new ArrayList<>();
        List<TraceStep> traces = new ArrayList<>();
        int sequence = 1;
        Map<UUID, List<ClockInputFact>> byWorker = new HashMap<>();
        facts.forEach(fact -> byWorker.computeIfAbsent(
                fact.workerPublicId(), ignored -> new ArrayList<>()).add(fact));

        for (UUID workerId : requestedWorkers.stream().sorted().toList()) {
            RawMinutes raw = rawMinutes(byWorker.getOrDefault(workerId, List.of()));
            EnumMap<EntryCode, Integer> allocated = evaluate(
                    ruleVersion.outputs(), raw.workedMinutes(), raw.unpaidBreakMinutes());
            int regular = required(allocated, EntryCode.REGULAR_MINUTES);
            int overtime = required(allocated, EntryCode.OVERTIME_MINUTES);
            if (regular < 0 || overtime < 0 || regular + overtime != raw.workedMinutes()) {
                throw new InterpretationRejectedException(
                        RejectionCode.RULE_TOTAL_MISMATCH,
                        "Rule outputs must partition worked minutes exactly.");
            }
            results.add(new WorkerMinuteResult(
                    workerId,
                    raw.workedMinutes(),
                    regular,
                    overtime,
                    raw.unpaidBreakMinutes()));
            for (RuleOutput output : ruleVersion.outputs().stream()
                    .sorted(Comparator.comparing(candidate -> candidate.outputCode().name()))
                    .toList()) {
                int outputMinutes = allocated.get(output.outputCode());
                traces.add(new TraceStep(
                        sequence++,
                        output.outputCode().name(),
                        raw.workedMinutes(),
                        outputMinutes));
            }
        }

        String outputDigest = outputDigest(inputDigest, ruleVersion.ruleDigest(), results);
        return new InterpretationComputation(
                inputDigest,
                ruleVersion.ruleDigest(),
                outputDigest,
                results,
                traces);
    }

    private EnumMap<EntryCode, Integer> evaluate(
            List<RuleOutput> outputs, int workedMinutes, int breakMinutes) {
        EnumMap<EntryCode, Integer> result = new EnumMap<>(EntryCode.class);
        for (RuleOutput output : outputs) {
            Map<String, RuleInput> inputs = new LinkedHashMap<>();
            for (TypedRuleNode node : output.expression().nodes()) {
                if (node.operator() != InterpretationModels.RuleOperator.INPUT) continue;
                long value = switch (node.inputRef().schemaId()) {
                    case WORKED_SCHEMA -> workedMinutes;
                    case BREAK_SCHEMA -> breakMinutes;
                    default -> throw new InterpretationRejectedException(
                            RejectionCode.UNKNOWN_INPUT,
                            "The published rule references an unavailable input schema.");
                };
                inputs.put(node.nodeId(), RuleInput.quantityMinutes(value, node.inputRef()));
            }
            TypedRuleEngine.Evaluation evaluation = rules.evaluate(output.expression(), inputs);
            if (evaluation.root().type() != RuleValueType.QUANTITY_MINUTE) {
                throw new InterpretationRejectedException(
                        RejectionCode.RULE_TYPE_MISMATCH,
                        "Minute outputs must return QUANTITY_MINUTE.");
            }
            long value = evaluation.root().exactLong();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new InterpretationRejectedException(
                        RejectionCode.MINUTE_LIMIT_EXCEEDED,
                        "Rule output exceeds the supported minute range.");
            }
            result.put(output.outputCode(), (int) value);
        }
        return result;
    }

    private static RawMinutes rawMinutes(List<ClockInputFact> facts) {
        Instant workingSince = null;
        Instant breakSince = null;
        long workedSeconds = 0;
        long breakSeconds = 0;
        for (ClockInputFact fact : facts) {
            switch (fact.eventType()) {
                case IN -> {
                    if (workingSince != null || breakSince != null) {
                        throw sequence("IN occurred while a work interval was already open.");
                    }
                    workingSince = fact.occurredAt();
                }
                case BREAK_START -> {
                    if (workingSince == null || breakSince != null) {
                        throw sequence("BREAK_START requires one open work interval.");
                    }
                    workedSeconds += seconds(workingSince, fact.occurredAt());
                    workingSince = null;
                    breakSince = fact.occurredAt();
                }
                case BREAK_END -> {
                    if (breakSince == null || workingSince != null) {
                        throw sequence("BREAK_END requires one open break interval.");
                    }
                    breakSeconds += seconds(breakSince, fact.occurredAt());
                    breakSince = null;
                    workingSince = fact.occurredAt();
                }
                case OUT -> {
                    if (workingSince == null || breakSince != null) {
                        throw sequence("OUT requires one open work interval.");
                    }
                    workedSeconds += seconds(workingSince, fact.occurredAt());
                    workingSince = null;
                }
            }
        }
        if (workingSince != null || breakSince != null) {
            throw sequence("Clock input ends with an open interval.");
        }
        if (workedSeconds % 60 != 0 || breakSeconds % 60 != 0) {
            throw new InterpretationRejectedException(
                    RejectionCode.SUB_MINUTE_INPUT,
                    "Clock input must resolve to exact whole minutes.");
        }
        long workedMinutes = workedSeconds / 60;
        long unpaidBreakMinutes = breakSeconds / 60;
        if (workedMinutes > Integer.MAX_VALUE || unpaidBreakMinutes > Integer.MAX_VALUE) {
            throw new InterpretationRejectedException(
                    RejectionCode.MINUTE_LIMIT_EXCEEDED,
                    "Clock input exceeds the supported minute range.");
        }
        return new RawMinutes((int) workedMinutes, (int) unpaidBreakMinutes);
    }

    private static long seconds(Instant start, Instant end) {
        if (!end.isAfter(start)) throw sequence("Clock instants must increase strictly.");
        return Duration.between(start, end).toSeconds();
    }

    private static List<ClockInputFact> canonicalFacts(
            long tenantId,
            Set<UUID> workers,
            Instant asOf,
            List<ClockInputFact> inputFacts) {
        List<ClockInputFact> facts = inputFacts == null ? List.of() : List.copyOf(inputFacts);
        for (ClockInputFact fact : facts) {
            if (fact.tenantId() != tenantId || !workers.contains(fact.workerPublicId())
                    || fact.occurredAt().isAfter(asOf)) {
                throw new InterpretationRejectedException(
                        RejectionCode.INPUT_SCOPE_MISMATCH,
                        "Clock input escaped the trusted tenant, population, or as-of boundary.");
            }
        }
        return facts.stream()
                .sorted(Comparator.comparing(ClockInputFact::workerPublicId)
                        .thenComparing(ClockInputFact::occurredAt)
                        .thenComparingLong(ClockInputFact::eventOrder)
                        .thenComparing(ClockInputFact::clockEventPublicId))
                .toList();
    }

    private static void validateRequest(
            long tenantId,
            List<UUID> workers,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant asOf,
            PublishedRuleVersion rule) {
        if (tenantId <= 0 || workers == null || workers.isEmpty() || workers.size() > 1_000
                || workers.stream().distinct().count() != workers.size()) {
            throw new IllegalArgumentException("Interpretation target is invalid.");
        }
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(rule, "ruleVersion");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("Interpretation period is invalid.");
        }
    }

    private void validateRuleVersion(
            PublishedRuleVersion rule, LocalDate periodStart, LocalDate periodEnd) {
        if (!rule.effectiveFrom().isAfter(periodStart)
                && (rule.effectiveTo() == null || rule.effectiveTo().isAfter(periodEnd))) {
            // Half-open rule validity covers both inclusive command boundaries.
        } else {
            throw new InterpretationRejectedException(
                    RejectionCode.RULE_NOT_EFFECTIVE,
                    "The published rule does not cover the complete interpretation period.");
        }
        rule.outputs().forEach(output -> rules.validate(output.expression()));
        String actualDigest = TypedRuleEngine.digest(rule.outputs());
        if (!actualDigest.equals(rule.ruleDigest())) {
            throw new InterpretationRejectedException(
                    RejectionCode.RULE_DIGEST_MISMATCH,
                    "The typed rule version is not content-addressed by its payload.");
        }
        Set<EntryCode> codes = rule.outputs().stream().map(RuleOutput::outputCode).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        if (!codes.containsAll(Set.of(EntryCode.REGULAR_MINUTES, EntryCode.OVERTIME_MINUTES))) {
            throw new InterpretationRejectedException(
                    RejectionCode.RULE_TYPE_MISMATCH,
                    "The rule version must publish regular and overtime minute outputs.");
        }
    }

    private static int required(EnumMap<EntryCode, Integer> values, EntryCode code) {
        Integer value = values.get(code);
        if (value == null) {
            throw new InterpretationRejectedException(
                    RejectionCode.RULE_TYPE_MISMATCH,
                    "The required rule output " + code + " is absent.");
        }
        return value;
    }

    private static String inputDigest(
            long tenantId,
            List<UUID> workers,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant asOf,
            List<ClockInputFact> facts) {
        List<Object> values = new ArrayList<>();
        values.add("InterpretationInputSnapshot.v1");
        values.add(tenantId);
        values.add(workers.stream().sorted().toList());
        values.add(periodStart);
        values.add(periodEnd);
        values.add(asOf);
        for (ClockInputFact fact : facts) {
            values.add(fact.eventOrder());
            values.add(fact.clockEventPublicId());
            values.add(fact.workerPublicId());
            values.add(fact.eventType());
            values.add(fact.occurredAt());
            values.add(fact.sourceTimeZone());
            values.add(fact.sourceUtcOffsetMinutes());
            values.add(fact.timeZoneRuleVersion());
            values.add(fact.payloadDigest());
        }
        return InterpretationDigests.sha256(values.toArray());
    }

    private static String outputDigest(
            String inputDigest, String ruleDigest, List<WorkerMinuteResult> results) {
        List<Object> values = new ArrayList<>();
        values.add("TimeInterpretedOutput.v1");
        values.add(inputDigest);
        values.add(ruleDigest);
        for (WorkerMinuteResult result : results) {
            values.add(result.workerPublicId());
            values.add(result.workedMinutes());
            values.add(result.regularMinutes());
            values.add(result.overtimeMinutes());
            values.add(result.unpaidBreakMinutes());
        }
        return InterpretationDigests.sha256(values.toArray());
    }

    private static InterpretationRejectedException sequence(String message) {
        return new InterpretationRejectedException(RejectionCode.CLOCK_SEQUENCE_INVALID, message);
    }

    public enum RejectionCode {
        INPUT_SCOPE_MISMATCH,
        CLOCK_SEQUENCE_INVALID,
        SUB_MINUTE_INPUT,
        UNKNOWN_INPUT,
        RULE_NOT_EFFECTIVE,
        RULE_DIGEST_MISMATCH,
        RULE_TYPE_MISMATCH,
        RULE_TOTAL_MISMATCH,
        MINUTE_LIMIT_EXCEEDED
    }

    public static final class InterpretationRejectedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final RejectionCode code;

        InterpretationRejectedException(RejectionCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public RejectionCode code() {
            return code;
        }
    }

    private record RawMinutes(int workedMinutes, int unpaidBreakMinutes) {}
}
