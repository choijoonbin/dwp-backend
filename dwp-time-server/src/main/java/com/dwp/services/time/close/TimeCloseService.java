package com.dwp.services.time.close;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.ApprovalEvidence;
import com.dwp.services.time.close.TimeCloseModels.CloseAction;
import com.dwp.services.time.close.TimeCloseModels.CloseCommand;
import com.dwp.services.time.close.TimeCloseModels.CloseEvidence;
import com.dwp.services.time.close.TimeCloseModels.ClosePeriod;
import com.dwp.services.time.close.TimeCloseModels.CloseState;
import com.dwp.services.time.close.TimeCloseModels.CommandKind;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffCommandV1;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffOutcomeV1;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffStatusV1;
import com.dwp.services.time.close.TimeCloseModels.PersistedClosedResult;
import com.dwp.services.time.close.TimeCloseModels.PlannedCloseCommand;
import com.dwp.services.time.close.TimeCloseModels.StoredReceipt;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseCommandReceiptV1;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseCommandV1;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseValidationCommandV1;
import com.dwp.services.time.close.TimeCloseModels.TimeReopenCommandV1;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Application boundary for the five sealed TIM close/handoff operations. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimeCloseService implements TimeCloseReceiptOwnership {

    private final TimeCloseRepository repository;
    private final TimeCloseEvidenceSource evidenceSource;
    private final TimeCloseApprovalVerifier approvalVerifier;
    private final TimeCloseStepUpVerifier stepUpVerifier;
    private final TimeCloseActivationGate activationGate;
    private final Clock clock;

    @Autowired
    public TimeCloseService(
            TimeCloseRepository repository,
            TimeCloseEvidenceSource evidenceSource,
            TimeCloseApprovalVerifier approvalVerifier,
            TimeCloseStepUpVerifier stepUpVerifier,
            TimeCloseActivationGate activationGate) {
        this(repository, evidenceSource, approvalVerifier, stepUpVerifier,
                activationGate, Clock.systemUTC());
    }

    TimeCloseService(
            TimeCloseRepository repository,
            TimeCloseEvidenceSource evidenceSource,
            TimeCloseApprovalVerifier approvalVerifier,
            TimeCloseStepUpVerifier stepUpVerifier,
            Clock clock) {
        this(repository, evidenceSource, approvalVerifier, stepUpVerifier,
                ignored -> true, clock);
    }

    TimeCloseService(
            TimeCloseRepository repository,
            TimeCloseEvidenceSource evidenceSource,
            TimeCloseApprovalVerifier approvalVerifier,
            TimeCloseStepUpVerifier stepUpVerifier,
            TimeCloseActivationGate activationGate,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.evidenceSource = Objects.requireNonNull(evidenceSource, "evidenceSource");
        this.approvalVerifier = Objects.requireNonNull(approvalVerifier, "approvalVerifier");
        this.stepUpVerifier = Objects.requireNonNull(stepUpVerifier, "stepUpVerifier");
        this.activationGate = Objects.requireNonNull(activationGate, "activationGate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TimeCloseCommandReceiptV1 validate(
            VerifiedCloseRequest authority,
            UUID periodId,
            String idempotencyKey,
            long expectedVersion,
            TimeCloseValidationCommandV1 body) {
        return validate(authority, periodId, idempotencyKey, expectedVersion,
                body, TimeCloseDigests.canonicalJson(body));
    }

    TimeCloseCommandReceiptV1 validate(
            VerifiedCloseRequest authority,
            UUID periodId,
            String idempotencyKey,
            long expectedVersion,
            TimeCloseValidationCommandV1 body,
            String rawBody) {
        requireAuthority(authority, CloseAction.VALIDATE, CommandKind.VALIDATE.purpose());
        String digest = requestDigest(authority, periodId, expectedVersion, rawBody);
        TimeCloseCommandReceiptV1 replay = replay(
                authority, idempotencyKey, digest).orElse(null);
        if (replay != null) return replay;
        ClosePeriod period = period(authority, periodId);
        requireVersion(period, expectedVersion);
        if (period.state() != CloseState.OPEN && period.state() != CloseState.REOPENED
                && period.state() != CloseState.FAILED) {
            throw conflict("The period cannot be validated in its current state.");
        }
        CloseCommand command = new CloseCommand(
                authority, CommandKind.VALIDATE, periodId, expectedVersion,
                idempotencyKey, digest, body.asOf(), null, null, null, null);
        CloseEvidence evidence = evidenceSource.freeze(period, authority, body.asOf());
        return repository.execute(new PlannedCloseCommand(
                command, period, evidence, null, null));
    }

    public TimeCloseCommandReceiptV1 close(
            VerifiedCloseRequest authority,
            UUID periodId,
            String idempotencyKey,
            long expectedVersion,
            String stepUpChallenge,
            TimeCloseCommandV1 body) {
        return close(authority, periodId, idempotencyKey, expectedVersion,
                stepUpChallenge, body, TimeCloseDigests.canonicalJson(body));
    }

    TimeCloseCommandReceiptV1 close(
            VerifiedCloseRequest authority,
            UUID periodId,
            String idempotencyKey,
            long expectedVersion,
            String stepUpChallenge,
            TimeCloseCommandV1 body,
            String rawBody) {
        requireAuthority(authority, CloseAction.CLOSE, CommandKind.CLOSE.purpose());
        String digest = requestDigest(authority, periodId, expectedVersion, rawBody);
        TimeCloseCommandReceiptV1 replay = replay(
                authority, idempotencyKey, digest).orElse(null);
        if (replay != null) return replay;
        ClosePeriod period = period(authority, periodId);
        requireVersion(period, expectedVersion);
        if (period.state() != CloseState.OPEN && period.state() != CloseState.REOPENED) {
            throw conflict("The period cannot be closed in its current state.");
        }
        CloseCommand command = new CloseCommand(
                authority, CommandKind.CLOSE, periodId, expectedVersion,
                idempotencyKey, digest, body.asOf(), body.approvalReceiptId(),
                null, stepUpChallenge, null);
        stepUpVerifier.verify(command, period);
        ApprovalEvidence approval = approvalVerifier.verify(command, period);
        requireApproval(command, period, approval, "TIME_PERIOD_CLOSE", clock.instant());
        if (approval.approvalActorSubjectId()
                .equals(authority.subjectPrincipalPublicId())
                || (period.correctionMakerSubjectId() != null
                    && period.correctionMakerSubjectId()
                            .equals(approval.approvalActorSubjectId()))) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The close maker, correction maker and approver must be separated.");
        }
        CloseEvidence evidence = evidenceSource.freeze(period, authority, body.asOf());
        if (!evidence.blockers().isEmpty()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The period has unresolved close blockers.");
        }
        return repository.execute(new PlannedCloseCommand(
                command, period, evidence, approval, null));
    }

    public TimeCloseCommandReceiptV1 reopen(
            VerifiedCloseRequest authority,
            UUID periodId,
            String idempotencyKey,
            long expectedVersion,
            String stepUpChallenge,
            TimeReopenCommandV1 body) {
        return reopen(authority, periodId, idempotencyKey, expectedVersion,
                stepUpChallenge, body, TimeCloseDigests.canonicalJson(body));
    }

    TimeCloseCommandReceiptV1 reopen(
            VerifiedCloseRequest authority,
            UUID periodId,
            String idempotencyKey,
            long expectedVersion,
            String stepUpChallenge,
            TimeReopenCommandV1 body,
            String rawBody) {
        requireAuthority(authority, CloseAction.REOPEN, CommandKind.REOPEN.purpose());
        String digest = requestDigest(authority, periodId, expectedVersion, rawBody);
        TimeCloseCommandReceiptV1 replay = replay(
                authority, idempotencyKey, digest).orElse(null);
        if (replay != null) return replay;
        ClosePeriod period = period(authority, periodId);
        requireVersion(period, expectedVersion);
        if (period.state() != CloseState.CLOSED || period.latestResultId() == null) {
            throw conflict("Only a closed period can be reopened.");
        }
        CloseCommand command = new CloseCommand(
                authority, CommandKind.REOPEN, periodId, expectedVersion,
                idempotencyKey, digest, body.asOf(), body.approvalReceiptId(),
                body.reasonCode(), stepUpChallenge, null);
        stepUpVerifier.verify(command, period);
        ApprovalEvidence approval = approvalVerifier.verify(command, period);
        requireApproval(command, period, approval, "TIME_PERIOD_REOPEN", clock.instant());
        if (!Objects.equals(body.reasonCode(), approval.reasonCode())
                || authority.subjectPrincipalPublicId().equals(period.closedBySubjectId())
                || authority.subjectPrincipalPublicId().equals(period.closeApprovalActorId())
                || authority.subjectPrincipalPublicId()
                        .equals(approval.approvalActorSubjectId())
                || approval.approvalActorSubjectId().equals(period.closedBySubjectId())) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The reopen actor and approver violate maker-checker separation.");
        }
        return repository.execute(new PlannedCloseCommand(
                command, period, null, approval, null));
    }

    public TimeCloseCommandReceiptV1 createHandoff(
            VerifiedCloseRequest authority,
            UUID periodId,
            String idempotencyKey,
            long expectedVersion,
            PayrollTimeHandoffCommandV1 body) {
        return createHandoff(authority, periodId, idempotencyKey, expectedVersion,
                body, TimeCloseDigests.canonicalJson(body));
    }

    TimeCloseCommandReceiptV1 createHandoff(
            VerifiedCloseRequest authority,
            UUID periodId,
            String idempotencyKey,
            long expectedVersion,
            PayrollTimeHandoffCommandV1 body,
            String rawBody) {
        requireAuthority(authority, CloseAction.HANDOFF_CREATE, CommandKind.HANDOFF.purpose());
        String digest = requestDigest(authority, periodId, expectedVersion, rawBody);
        TimeCloseCommandReceiptV1 replay = replay(
                authority, idempotencyKey, digest).orElse(null);
        if (replay != null) return replay;
        ClosePeriod period = period(authority, periodId);
        requireVersion(period, expectedVersion);
        if (period.state() != CloseState.CLOSED
                || !body.closedTimeResultId().equals(period.latestResultId())) {
            throw conflict("The selected immutable result is not handoff eligible.");
        }
        PersistedClosedResult result = repository.findResult(
                        authority.tenantId(), body.closedTimeResultId())
                .filter(value -> value.result().closePeriodId().equals(period.publicId()))
                .filter(value -> value.result().closeRevision() == period.closeRevision())
                .filter(value -> value.result().payrollEntityId()
                        .equals(body.payrollEntityId()))
                .filter(value -> value.result().payPeriodId().equals(body.payPeriodId()))
                .orElseThrow(TimeCloseService::opaqueNotFound);
        CloseCommand command = new CloseCommand(
                authority, CommandKind.HANDOFF, periodId, expectedVersion,
                idempotencyKey, digest, clock.instant(), null, null, null, body);
        return repository.execute(new PlannedCloseCommand(
                command, period, null, null, result));
    }

    public PayrollTimeHandoffStatusV1 getHandoff(
            VerifiedCloseRequest authority, UUID handoffId) {
        requireAuthority(authority, CloseAction.HANDOFF_QUERY,
                CommandKind.HANDOFF.purpose());
        return repository.findHandoff(authority.tenantId(), handoffId)
                .filter(record -> {
                    ClosePeriod period = repository.findPeriod(
                            authority.tenantId(), record.projection().closePeriodId())
                            .orElse(null);
                    return period != null && withinScope(authority, period);
                })
                .map(TimeCloseModels.HandoffRecord::projection)
                .orElseThrow(TimeCloseService::opaqueNotFound);
    }

    PayrollTimeHandoffStatusV1 recordOutcome(
            long tenantId,
            UUID handoffId,
            String rawBody,
            PayrollTimeHandoffOutcomeV1 outcome) {
        Objects.requireNonNull(rawBody, "rawBody");
        String digest = TimeCloseDigests.sha256Utf8(
                TimeCloseDigests.canonicalJsonWithBindings(
                        rawBody, tenantId, handoffId.toString()));
        return repository.applyOutcome(tenantId, handoffId, digest, outcome);
    }

    @Override
    public boolean owns(long tenantId, UUID receiptId) {
        return tenantId > 0 && receiptId != null
                && activationGate.allows(tenantId)
                && repository.findReceipt(tenantId, receiptId).isPresent();
    }

    @Override
    public Optional<TimeCloseCommandReceiptV1> reauthorizedRead(
            long tenantId,
            UUID receiptId,
            TimeCloseReceiptOwnership.OriginAuthority authority) {
        if (tenantId <= 0 || receiptId == null || authority == null) {
            return Optional.empty();
        }
        if (!activationGate.allows(tenantId)) return Optional.empty();
        return repository.findReceipt(tenantId, receiptId)
                .filter(authority::permits)
                .map(StoredReceipt::receipt);
    }

    private ClosePeriod period(VerifiedCloseRequest authority, UUID periodId) {
        return repository.findPeriod(authority.tenantId(), periodId)
                .filter(value -> withinScope(authority, value))
                .orElseThrow(TimeCloseService::opaqueNotFound);
    }

    private Optional<TimeCloseCommandReceiptV1> replay(
            VerifiedCloseRequest authority,
            String idempotencyKey,
            String requestDigest) {
        return repository.findReceipt(
                        authority.tenantId(), authority.subjectPrincipalPublicId(),
                        authority.routeContractKey(), idempotencyKey)
                .map(stored -> {
                    if (!stored.receipt().requestDigest().equals(requestDigest)) {
                        throw conflict("The idempotency key is bound to another request.");
                    }
                    if (!stored.receipt().populationScopeDigest()
                            .equals(authority.populationScopeDigest())
                            || stored.populationRevision() != authority.populationRevision()
                            || stored.grantRevision() != authority.grantRevision()
                            || stored.receipt().fieldPolicyRevision()
                                != authority.fieldPolicyRevision()
                            || stored.receipt().authorizationRevision()
                                != authority.authorizationRevision()) {
                        throw new BaseException(
                                ErrorCode.FORBIDDEN,
                                "The original command authority is no longer current.");
                    }
                    return stored.receipt();
                });
    }

    private boolean withinScope(VerifiedCloseRequest authority, ClosePeriod period) {
        return period.tenantId() == authority.tenantId()
                && period.legalEntityPublicId().equals(authority.legalEntityPublicId())
                && period.timeGroupPublicId().equals(authority.timeGroupPublicId());
    }

    private void requireAuthority(
            VerifiedCloseRequest authority, CloseAction action, String purpose) {
        if (authority == null || authority.action() != action
                || !purpose.equals(authority.purposeCode())
                || !authority.revalidateAt().isAfter(clock.instant())
                || !authority.maskedFieldGroups().isEmpty()
                || !authority.omittedFieldGroups().isEmpty()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Current exact TIM close authority is required.");
        }
    }

    private static void requireVersion(ClosePeriod period, long expectedVersion) {
        if (expectedVersion <= 0) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        if (period.version() != expectedVersion) {
            throw new TimeCloseBoundaryFailure(
                    412, "PRECONDITION_FAILED", false, period.version(),
                    "The close period aggregate version changed.");
        }
    }

    private static void requireApproval(
            CloseCommand command,
            ClosePeriod period,
            ApprovalEvidence approval,
            String exactAction,
            Instant processingTime) {
        if (approval == null
                || !command.approvalReceiptId().equals(approval.approvalReceiptId())
                || !exactAction.equals(approval.action())
                || !("time-period:" + period.publicId()).equals(approval.subjectRef())
                || approval.subjectRevision() != command.expectedVersion()
                || !command.requestDigest().equals(approval.requestDigest())
                || !command.authority().decisionRevision()
                        .equals(approval.decisionRevision())
                || ("TIME_PERIOD_CLOSE".equals(exactAction)
                    && approval.reasonCode() != null)
                || !approval.expiresAt().isAfter(processingTime)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The close approval evidence is stale or mismatched.");
        }
    }

    private static String requestDigest(
            VerifiedCloseRequest authority,
            UUID periodId,
            long expectedVersion,
            String rawBody) {
        return TimeCloseDigests.timeCloseRequestDigest(
                rawBody, periodId.toString(), expectedVersion,
                authority.purposeCode(), authority.decisionRevision());
    }

    private static BaseException opaqueNotFound() {
        return new BaseException(
                ErrorCode.NOT_FOUND,
                "The requested TIM close resource was not found.");
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
