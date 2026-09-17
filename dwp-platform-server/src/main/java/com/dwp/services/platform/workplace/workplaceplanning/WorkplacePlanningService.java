package com.dwp.services.platform.workplace.workplaceplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningDtos.*;
import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningRepository.*;

@Service
public class WorkplacePlanningService {
    static final Duration PREVIEW_TTL = Duration.ofMinutes(15);

    private final WorkplacePlanningRepository repository;
    private final WorkplacePlanningEvidenceService evidence;
    private final Clock clock;

    @Autowired
    public WorkplacePlanningService(
            WorkplacePlanningRepository repository,
            WorkplacePlanningEvidenceService evidence) {
        this(repository, evidence, Clock.systemUTC());
    }

    WorkplacePlanningService(WorkplacePlanningRepository repository, Clock clock) {
        this(repository, new WorkplacePlanningEvidenceService(repository, clock), clock);
    }

    WorkplacePlanningService(
            WorkplacePlanningRepository repository,
            WorkplacePlanningEvidenceService evidence,
            Clock clock) {
        this.repository = repository;
        this.evidence = evidence;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PlanningOverview overview(long tenantId, PlanningScope requestedScope) {
        requireTenant(tenantId);
        PlanningScope scope = evidence.validateScope(tenantId, requestedScope);
        List<PlanningSourceStatus> sources = evidence.sourceStatuses(tenantId, scope);
        ForecastProjection forecast = evidence.forecast(tenantId, scope, sources);
        return new PlanningOverview(scope, repository.currentMetrics(tenantId, scope), sources,
                forecast, repository.latestEmission(tenantId, scope).orElse(null),
                repository.scenarios(tenantId, scope.siteId(), null).stream()
                        .map(row -> view(tenantId, row)).toList(), now());
    }

    @Transactional(readOnly = true)
    public List<PlanningSourceStatus> sources(long tenantId, PlanningScope requestedScope) {
        requireTenant(tenantId);
        return evidence.sourceStatuses(
                tenantId, evidence.validateScope(tenantId, requestedScope));
    }

    @Transactional(readOnly = true)
    public List<ScenarioView> scenarios(long tenantId, UUID siteId, ScenarioState state) {
        requireTenant(tenantId);
        if (siteId != null && !repository.siteExists(tenantId, siteId)) {
            throw notFound("The space-planning site was not found in this tenant.");
        }
        return repository.scenarios(tenantId, siteId, state).stream()
                .map(row -> view(tenantId, row)).toList();
    }

    @Transactional(readOnly = true)
    public ScenarioView scenario(long tenantId, UUID scenarioId) {
        requireTenant(tenantId);
        return view(tenantId, requireScenario(tenantId, scenarioId));
    }

    @Transactional
    public ScenarioCommandResult create(
            long tenantId,
            long actorId,
            String idempotencyKey,
            String correlationId,
            CreateScenarioRequest request) {
        if (request == null) throw invalid("A scenario request is required.");
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        requireScenarioText(request.name(), request.description());
        String key = requireIdempotencyKey(idempotencyKey);
        String correlation = correlation(correlationId);
        requireReason(request.reason());
        repository.lockCommand(tenantId, actorId, "CREATE", key);
        String fingerprint = fingerprint("CREATE", request);
        ScenarioCommandResult replay = replay(
                tenantId, actorId, key, null, "CREATE", fingerprint);
        if (replay != null) return replay;

        PlanningScope scope = evidence.validateScope(tenantId, request.scope());
        ScenarioDraftInput draft = evidence.validateDraft(tenantId, scope, request.draft());
        OffsetDateTime now = now();
        ScenarioRow row = new ScenarioRow(UUID.randomUUID(), tenantId, request.name().trim(),
                normalized(request.description()), ScenarioState.DRAFT, scope, draft, null, 1,
                null, null, null, null, null, null, null, null, null, null,
                now, actorId, now, actorId);
        repository.insertScenario(row);
        ScenarioView result = view(tenantId, row);
        CommandRow command = repository.saveCommand(tenantId, actorId, row.scenarioId(),
                "CREATE", key, fingerprint, request.reason(), correlation, result, true, now);
        repository.audit(tenantId, actorId, "workplace.spaceplanning.scenario.created",
                row.scenarioId(), request.reason(), correlation, result.state(), result.version(), now);
        return new ScenarioCommandResult(result, receipt(command, false));
    }

    @Transactional
    public ScenarioCommandResult update(
            long tenantId,
            long actorId,
            UUID scenarioId,
            String idempotencyKey,
            String correlationId,
            UpdateScenarioRequest request) {
        if (request == null) throw invalid("A scenario update request is required.");
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        requireScenarioText(request.name(), request.description());
        requireVersion(request.expectedVersion());
        requireReason(request.reason());
        String key = requireIdempotencyKey(idempotencyKey);
        String correlation = correlation(correlationId);
        repository.lockCommand(tenantId, actorId, "UPDATE", key);
        String fingerprint = fingerprint("UPDATE", scenarioId, request);
        ScenarioCommandResult replay = replay(
                tenantId, actorId, key, scenarioId, "UPDATE", fingerprint);
        if (replay != null) return replay;

        ScenarioRow current = requireScenario(tenantId, scenarioId);
        ScenarioDraftInput draft = evidence.validateDraft(tenantId, current.scope(), request.draft());
        UpdateScenarioRequest normalizedRequest = new UpdateScenarioRequest(
                request.expectedVersion(), request.name().trim(), normalized(request.description()),
                draft, request.reason().trim(), true);
        OffsetDateTime now = now();
        if (!repository.updateDraft(tenantId, actorId, scenarioId, request.expectedVersion(),
                normalizedRequest, now)) {
            throw conflict("The scenario state or version changed; refresh before updating it.");
        }
        ScenarioView result = scenario(tenantId, scenarioId);
        CommandRow command = repository.saveCommand(tenantId, actorId, scenarioId, "UPDATE",
                key, fingerprint, request.reason(), correlation, result, true, now);
        repository.audit(tenantId, actorId, "workplace.spaceplanning.scenario.updated",
                scenarioId, request.reason(), correlation, result.state(), result.version(), now);
        return new ScenarioCommandResult(result, receipt(command, false));
    }

    @Transactional
    public ScenarioCommandResult preview(
            long tenantId,
            long actorId,
            UUID scenarioId,
            String idempotencyKey,
            String correlationId,
            PreviewScenarioRequest request) {
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        requireVersion(request.expectedVersion());
        requireReason(request.reason());
        String key = requireIdempotencyKey(idempotencyKey);
        String correlation = correlation(correlationId);
        repository.lockCommand(tenantId, actorId, "PREVIEW", key);
        String fingerprint = fingerprint("PREVIEW", scenarioId, request);
        ScenarioCommandResult replay = replay(
                tenantId, actorId, key, scenarioId, "PREVIEW", fingerprint);
        if (replay != null) return replay;

        ScenarioRow current = requireScenario(tenantId, scenarioId);
        if (current.version() != request.expectedVersion()
                || (current.state() != ScenarioState.DRAFT
                && current.state() != ScenarioState.PREVIEWED)) {
            throw conflict("Only the current draft can be previewed.");
        }
        List<PlanningSourceStatus> sources = evidence.sourceStatuses(tenantId, current.scope());
        ForecastProjection forecast = evidence.forecast(tenantId, current.scope(), sources);
        CurrentSpaceMetrics currentMetrics = repository.currentMetrics(tenantId, current.scope());
        BookingImpactState impactState = evidence.impactState(current, currentMetrics);
        List<BookingImpactItem> impacted = impactState == BookingImpactState.READY
                ? repository.bookingImpact(current) : List.of();
        Integer impactedCount = impactState == BookingImpactState.READY ? impacted.size() : null;
        SpaceComparison comparison = evidence.comparison(currentMetrics, current.draft(), forecast,
                impactedCount);
        EmissionProjection emission = evidence.selectedEmission(tenantId, current);
        List<String> limitations = new ArrayList<>(forecast.limitations());
        if (impactState != BookingImpactState.READY) {
            limitations.add("Booking impact scope is incomplete; identify affected resources before submission.");
        }
        boolean eligible = forecast.state() == ForecastState.READY
                && impactState == BookingImpactState.READY;
        OffsetDateTime now = now();
        ScenarioPreview preview = new ScenarioPreview(UUID.randomUUID(), scenarioId,
                current.version(), forecast.state(), forecast, comparison, emission, eligible,
                List.copyOf(limitations), now.plus(PREVIEW_TTL), now);
        if (!repository.saveScenarioPreview(tenantId, actorId, scenarioId,
                request.expectedVersion(), preview, now)) {
            throw conflict("The scenario state or version changed; refresh before saving the preview.");
        }
        ScenarioView result = scenario(tenantId, scenarioId);
        CommandRow command = repository.saveCommand(tenantId, actorId, scenarioId, "PREVIEW",
                key, fingerprint, request.reason(), correlation, result, true, now);
        repository.audit(tenantId, actorId, "workplace.spaceplanning.scenario.previewed",
                scenarioId, request.reason(), correlation, result.state(), result.version(), now);
        return new ScenarioCommandResult(result, receipt(command, false));
    }

    @Transactional
    public ScenarioCommandResult submit(
            long tenantId,
            long actorId,
            UUID scenarioId,
            String idempotencyKey,
            String correlationId,
            ScenarioTransitionRequest request) {
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        requireVersion(request.expectedVersion());
        requireReason(request.reason());
        String key = requireIdempotencyKey(idempotencyKey);
        String correlation = correlation(correlationId);
        repository.lockCommand(tenantId, actorId, "SUBMIT", key);
        String fingerprint = fingerprint("SUBMIT", scenarioId, request);
        ScenarioCommandResult replay = replay(
                tenantId, actorId, key, scenarioId, "SUBMIT", fingerprint);
        if (replay != null) return replay;

        ScenarioRow current = requireScenario(tenantId, scenarioId);
        if (current.version() != request.expectedVersion()
                || current.state() != ScenarioState.PREVIEWED
                || current.activePreviewId() == null) {
            throw conflict("Only the current previewed scenario can be submitted.");
        }
        ScenarioPreviewRow preview = repository.preview(tenantId, current.activePreviewId())
                .orElseThrow(() -> conflict("The active scenario preview is unavailable."));
        if (!preview.eligible() || !preview.expiresAt().isAfter(now())
                || preview.scenarioVersion() != current.version() - 1) {
            throw conflict("The scenario preview is stale, incomplete, or ineligible; create a new preview.");
        }
        OffsetDateTime now = now();
        if (!repository.submit(tenantId, actorId, scenarioId, request.expectedVersion(), now)) {
            throw conflict("The scenario state or version changed; refresh before submission.");
        }
        ScenarioView result = scenario(tenantId, scenarioId);
        CommandRow command = repository.saveCommand(tenantId, actorId, scenarioId, "SUBMIT",
                key, fingerprint, request.reason(), correlation, result, true, now);
        repository.audit(tenantId, actorId, "workplace.spaceplanning.scenario.submitted",
                scenarioId, request.reason(), correlation, result.state(), result.version(), now);
        return new ScenarioCommandResult(result, receipt(command, false));
    }

    @Transactional
    public ScenarioCommandResult approve(
            long tenantId,
            long actorId,
            UUID scenarioId,
            String idempotencyKey,
            String correlationId,
            ScenarioApprovalRequest request) {
        if (request == null) throw invalid("A scenario decision is required.");
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        requireVersion(request.expectedVersion());
        requireReason(request.reason());
        if (request.decision() == null) throw invalid("An approval decision is required.");
        if (normalized(request.approvalAuthorityReference()) == null
                || request.approvalAuthorityReference().trim().length() > 160) {
            throw invalid("A valid approval authority reference is required.");
        }
        String commandType = request.decision() == ApprovalDecision.APPROVE ? "APPROVE" : "REJECT";
        String key = requireIdempotencyKey(idempotencyKey);
        String correlation = correlation(correlationId);
        repository.lockCommand(tenantId, actorId, commandType, key);
        String fingerprint = fingerprint(commandType, scenarioId, request);
        ScenarioCommandResult replay = replay(
                tenantId, actorId, key, scenarioId, commandType, fingerprint);
        if (replay != null) return replay;

        ScenarioRow current = requireScenario(tenantId, scenarioId);
        if (current.version() != request.expectedVersion()
                || current.state() != ScenarioState.SUBMITTED) {
            throw conflict("Only the current submitted scenario can receive a decision.");
        }
        if (request.decision() == ApprovalDecision.APPROVE
                && Objects.equals(current.submittedBy(), actorId)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "The submitting actor cannot approve the same space-planning scenario.");
        }
        OffsetDateTime now = now();
        if (!repository.decide(tenantId, actorId, scenarioId,
                request.expectedVersion(), request, now)) {
            throw conflict("The scenario state or version changed; refresh before deciding.");
        }
        ScenarioView result = scenario(tenantId, scenarioId);
        CommandRow command = repository.saveCommand(tenantId, actorId, scenarioId, commandType,
                key, fingerprint, request.reason(), correlation, result, true, now);
        repository.audit(tenantId, actorId,
                request.decision() == ApprovalDecision.APPROVE
                        ? "workplace.spaceplanning.scenario.approved"
                        : "workplace.spaceplanning.scenario.rejected",
                scenarioId, request.reason(), correlation, result.state(), result.version(), now);
        return new ScenarioCommandResult(result, receipt(command, false));
    }

    @Transactional
    public ScenarioCommandResult publish(
            long tenantId,
            long actorId,
            UUID scenarioId,
            String idempotencyKey,
            String correlationId,
            ScenarioTransitionRequest request) {
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        requireVersion(request.expectedVersion());
        requireReason(request.reason());
        String key = requireIdempotencyKey(idempotencyKey);
        String correlation = correlation(correlationId);
        repository.lockCommand(tenantId, actorId, "PUBLISH", key);
        String fingerprint = fingerprint("PUBLISH", scenarioId, request);
        ScenarioCommandResult replay = replay(
                tenantId, actorId, key, scenarioId, "PUBLISH", fingerprint);
        if (replay != null) return replay;

        ScenarioRow current = requireScenario(tenantId, scenarioId);
        if (current.version() != request.expectedVersion()
                || current.state() != ScenarioState.APPROVED
                || current.approvedAt() == null || current.approvedBy() == null
                || normalized(current.approvalAuthorityReference()) == null) {
            throw conflict("Only the current approved scenario can be published.");
        }
        OffsetDateTime now = now();
        if (!repository.publish(tenantId, actorId, scenarioId, request.expectedVersion(), now)) {
            throw conflict("The scenario state or version changed; refresh before publication.");
        }
        ScenarioView result = scenario(tenantId, scenarioId);
        CommandRow command = repository.saveCommand(tenantId, actorId, scenarioId, "PUBLISH",
                key, fingerprint, request.reason(), correlation, result, true, now);
        repository.audit(tenantId, actorId, "workplace.spaceplanning.scenario.published",
                scenarioId, request.reason(), correlation, result.state(), result.version(), now);
        return new ScenarioCommandResult(result, receipt(command, false));
    }

    @Transactional
    public BookingImpactCommandResult previewBookingImpact(
            long tenantId,
            long actorId,
            UUID scenarioId,
            String idempotencyKey,
            String correlationId,
            BookingImpactPreviewRequest request) {
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        requireVersion(request.expectedVersion());
        requireReason(request.reason());
        String key = requireIdempotencyKey(idempotencyKey);
        String correlation = correlation(correlationId);
        repository.lockCommand(tenantId, actorId, "BOOKING_IMPACT_PREVIEW", key);
        String fingerprint = fingerprint("BOOKING_IMPACT_PREVIEW", scenarioId, request);
        BookingImpactCommandResult replay = replayBookingImpact(
                tenantId, actorId, key, scenarioId, fingerprint);
        if (replay != null) return replay;
        ScenarioRow scenario = requireScenario(tenantId, scenarioId);
        if (scenario.version() != request.expectedVersion()
                || scenario.state() == ScenarioState.PUBLISHED) {
            throw conflict("The scenario version changed or is already published.");
        }
        CurrentSpaceMetrics current = repository.currentMetrics(tenantId, scenario.scope());
        BookingImpactState state = evidence.impactState(scenario, current);
        List<BookingImpactItem> bookings = state == BookingImpactState.READY
                ? repository.bookingImpact(scenario) : List.of();
        List<String> limitations = state == BookingImpactState.READY ? List.of()
                : List.of("Affected resources are required for an authoritative booking-impact preview.");
        OffsetDateTime now = now();
        BookingImpactPreview preview = new BookingImpactPreview(UUID.randomUUID(), scenarioId,
                scenario.version(), state, state == BookingImpactState.READY ? bookings.size() : null,
                bookings, limitations, now.plus(PREVIEW_TTL), now);
        repository.saveBookingImpactPreview(tenantId, actorId, preview);
        CommandRow command = repository.saveBookingImpactCommand(tenantId, actorId,
                scenario.state(), key, fingerprint, request.reason(), correlation, preview, now);
        repository.audit(tenantId, actorId, "workplace.spaceplanning.bookingimpact.previewed",
                scenarioId, request.reason(), correlation, scenario.state(), scenario.version(), now);
        return new BookingImpactCommandResult(preview, receipt(command, false));
    }



    @Transactional
    public PlanningSourceStatus observeSource(SourceObservation observation) {
        return evidence.observeSource(observation);
    }

    @Transactional
    public ForecastProjection observeForecast(ForecastObservation observation) {
        return evidence.observeForecast(observation);
    }

    @Transactional
    public EmissionProjection observeEmission(EmissionObservation observation) {
        return evidence.observeEmission(observation);
    }

    private ScenarioView view(long tenantId, ScenarioRow row) {
        ScenarioPreview activePreview = row.activePreviewId() == null ? null
                : repository.preview(tenantId, row.activePreviewId()).map(this::preview).orElse(null);
        return new ScenarioView(row.scenarioId(), row.name(), row.description(), row.state(),
                row.scope(), row.draft(), activePreview, row.version(), row.submittedAt(),
                row.submittedBy(), row.approvedAt(), row.approvedBy(),
                row.approvalAuthorityReference(), row.publishedAt(), row.publishedBy(),
                row.lastRejectedAt(), row.lastRejectedBy(), row.lastRejectionReason(),
                row.createdAt(), row.updatedAt());
    }

    private ScenarioPreview preview(ScenarioPreviewRow row) {
        return new ScenarioPreview(row.previewId(), row.scenarioId(), row.scenarioVersion(),
                row.forecastState(), row.forecast(), row.comparison(), row.emission(), row.eligible(),
                List.copyOf(row.limitations()), row.expiresAt(), row.createdAt());
    }

    private ScenarioCommandResult replay(
            long tenantId,
            long actorId,
            String idempotencyKey,
            UUID scenarioId,
            String commandType,
            String fingerprint) {
        CommandRow row = repository.commandByIdempotency(tenantId, actorId, idempotencyKey)
                .orElse(null);
        if (row == null) return null;
        if (!row.commandType().equals(commandType)
                || !row.requestFingerprint().equals(fingerprint)
                || (scenarioId != null && !row.scenarioId().equals(scenarioId))) {
            throw conflict("The idempotency key was already used for a different planning command.");
        }
        if (row.result() == null) {
            throw conflict("The prior command result is unavailable; reconcile it before retrying.");
        }
        return new ScenarioCommandResult(row.result(), receipt(row, true));
    }

    private BookingImpactCommandResult replayBookingImpact(
            long tenantId,
            long actorId,
            String idempotencyKey,
            UUID scenarioId,
            String fingerprint) {
        CommandRow row = repository.commandByIdempotency(tenantId, actorId, idempotencyKey)
                .orElse(null);
        if (row == null) return null;
        if (!row.commandType().equals("BOOKING_IMPACT_PREVIEW")
                || !row.requestFingerprint().equals(fingerprint)
                || !row.scenarioId().equals(scenarioId)) {
            throw conflict("The idempotency key was already used for a different planning command.");
        }
        if (row.bookingImpactResult() == null) {
            throw conflict("The prior booking-impact result is unavailable; reconcile it before retrying.");
        }
        return new BookingImpactCommandResult(row.bookingImpactResult(), receipt(row, true));
    }

    private CommandReceipt receipt(CommandRow row, boolean replay) {
        return new CommandReceipt(row.commandId(), row.commandType(), row.state(), row.scenarioId(),
                row.resultingState(), row.resultingVersion(), row.outboxId(), row.outboxState(),
                replay, row.correlationId(), row.acceptedAt());
    }

    private ScenarioRow requireScenario(long tenantId, UUID scenarioId) {
        if (scenarioId == null) throw invalid("A scenario identifier is required.");
        return repository.scenario(tenantId, scenarioId)
                .orElseThrow(() -> notFound("The space-planning scenario was not found in this tenant."));
    }


    private String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String requireIdempotencyKey(String value) {
        String key = normalized(value);
        if (key == null || key.length() > 160) {
            throw invalid("A non-empty idempotency key of at most 160 characters is required.");
        }
        return key;
    }

    private static String correlation(String value) {
        String correlation = normalized(value);
        if (correlation != null && correlation.length() > 160) {
            throw invalid("The correlation identifier is too long.");
        }
        return correlation;
    }

    private static void requireReason(String value) {
        String reason = normalized(value);
        if (reason == null || reason.length() > 500) {
            throw invalid("A reason of at most 500 characters is required.");
        }
    }

    private static void requireScenarioText(String name, String description) {
        if (normalized(name) == null || name.trim().length() > 160) {
            throw invalid("A scenario name of at most 160 characters is required.");
        }
        if (description != null && description.trim().length() > 1000) {
            throw invalid("The scenario description is too long.");
        }
    }

    private static void requireVersion(long version) {
        if (version < 1) throw invalid("A positive scenario version is required.");
    }

    private static void requireConfirmation(boolean confirmed) {
        if (!confirmed) throw invalid("Explicit confirmation is required.");
    }

    private static void requireTenant(long tenantId) {
        if (tenantId < 1) throw new BaseException(ErrorCode.TENANT_MISSING,
                "A positive tenant identifier is required.");
    }

    private static void requireActor(long tenantId, long actorId) {
        requireTenant(tenantId);
        if (actorId < 1) throw new BaseException(ErrorCode.UNAUTHORIZED,
                "A positive actor identifier is required.");
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

}
