package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollCalculationAuthority;
import com.dwp.services.payroll.security.PayrollHighRiskCommandGuard;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Attempt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculationOutcome;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Checkpoint;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CommandIdentity;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.EventFact;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FormulaBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Lease;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PayrollCommandReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PersistedReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ReceiptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunRevision;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SourceInput;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.Category;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.EvaluatedLine;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.Resolution;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.QuantizationStep;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.RoundingStage;

final class PayrollCalculationTestSupport {

    static final Instant NOW = Instant.parse("2026-10-14T02:00:00Z");
    static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000001101");
    static final UUID PAY_PERIOD = UUID.fromString("00000000-0000-0000-0000-000000001102");
    static final UUID LEGAL_ENTITY = UUID.fromString("00000000-0000-0000-0000-000000001103");
    static final UUID PAY_GROUP = UUID.fromString("00000000-0000-0000-0000-000000001104");
    static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000001105");
    static final String DIGEST = "a".repeat(64);
    static final String FORMULA_DIGEST = "b".repeat(64);
    static final String TARGET_DIGEST = "c".repeat(64);
    static final String INPUT_DIGEST = "d".repeat(64);

    private PayrollCalculationTestSupport() {
    }

    static PayrollCalculationCanonical canonical() {
        return new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
    }

    static PayrollCalculationSemanticMappingPort exactSemanticMappings() {
        return exactSemanticMappings(Map.of());
    }

    static PayrollCalculationSemanticMappingPort exactSemanticMappings(
            Map<String, BigDecimal> evaluatedAmounts) {
        PayrollCalculationCanonical canonical = canonical();
        Map<String, UUID> elementIds = Map.of(
                "BASE", UUID.fromString("00000000-0000-0000-0000-000000001109"),
                "ALLOWANCE", UUID.fromString("00000000-0000-0000-0000-000000001110"),
                "X7Q9", UUID.fromString("00000000-0000-0000-0000-000000001111"));
        Map<String, Category> categories = Map.of(
                "BASE", Category.EARNING,
                "ALLOWANCE", Category.EARNING,
                "X7Q9", Category.DEDUCTION);
        return (snapshot, input) -> {
            if (snapshot.formulas().isEmpty()) return Resolution.unavailable();
            FormulaBinding formula = snapshot.formulas().getFirst();
            UUID elementId = elementIds.get(input.componentCode());
            Category category = categories.get(input.componentCode());
            if (elementId == null || category == null) return Resolution.unavailable();
            BigDecimal amount = evaluatedAmounts.getOrDefault(
                    input.componentCode(), input.approvedAmount()).setScale(4);
            BigDecimal unrounded = amount.setScale(8);
            String formulaPolicyDigest = canonical.digest(Map.of(
                    "stage", RoundingStage.FORMULA_DECLARED.name(),
                    "scale", 4,
                    "mode", RoundingMode.HALF_UP.name(),
                    "increment", "0.0001"));
            String inputDigest = canonical.digest(Map.of(
                    "sourcePublicId", input.sourcePublicId(),
                    "sourcePayloadDigest", input.payloadDigest(),
                    "formulaId", formula.formulaId(),
                    "formulaVersion", formula.version()));
            String formulaOutputDigest = canonical.digest(Map.of(
                    "formulaInputDigest", inputDigest,
                    "amount", amount.toPlainString(),
                    "currency", input.currency()));
            String elementOutputDigest = canonical.digest(Map.of(
                    "formulaOutputDigest", formulaOutputDigest,
                    "elementId", elementId,
                    "elementVersion", 1,
                    "amount", amount.toPlainString(),
                    "currency", input.currency()));
            String elementPolicyDigest = canonical.digest(Map.of(
                    "stage", RoundingStage.ELEMENT.name(),
                    "scale", 4,
                    "mode", RoundingMode.HALF_UP.name(),
                    "increment", "0.0001"));
            List<QuantizationStep> trace = List.of(
                    new QuantizationStep(
                            1, null, RoundingStage.FORMULA_DECLARED, formula,
                            inputDigest, formulaOutputDigest, unrounded, amount,
                            4, RoundingMode.HALF_UP, new BigDecimal("0.0001"),
                            formulaPolicyDigest),
                    new QuantizationStep(
                            2, 1, RoundingStage.ELEMENT, formula,
                            formulaOutputDigest, elementOutputDigest,
                            unrounded, amount, 4, RoundingMode.HALF_UP,
                            new BigDecimal("0.0001"), elementPolicyDigest));
            return Resolution.exact(new EvaluatedLine(
                    input.sourcePublicId(), input.payloadDigest(), input.componentCode(),
                    elementId, 1,
                    canonical.digest(Map.of(
                            "elementId", elementId, "elementVersion", 1)),
                    "PAY011_TEST:" + input.componentCode(),
                    canonical.digest(Map.of(
                            "mapping", "PAY011_TEST:" + input.componentCode(),
                            "category", category.name(),
                            "currency", input.currency())),
                    formula, List.of(formula), "PAY_RULE_AST_V1:1",
                    category, amount, input.currency(),
                    trace));
        };
    }

    static PayrollCalculationAuthority.Actor actor(
            long tenantId,
            long actorId,
            PayrollCalculationAuthority.Action action,
            String purpose,
            String populationKind,
            Set<String> permissions) {
        PayrollCalculationAuthority.Subject subject = new PayrollCalculationAuthority.Subject(
                tenantId, actorId, SUBJECT, action, route(action), capability(action),
                purpose, "ctx-payroll", "les:root", populationKind,
                7, 41, "decision-41", NOW.plus(Duration.ofHours(1)),
                action == PayrollCalculationAuthority.Action.APPROVE
                        || action == PayrollCalculationAuthority.Action.FINALIZE
                        ? "ELEVATED" : "NORMAL",
                permissions);
        PayrollCalculationAuthority.Scope scope = new PayrollCalculationAuthority.Scope(
                Set.of(new PayrollCalculationAuthority.PopulationTuple(
                        LEGAL_ENTITY, PAY_GROUP, populationKind, "les:root")),
                "projection-41", TARGET_DIGEST);
        return new PayrollCalculationAuthority.Actor(subject, scope);
    }

    static PayrollCalculationAuthority.Actor calculator(long tenantId, long actorId) {
        return actor(tenantId, actorId, PayrollCalculationAuthority.Action.CALCULATE,
                "PAYROLL_PROCESSING", "PAY_GROUP",
                Set.of("APP.HCM:VIEW", "ACTION.HRIS_PAYROLL_RUN:EXECUTE"));
    }

    static PayrollCalculationService service(
            InMemoryStore store,
            PayrollCalculationCanonical canonical,
            PayrollHighRiskCommandGuard guard,
            Clock clock) {
        return new PayrollCalculationService(
                store, canonical, guard,
                new PayrollCalculationSemanticBinder(
                        exactSemanticMappings(), canonical),
                clock);
    }

    static PayrollCalculationStore.Preparation preparation(
            long tenantId, UUID runId, int attemptNo, Instant pinnedAt) {
        PayrollCalculationStore.Preparation unresolved = unboundPreparation(
                tenantId, runId, attemptNo, pinnedAt);
        PayrollCalculationCanonical canonical = canonical();
        FrozenSnapshot bound = new PayrollCalculationSemanticBinder(
                exactSemanticMappings(), canonical).bind(unresolved.snapshot());
        return new PayrollCalculationStore.Preparation(
                unresolved.payPeriodId(), unresolved.legalEntityId(),
                unresolved.payrollGroupId(), unresolved.currency(), bound);
    }

    private static PayrollCalculationStore.Preparation unboundPreparation(
            long tenantId, UUID runId, int attemptNo, Instant pinnedAt) {
        UUID snapshotId = UUID.fromString("00000000-0000-0000-0000-000000001106");
        UUID worker = UUID.fromString("00000000-0000-0000-0000-000000001107");
        List<SourceInput> inputs = List.of(
                input(1, snapshotId, worker, "BASE", "3000.00", "1".repeat(64)),
                input(2, snapshotId, worker, "ALLOWANCE", "200.00", "2".repeat(64)),
                input(3, snapshotId, worker, "X7Q9", "100.00",
                        "3".repeat(64)));
        FrozenSnapshot snapshot = new FrozenSnapshot(
                tenantId, runId, attemptNo, snapshotId, 9, DIGEST,
                FORMULA_DIGEST, TARGET_DIGEST, INPUT_DIGEST, null, inputs,
                List.of(new FormulaBinding(
                        UUID.fromString("00000000-0000-0000-0000-000000001108"),
                        1, FORMULA_DIGEST)), List.of(), pinnedAt);
        return new PayrollCalculationStore.Preparation(
                PAY_PERIOD, LEGAL_ENTITY, PAY_GROUP, "TST", snapshot);
    }

    static RunRevision preparedRun(long tenantId, long makerActorId, UUID runId) {
        return new RunRevision(
                tenantId, runId, 2, PayrollCalculationModels.RunType.REGULAR,
                PAY_PERIOD, UUID.fromString("00000000-0000-0000-0000-000000001106"),
                9, DIGEST, LEGAL_ENTITY, PAY_GROUP, "PAY_GROUP", TARGET_DIGEST,
                RunState.READY, makerActorId, SUBJECT, 0, 0,
                new BigDecimal("0.0000"), new BigDecimal("0.0000"), "TST", null,
                null, null, null, null, null, new UUID(0, 1_113), NOW);
    }

    private static SourceInput input(
            long id, UUID snapshotId, UUID workerId, String component,
            String amount, String digest) {
        return new SourceInput(
                id, new UUID(0, 200 + id), new UUID(0, 300 + id), snapshotId, 9,
                DIGEST, new UUID(0, 400), PAY_PERIOD, (int) id, workerId,
                new UUID(0, 500 + id), component, "TST", new BigDecimal(amount),
                LocalDate.of(2026, 10, 1), null, digest);
    }

    private static String route(PayrollCalculationAuthority.Action action) {
        return switch (action) {
            case CALCULATE -> "route.hcm.operations.payroll-run-calculate.action";
            case RUN_VIEW -> "route.hcm.operations.payroll-run.data";
            case APPROVE -> "route.hcm.operations.payroll-run-approve.action";
            case FINALIZE -> "route.hcm.operations.payroll-run-finalize.action";
            case RECEIPT_VIEW -> "route.hcm.operations.payroll-command-receipt.data";
        };
    }

    private static String capability(PayrollCalculationAuthority.Action action) {
        return switch (action) {
            case CALCULATE -> "hcm.operations.payroll-run.execute";
            case RUN_VIEW -> "hcm.operations.payroll-support.view";
            case APPROVE -> "hcm.operations.payroll.approve";
            case FINALIZE -> "hcm.operations.payroll.release";
            case RECEIPT_VIEW -> "hcm.command-receipt.view";
        };
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    static class InMemoryStore implements PayrollCalculationStore {
        enum CompletionFailure { NONE, BEFORE_COMMIT, AFTER_COMMIT }

        private final Map<String, PersistedReceipt> commands = new HashMap<>();
        private final Map<UUID, PersistedReceipt> receipts = new HashMap<>();
        private final Map<String, List<RunRevision>> runs = new HashMap<>();
        private final Map<String, Attempt> attempts = new HashMap<>();
        private final Map<String, List<AttemptStatus>> statuses = new HashMap<>();
        private final Map<String, FrozenSnapshot> snapshots = new HashMap<>();
        private final Map<String, Lease> leases = new HashMap<>();
        private final Map<String, Checkpoint> checkpoints = new HashMap<>();
        private final Map<String, PayrollCalculationModels.CalculatedLine> lines =
                new LinkedHashMap<>();
        private final List<EventFact> events = new ArrayList<>();
        private final List<String> audits = new ArrayList<>();
        private CompletionFailure completionFailure = CompletionFailure.NONE;
        private int prepareCalls;
        private int freezeCalls;
        private int cloneCalls;

        @Override
        public void bindTenant(long tenantId) {
        }

        @Override
        public void lockRun(long tenantId, UUID runId) {
        }

        @Override
        public Optional<PersistedReceipt> command(CommandIdentity identity) {
            return Optional.ofNullable(commands.get(commandKey(
                    identity.tenantId(), identity.subjectPrincipalPublicId(),
                    identity.commandType().originatingAction(), identity.idempotencyKey())));
        }

        @Override
        public Optional<PersistedReceipt> receipt(long tenantId, UUID receiptId) {
            PersistedReceipt value = receipts.get(receiptId);
            return value != null && value.tenantId() == tenantId
                    ? Optional.of(value) : Optional.empty();
        }

        @Override
        public Optional<RunRevision> currentRun(long tenantId, UUID runId) {
            List<RunRevision> revisions = runs.get(runKey(tenantId, runId));
            return revisions == null || revisions.isEmpty()
                    ? Optional.empty() : Optional.of(revisions.getLast());
        }

        @Override
        public Optional<AttemptStatus> currentAttemptStatus(
                long tenantId, UUID runId, int attemptNo) {
            List<AttemptStatus> history = statuses.get(attemptKey(tenantId, runId, attemptNo));
            return history == null || history.isEmpty()
                    ? Optional.empty() : Optional.of(history.getLast());
        }

        @Override
        public Preparation prepare(
                RunRevision run, int attemptNo,
                String targetPopulationDigest, Instant pinnedAt) {
            prepareCalls++;
            return unboundPreparation(run.tenantId(), run.runId(), attemptNo, pinnedAt);
        }

        void seedPreparedRun(RunRevision run) {
            runs.computeIfAbsent(runKey(run.tenantId(), run.runId()),
                    ignored -> new ArrayList<>()).add(run);
            if (run.state() == RunState.READY && run.latestAttemptNo() == 0) {
                snapshots.put(attemptKey(run.tenantId(), run.runId(), 1),
                        preparation(run.tenantId(), run.runId(), 1, NOW).snapshot());
            }
        }

        @Override
        public void freeze(Preparation preparation) {
            freezeCalls++;
            String key = attemptKey(
                    preparation.snapshot().tenantId(), preparation.snapshot().runId(),
                    preparation.snapshot().attemptNo());
            if (snapshots.putIfAbsent(key, preparation.snapshot()) != null) {
                throw new IllegalStateException("snapshot already frozen");
            }
        }

        @Override
        public void appendAttempt(Attempt attempt) {
            String key = attemptKey(attempt.tenantId(), attempt.runId(), attempt.attemptNo());
            if (attempts.putIfAbsent(key, attempt) != null) {
                throw new IllegalStateException("attempt already exists");
            }
            appendAttemptStatus(new AttemptStatus(
                    attempt.tenantId(), attempt.runId(), attempt.attemptNo(), 1,
                    AttemptState.READY, null, attempt.createdAt()));
        }

        @Override
        public void appendAttemptStatus(AttemptStatus status) {
            String key = attemptKey(status.tenantId(), status.runId(), status.attemptNo());
            List<AttemptStatus> history = statuses.computeIfAbsent(
                    key, ignored -> new ArrayList<>());
            if (status.statusSequence() == 1 && !history.isEmpty()
                    && history.getFirst().state() == AttemptState.READY) return;
            if (status.statusSequence() != history.size() + 1) {
                throw new IllegalStateException("attempt status sequence gap");
            }
            history.add(status);
        }

        @Override
        public void appendRunRevision(RunRevision revision) {
            List<RunRevision> revisions = runs.computeIfAbsent(
                    runKey(revision.tenantId(), revision.runId()),
                    ignored -> new ArrayList<>());
            long expected = revisions.isEmpty()
                    ? 1L : revisions.getLast().revision() + 1L;
            if (revision.revision() != expected) {
                throw new IllegalStateException("run revision gap");
            }
            revisions.add(revision);
        }

        @Override
        public void insertReceipt(PersistedReceipt receipt) {
            String key = commandKey(
                    receipt.tenantId(), receipt.receipt().subjectPrincipalPublicId(),
                    receipt.receipt().originatingAction(), receipt.idempotencyKey());
            if (commands.putIfAbsent(key, receipt) != null
                    || receipts.putIfAbsent(receipt.receipt().receiptId(), receipt) != null) {
                throw new IllegalStateException("receipt already exists");
            }
        }

        @Override
        public void appendReceiptStatus(
                long tenantId, UUID receiptId, ReceiptStatus status, Instant occurredAt) {
            PersistedReceipt old = receipt(tenantId, receiptId).orElseThrow();
            if (old.receipt().status() == status) return;
            PayrollCommandReceipt source = old.receipt();
            PayrollCommandReceipt changed = new PayrollCommandReceipt(
                    source.receiptId(), source.commandType(), source.originatingAction(),
                    source.subjectPrincipalPublicId(), source.populationScopeDigest(),
                    source.fieldPolicyRevision(), source.purposeCode(),
                    source.authorizationRevision(), status, source.correlationId(),
                    source.requestDigest(), source.createdAt(), source.aggregateVersion(),
                    source.resultRef());
            PersistedReceipt replacement = new PersistedReceipt(
                    old.tenantId(), old.actorId(), old.runId(), old.populationKind(),
                    old.contextScopeKey(), old.idempotencyKey(), old.commandType(),
                    changed, old.stepUpEvidence());
            receipts.put(receiptId, replacement);
            commands.put(commandKey(
                    tenantId, changed.subjectPrincipalPublicId(),
                    changed.originatingAction(), old.idempotencyKey()), replacement);
        }

        @Override
        public void appendAudit(
                PersistedReceipt receipt, long runRevision, String eventType,
                String eventPayload, Instant occurredAt) {
            audits.add(eventType + ":" + runRevision + ":" + eventPayload);
        }

        @Override
        public void appendEvent(long tenantId, EventFact event) {
            if (events.stream().noneMatch(value -> value.eventId().equals(event.eventId()))) {
                events.add(event);
            }
        }

        @Override
        public Optional<Lease> claim(
                long tenantId, UUID runId, int attemptNo, String workerInstanceId,
                Instant now, Duration leaseDuration) {
            String key = attemptKey(tenantId, runId, attemptNo);
            Lease previous = leases.get(key);
            if (previous != null && previous.expiresAt().isAfter(now)) return Optional.empty();
            Attempt attempt = attempts.get(key);
            if (attempt == null) throw new IllegalStateException("attempt missing");
            Lease lease = new Lease(
                    tenantId, runId, attemptNo, attempt.attemptId(),
                    previous == null ? 1 : previous.claimNo() + 1,
                    UUID.randomUUID(), workerInstanceId, now, now.plus(leaseDuration));
            leases.put(key, lease);
            return Optional.of(lease);
        }

        @Override
        public Optional<Lease> renew(Lease lease, Instant now, Duration leaseDuration) {
            String key = attemptKey(lease.tenantId(), lease.runId(), lease.attemptNo());
            Lease current = leases.get(key);
            if (!sameLease(current, lease) || !current.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            Lease renewed = new Lease(
                    lease.tenantId(), lease.runId(), lease.attemptNo(), lease.attemptId(),
                    lease.claimNo(), lease.leaseToken(), lease.workerInstanceId(),
                    lease.claimedAt(), now.plus(leaseDuration));
            leases.put(key, renewed);
            return Optional.of(renewed);
        }

        @Override
        public void verifyLease(Lease lease) {
            requireLease(lease, lease.claimedAt());
        }

        @Override
        public FrozenSnapshot snapshot(long tenantId, UUID runId, int attemptNo) {
            FrozenSnapshot snapshot = snapshots.get(attemptKey(tenantId, runId, attemptNo));
            if (snapshot == null) throw new IllegalStateException("snapshot missing");
            return snapshot;
        }

        @Override
        public FrozenSnapshot cloneSnapshot(
                long tenantId, UUID runId,
                int sourceAttemptNo, int targetAttemptNo) {
            cloneCalls++;
            FrozenSnapshot source = snapshot(tenantId, runId, sourceAttemptNo);
            FrozenSnapshot clone = new FrozenSnapshot(
                    source.tenantId(), source.runId(), targetAttemptNo,
                    source.sourceSnapshotId(), source.sourceSnapshotRevision(),
                    source.sourcePayloadDigest(), source.formulaSetDigest(),
                    source.targetPopulationDigest(), source.compensationInputDigest(),
                    source.semanticBindingDigest(), source.inputs(), source.formulas(),
                    source.semanticBindings(), source.pinnedAt());
            String key = attemptKey(tenantId, runId, targetAttemptNo);
            if (snapshots.putIfAbsent(key, clone) != null) {
                throw new IllegalStateException("snapshot already cloned");
            }
            return clone;
        }

        @Override
        public Optional<Checkpoint> latestCheckpoint(
                long tenantId, UUID runId, int attemptNo) {
            return Optional.ofNullable(checkpoints.get(attemptKey(tenantId, runId, attemptNo)));
        }

        @Override
        public void verifyCheckpoint(
                Lease lease, Checkpoint checkpoint, CalculationOutcome outcome) {
            requireLease(lease, lease.claimedAt());
            int prefixSize = checkpoint.nextInputOrdinal() - 1;
            if (prefixSize < 1 || prefixSize > outcome.lines().size()) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "checkpoint prefix is invalid");
            }
            List<PayrollCalculationModels.CalculatedLine> durable =
                    outcome.lines().subList(0, prefixSize).stream()
                            .map(expected -> lines.get(
                                    attemptKey(lease.tenantId(), lease.runId(),
                                            lease.attemptNo()) + ":"
                                            + expected.sourceInputId()))
                            .toList();
            if (durable.stream().anyMatch(java.util.Objects::isNull)
                    || !durable.equals(outcome.lines().subList(0, prefixSize))) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "checkpoint durable facts changed");
            }
            int completedWorkers = (int) durable.stream()
                    .map(PayrollCalculationModels.CalculatedLine::workerId)
                    .distinct().count();
            String digest = PayrollCalculationTestSupport.canonical().checkpointDigest(
                    lease.runId(), lease.attemptNo(), checkpoint.checkpointNo(),
                    checkpoint.nextInputOrdinal(), durable.stream()
                            .map(PayrollCalculationModels.CalculatedLine::resultLineDigest)
                            .toList());
            if (completedWorkers != checkpoint.completedWorkerCount()
                    || !digest.equals(checkpoint.checkpointDigest())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "checkpoint digest changed");
            }
        }

        @Override
        public void appendProgress(
                Lease lease, CalculationOutcome outcome, int fromInputOrdinal,
                int toInputOrdinal, Checkpoint checkpoint, Instant recordedAt) {
            requireLease(lease, recordedAt);
            for (int index = fromInputOrdinal - 1; index < toInputOrdinal; index++) {
                PayrollCalculationModels.CalculatedLine line = outcome.lines().get(index);
                lines.putIfAbsent(
                        attemptKey(lease.tenantId(), lease.runId(), lease.attemptNo())
                                + ":" + line.sourceInputId(), line);
            }
            checkpoints.put(
                    attemptKey(lease.tenantId(), lease.runId(), lease.attemptNo()), checkpoint);
        }

        @Override
        public void complete(
                Lease lease, CalculationOutcome outcome, RunRevision completedRun,
                UUID receiptId, EventFact event, String auditPayload, Instant completedAt) {
            if (completionFailure == CompletionFailure.BEFORE_COMMIT) {
                throw new TransactionSystemException(
                        "simulated connection loss before commit outcome");
            }
            requireLease(lease, completedAt);
            List<PayrollCalculationModels.CalculatedLine> durable = outcome.lines().stream()
                    .map(expected -> lines.get(
                            attemptKey(lease.tenantId(), lease.runId(),
                                    lease.attemptNo()) + ":" + expected.sourceInputId()))
                    .toList();
            if (durable.stream().anyMatch(java.util.Objects::isNull)
                    || !durable.equals(outcome.lines())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "durable calculation facts differ");
            }
            AttemptStatus current = currentAttemptStatus(
                    lease.tenantId(), lease.runId(), lease.attemptNo()).orElseThrow();
            appendAttemptStatus(new AttemptStatus(
                    lease.tenantId(), lease.runId(), lease.attemptNo(),
                    current.statusSequence() + 1, AttemptState.VALIDATED, null, completedAt));
            appendRunRevision(completedRun);
            appendReceiptStatus(lease.tenantId(), receiptId, ReceiptStatus.SUCCEEDED, completedAt);
            appendAudit(receipt(lease.tenantId(), receiptId).orElseThrow(),
                    completedRun.revision(), "PAYROLL_CALCULATION_COMPLETED",
                    auditPayload, completedAt);
            appendEvent(lease.tenantId(), event);
            if (completionFailure == CompletionFailure.AFTER_COMMIT) {
                throw new TransactionSystemException(
                        "simulated connection loss after durable commit");
            }
        }

        @Override
        public void terminalFailure(
                Lease lease, UUID receiptId,
                AttemptState state, String failureCode, RunRevision failedRun,
                String auditPayload, Instant occurredAt) {
            requireLease(lease, occurredAt);
            RunRevision run = currentRun(lease.tenantId(), lease.runId()).orElseThrow();
            if (run.latestAttemptNo() != lease.attemptNo()) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "stale attempt");
            }
            AttemptStatus current = currentAttemptStatus(
                    lease.tenantId(), lease.runId(), lease.attemptNo())
                    .orElseThrow();
            appendAttemptStatus(new AttemptStatus(
                    lease.tenantId(), lease.runId(), lease.attemptNo(),
                    current.statusSequence() + 1,
                    state, failureCode, occurredAt));
            appendRunRevision(failedRun);
            appendReceiptStatus(lease.tenantId(), receiptId,
                    state == AttemptState.RESULT_UNKNOWN
                            ? ReceiptStatus.RESULT_UNKNOWN : ReceiptStatus.FAILED,
                    occurredAt);
        }

        @Override
        public boolean completionExists(
                long tenantId, UUID runId, int attemptNo, CalculationOutcome outcome) {
            boolean completed = events.stream().anyMatch(event -> event.runId().equals(runId)
                    && Integer.valueOf(attemptNo).equals(event.attemptNo())
                    && event.eventType().equals("PayrollCalculated"));
            if (!completed) return false;
            List<PayrollCalculationModels.CalculatedLine> durable = outcome.lines().stream()
                    .map(expected -> lines.get(
                            attemptKey(tenantId, runId, attemptNo) + ":"
                                    + expected.sourceInputId()))
                    .toList();
            if (durable.stream().anyMatch(java.util.Objects::isNull)
                    || !durable.equals(outcome.lines())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "committed result differs from durable facts");
            }
            return true;
        }

        @Override
        public long outboxCount(long tenantId, UUID runId, String eventType) {
            return events.stream().filter(event -> event.runId().equals(runId)
                    && event.eventType().equals(eventType)).count();
        }

        @Override
        public List<PayrollCalculationStore.ClaimableAttempt> claimable(
                long tenantId, int limit, String workerInstanceId,
                Instant now, Duration leaseDuration) {
            List<RunRevision> candidates = runs.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(tenantId + ":"))
                    .map(entry -> entry.getValue().getLast())
                    .filter(run -> List.of(RunState.READY, RunState.CALCULATING)
                            .contains(run.state()))
                    .filter(run -> {
                        AttemptStatus status = currentAttemptStatus(
                                tenantId, run.runId(), run.latestAttemptNo()).orElseThrow();
                        Lease lease = leases.get(attemptKey(
                                tenantId, run.runId(), run.latestAttemptNo()));
                        return List.of(AttemptState.READY, AttemptState.CALCULATING)
                                .contains(status.state())
                                && (lease == null || !lease.expiresAt().isAfter(now));
                    })
                    .sorted(java.util.Comparator.comparing(RunRevision::recordedAt)
                            .thenComparing(RunRevision::runId))
                    .limit(limit)
                    .toList();
            return candidates.stream().map(run -> claim(
                            tenantId, run.runId(), run.latestAttemptNo(),
                            workerInstanceId, now, leaseDuration)
                            .map(PayrollCalculationStore.ClaimableAttempt::new))
                    .flatMap(Optional::stream)
                    .toList();
        }

        @Override
        public List<PayrollCalculationStore.RecoveryCandidate> recoveryCandidates(
                long tenantId, int limit) {
            return runs.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(tenantId + ":"))
                    .map(entry -> entry.getValue().getLast())
                    .filter(run -> run.state() == RunState.RESULT_UNKNOWN)
                    .sorted(java.util.Comparator.comparing(RunRevision::recordedAt)
                            .thenComparing(RunRevision::runId))
                    .limit(limit)
                    .map(run -> new PayrollCalculationStore.RecoveryCandidate(
                            tenantId, run.runId()))
                    .toList();
        }

        private void requireLease(Lease lease, Instant now) {
            Lease current = leases.get(
                    attemptKey(lease.tenantId(), lease.runId(), lease.attemptNo()));
            if (!sameLease(current, lease) || !current.expiresAt().isAfter(now)) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "stale lease");
            }
        }

        private boolean sameLease(Lease left, Lease right) {
            return left != null && right != null
                    && left.attemptId().equals(right.attemptId())
                    && left.claimNo() == right.claimNo()
                    && left.leaseToken().equals(right.leaseToken())
                    && left.workerInstanceId().equals(right.workerInstanceId());
        }

        int attemptCount(long tenantId, UUID runId) {
            return (int) attempts.values().stream().filter(attempt ->
                    attempt.tenantId() == tenantId && attempt.runId().equals(runId)).count();
        }

        int lineCount(long tenantId, UUID runId) {
            return (int) lines.keySet().stream().filter(key ->
                    key.startsWith(runKey(tenantId, runId) + ":")).count();
        }

        List<AttemptStatus> history(long tenantId, UUID runId, int attemptNo) {
            return List.copyOf(statuses.getOrDefault(
                    attemptKey(tenantId, runId, attemptNo), List.of()));
        }

        Attempt attempt(long tenantId, UUID runId, int attemptNo) {
            return attempts.get(attemptKey(tenantId, runId, attemptNo));
        }

        void failCompletion(CompletionFailure failure) {
            completionFailure = failure;
        }

        void tamperCheckpointDigest(long tenantId, UUID runId, int attemptNo) {
            String key = attemptKey(tenantId, runId, attemptNo);
            Checkpoint old = checkpoints.get(key);
            if (old == null) throw new IllegalStateException("checkpoint missing");
            checkpoints.put(key, new Checkpoint(
                    old.checkpointNo(), old.nextInputOrdinal(),
                    old.completedWorkerCount(), "f".repeat(64)));
        }

        int receiptCount() {
            return receipts.size();
        }

        int snapshotCount() {
            return snapshots.size();
        }

        int prepareCalls() {
            return prepareCalls;
        }

        int freezeCalls() {
            return freezeCalls;
        }

        int cloneCalls() {
            return cloneCalls;
        }

        int auditCount() {
            return audits.size();
        }

        int runRevisionCount(long tenantId, UUID runId) {
            return runs.getOrDefault(runKey(tenantId, runId), List.of()).size();
        }

        private String commandKey(
                long tenantId, UUID subject, String action, String idempotencyKey) {
            return tenantId + ":" + subject + ":" + action + ":" + idempotencyKey;
        }

        private String runKey(long tenantId, UUID runId) {
            return tenantId + ":" + runId;
        }

        private String attemptKey(long tenantId, UUID runId, int attemptNo) {
            return runKey(tenantId, runId) + ":" + attemptNo;
        }
    }
}
