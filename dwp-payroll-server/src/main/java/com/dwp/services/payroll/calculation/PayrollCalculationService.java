package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollCalculationAuthority;
import com.dwp.services.payroll.security.PayrollHighRiskCommandGuard;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import com.dwp.services.payroll.security.PayrollStepUpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Attempt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CommandIdentity;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CommandType;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.EventFact;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PayrollCommandReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PersistedReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ReceiptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunResponse;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunRevision;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunType;

@Service
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollCalculationService {

    private static final BigDecimal ZERO = new BigDecimal("0.0000");
    private static final Set<RunState> CALCULABLE = Set.of(
            RunState.READY, RunState.FAILED);

    private final PayrollCalculationStore store;
    private final PayrollCalculationCanonical canonical;
    private final PayrollHighRiskCommandGuard highRisk;
    private final PayrollCalculationSemanticBinder semanticBinder;
    private final Clock clock;

    @Autowired
    PayrollCalculationService(
            PayrollCalculationStore store,
            PayrollCalculationCanonical canonical,
            PayrollHighRiskCommandGuard highRisk,
            PayrollCalculationSemanticBinder semanticBinder) {
        this(store, canonical, highRisk, semanticBinder, Clock.systemUTC());
    }

    PayrollCalculationService(
            PayrollCalculationStore store,
            PayrollCalculationCanonical canonical,
            PayrollHighRiskCommandGuard highRisk,
            PayrollCalculationSemanticBinder semanticBinder,
            Clock clock) {
        this.store = store;
        this.canonical = canonical;
        this.highRisk = highRisk;
        this.semanticBinder = semanticBinder;
        this.clock = clock;
    }

    @Transactional
    PayrollCommandReceipt calculate(
            PayrollCalculationAuthority.Actor actor,
            UUID runId,
            String idempotencyKey,
            long expectedVersion,
            UUID correlationId,
            ActionCommand command) {
        return calculate(
                actor, runId, runId.toString(), idempotencyKey, expectedVersion,
                correlationId, command, null);
    }

    @Transactional
    PayrollCommandReceipt calculate(
            PayrollCalculationAuthority.Actor actor,
            UUID runId,
            String runIdText,
            String idempotencyKey,
            long expectedVersion,
            UUID correlationId,
            ActionCommand command,
            PayrollStepUpHeaders headers) {
        actor.require(PayrollCalculationAuthority.Action.CALCULATE);
        String key = idempotencyKey(idempotencyKey);
        String requestDigest = canonical.requestDigest(
                CommandType.RUN_CALCULATE, runId, expectedVersion, command);
        CommandIdentity identity = new CommandIdentity(
                actor.tenantId(), actor.subjectPrincipalPublicId(),
                CommandType.RUN_CALCULATE, key, requestDigest);
        store.lockRun(actor.tenantId(), runId);
        Optional<PersistedReceipt> replay = store.command(identity);
        if (replay.isPresent()) return replay(replay.get(), runId, requestDigest, actor);

        RunRevision current = store.currentRun(actor.tenantId(), runId)
                .orElseThrow(this::notFound);
        requireVisible(actor, current);
        requireRegular(current);
        if (current.revision() != expectedVersion) throw versionConflict();
        if (!CALCULABLE.contains(current.state())
                || (current.state() == RunState.READY
                && current.latestAttemptNo() != 0)) {
            if (current.state() == RunState.RESULT_UNKNOWN) {
                throw conflict("RESULT_UNKNOWN requires explicit reconciliation.");
            }
            throw conflict("Payroll run is not ready for calculation.");
        }
        UUID lexicalRunId;
        try {
            lexicalRunId = UUID.fromString(runIdText);
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE, "runId is not a UUID.");
        }
        if (!runId.equals(lexicalRunId)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "runId path text does not identify the requested run.");
        }
        PayrollStepUpEvidence stepUpEvidence = highRisk.require(
                actor, runId, expectedVersion,
                "/api/payroll/v1/runs/" + runIdText + "/attempts",
                command, headers);

        int attemptNo = current.latestAttemptNo() + 1;
        Instant now = clock.instant();
        PayrollCalculationModels.FrozenSnapshot snapshot =
                current.state() == RunState.READY
                        ? store.snapshot(current.tenantId(), current.runId(), attemptNo)
                        : store.cloneSnapshot(
                        current.tenantId(), current.runId(),
                        current.latestAttemptNo(), attemptNo);
        PayrollCalculationStore.Preparation preparation =
                new PayrollCalculationStore.Preparation(
                        current.payPeriodId(), current.legalEntityId(),
                        current.payrollGroupId(), current.currency(), snapshot);
        if (!current.legalEntityId().equals(preparation.legalEntityId())
                || !current.payrollGroupId().equals(preparation.payrollGroupId())
                || !current.populationScopeDigest().equals(actor.scope().scopeDigest())
                || snapshot.attemptNo() != attemptNo
                || !current.sourceSnapshotId().equals(snapshot.sourceSnapshotId())
                || current.sourceSnapshotRevision() != snapshot.sourceSnapshotRevision()
                || !current.sourcePayloadDigest().equals(snapshot.sourcePayloadDigest())) {
            throw notFound();
        }
        // Recheck the prepared semantics before accepting any calculation facts.
        for (PayrollCalculationModels.SourceInput input : snapshot.inputs()) {
            semanticBinder.evaluate(snapshot, input);
        }
        long runRevision = current.revision() + 1;
        UUID receiptId = UUID.randomUUID();
        UUID correlation = correlationId == null ? UUID.randomUUID() : correlationId;
        PayrollCommandReceipt publicReceipt = receipt(
                actor, receiptId, CommandType.RUN_CALCULATE, requestDigest,
                ReceiptStatus.ACCEPTED, correlation, runRevision,
                "payroll-run:" + runId, now);
        PersistedReceipt persisted = persisted(actor, runId, key,
                CommandType.RUN_CALCULATE, publicReceipt, stepUpEvidence);

        // AC-01: the V50 copy and all source/formula digests are durable before
        // the attempt identity is allowed by its reverse foreign key.
        store.insertReceipt(persisted);
        store.appendReceiptStatus(
                actor.tenantId(), receiptId, ReceiptStatus.ACCEPTED, now);
        store.appendAttempt(new Attempt(
                actor.tenantId(), runId, attemptNo, UUID.randomUUID(),
                current.latestAttemptNo() == 0 ? null : current.latestAttemptNo(),
                PayrollCalculationModels.ENGINE_VERSION, receiptId, now));
        store.appendAttemptStatus(new AttemptStatus(
                actor.tenantId(), runId, attemptNo, 1,
                AttemptState.READY, null, now));
        store.appendRunRevision(new RunRevision(
                actor.tenantId(), runId, runRevision, current.runType(),
                preparation.payPeriodId(),
                current.sourceSnapshotId(), current.sourceSnapshotRevision(),
                current.sourcePayloadDigest(), preparation.legalEntityId(),
                preparation.payrollGroupId(), actor.subject().populationKind(),
                actor.scope().scopeDigest(), RunState.READY,
                current.makerActorId(), current.makerSubjectPublicId(),
                attemptNo, 0, ZERO, ZERO, preparation.currency(), null,
                null, null, null, null, null, receiptId, now));
        store.appendAudit(persisted, runRevision,
                "PAYROLL_CALCULATION_REQUESTED",
                canonical.json(Map.of(
                        "attemptNo", attemptNo,
                        "reasonCode", command.reasonCode(),
                        "sourceSnapshotId", preparation.snapshot().sourceSnapshotId(),
                        "sourceSnapshotRevision",
                        preparation.snapshot().sourceSnapshotRevision(),
                        "sourcePayloadDigest", preparation.snapshot().sourcePayloadDigest(),
                        "formulaSetDigest", preparation.snapshot().formulaSetDigest(),
                        "targetPopulationDigest",
                        preparation.snapshot().targetPopulationDigest())), now);
        return publicReceipt;
    }

    @Transactional(readOnly = true)
    RunResponse run(PayrollCalculationAuthority.Actor actor, UUID runId) {
        actor.require(PayrollCalculationAuthority.Action.RUN_VIEW);
        store.bindTenant(actor.tenantId());
        RunRevision run = store.currentRun(actor.tenantId(), runId).orElseThrow(this::notFound);
        requireVisible(actor, run);
        return run.publicView();
    }

    @Transactional
    PayrollCommandReceipt approve(
            PayrollCalculationAuthority.Actor actor,
            UUID runId,
            String idempotencyKey,
            long expectedVersion,
            UUID correlationId,
            ActionCommand command,
            PayrollStepUpHeaders headers) {
        return approve(
                actor, runId, runId.toString(), idempotencyKey, expectedVersion,
                correlationId, command, headers);
    }

    @Transactional
    PayrollCommandReceipt approve(
            PayrollCalculationAuthority.Actor actor,
            UUID runId,
            String runIdText,
            String idempotencyKey,
            long expectedVersion,
            UUID correlationId,
            ActionCommand command,
            PayrollStepUpHeaders headers) {
        actor.require(PayrollCalculationAuthority.Action.APPROVE);
        return highRiskTransition(
                actor, runId, runIdText, idempotencyKey, expectedVersion, correlationId,
                command, headers, CommandType.RUN_APPROVE);
    }

    @Transactional
    PayrollCommandReceipt finalizeRun(
            PayrollCalculationAuthority.Actor actor,
            UUID runId,
            String idempotencyKey,
            long expectedVersion,
            UUID correlationId,
            ActionCommand command,
            PayrollStepUpHeaders headers) {
        return finalizeRun(
                actor, runId, runId.toString(), idempotencyKey, expectedVersion,
                correlationId, command, headers);
    }

    @Transactional
    PayrollCommandReceipt finalizeRun(
            PayrollCalculationAuthority.Actor actor,
            UUID runId,
            String runIdText,
            String idempotencyKey,
            long expectedVersion,
            UUID correlationId,
            ActionCommand command,
            PayrollStepUpHeaders headers) {
        actor.require(PayrollCalculationAuthority.Action.FINALIZE);
        return highRiskTransition(
                actor, runId, runIdText, idempotencyKey, expectedVersion, correlationId,
                command, headers, CommandType.RUN_FINALIZE);
    }

    private PayrollCommandReceipt highRiskTransition(
            PayrollCalculationAuthority.Actor actor,
            UUID runId,
            String runIdText,
            String idempotencyKey,
            long expectedVersion,
            UUID correlationId,
            ActionCommand command,
            PayrollStepUpHeaders headers,
            CommandType type) {
        String key = idempotencyKey(idempotencyKey);
        String requestDigest = canonical.requestDigest(type, runId, expectedVersion, command);
        CommandIdentity identity = new CommandIdentity(
                actor.tenantId(), actor.subjectPrincipalPublicId(), type, key, requestDigest);
        store.lockRun(actor.tenantId(), runId);
        Optional<PersistedReceipt> replay = store.command(identity);
        if (replay.isPresent()) return replay(replay.get(), runId, requestDigest, actor);
        RunRevision current = store.currentRun(actor.tenantId(), runId)
                .orElseThrow(this::notFound);
        requireVisible(actor, current);
        requireRegular(current);
        if (current.revision() != expectedVersion) throw versionConflict();
        if (actor.actorId() == current.makerActorId()) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "Payroll run maker cannot approve or finalize the same run.");
        }
        if (type == CommandType.RUN_APPROVE && current.state() != RunState.VALIDATED) {
            throw conflict("Only a validated payroll run can be approved.");
        }
        if (type == CommandType.RUN_FINALIZE) {
            if (current.state() != RunState.APPROVED) {
                throw conflict("Only an approved payroll run can be finalized.");
            }
        }
        UUID lexicalRunId;
        try {
            lexicalRunId = UUID.fromString(runIdText);
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE, "runId is not a UUID.");
        }
        if (!runId.equals(lexicalRunId)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "runId path text does not identify the requested run.");
        }
        String path = "/api/payroll/v1/runs/" + runIdText
                + (type == CommandType.RUN_APPROVE ? "/approvals" : "/finalize");
        PayrollStepUpEvidence stepUpEvidence = highRisk.require(
                actor, runId, expectedVersion, path, command, headers);

        Instant now = clock.instant();
        long nextRevision = current.revision() + 1;
        UUID correlation = correlationId == null ? UUID.randomUUID() : correlationId;
        UUID receiptId = UUID.randomUUID();
        PayrollCommandReceipt publicReceipt = receipt(
                actor, receiptId, type, requestDigest, ReceiptStatus.SUCCEEDED,
                correlation, nextRevision, "payroll-run:" + runId, now);
        PersistedReceipt persisted = persisted(
                actor, runId, key, type, publicReceipt, stepUpEvidence);
        RunRevision next;
        EventFact event;
        String auditType;
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("reasonCode", command.reasonCode());
        audit.put("requestDigest", requestDigest);
        if (type == CommandType.RUN_APPROVE) {
            UUID approvalCaseId = command.approvalReceiptId() == null
                    ? canonical.deterministicUuid("payroll-approval-case-v1", Map.of(
                    "runId", runId, "runRevision", nextRevision,
                    "receiptId", receiptId))
                    : UUID.fromString(command.approvalReceiptId());
            String decisionDigest = canonical.digest(Map.of(
                    "schema", "PayrollRunApprovalDecision.v1",
                    "runId", runId, "sourceRevision", current.revision(),
                    "resultDigest", current.resultDigest(),
                    "approvalCaseId", approvalCaseId,
                    "approverSubject", actor.subjectPrincipalPublicId(),
                    "reasonCode", command.reasonCode()));
            next = copy(current, nextRevision, RunState.APPROVED, receiptId, now,
                    approvalCaseId, decisionDigest, actor.actorId(), now,
                    current.auditBundleDigest());
            event = event(actor.tenantId(), "PayrollRunApproved", runId, null,
                    nextRevision, receiptId, correlation, Map.of(
                            "runId", runId,
                            "runRevision", nextRevision,
                            "approvalCaseId", approvalCaseId,
                            "decisionDigest", decisionDigest), now);
            auditType = "PAYROLL_RUN_APPROVED";
            audit.put("approvalCaseId", approvalCaseId);
            audit.put("decisionDigest", decisionDigest);
        } else {
            String bundleDigest = canonical.digest(Map.of(
                    "schema", "PayrollFinalizationAuditBundle.v1",
                    "runId", runId,
                    "sourceRevision", current.revision(),
                    "resultDigest", current.resultDigest(),
                    "decisionDigest", current.decisionDigest(),
                    "finalizerSubject", actor.subjectPrincipalPublicId(),
                    "reasonCode", command.reasonCode()));
            next = copy(current, nextRevision, RunState.FINALIZED, receiptId, now,
                    current.approvalCaseId(), current.decisionDigest(),
                    current.approvedByActorId(), current.approvedAt(), bundleDigest);
            event = event(actor.tenantId(), "PayrollFinalized", runId, null,
                    nextRevision, receiptId, correlation, Map.of(
                            "runId", runId,
                            "runRevision", nextRevision,
                            "resultDigest", current.resultDigest(),
                            "auditBundleDigest", bundleDigest), now);
            auditType = "PAYROLL_RUN_FINALIZED";
            audit.put("auditBundleDigest", bundleDigest);
        }
        store.insertReceipt(persisted);
        store.appendReceiptStatus(
                actor.tenantId(), receiptId, ReceiptStatus.SUCCEEDED, now);
        store.appendRunRevision(next);
        store.appendAudit(persisted, nextRevision, auditType, canonical.json(audit), now);
        store.appendEvent(actor.tenantId(), event);
        return publicReceipt;
    }

    @Transactional(readOnly = true)
    PayrollCommandReceipt receipt(
            PayrollCalculationAuthority.Actor actor,
            UUID receiptId) {
        actor.require(PayrollCalculationAuthority.Action.RECEIPT_VIEW);
        store.bindTenant(actor.tenantId());
        PersistedReceipt persisted = store.receipt(actor.tenantId(), receiptId)
                .orElseThrow(this::notFound);
        RunRevision run = store.currentRun(actor.tenantId(), persisted.runId())
                .orElseThrow(this::notFound);
        if (!persisted.receipt().subjectPrincipalPublicId().equals(
                actor.subjectPrincipalPublicId())
                || !"LEGAL_ENTITY".equals(persisted.populationKind())
                || !"COMMAND_STATUS".equals(actor.subject().purposeCode())
                || actor.subject().fieldPolicyRevision()
                < persisted.receipt().fieldPolicyRevision()
                || actor.subject().authorizationRevision()
                < persisted.receipt().authorizationRevision()
                || !persisted.contextScopeKey().equals(
                actor.subject().contextScopeKey())
                || !persisted.receipt().populationScopeDigest().equals(
                actor.scope().scopeDigest())
                || !actor.contains(run.legalEntityId(), run.payrollGroupId())) {
            throw notFound();
        }
        return persisted.receipt();
    }

    private PayrollCommandReceipt replay(
            PersistedReceipt persisted,
            UUID runId,
            String requestDigest,
            PayrollCalculationAuthority.Actor actor) {
        if (!persisted.runId().equals(runId)
                || !persisted.receipt().requestDigest().equals(requestDigest)
                || !persisted.receipt().populationScopeDigest().equals(
                actor.scope().scopeDigest())) {
            throw conflict("Idempotency-Key was reused with a different Payroll command.");
        }
        return persisted.receipt();
    }

    private void requireVisible(
            PayrollCalculationAuthority.Actor actor, RunRevision run) {
        if (!actor.contains(run.legalEntityId(), run.payrollGroupId())
                || !run.populationScopeDigest().equals(actor.scope().scopeDigest())) {
            throw notFound();
        }
    }

    private void requireRegular(RunRevision run) {
        if (run.runType() != RunType.REGULAR) {
            throw conflict("This PAY-011 slice processes REGULAR payroll runs only.");
        }
    }

    private PayrollCommandReceipt receipt(
            PayrollCalculationAuthority.Actor actor,
            UUID receiptId,
            CommandType type,
            String requestDigest,
            ReceiptStatus status,
            UUID correlationId,
            long aggregateVersion,
            String resultRef,
            Instant now) {
        return new PayrollCommandReceipt(
                receiptId, type.name(), type.originatingAction(),
                actor.subjectPrincipalPublicId(), actor.scope().scopeDigest(),
                actor.subject().fieldPolicyRevision(), actor.subject().purposeCode(),
                actor.subject().authorizationRevision(), status, correlationId,
                requestDigest, now, aggregateVersion, resultRef);
    }

    private PersistedReceipt persisted(
            PayrollCalculationAuthority.Actor actor,
            UUID runId,
            String idempotencyKey,
            CommandType type,
            PayrollCommandReceipt receipt,
            PayrollStepUpEvidence stepUpEvidence) {
        return new PersistedReceipt(
                actor.tenantId(), actor.actorId(), runId,
                actor.subject().populationKind(), actor.subject().contextScopeKey(),
                idempotencyKey, type, receipt, stepUpEvidence);
    }

    private EventFact event(
            long tenantId,
            String type,
            UUID runId,
            Integer attemptNo,
            long runRevision,
            UUID receiptId,
            UUID correlationId,
            Map<String, ?> data,
            Instant now) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specVersion", "1.0");
        envelope.put("id", eventId);
        envelope.put("source", "urn:dwp:payroll:calculation");
        envelope.put("type", type + ".v1");
        envelope.put("schemaVersion", 1);
        envelope.put("time", now.toString());
        envelope.put("tenantId", tenantId);
        envelope.put("aggregateType", "PAYROLL_RUN");
        envelope.put("aggregateId", runId);
        envelope.put("aggregateSequence", runRevision);
        envelope.put("correlationId", correlationId);
        envelope.put("data", data);
        return new EventFact(
                eventId, type, 1, runId, attemptNo, runRevision,
                receiptId, runRevision, correlationId, canonical.json(envelope), now);
    }

    private RunRevision copy(
            RunRevision source,
            long revision,
            RunState state,
            UUID receiptId,
            Instant recordedAt,
            UUID approvalCaseId,
            String decisionDigest,
            Long approvedByActorId,
            Instant approvedAt,
            String auditBundleDigest) {
        return new RunRevision(
                source.tenantId(), source.runId(), revision, source.runType(),
                source.payPeriodId(), source.sourceSnapshotId(),
                source.sourceSnapshotRevision(), source.sourcePayloadDigest(),
                source.legalEntityId(), source.payrollGroupId(),
                source.populationKind(), source.populationScopeDigest(), state,
                source.makerActorId(), source.makerSubjectPublicId(),
                source.latestAttemptNo(), source.workerCount(), source.gross(),
                source.net(), source.currency(), source.resultDigest(),
                approvalCaseId, decisionDigest, approvedByActorId, approvedAt,
                auditBundleDigest, receiptId, recordedAt);
    }

    private String idempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200
                || !value.equals(value.strip()) || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf(',') >= 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Idempotency-Key is invalid.");
        }
        return value;
    }

    private BaseException versionConflict() {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                "The payroll run version changed.");
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND,
                "The payroll resource was not found.");
    }
}
