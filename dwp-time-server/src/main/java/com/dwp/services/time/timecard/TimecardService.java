package com.dwp.services.time.timecard;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.timecard.TimecardModels.CommandKind;
import com.dwp.services.time.timecard.TimecardModels.PopulationKind;
import com.dwp.services.time.timecard.TimecardModels.StoredTimecardReceipt;
import com.dwp.services.time.timecard.TimecardModels.TimeCommandReceiptV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardAction;
import com.dwp.services.time.timecard.TimecardModels.TimecardCommand;
import com.dwp.services.time.timecard.TimecardModels.TimecardCorrectionCommandV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardCorrectionEntryV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardDecisionCommandV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardRecord;
import com.dwp.services.time.timecard.TimecardModels.TimecardSubmitCommandV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardV1;
import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Application boundary for the five sealed Timecard operations. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.time.timecard.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimecardService {

    static final String TIMECARD_PURPOSE = "TIME_TIMECARD";
    static final String RECEIPT_PURPOSE = "COMMAND_STATUS";

    private final TimecardRepository repository;
    private final Clock clock;

    @Autowired
    public TimecardService(TimecardRepository repository) {
        this(repository, Clock.systemUTC());
    }

    TimecardService(TimecardRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TimecardV1 get(VerifiedTimecardRequest authority, UUID timecardId) {
        requireAuthority(authority, TimecardAction.VIEW, TIMECARD_PURPOSE);
        return disclose(authority, find(authority.tenantId(), timecardId)).projection();
    }

    public TimeCommandReceiptV1 correct(
            VerifiedTimecardRequest authority,
            UUID timecardId,
            String idempotencyKey,
            long expectedVersion,
            TimecardCorrectionCommandV1 command) {
        requireAuthority(authority, TimecardAction.CORRECT, TIMECARD_PURPOSE);
        TimecardRecord current = disclose(authority, find(authority.tenantId(), timecardId));
        for (TimecardCorrectionEntryV1 entry : command.entries()) {
            if (entry.workDate().isBefore(current.projection().periodStart())
                    || entry.workDate().isAfter(current.projection().periodEnd())) {
                throw new BaseException(
                        ErrorCode.VALIDATION_ERROR,
                        "Correction entries must fall within the Timecard period.");
            }
        }
        String entries = command.entries().stream()
                .map(entry -> TimecardDigests.sha256(
                        "TimecardCorrectionEntryV1",
                        entry.workDate(), entry.payCode(), entry.minutes()))
                .collect(Collectors.joining(":"));
        String digest = TimecardDigests.sha256(
                "TimecardCorrectionCommandV1", timecardId, expectedVersion,
                command.reasonCode(), entries);
        return repository.execute(new TimecardCommand(
                CommandKind.CORRECT, authority, timecardId, idempotencyKey,
                expectedVersion, digest, command.reasonCode(), command.entries(),
                null, null, clock.instant()));
    }

    public TimeCommandReceiptV1 submit(
            VerifiedTimecardRequest authority,
            UUID timecardId,
            String idempotencyKey,
            long expectedVersion,
            TimecardSubmitCommandV1 command) {
        requireAuthority(authority, TimecardAction.SUBMIT, TIMECARD_PURPOSE);
        if (authority.populationKind() != PopulationKind.SELF) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Timecard submission is restricted to SELF scope.");
        }
        disclose(authority, find(authority.tenantId(), timecardId));
        String digest = TimecardDigests.sha256(
                "TimecardSubmitCommandV1", timecardId, expectedVersion,
                command.reasonCode() == null ? "" : command.reasonCode());
        return repository.execute(new TimecardCommand(
                CommandKind.SUBMIT, authority, timecardId, idempotencyKey,
                expectedVersion, digest, command.reasonCode(), List.of(),
                null, null, clock.instant()));
    }

    public TimeCommandReceiptV1 decide(
            VerifiedTimecardRequest authority,
            UUID timecardId,
            String idempotencyKey,
            long expectedVersion,
            TimecardDecisionCommandV1 command) {
        requireAuthority(authority, TimecardAction.DECIDE, TIMECARD_PURPOSE);
        if (authority.populationKind() != PopulationKind.TARGET_POPULATION) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Timecard decisions require a target population.");
        }
        disclose(authority, find(authority.tenantId(), timecardId));
        String digest = TimecardDigests.sha256(
                "TimecardDecisionCommandV1", timecardId, expectedVersion,
                command.decision(), command.reasonCode(),
                command.comment() == null ? "" : command.comment());
        return repository.execute(new TimecardCommand(
                CommandKind.DECIDE, authority, timecardId, idempotencyKey,
                expectedVersion, digest, command.reasonCode(), List.of(),
                command.decision(), command.comment(), clock.instant()));
    }

    public TimeCommandReceiptV1 receipt(
            VerifiedTimecardRequest authority, UUID receiptId) {
        requireAuthority(authority, TimecardAction.RECEIPT_VIEW, RECEIPT_PURPOSE);
        StoredTimecardReceipt stored = repository.findReceipt(authority.tenantId(), receiptId)
                .orElseThrow(TimecardService::opaqueNotFound);
        if (!stored.subjectPrincipalPublicId().equals(authority.subjectPrincipalPublicId())
                || !authority.authorizedWorkerPublicIds().contains(stored.workerPublicId())
                || !stored.populationScopeDigest().equals(authority.populationScopeDigest())
                || stored.fieldPolicyRevision() != authority.fieldPolicyRevision()
                || stored.authorizationRevision() != authority.authorizationRevision()
                || !TIMECARD_PURPOSE.equals(stored.originatingPurposeCode())
                || !stillAuthorizedForOrigin(authority, stored)) {
            throw opaqueNotFound();
        }
        return stored.receipt();
    }

    private TimecardRecord find(long tenantId, UUID timecardId) {
        return repository.find(tenantId, timecardId)
                .orElseThrow(TimecardService::opaqueNotFound);
    }

    private TimecardRecord disclose(
            VerifiedTimecardRequest authority, TimecardRecord record) {
        if (!authority.authorizedWorkerPublicIds().contains(record.projection().workerId())) {
            throw opaqueNotFound();
        }
        return record;
    }

    private void requireAuthority(
            VerifiedTimecardRequest authority, TimecardAction action, String purpose) {
        Instant now = clock.instant();
        if (authority == null || !authority.actions().contains(action)
                || !purpose.equals(authority.purposeCode())
                || !authority.revalidateAt().isAfter(now)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Current Timecard owner authority is required.");
        }
    }

    private boolean stillAuthorizedForOrigin(
            VerifiedTimecardRequest authority, StoredTimecardReceipt stored) {
        CommandKind kind = switch (stored.receipt().originatingAction()) {
            case "TIME_TIMECARD_CORRECT" -> CommandKind.CORRECT;
            case "TIME_TIMECARD_SUBMIT" -> CommandKind.SUBMIT;
            case "TIME_TIMECARD_DECIDE" -> CommandKind.DECIDE;
            default -> null;
        };
        if (kind == null || !kind.commandType().equals(stored.receipt().commandType())) {
            return false;
        }
        try {
            String exactPermission = kind.requiredPermission(
                    stored.originatingPopulationKind());
            return exactPermission.equals(stored.originatingRequiredPermission())
                    && authority.permissions().contains(exactPermission);
        } catch (IllegalArgumentException invalidOriginProfile) {
            return false;
        }
    }

    private static BaseException opaqueNotFound() {
        return new BaseException(ErrorCode.NOT_FOUND, "Timecard resource was not found.");
    }
}
