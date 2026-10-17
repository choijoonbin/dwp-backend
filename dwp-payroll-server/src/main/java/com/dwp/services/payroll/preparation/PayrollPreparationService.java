package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollPreparationService {

    private final PayrollPreparationStore store;
    private final PayrollPreparationCanonical canonical;
    private final PayrollPreparationStepUpGuard stepUp;
    private final Clock clock;

    @Autowired
    PayrollPreparationService(
            PayrollPreparationStore store,
            PayrollPreparationCanonical canonical,
            PayrollPreparationStepUpGuard stepUp) {
        this(store, canonical, stepUp, Clock.systemUTC());
    }

    PayrollPreparationService(
            PayrollPreparationStore store,
            PayrollPreparationCanonical canonical,
            PayrollPreparationStepUpGuard stepUp,
            Clock clock) {
        this.store = store;
        this.canonical = canonical;
        this.stepUp = stepUp;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    PayrollPreparationModels.EntryQueryResponse population(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.EntryQuery query) {
        actor.requireRead();
        if (query.asOf() != null && query.asOf().isAfter(clock.instant())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Payroll population asOf cannot be in the future.");
        }
        return store.population(actor, query);
    }

    @Transactional
    PayrollPreparationModels.PayrollCommandReceipt prepare(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.PrepareCommand command,
            PayrollPreparationStepUpGuard.Headers headers,
            UUID correlationId) {
        actor.requirePrepare();
        requireSelection(actor, command.selection(actor.subject().populationKind()));
        Instant now = clock.instant();
        if (command.asOf().isAfter(now)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Payroll preparation asOf cannot be in the future.");
        }
        String idempotencyKey = idempotencyKey(headers.idempotencyKey());
        String requestDigest = canonical.requestDigest(command);

        store.lockPreparation(actor.subject().tenantId(), command.preparationId());
        Optional<PayrollPreparationModels.StoredCommand> replay = store.command(
                actor.subject().tenantId(),
                actor.subject().subjectPrincipalPublicId(), idempotencyKey);
        if (replay.isPresent()) {
            requireReplayIdentity(replay.get(), command, requestDigest, actor);
            PayrollStepUpEvidence replayEvidence =
                    stepUp.require(actor, command, headers);
            consumeStepUp(
                    actor, command, idempotencyKey, requestDigest,
                    replayEvidence, "IDEMPOTENT_REPLAY", now);
            return replay.get().response();
        }

        PayrollStepUpEvidence evidence = stepUp.require(actor, command, headers);
        consumeStepUp(
                actor, command, idempotencyKey, requestDigest,
                evidence, "PREPARE_COMMIT", now);
        PayrollPreparationModels.PreparationCandidate candidate =
                store.candidate(actor, command);
        if (candidate == null) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Authoritative Payroll preparation facts are unavailable.");
        }
        requireExactCandidate(actor, command, candidate);

        UUID runId = canonical.deterministicUuid(
                "payroll-run-from-preparation-v1", Map.of(
                        "tenantId", actor.subject().tenantId(),
                        "preparationId", command.preparationId()));
        UUID snapshotId = canonical.deterministicUuid(
                "payroll-input-snapshot-v1", Map.of(
                        "tenantId", actor.subject().tenantId(),
                        "runId", runId,
                        "attemptNo", 1,
                        "sourceSnapshotId", candidate.material().sourceSnapshotId(),
                        "sourceSnapshotRevision",
                        candidate.material().sourceSnapshotRevision(),
                        "sourcePayloadDigest",
                        candidate.material().sourcePayloadDigest()));
        String snapshotDigest = canonical.digest(Map.of(
                "schema", "PayrollRunPreparationSnapshot.v1",
                "tenantId", actor.subject().tenantId(),
                "preparationId", command.preparationId(),
                "runId", runId,
                "selection", command.selection(actor.subject().populationKind()),
                "populationRevision", candidate.population().populationRevision(),
                "targetPopulationDigest", command.inputSnapshotDigest(),
                "sources", candidate.population().sources(),
                "preflight", candidate.population().preflight(),
                "material", candidate.material()));
        UUID receiptId = canonical.deterministicUuid(
                "payroll-run-prepare-receipt-v1", Map.of(
                        "tenantId", actor.subject().tenantId(),
                        "subject", actor.subject().subjectPrincipalPublicId(),
                        "idempotencyKey", idempotencyKey));
        UUID eventId = canonical.deterministicUuid(
                "payroll-run-prepared-event-v1", Map.of(
                        "tenantId", actor.subject().tenantId(),
                        "preparationId", command.preparationId(),
                        "snapshotDigest", snapshotDigest));
        UUID correlation = correlationId == null
                ? canonical.deterministicUuid(
                "payroll-run-prepare-correlation-v1", Map.of(
                        "tenantId", actor.subject().tenantId(),
                        "preparationId", command.preparationId()))
                : correlationId;
        PayrollPreparationModels.PayrollCommandReceipt response =
                new PayrollPreparationModels.PayrollCommandReceipt(
                        receiptId, "RUN_PREPARE", "PAY_RUN_PREPARE",
                        actor.subject().subjectPrincipalPublicId(),
                        actor.scope().scopeDigest(),
                        actor.subject().fieldPolicyRevision(),
                        "PAYROLL_PROCESSING",
                        actor.subject().authorizationRevision(),
                        PayrollPreparationModels.ReceiptStatus.SUCCEEDED,
                        correlation, requestDigest, now, 2L,
                        "payroll-run:" + runId);
        store.commit(new PayrollPreparationModels.PreparationCommit(
                actor, command, candidate, idempotencyKey, requestDigest,
                runId, snapshotId, snapshotDigest, receiptId, eventId,
                correlation, evidence, now, response));
        return response;
    }

    private void requireReplayIdentity(
            PayrollPreparationModels.StoredCommand stored,
            PayrollPreparationModels.PrepareCommand command,
            String requestDigest,
            PayrollPreparationAuthority.Actor actor) {
        if (stored.tenantId() != actor.subject().tenantId()
                || !stored.subjectPrincipalPublicId().equals(
                actor.subject().subjectPrincipalPublicId())
                || !stored.requestDigest().equals(requestDigest)
                || !stored.preparationId().equals(command.preparationId())) {
            throw conflict("Idempotency-Key was reused with a different prepare body.");
        }
    }

    private void requireExactCandidate(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.PrepareCommand command,
            PayrollPreparationModels.PreparationCandidate candidate) {
        PayrollPreparationModels.PopulationResponse population = candidate.population();
        if (!population.selection().equals(
                command.selection(actor.subject().populationKind()))
                || !population.targetPopulationDigest().equals(
                command.inputSnapshotDigest())
                || !population.scopeRevision().equals(
                actor.scope().projectionRevision())
                || !population.scopeDigest().equals(actor.scope().scopeDigest())
                || population.preflight().status()
                != PayrollPreparationModels.PreflightStatus.PASS
                || population.sources().stream().anyMatch(source ->
                source.status() != PayrollPreparationModels.SourceStatus.CURRENT)) {
            throw conflict("Payroll population or preflight evidence changed.");
        }
    }

    private void requireSelection(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.PopulationSelection selection) {
        if (!selection.populationKind().equals(actor.subject().populationKind())
                || !actor.contains(
                selection.legalEntityId(), selection.payrollGroupId())) {
            throw new BaseException(ErrorCode.NOT_FOUND,
                    "The payroll resource was not found.");
        }
    }

    private String idempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() < 16
                || value.length() > 128
                || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Idempotency-Key is invalid.");
        }
        return value;
    }

    private void consumeStepUp(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.PrepareCommand command,
            String idempotencyKey,
            String requestDigest,
            PayrollStepUpEvidence evidence,
            String useKind,
            Instant usedAt) {
        store.consumeStepUp(
                actor.subject().tenantId(),
                command.preparationId(),
                actor.subject().subjectPrincipalPublicId(),
                idempotencyKey, requestDigest, evidence, useKind, usedAt);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
