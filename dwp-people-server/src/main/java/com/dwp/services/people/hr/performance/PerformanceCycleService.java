package com.dwp.services.people.hr.performance;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class PerformanceCycleService {

    private static final Duration PREVIEW_TTL = Duration.ofHours(1);
    private static final String CREATE_ACTION = "performance.cycle.create";
    private static final String UPDATE_ACTION = "performance.cycle.update";
    private static final String VALIDATE_ACTION = "performance.cycle.validate";
    private static final String PREVIEW_ACTION = "performance.cycle.preview";
    private static final String PUBLISH_ACTION = "performance.cycle.publish";
    private static final String LIST_ACTION = "performance.cycle.list";
    private static final String READ_ACTION = "performance.cycle.read";
    private static final String RECEIPT_READ_ACTION = "performance.cycle.receipt.read";
    private static final String APPLICATION_ENTITLEMENT = "APP.HRIS";
    private static final String AUTHORITY_PURPOSE = "HRIS_PERFORMANCE_CYCLE";

    private final PerformanceCycleQueryRepository queries;
    private final PerformanceCycleCommandRepository commands;
    private final PerformanceParticipantPreviewService previewService;
    private final PerformanceCycleCanonicalizer canonicalizer;
    private final AuditOutboxRecorder audit;
    private final Clock clock;
    private final Supplier<PerformanceCycleAuthorityPort> authoritySupplier;

    @org.springframework.beans.factory.annotation.Autowired
    public PerformanceCycleService(
            PerformanceCycleQueryRepository queries,
            PerformanceCycleCommandRepository commands,
            PerformanceParticipantPreviewService previewService,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            AuditOutboxRecorder audit,
            org.springframework.beans.factory.ObjectProvider<PerformanceCycleAuthorityPort>
                    authorityProvider) {
        this(queries, commands, previewService,
                new PerformanceCycleCanonicalizer(objectMapper), audit, Clock.systemUTC(),
                authorityProvider::getIfAvailable);
    }

    PerformanceCycleService(
            PerformanceCycleQueryRepository queries,
            PerformanceCycleCommandRepository commands,
            PerformanceParticipantPreviewService previewService,
            PerformanceCycleCanonicalizer canonicalizer,
            AuditOutboxRecorder audit,
            Clock clock,
            Supplier<PerformanceCycleAuthorityPort> authoritySupplier) {
        this.queries = queries;
        this.commands = commands;
        this.previewService = previewService;
        this.canonicalizer = canonicalizer;
        this.audit = audit;
        this.clock = clock;
        this.authoritySupplier = authoritySupplier;
    }

    @Transactional(readOnly = true)
    public PerformanceCycleDtos.CycleCollection cycles() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        PerformanceCycleAuthorization.requireRead(actor);
        UUID subjectId = PerformanceCycleAuthorization.requireSubject(actor);
        requireAuthority(actor, subjectId, "VIEW", LIST_ACTION, null);
        List<PerformanceCycleDtos.CycleSummary> summaries = queries.cycles(actor.tenantId())
                .stream().map(cycle -> new PerformanceCycleDtos.CycleSummary(
                        cycle.cycleId(), cycle.cycleKey(), cycle.displayName(),
                        cycle.lifecycleState(), cycle.activeVersionNo(), cycle.aggregateVersion(),
                        cycle.version() == null ? null : cycle.version().effectiveFrom(),
                        cycle.version() == null ? null : cycle.version().effectiveTo(),
                        PerformanceCycleAuthorization.allowedActions(
                                actor, cycle.lifecycleState(),
                                cycle.version() == null ? null : cycle.version().authoredBy())))
                .toList();
        return new PerformanceCycleDtos.CycleCollection(
                summaries,
                PerformanceCycleAuthorization.allowedActions(actor, null, null));
    }

    @Transactional(readOnly = true)
    public PerformanceCycleDtos.CycleDetail cycle(UUID cycleId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        PerformanceCycleAuthorization.requireRead(actor);
        UUID subjectId = PerformanceCycleAuthorization.requireSubject(actor);
        requireAuthority(actor, subjectId, "VIEW", READ_ACTION, cycleId);
        return withActions(requireCycle(actor.tenantId(), cycleId), actor);
    }

    @Transactional
    public PerformanceCycleDtos.CycleCommandResult create(
            PerformanceCycleDtos.CreateCycleRequest request,
            String idempotencyKey,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        PerformanceCycleAuthorization.requireCreate(actor);
        UUID subjectId = PerformanceCycleAuthorization.requireSubject(actor);
        PerformanceCycleAuthorityPort.AuthorityEvidence authority = requireAuthority(
                actor, subjectId, "CREATE", CREATE_ACTION, null);
        String key = idempotencyKey(idempotencyKey);
        List<PerformanceCycleDtos.StageInput> stages = canonicalizer.validatedStages(
                request.effectiveFrom(), request.effectiveTo(), request.stages());
        String requestHash = commandHash(actor, CREATE_ACTION, null, request);
        Optional<PerformanceCycleDtos.CycleCommandResult> replay = replayCycle(
                actor, subjectId, key, CREATE_ACTION, request.commandId(), requestHash, null);
        if (replay.isPresent()) return replay.get();

        UUID cycleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String contentHash = canonicalizer.contentHash(
                request.displayName(), request.effectiveFrom(), request.effectiveTo(),
                request.timezoneId(), request.policyVersionId(),
                request.populationRuleVersionId(), stages);
        try {
            if (!reserveReceipt(actor, subjectId, request.commandId(), key,
                    "CREATE_DRAFT", CREATE_ACTION, cycleId, 0, requestHash, authority)) {
                return replayCycle(actor, subjectId, key, CREATE_ACTION,
                        request.commandId(), requestHash, null).orElseThrow(
                        () -> conflict("The command receipt winner is unavailable.", null));
            }
            commands.create(actor.tenantId(), actor.userId(), cycleId, versionId,
                    request, stages, contentHash);
            commands.insertAppliedMarker(
                    actor.tenantId(), request.commandId(), cycleId, 1, "CREATE_DRAFT");
            commands.completeReceipt(actor.tenantId(), request.commandId(), cycleId, 1);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The cycle key or command identity already exists.", exception);
        }
        audit(actor, "performance.cycle.created", cycleId, correlationId, null,
                request.commandId(), Map.of("cycleKey", request.cycleKey(), "versionNo", 1));
        return cycleResult(actor, cycleId, request.commandId());
    }

    @Transactional
    public PerformanceCycleDtos.CycleCommandResult update(
            UUID cycleId,
            PerformanceCycleDtos.UpdateCycleRequest request,
            String idempotencyKey,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        PerformanceCycleAuthorization.requireUpdate(actor);
        UUID subjectId = PerformanceCycleAuthorization.requireSubject(actor);
        PerformanceCycleAuthorityPort.AuthorityEvidence authority = requireAuthority(
                actor, subjectId, "UPDATE", UPDATE_ACTION, cycleId);
        String key = idempotencyKey(idempotencyKey);
        List<PerformanceCycleDtos.StageInput> stages = canonicalizer.validatedStages(
                request.effectiveFrom(), request.effectiveTo(), request.stages());
        String requestHash = commandHash(actor, UPDATE_ACTION, cycleId, request);
        Optional<PerformanceCycleDtos.CycleCommandResult> replay = replayCycle(
                actor, subjectId, key, UPDATE_ACTION, request.commandId(), requestHash, cycleId);
        if (replay.isPresent()) return replay.get();
        if (!reserveReceipt(actor, subjectId, request.commandId(), key,
                "UPDATE_DRAFT", UPDATE_ACTION, cycleId,
                request.expectedRevision(), requestHash, authority)) {
            return replayCycle(actor, subjectId, key, UPDATE_ACTION,
                    request.commandId(), requestHash, cycleId).orElseThrow(
                    () -> conflict("The command receipt winner is unavailable.", null));
        }
        PerformanceCycleDtos.CycleDetail current = requireCycle(actor.tenantId(), cycleId);
        requireRevision(current, request.expectedRevision());
        if (current.version() == null || "RETIRED".equals(current.lifecycleState())) {
            throw invalidState("A retired or unversioned cycle cannot be updated.");
        }
        String contentHash = canonicalizer.contentHash(
                request.displayName(), request.effectiveFrom(), request.effectiveTo(),
                request.timezoneId(), request.policyVersionId(),
                request.populationRuleVersionId(), stages);
        UUID versionId = commands.update(actor.tenantId(), actor.userId(), cycleId,
                current, request, stages, contentHash);
        long appliedVersion = request.expectedRevision() + 1;
        commands.insertAppliedMarker(actor.tenantId(), request.commandId(), cycleId,
                appliedVersion, "UPDATE_DRAFT");
        commands.completeReceipt(
                actor.tenantId(), request.commandId(), cycleId, appliedVersion);
        audit(actor, "performance.cycle.updated", cycleId, correlationId, null,
                request.commandId(), Map.of(
                        "cycleVersionId", versionId,
                        "successorDraft", "PUBLISHED".equals(current.version().versionState())));
        return cycleResult(actor, cycleId, request.commandId());
    }

    @Transactional
    public PerformanceCycleDtos.CycleCommandResult validate(
            UUID cycleId,
            PerformanceCycleDtos.ValidateCycleRequest request,
            String idempotencyKey,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        PerformanceCycleAuthorization.requireUpdate(actor);
        UUID subjectId = PerformanceCycleAuthorization.requireSubject(actor);
        PerformanceCycleAuthorityPort.AuthorityEvidence authority = requireAuthority(
                actor, subjectId, "UPDATE", VALIDATE_ACTION, cycleId);
        String key = idempotencyKey(idempotencyKey);
        String requestHash = commandHash(actor, VALIDATE_ACTION, cycleId, request);
        Optional<PerformanceCycleDtos.CycleCommandResult> replay = replayCycle(
                actor, subjectId, key, VALIDATE_ACTION, request.commandId(), requestHash, cycleId);
        if (replay.isPresent()) return replay.get();
        if (!reserveReceipt(actor, subjectId, request.commandId(), key,
                "VALIDATE_DRAFT", VALIDATE_ACTION, cycleId,
                request.expectedRevision(), requestHash, authority)) {
            return replayCycle(actor, subjectId, key, VALIDATE_ACTION,
                    request.commandId(), requestHash, cycleId).orElseThrow(
                    () -> conflict("The command receipt winner is unavailable.", null));
        }
        PerformanceCycleDtos.CycleDetail current = requireCycle(actor.tenantId(), cycleId);
        requireRevision(current, request.expectedRevision());
        if (!"DRAFT".equals(current.lifecycleState()) || current.version() == null) {
            throw invalidState("Only an active draft cycle can be validated.");
        }
        canonicalizer.validatedStages(
                current.version().effectiveFrom(), current.version().effectiveTo(),
                stageInputs(current.version().stages()));
        commands.validate(actor.tenantId(), actor.userId(), cycleId,
                current.version().cycleVersionId(), request.expectedRevision());
        long appliedVersion = request.expectedRevision() + 1;
        commands.insertAppliedMarker(actor.tenantId(), request.commandId(), cycleId,
                appliedVersion, "VALIDATE_DRAFT");
        commands.completeReceipt(
                actor.tenantId(), request.commandId(), cycleId, appliedVersion);
        audit(actor, "performance.cycle.validated", cycleId, correlationId, null,
                request.commandId(), Map.of("contentHash", current.version().contentHash()));
        return cycleResult(actor, cycleId, request.commandId());
    }

    @Transactional
    public PerformanceCycleDtos.PreviewCommandResult preview(
            UUID cycleId,
            PerformanceCycleDtos.PreviewPopulationRequest request,
            String idempotencyKey,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        PerformanceCycleAuthorization.requirePreview(actor);
        UUID subjectId = PerformanceCycleAuthorization.requireSubject(actor);
        PerformanceCycleAuthorityPort.AuthorityEvidence authority = requireAuthority(
                actor, subjectId, "UPDATE", PREVIEW_ACTION, cycleId);
        String key = idempotencyKey(idempotencyKey);
        String requestHash = commandHash(actor, PREVIEW_ACTION, cycleId, request);
        Optional<PerformanceCycleDtos.PreviewCommandResult> replay = replayPreview(
                actor, subjectId, key, cycleId, request.commandId(), requestHash);
        if (replay.isPresent()) return replay.get();
        if (!reserveReceipt(actor, subjectId, request.commandId(), key,
                "PREVIEW_PARTICIPANTS", PREVIEW_ACTION, cycleId,
                request.expectedRevision(), requestHash, authority)) {
            return replayPreview(actor, subjectId, key, cycleId,
                    request.commandId(), requestHash).orElseThrow(
                    () -> conflict("The command receipt winner is unavailable.", null));
        }
        PerformanceCycleDtos.CycleDetail current = requireCycle(actor.tenantId(), cycleId);
        requireRevision(current, request.expectedRevision());
        if (current.version() == null || !List.of("DRAFT", "VALIDATED")
                .contains(current.lifecycleState())) {
            throw invalidState("Participant preview requires a draft or validated cycle.");
        }
        PerformanceParticipantPreviewService.PreparedPreview prepared = previewService.prepare(
                actor.tenantId(), request.asOf(), current.version().populationRuleVersionId());
        UUID proposedPreviewId = UUID.randomUUID();
        UUID previewId = commands.createPreview(
                actor.tenantId(), actor.userId(), proposedPreviewId,
                current.version().cycleVersionId(), current.aggregateVersion(), prepared,
                clock.instant().plus(PREVIEW_TTL));
        commands.insertAppliedMarker(actor.tenantId(), request.commandId(), previewId,
                1, "PREVIEW_PARTICIPANTS");
        commands.completeReceipt(actor.tenantId(), request.commandId(), previewId, 1);
        audit(actor, "performance.cycle.population-previewed", cycleId,
                correlationId, null, request.commandId(), Map.of(
                        "populationPreviewId", previewId,
                        "populationRuleVersionId", prepared.populationRuleVersionId(),
                        "workforceSnapshotRevision", prepared.workforceSnapshotRevision(),
                        "participantCount", prepared.participantCount()));
        return previewResult(actor, previewId, request.commandId());
    }

    @Transactional
    public PerformanceCycleDtos.CycleCommandResult publish(
            UUID cycleId,
            PerformanceCycleDtos.PublishCycleRequest request,
            String idempotencyKey,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        PerformanceCycleAuthorization.requirePublish(actor);
        UUID subjectId = PerformanceCycleAuthorization.requireSubject(actor);
        PerformanceCycleAuthorityPort.AuthorityEvidence authority = requireAuthority(
                actor, subjectId, "APPROVE", PUBLISH_ACTION, cycleId);
        String key = idempotencyKey(idempotencyKey);
        String requestHash = commandHash(actor, PUBLISH_ACTION, cycleId, request);
        Optional<PerformanceCycleDtos.CycleCommandResult> replay = replayCycle(
                actor, subjectId, key, PUBLISH_ACTION, request.commandId(), requestHash, cycleId);
        if (replay.isPresent()) return replay.get();
        if (!reserveReceipt(actor, subjectId, request.commandId(), key,
                "PUBLISH", PUBLISH_ACTION, cycleId,
                request.expectedRevision(), requestHash, authority)) {
            return replayCycle(actor, subjectId, key, PUBLISH_ACTION,
                    request.commandId(), requestHash, cycleId).orElseThrow(
                    () -> conflict("The command receipt winner is unavailable.", null));
        }
        PerformanceCycleDtos.CycleDetail current = requireCycle(actor.tenantId(), cycleId);
        requireRevision(current, request.expectedRevision());
        if (!"VALIDATED".equals(current.lifecycleState()) || current.version() == null) {
            throw invalidState("Only a validated cycle can be published.");
        }
        if (actor.userId().equals(current.version().authoredBy())) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The cycle author cannot publish the same version.");
        }
        PerformanceCycleDtos.PopulationPreview preview = queries.preview(
                actor.tenantId(), request.populationPreviewId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!preview.cycleVersionId().equals(current.version().cycleVersionId())
                || !preview.populationRuleVersionId().equals(
                current.version().populationRuleVersionId())
                || preview.sourceCycleAggregateVersion() != current.aggregateVersion()
                || !"READY".equals(preview.state())
                || preview.expiresAt() == null
                || !clock.instant().isBefore(preview.expiresAt())
                || preview.workforceSnapshotRevision()
                != request.expectedWorkforceOwnerRevision()) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The population preview is stale or does not match this cycle version.");
        }
        commands.publish(actor.tenantId(), actor.userId(), cycleId,
                current.version().cycleVersionId(), request.expectedRevision(),
                request.publicationApprovalRef(), request.commandId(),
                preview.populationPreviewId(), preview.workforceSnapshotRevision(),
                current.version().contentHash(), current.version().effectiveFrom(),
                current.version().effectiveTo(), current.version().stages().stream()
                        .map(PerformanceCycleDtos.CycleStage::stageKey).toList());
        long appliedVersion = request.expectedRevision() + 1;
        commands.completeReceipt(
                actor.tenantId(), request.commandId(), cycleId, appliedVersion);
        audit(actor, "performance.cycle.published", cycleId, correlationId,
                request.reason().trim(), request.publicationApprovalRef(), Map.of(
                        "commandReceiptId", request.commandId(),
                        "cycleVersionId", current.version().cycleVersionId(),
                        "publicationApprovalRef", request.publicationApprovalRef(),
                        "populationPreviewId", preview.populationPreviewId(),
                        "workforceSnapshotRevision", preview.workforceSnapshotRevision()));
        return cycleResult(actor, cycleId, request.commandId());
    }

    @Transactional
    public PerformanceCycleDtos.CommandReceipt receipt(UUID receiptId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        PerformanceCycleAuthorization.requireRead(actor);
        UUID subjectId = PerformanceCycleAuthorization.requireSubject(actor);
        requireAuthority(actor, subjectId, "VIEW", RECEIPT_READ_ACTION, receiptId);
        PerformanceCycleQueryRepository.ReceiptRecord receipt = queries
                .receiptById(actor.tenantId(), receiptId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return queries.reconcileUnknown(actor.tenantId(), receipt);
    }

    private Optional<PerformanceCycleDtos.CycleCommandResult> replayCycle(
            PeopleRequestContext.Actor actor, UUID subjectId, String key,
            String action, UUID commandId, String requestHash, UUID requestedAggregateId) {
        return replay(actor, subjectId, key, action, commandId, requestHash,
                requestedAggregateId)
                .map(receipt -> new PerformanceCycleDtos.CycleCommandResult(
                        receipt.resultRef() == null ? null
                                : queries.cycle(actor.tenantId(), receipt.resultRef())
                                .map(cycle -> withActions(cycle, actor)).orElse(null),
                        receipt));
    }

    private Optional<PerformanceCycleDtos.PreviewCommandResult> replayPreview(
            PeopleRequestContext.Actor actor, UUID subjectId, String key,
            UUID requestedAggregateId, UUID commandId, String requestHash) {
        return replay(actor, subjectId, key, PREVIEW_ACTION, commandId, requestHash,
                requestedAggregateId)
                .map(receipt -> new PerformanceCycleDtos.PreviewCommandResult(
                        receipt.resultRef() == null ? null
                                : queries.preview(actor.tenantId(), receipt.resultRef()).orElse(null),
                        receipt));
    }

    private Optional<PerformanceCycleDtos.CommandReceipt> replay(
            PeopleRequestContext.Actor actor, UUID subjectId, String key,
            String action, UUID commandId, String requestHash, UUID requestedAggregateId) {
        Optional<PerformanceCycleQueryRepository.ReceiptRecord> existing =
                queries.receiptByIdempotency(actor.tenantId(), subjectId, action, key);
        if (existing.isEmpty()) return Optional.empty();
        PerformanceCycleQueryRepository.ReceiptRecord receipt = existing.get();
        if (!receipt.view().receiptId().equals(commandId)
                || !receipt.requestHash().equals(requestHash)
                || (requestedAggregateId != null
                && !requestedAggregateId.equals(receipt.view().aggregateId()))) {
            throw conflict("The idempotency key is already bound to a different command.", null);
        }
        return Optional.of(queries.reconcileUnknown(actor.tenantId(), receipt));
    }

    private boolean reserveReceipt(
            PeopleRequestContext.Actor actor, UUID subjectId, UUID receiptId,
            String key, String commandType, String action, UUID aggregateId,
            long expectedVersion, String requestHash,
            PerformanceCycleAuthorityPort.AuthorityEvidence authority) {
        return commands.insertAcceptedReceipt(
                actor.tenantId(), actor.userId(), subjectId, receiptId, key,
                commandType, action, aggregateId, expectedVersion, requestHash,
                authority);
    }

    private PerformanceCycleAuthorityPort.AuthorityEvidence requireAuthority(
            PeopleRequestContext.Actor actor,
            UUID subjectId,
            String action,
            String operation,
            UUID aggregateId) {
        PerformanceCycleAuthorityPort port = authoritySupplier.get();
        if (port == null) throw authorityUnavailable(null);
        PerformanceCycleAuthorityPort.AuthorityEvidence evidence;
        try {
            evidence = port.authorize(new PerformanceCycleAuthorityPort.AuthorityRequest(
                    actor.tenantId(), actor.userId(), subjectId,
                    APPLICATION_ENTITLEMENT, PerformanceCycleAuthorization.RESOURCE,
                    action, operation, AUTHORITY_PURPOSE, aggregateId));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw authorityUnavailable(exception);
        }
        if (evidence == null
                || evidence.tenantId() != actor.tenantId()
                || evidence.actorId() != actor.userId()
                || !subjectId.equals(evidence.subjectPrincipalPublicId())
                || !APPLICATION_ENTITLEMENT.equals(evidence.applicationEntitlement())
                || !PerformanceCycleAuthorization.RESOURCE.equals(evidence.resource())
                || !action.equals(evidence.action())
                || !operation.equals(evidence.operation())
                || !AUTHORITY_PURPOSE.equals(evidence.purposeCode())
                || evidence.populationScopeDigest() == null
                || !evidence.populationScopeDigest().matches("[0-9a-f]{64}")
                || evidence.fieldPolicyRevision() <= 0
                || evidence.authorizationRevision() <= 0) {
            throw authorityUnavailable(null);
        }
        return evidence;
    }

    private PerformanceCycleDtos.CycleCommandResult cycleResult(
            PeopleRequestContext.Actor actor, UUID cycleId, UUID receiptId) {
        return new PerformanceCycleDtos.CycleCommandResult(
                withActions(requireCycle(actor.tenantId(), cycleId), actor),
                queries.receiptById(actor.tenantId(), receiptId).orElseThrow().view());
    }

    private PerformanceCycleDtos.PreviewCommandResult previewResult(
            PeopleRequestContext.Actor actor, UUID previewId, UUID receiptId) {
        return new PerformanceCycleDtos.PreviewCommandResult(
                queries.preview(actor.tenantId(), previewId).orElseThrow(),
                queries.receiptById(actor.tenantId(), receiptId).orElseThrow().view());
    }

    private PerformanceCycleDtos.CycleDetail requireCycle(long tenantId, UUID cycleId) {
        return queries.cycle(tenantId, cycleId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private PerformanceCycleDtos.CycleDetail withActions(
            PerformanceCycleDtos.CycleDetail value,
            PeopleRequestContext.Actor actor) {
        return new PerformanceCycleDtos.CycleDetail(
                value.cycleId(), value.cycleKey(), value.displayName(), value.lifecycleState(),
                value.activeVersionNo(), value.aggregateVersion(), value.retentionPolicyId(),
                value.createdAt(), value.createdBy(), value.updatedAt(), value.updatedBy(),
                value.version(), PerformanceCycleAuthorization.allowedActions(
                actor, value.lifecycleState(),
                value.version() == null ? null : value.version().authoredBy()));
    }

    private List<PerformanceCycleDtos.StageInput> stageInputs(
            List<PerformanceCycleDtos.CycleStage> stages) {
        return stages.stream().map(stage -> new PerformanceCycleDtos.StageInput(
                stage.stageKey(), stage.stageType(), stage.sequenceNo(),
                stage.opensAt(), stage.closesAt(), stage.required(), stage.stageConfig())).toList();
    }

    private void requireRevision(PerformanceCycleDtos.CycleDetail cycle, long expected) {
        if (cycle.aggregateVersion() != expected) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The performance cycle revision changed; refresh and retry.");
        }
    }

    private String idempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Idempotency-Key must be nonblank and at most 200 characters.");
        }
        return value.trim();
    }

    private String commandHash(
            PeopleRequestContext.Actor actor,
            String action,
            UUID aggregateId,
            Object request) {
        return PerformanceCycleCanonicalizer.sha256(
                canonicalizer.requestHash(request)
                        + "|" + action
                        + "|" + actor.tenantId()
                        + "|" + actor.userId()
                        + "|" + actor.personPublicId()
                        + "|" + (aggregateId == null ? "" : aggregateId));
    }

    private BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private BaseException authorityUnavailable(Throwable cause) {
        String message = "Canonical APP.HRIS policy authority is unavailable; access is denied.";
        return cause == null
                ? new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message)
                : new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message, cause);
    }

    private BaseException conflict(String message, Throwable cause) {
        return cause == null
                ? new BaseException(ErrorCode.RESOURCE_CONFLICT, message)
                : new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }

    private void audit(
            PeopleRequestContext.Actor actor,
            String action,
            UUID cycleId,
            String correlationId,
            String reason,
            UUID evidenceId,
            Map<String, Object> metadata) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("performance.cycle.published".equals(action) ? "HIGH" : "MEDIUM")
                .riskScore("performance.cycle.published".equals(action) ? 80 : 45)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("hris-performance")
                .targetType("PERFORMANCE_CYCLE")
                .targetId(cycleId.toString())
                .reason(reason)
                .correlationId(correlationId)
                .approvalId(evidenceId.toString())
                .metadata(metadata)
                .retentionClass("EXTENDED")
                .build());
    }
}
