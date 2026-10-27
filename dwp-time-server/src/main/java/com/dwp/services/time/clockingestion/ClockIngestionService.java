package com.dwp.services.time.clockingestion;

import java.util.Locale;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.clockingestion.ClockIngestionModels.CaptureMode;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAction;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAppend;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockCommandReceipt;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEvent;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEventType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockException;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockExceptionCandidate;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockExceptionProjectionQuery;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionCandidate;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionPage;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionQuery;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockSourceType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockStepUpHeaders;
import com.dwp.services.time.clockingestion.ClockIngestionModels.DstResolution;
import com.dwp.services.time.clockingestion.ClockIngestionModels.InstantAuthority;
import com.dwp.services.time.clockingestion.ClockIngestionModels.RecordClockCommand;
import com.dwp.services.time.clockingestion.ClockIngestionModels.TrustLevel;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VoidClockCommand;
import com.dwp.services.time.clockingestion.ClockIaQueryNormalizer.NormalizedClockQuery;
import com.dwp.services.time.clockingestion.ClockIaQueryNormalizer.NormalizedExceptionQuery;
import static com.dwp.services.time.clockingestion.ClockCommandDigests.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Owner use cases for canonical IAQ projections and immutable clock commands. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.time.clock-ingestion.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class ClockIngestionService {

    public static final String VIEW_PURPOSE = "TIME_OPERATIONS";
    public static final String CAPTURE_PURPOSE = "TIME_CAPTURE";

    private static final String PROJECTION_VERSION = "1";
    private static final String INVALIDATION_EVENT = "dwp.hris.access.assignment.changed.v1";
    private static final String CLOCK_VARIANT = "CLOCK_INGESTION";
    private static final String EXCEPTION_VARIANT = "EXCEPTIONS";
    private static final String CLOCK_ROUTE = "/hr/operations/time/clock-ingestion";
    private static final String EXCEPTION_ROUTE = "/hr/operations/time/exceptions";
    private static final String VOID_ACTION = "hris.action.tim.clock.void";
    private static final String VOID_HREF = "/api/time/v1/clock-events/{eventId}/voids";
    private static final int MAX_AUTHORIZED_WORKERS = 1_000;
    private static final String DIGEST_PATTERN = "^[0-9a-f]{64}$";
    private static final String REASON_PATTERN = "^[A-Z][A-Z0-9_]{1,79}$";
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Set<String> CLOCK_GROUPS = Set.of(
            "CLOCK_EVENT", "INGESTION_STATUS", "QUALITY_FINDING");
    private static final Set<String> EXCEPTION_GROUPS = Set.of(
            "EXCEPTION", "OWNER_DEEP_LINK", "RECOVERY_METADATA");

    private final ClockEventRepository repository;
    private final ClockEvidenceValidator validator;
    private final Clock clock;
    private final ClockIaQueryNormalizer queries;
    private final ClockQueryCursorCodec cursors;
    private final ClockStepUpVerifier stepUpVerifier;

    @Autowired
    public ClockIngestionService(
            ClockEventRepository repository,
            ClockQueryCursorCodec cursors,
            ClockStepUpVerifier stepUpVerifier) {
        this(repository, new ClockEvidenceValidator(), Clock.systemUTC(), cursors,
                stepUpVerifier);
    }

    ClockIngestionService(
            ClockEventRepository repository,
            ClockEvidenceValidator validator,
            Clock clock,
            ClockQueryCursorCodec cursors,
            ClockStepUpVerifier stepUpVerifier) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.queries = new ClockIaQueryNormalizer(clock);
        this.cursors = Objects.requireNonNull(cursors, "cursors must not be null");
        this.stepUpVerifier = Objects.requireNonNull(
                stepUpVerifier, "stepUpVerifier must not be null");
    }

    public ClockIaQueryModels.ClockProjection workbench(
            VerifiedClockRequest authority,
            ClockIaQueryModels.ClockQueryRequest request) {
        requireAuthority(authority, ClockAction.VIEW, VIEW_PURPOSE, false);
        requireFieldPolicy(authority, CLOCK_GROUPS);
        NormalizedClockQuery query = queries.normalizeClock(request);
        requireQueryCorrelation(authority, query.correlationId());
        QueryWindow window = queryWindow(
                authority,
                "IAQ-034|" + query.fingerprintSource(),
                query.cursor());
        ClockProjectionPage<ClockProjectionCandidate> page = repository.queryClock(
                new ClockProjectionQuery(
                        authority.tenantId(),
                        authority.authorizedWorkerPublicIds(),
                        query.asOf(),
                        window.snapshotHighWater(),
                        window.afterKey(),
                        query.limit(),
                        query.sort(),
                        query.startFrom(),
                        query.startTo(),
                        query.endFrom(),
                        query.endTo(),
                        query.effectiveOn(),
                        query.zoneId().getId(),
                        query.ingestionCodes(),
                        query.minimumIngestionRevision(),
                        query.qualityCodes(),
                        query.evidenceDigest()));
        List<ClockIaQueryModels.ClockItem> items = page.items().stream()
                .map(candidate -> clockItem(candidate, authority))
                .toList();
        String nextCursor = nextCursor(authority, window, page);
        return new ClockIaQueryModels.ClockProjection(
                PROJECTION_VERSION,
                query.asOf(),
                CLOCK_VARIANT,
                queryState(authority, query.correlationId(), nextCursor, items),
                items);
    }

    /** IAQ-042 is rebuilt from immutable clock facts rather than a second mutable ledger. */
    public ClockIaQueryModels.ExceptionProjection exceptions(
            VerifiedClockRequest authority,
            ClockIaQueryModels.ExceptionQueryRequest request) {
        requireAuthority(authority, ClockAction.VIEW, VIEW_PURPOSE, false);
        requireFieldPolicy(authority, EXCEPTION_GROUPS);
        NormalizedExceptionQuery query = queries.normalizeException(request);
        requireQueryCorrelation(authority, query.correlationId());
        QueryWindow window = queryWindow(
                authority,
                "IAQ-042|" + query.fingerprintSource(),
                query.cursor());
        ClockProjectionPage<ClockExceptionCandidate> page = repository.queryExceptions(
                new ClockExceptionProjectionQuery(
                        authority.tenantId(),
                        authority.authorizedWorkerPublicIds(),
                        query.asOf(),
                        window.snapshotHighWater(),
                        window.afterKey(),
                        query.limit(),
                        query.sort(),
                        query.effectiveOn(),
                        query.zoneId().getId(),
                        query.exceptionCodes(),
                        query.minimumRevision(),
                        query.ownerCodes(),
                        query.ownerPublicIds(),
                        query.metadataKeys(),
                        query.metadataVersions()));
        List<ClockIaQueryModels.ExceptionItem> items = page.items().stream()
                .map(candidate -> exceptionItem(exception(candidate), authority))
                .toList();
        String nextCursor = nextCursor(authority, window, page);
        return new ClockIaQueryModels.ExceptionProjection(
                PROJECTION_VERSION,
                query.asOf(),
                EXCEPTION_VARIANT,
                queryState(authority, query.correlationId(), nextCursor, items),
                items);
    }

    public ClockCommandReceipt record(
            VerifiedClockRequest authority,
            UUID idempotencyKey,
            RecordClockCommand command) {
        requireAuthority(authority, ClockAction.RECORD, CAPTURE_PURPOSE, false);
        requireIdempotencyKey(idempotencyKey);
        if (command == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Clock evidence is required.");
        }
        requireTarget(authority, command.workerPublicId(), command.peopleAssignmentPublicId(),
                command.peopleAssignmentRevision());
        if (!authority.sourceScope().contains(command.sourcePublicId())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Clock source is outside the active scope.");
        }
        validator.validate(command);

        String requestDigest = recordRequestDigest(authority, command);
        UUID eventId = stableId(
                authority.tenantId(), authority.subjectPrincipalPublicId(),
                "CLOCK_RECORD", idempotencyKey);
        Instant receivedAt = clock.instant();
        ClockEvent event = new ClockEvent(
                eventId,
                0,
                authority.tenantId(),
                command.sourcePublicId(),
                command.sourceEventKey(),
                command.sourceSequence(),
                command.sourceType(),
                command.devicePublicId(),
                command.captureMode(),
                command.offlineBatchPublicId(),
                command.workerPublicId(),
                command.peopleAssignmentPublicId(),
                command.peopleAssignmentRevision(),
                command.eventType(),
                command.occurredAt(),
                receivedAt,
                command.sourceLocalDateTime(),
                command.sourceTimeZone(),
                command.sourceUtcOffsetMinutes(),
                command.dstResolution(),
                command.instantAuthority(),
                command.tzdbVersion(),
                command.timeZoneRuleVersion(),
                command.sourceProvenanceDigest(),
                recordPayloadDigest(authority.tenantId(), eventId, receivedAt, command),
                command.trustLevel(),
                null,
                null,
                authority.correlationId(),
                authority.actorId());
        return repository.append(new ClockAppend(
                authority, idempotencyKey, "CLOCK_RECORD", requestDigest, event, null));
    }

    public ClockCommandReceipt voidEvent(
            VerifiedClockRequest authority,
            UUID clockEventId,
            UUID idempotencyKey,
            ClockStepUpHeaders stepUpHeaders,
            VoidClockCommand command) {
        requireAuthority(authority, ClockAction.CORRECT, CAPTURE_PURPOSE, true);
        requireIdempotencyKey(idempotencyKey);
        Objects.requireNonNull(clockEventId, "clockEventId must not be null");
        if (command == null || command.reasonCode() == null
                || !command.reasonCode().matches(REASON_PATTERN)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A closed corrective reason code is required.");
        }
        ClockEvent original = repository.find(authority.tenantId(), clockEventId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireTarget(authority, original.workerPublicId(), original.peopleAssignmentPublicId(),
                original.peopleAssignmentRevision());
        if (original.eventType() == ClockEventType.VOID || original.voidsClockEventId() != null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        var verifiedChallenge = stepUpVerifier.verifyVoid(
                authority,
                clockEventId,
                original.eventOrder(),
                idempotencyKey,
                command,
                stepUpHeaders);

        Instant correctedAt = clock.instant();
        UUID correctionSourceId = stableId(
                authority.tenantId(), authority.subjectPrincipalPublicId(),
                "CORRECTION_SOURCE", new UUID(0, 0));
        UUID voidEventId = stableId(
                authority.tenantId(), authority.subjectPrincipalPublicId(),
                "CLOCK_VOID", idempotencyKey);
        String sourceEventKey = "void:" + idempotencyKey;
        String requestDigest = ClockDigests.sha256(
                "ClockVoidCommand.v1",
                clockEventId,
                command.reasonCode(),
                authority.actorId(),
                authority.subjectPrincipalPublicId(),
                authority.populationScopeDigest(),
                authority.fieldPolicyRevision(),
                authority.purposeCode(),
                authority.authorizationRevision());
        String provenanceDigest = ClockDigests.sha256(
                "ClockVoidProvenance.v1",
                correctionSourceId,
                sourceEventKey,
                clockEventId,
                command.reasonCode(),
                authority.actorId(),
                authority.correlationId());
        String tzdbVersion = ClockEvidenceValidator.expectedTzdbVersion("UTC");
        ClockEvent correction = new ClockEvent(
                voidEventId,
                0,
                authority.tenantId(),
                correctionSourceId,
                sourceEventKey,
                null,
                ClockSourceType.API,
                null,
                CaptureMode.ONLINE,
                null,
                original.workerPublicId(),
                original.peopleAssignmentPublicId(),
                original.peopleAssignmentRevision(),
                ClockEventType.VOID,
                correctedAt,
                correctedAt,
                LocalDateTime.ofInstant(correctedAt, ZoneOffset.UTC),
                "UTC",
                0,
                DstResolution.EXACT_OFFSET_SUPPLIED,
                InstantAuthority.SERVER_RESOLVED,
                tzdbVersion,
                ClockEvidenceValidator.expectedTimeZoneRuleVersion("UTC", tzdbVersion),
                provenanceDigest,
                ClockDigests.sha256(
                        "ClockEventRecorded.v1",
                        voidEventId,
                        original.workerPublicId(),
                        ClockEventType.VOID,
                        correctedAt,
                        correctionSourceId,
                        0,
                        clockEventId,
                        command.reasonCode(),
                        provenanceDigest),
                TrustLevel.VERIFIED,
                clockEventId,
                command.reasonCode(),
                authority.correlationId(),
                authority.actorId());
        return repository.append(new ClockAppend(
                authority,
                idempotencyKey,
                "CLOCK_VOID",
                requestDigest,
                correction,
                verifiedChallenge));
    }

    private QueryWindow queryWindow(
            VerifiedClockRequest authority,
            String fingerprintSource,
            String cursor) {
        String fingerprint = ClockQueryCursorCodec.fingerprint(
                fingerprintSource + "|population:" + authority.populationScopeDigest());
        if (cursor == null) return new QueryWindow(null, List.of(), fingerprint);
        ClockQueryCursorCodec.CursorState state = cursors.verify(
                cursor,
                authority.tenantId(),
                fingerprint,
                authority.queryScopeRevision(),
                authority.decisionRevision());
        return new QueryWindow(state.snapshotHighWater(), state.afterKey(), fingerprint);
    }

    private <T> String nextCursor(
            VerifiedClockRequest authority,
            QueryWindow window,
            ClockProjectionPage<T> page) {
        if (!page.hasMore()) return null;
        return cursors.issue(
                authority.tenantId(),
                page.snapshotHighWater(),
                page.nextKey(),
                window.fingerprint(),
                authority.queryScopeRevision(),
                authority.decisionRevision());
    }

    private ClockIaQueryModels.QueryState queryState(
            VerifiedClockRequest authority,
            UUID correlationId,
            String nextCursor,
            List<?> items) {
        DecisionCounts counts = decisionCounts(items);
        return new ClockIaQueryModels.QueryState(
                items.isEmpty() ? "EMPTY" : "COMPLETE",
                authority.queryScopeRevision(),
                "CURRENT",
                nextCursor,
                new ClockIaQueryModels.FieldDecisionSummary(
                        counts.view(), counts.masked(), counts.omitted()),
                List.of(),
                correlationId,
                new ClockIaQueryModels.CachePolicy(
                        authority.decisionRevision(),
                        INVALIDATION_EVENT,
                        0));
    }

    private static DecisionCounts decisionCounts(List<?> items) {
        int view = 0;
        int masked = 0;
        int omitted = 0;
        for (Object item : items) {
            Object[] fields;
            if (item instanceof ClockIaQueryModels.ClockItem clockItem) {
                fields = new Object[] {
                    clockItem.fieldGroups().CLOCK_EVENT(),
                    clockItem.fieldGroups().INGESTION_STATUS(),
                    clockItem.fieldGroups().QUALITY_FINDING()
                };
            } else if (item instanceof ClockIaQueryModels.ExceptionItem exceptionItem) {
                fields = new Object[] {
                    exceptionItem.fieldGroups().EXCEPTION(),
                    exceptionItem.fieldGroups().OWNER_DEEP_LINK(),
                    exceptionItem.fieldGroups().RECOVERY_METADATA()
                };
            } else {
                throw new IllegalStateException("Unexpected TIM IAQ item type");
            }
            for (Object field : fields) {
                if (field == null) omitted++;
                else if (field instanceof ClockIaQueryModels.MaskedField) masked++;
                else view++;
            }
        }
        return new DecisionCounts(view, masked, omitted);
    }

    private ClockIaQueryModels.ClockItem clockItem(
            ClockProjectionCandidate candidate,
            VerifiedClockRequest authority) {
        ClockEvent event = candidate.event();
        ClockIaQueryModels.ClockFieldGroups groups = new ClockIaQueryModels.ClockFieldGroups(
                field(authority, "CLOCK_EVENT", new ClockIaQueryModels.ViewedTemporalField(
                        "VIEW", new ClockIaQueryModels.TemporalValue(
                                event.occurredAt(), null, 0))),
                field(authority, "INGESTION_STATUS", new ClockIaQueryModels.ViewedStatusField(
                        "VIEW", new ClockIaQueryModels.StatusValue(
                                candidate.statusCode(), event.eventOrder()))),
                field(authority, "QUALITY_FINDING", new ClockIaQueryModels.ViewedAuditField(
                        "VIEW", new ClockIaQueryModels.AuditValue(
                                candidate.qualityCode(),
                                event.payloadDigest(),
                                event.occurredAt(),
                                event.eventOrder()))));
        return new ClockIaQueryModels.ClockItem(
                event.clockEventId().toString(),
                event.eventType().name() + " clock event",
                candidate.statusCode(),
                groups,
                new ClockIaQueryModels.DeepLink(CLOCK_ROUTE, event.clockEventId().toString()),
                availableVoid(authority, event, candidate.resolutionVoidEventId() != null));
    }

    private ClockIaQueryModels.ExceptionItem exceptionItem(
            ClockException exception,
            VerifiedClockRequest authority) {
        String metadataKey = "clock.recovery."
                + exception.code().toLowerCase(Locale.ROOT).replace('_', '.');
        ClockIaQueryModels.ExceptionFieldGroups groups =
                new ClockIaQueryModels.ExceptionFieldGroups(
                        field(authority, "EXCEPTION", new ClockIaQueryModels.ViewedStatusField(
                                "VIEW", new ClockIaQueryModels.StatusValue(
                                        exception.code(), exception.eventOrder()))),
                        field(authority, "OWNER_DEEP_LINK",
                                new ClockIaQueryModels.ViewedReferenceField(
                                        "VIEW", new ClockIaQueryModels.ReferenceValue(
                                                exception.clockEventId().toString(),
                                                "CLOCK_EVENT",
                                                "Clock event",
                                                exception.eventOrder()))),
                        field(authority, "RECOVERY_METADATA",
                                new ClockIaQueryModels.ViewedMetadataField(
                                        "VIEW", new ClockIaQueryModels.MetadataValue(
                                                metadataKey,
                                                exception.state(),
                                                exception.evidenceDigest()))));
        List<ClockIaQueryModels.AvailableAction> actions =
                ("OPEN".equals(exception.state()) || "QUARANTINED".equals(exception.state()))
                        && canOfferVoid(authority, exception.workerPublicId())
                        ? List.of(voidAction())
                        : List.of();
        return new ClockIaQueryModels.ExceptionItem(
                exception.exceptionId().toString(),
                exception.code(),
                exception.state(),
                groups,
                new ClockIaQueryModels.DeepLink(
                        EXCEPTION_ROUTE, exception.exceptionId().toString()),
                actions);
    }

    private static Object field(
            VerifiedClockRequest authority,
            String group,
            Object viewed) {
        if (authority.omittedFieldGroups().contains(group)) return null;
        if (authority.maskedFieldGroups().contains(group)) {
            return new ClockIaQueryModels.MaskedField(
                    "MASK", null, "FIELD_POLICY", "hris.field.masked");
        }
        return viewed;
    }

    private static List<ClockIaQueryModels.AvailableAction> availableVoid(
            VerifiedClockRequest authority,
            ClockEvent event,
            boolean resolvedByVoid) {
        if (event.eventType() == ClockEventType.VOID
                || resolvedByVoid
                || !canOfferVoid(authority, event.workerPublicId())) {
            return List.of();
        }
        return List.of(voidAction());
    }

    private static boolean canOfferVoid(
            VerifiedClockRequest authority,
            UUID workerPublicId) {
        return authority.actions().contains(ClockAction.CORRECT)
                && authority.authorizedWorkerPublicIds().contains(workerPublicId);
    }

    private static ClockIaQueryModels.AvailableAction voidAction() {
        return new ClockIaQueryModels.AvailableAction(
                VOID_ACTION,
                "POST",
                VOID_HREF,
                "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
    }

    private void requireAuthority(
            VerifiedClockRequest authority,
            ClockAction action,
            String requiredPurpose,
            boolean requireStepUp) {
        Instant now = clock.instant();
        if (authority == null
                || authority.tenantId() <= 0
                || authority.actorId() <= 0
                || authority.subjectPrincipalPublicId() == null
                || authority.populationPublicId() == null
                || authority.populationRevision() <= 0
                || authority.grantRevision() <= 0
                || authority.membershipRevision() <= 0
                || authority.correlationId() == null
                || authority.revalidateAt() == null
                || !authority.revalidateAt().isAfter(now)
                || !authority.actions().contains(action)
                || !HcmEligibilityScopeKey.isCanonical(authority.gatewayScopeKey())
                || !digest(authority.populationScopeDigest())
                || !digest(authority.populationDigest())
                || !digest(authority.membershipDigest())
                || !digest(authority.grantDigest())
                || authority.fieldPolicyRevision() <= 0
                || authority.authorizationRevision() <= 0
                || authority.decisionRevision() == null
                || !authority.decisionRevision().matches("psr-[0-9a-f]{64}")
                || !requiredPurpose.equals(authority.purposeCode())) {
            throw authorityUnavailable();
        }
        if (action == ClockAction.VIEW) {
            if (authority.authorizedWorkerPublicIds().isEmpty()
                    || authority.authorizedWorkerPublicIds().size() > MAX_AUTHORIZED_WORKERS
                    || !safeToken(authority.queryScopeRevision(), 128)) {
                throw authorityUnavailable();
            }
        } else if (authority.workerPublicId() == null
                || authority.peopleAssignmentPublicId() == null
                || authority.peopleAssignmentRevision() < 0
                || (action == ClockAction.RECORD && authority.sourceScope().isEmpty())) {
            throw authorityUnavailable();
        }
        if (requireStepUp && !authority.stepUpSatisfied()) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED);
        }
    }

    private static void requireFieldPolicy(
            VerifiedClockRequest authority,
            Set<String> allowedGroups) {
        if (!allowedGroups.containsAll(authority.maskedFieldGroups())
                || !allowedGroups.containsAll(authority.omittedFieldGroups())
                || !java.util.Collections.disjoint(
                        authority.maskedFieldGroups(), authority.omittedFieldGroups())
                || authority.omittedFieldGroups().containsAll(allowedGroups)) {
            throw authorityUnavailable();
        }
    }

    private static void requireQueryCorrelation(
            VerifiedClockRequest authority,
            UUID correlationId) {
        if (!authority.correlationId().equals(correlationId)) throw authorityUnavailable();
    }

    private static void requireTarget(
            VerifiedClockRequest authority,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision) {
        if (!authority.workerPublicId().equals(workerPublicId)
                || !authority.peopleAssignmentPublicId().equals(peopleAssignmentPublicId)
                || authority.peopleAssignmentRevision() != peopleAssignmentRevision) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
    }

    private static void requireIdempotencyKey(UUID idempotencyKey) {
        if (idempotencyKey == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Idempotency-Key is required.");
        }
    }

    private static ClockException exception(ClockExceptionCandidate candidate) {
        ClockEvent event = candidate.event();
        UUID exceptionId = UUID.nameUUIDFromBytes(
                (event.clockEventId() + "|" + candidate.exceptionCode())
                        .getBytes(StandardCharsets.UTF_8));
        return new ClockException(
                exceptionId,
                event.clockEventId(),
                candidate.exceptionCode(),
                candidate.severity(),
                candidate.recoveryState(),
                event.eventOrder(),
                event.workerPublicId(),
                event.sourcePublicId(),
                event.sourceSequence(),
                event.occurredAt(),
                event.payloadDigest(),
                candidate.resolutionVoidEventId());
    }

    private static boolean digest(String value) {
        return value != null && value.matches(DIGEST_PATTERN);
    }

    private static boolean safeText(String value, int maximum) {
        return value != null
                && !value.isBlank()
                && value.length() <= maximum
                && value.equals(value.strip())
                && value.chars().noneMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static boolean safeToken(String value, int maximum) {
        return safeText(value, maximum) && PUBLIC_ID.matcher(value).matches();
    }

    private static BaseException authorityUnavailable() {
        return new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Current TIM authority is absent, stale, or mismatched.");
    }

    private record QueryWindow(
            Long snapshotHighWater,
            List<String> afterKey,
            String fingerprint) {

        QueryWindow {
            afterKey = List.copyOf(afterKey);
        }
    }

    private record DecisionCounts(int view, int masked, int omitted) {}


}
