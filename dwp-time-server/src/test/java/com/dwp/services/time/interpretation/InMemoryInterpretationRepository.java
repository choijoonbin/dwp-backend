package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.InterpretCommand;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAppend;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationCommandReceipt;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationInputs;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationQuery;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationRun;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class InMemoryInterpretationRepository implements InterpretationRepository {

    private InterpretationInputs inputs;
    private final List<InterpretationRun> runs = new ArrayList<>();
    private final Map<ReceiptKey, InterpretationCommandReceipt> receipts = new LinkedHashMap<>();

    InMemoryInterpretationRepository(InterpretationInputs inputs) {
        this.inputs = inputs;
    }

    synchronized void inputs(InterpretationInputs replacement) {
        inputs = replacement;
    }

    synchronized List<InterpretationRun> runs() {
        return List.copyOf(runs);
    }

    @Override
    public synchronized Optional<InterpretationCommandReceipt> findReceipt(
            long tenantId, UUID subjectPrincipalPublicId, UUID idempotencyKey) {
        return Optional.ofNullable(receipts.get(
                new ReceiptKey(tenantId, subjectPrincipalPublicId, idempotencyKey)));
    }

    @Override
    public synchronized InterpretationInputs loadInputs(long tenantId, InterpretCommand command) {
        if (tenantId != inputs.ruleVersion().tenantId()) {
            throw new AssertionError("tenant escaped repository boundary");
        }
        return inputs;
    }

    @Override
    public synchronized Optional<InterpretationRun> latestSucceeded(
            long tenantId, List<UUID> workerIds, InterpretCommand command) {
        return runs.reversed().stream()
                .filter(InterpretationRun::ledgerDeltaApplied)
                .filter(run -> run.tenantId() == tenantId)
                .filter(run -> run.periodStart().equals(command.periodStart()))
                .filter(run -> run.periodEnd().equals(command.periodEnd()))
                .filter(run -> run.results().stream()
                        .map(InterpretationModels.WorkerMinuteResult::workerPublicId)
                        .sorted().toList().equals(workerIds.stream().sorted().toList()))
                .findFirst();
    }

    @Override
    public synchronized InterpretationCommandReceipt append(InterpretationAppend append) {
        ReceiptKey key = new ReceiptKey(
                append.authority().tenantId(),
                append.authority().subjectPrincipalPublicId(),
                append.idempotencyKey());
        InterpretationCommandReceipt existing = receipts.get(key);
        if (existing != null) {
            if (!existing.requestDigest().equals(append.requestDigest())) {
                throw new IllegalStateException("idempotency conflict");
            }
            return replayed(existing);
        }
        InterpretationRun candidate = append.run();
        List<UUID> workers = candidate.results().stream()
                .map(InterpretationModels.WorkerMinuteResult::workerPublicId).toList();
        InterpretationRun run = InterpretationRunReconciler.reconcile(
                candidate,
                latestSucceeded(
                        candidate.tenantId(),
                        workers,
                        new InterpretCommand(
                                workers,
                                candidate.periodStart(),
                                candidate.periodEnd(),
                                candidate.asOf()))
                        .orElse(null));
        runs.add(run);
        InterpretationCommandReceipt receipt = new InterpretationCommandReceipt(
                InterpretationDigests.stableUuid("receipt", append.idempotencyKey()),
                InterpretationService.RECEIPT_COMMAND_TYPE,
                InterpretationService.RECEIPT_ACTION,
                append.authority().subjectPrincipalPublicId(),
                append.authority().populationScopeDigest(),
                append.authority().fieldPolicyRevision(),
                append.authority().purposeCode(),
                append.authority().authorizationRevision(),
                "SUCCEEDED",
                append.authority().correlationId(),
                append.requestDigest(),
                run.createdAt(),
                (long) run.attemptNo(),
                "/api/time/v1/interpretation-runs/" + run.runPublicId() + "/trace",
                run.runPublicId(),
                run.outputDigest(),
                false);
        receipts.put(key, receipt);
        return receipt;
    }

    @Override
    public synchronized Optional<InterpretationRun> findRun(long tenantId, UUID runPublicId) {
        return runs.stream()
                .filter(run -> run.tenantId() == tenantId && run.runPublicId().equals(runPublicId))
                .findFirst();
    }

    @Override
    public List<InterpretationSummary> query(InterpretationQuery query) {
        return List.of();
    }

    private static InterpretationCommandReceipt replayed(InterpretationCommandReceipt receipt) {
        return new InterpretationCommandReceipt(
                receipt.receiptId(), receipt.commandType(), receipt.originatingAction(),
                receipt.subjectPrincipalPublicId(), receipt.populationScopeDigest(),
                receipt.fieldPolicyRevision(), receipt.purposeCode(),
                receipt.authorizationRevision(), receipt.status(), receipt.correlationId(),
                receipt.requestDigest(), receipt.createdAt(), receipt.aggregateVersion(),
                receipt.resultRef(), receipt.runPublicId(), receipt.outputDigest(), true);
    }

    private record ReceiptKey(long tenantId, UUID subjectId, UUID idempotencyKey) {}
}
