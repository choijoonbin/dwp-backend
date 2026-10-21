package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CatalogPage;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RetrySelection;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RunRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.ScheduleRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.VersionRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.SignedArtifactVerifierPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;

/** Transactional owner for the closed SYS-006 control-plane operations. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.automation-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class AutomationService {

    static final String CREATE_DEFINITION =
            "hris.action.sys.post.platform.v1.admin.hris.automations";
    static final String QUEUE_RUN =
            "hris.action.sys.post.platform.v1.admin.hris.automations.resource.runs";
    static final String RETRY_RUN =
            "hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.retry";
    static final String CREATE_VERSION =
            "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions";
    static final String VALIDATE_VERSION = CREATE_VERSION + ".resource.validate";
    static final String SUBMIT_VERSION = CREATE_VERSION + ".resource.submit";
    static final String APPROVE_VERSION = CREATE_VERSION + ".resource.approve";
    static final String ACTIVATE_VERSION = CREATE_VERSION + ".resource.activate";
    static final String CREATE_SCHEDULE =
            "hris.action.sys.post.platform.v1.admin.hris.automations.resource.schedules";
    static final String SUBMIT_SCHEDULE =
            "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.submit";
    static final String APPROVE_SCHEDULE =
            "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.approve";
    static final String ACTIVATE_SCHEDULE =
            "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.activate";
    static final String SUSPEND_SCHEDULE =
            "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.suspend";
    static final String CANCEL_RUN =
            "hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.cancel";

    private static final String AUTHOR = "HRIS_CONFIG_AUTHOR";
    private static final String PUBLISHER = "HRIS_CONFIG_PUBLISHER";
    private static final String EXECUTOR = "HRIS_INTEGRATION_EXECUTOR";
    private static final Set<String> CATALOG_FIELDS = Set.of(
            "definitionPublicId", "automationKey", "ownerDomain",
            "derivedLifecycleState", "activeVersionNumber", "versionNumber",
            "lifecycleState", "handlerKey", "handlerContractVersion",
            "objectRevision", "schedulePublicId", "pinnedVersionNumber",
            "timezone", "nextFireAt");
    private static final String IDEMPOTENCY_POLICY = "REQUIRED_IDEMPOTENCY_KEY";
    private static final String STEP_UP_POLICY =
            "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED";

    private final AutomationRepository repository;
    private final AutomationHandlerRegistryPort registry;
    private final SignedArtifactVerifierPort verifier;
    private final AutomationCanonicalJson canonical;
    private final AutomationCursorCodec cursors;
    private final Clock clock;

    AutomationService(
            AutomationRepository repository,
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier,
            AutomationCursorCodec cursors,
            ObjectMapper json) {
        this(repository, registry, verifier, cursors, json, Clock.systemUTC());
    }

    AutomationService(
            AutomationRepository repository,
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier,
            AutomationCursorCodec cursors,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.verifier = verifier;
        this.cursors = cursors;
        this.canonical = new AutomationCanonicalJson(json);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    CatalogResponse catalog(
            Authority authority,
            OffsetDateTime asOf,
            List<String> fields,
            String freshness,
            int limit,
            String cursor,
            String filter,
            List<String> resourceTypes) {
        bind(authority);
        OffsetDateTime now = now();
        if (asOf == null || asOf.isAfter(now.plusMinutes(1))) {
            throw invalid("AUTOMATION_CATALOG_AS_OF_INVALID");
        }
        if (fields == null || fields.isEmpty() || fields.size() > CATALOG_FIELDS.size()
                || fields.stream().distinct().count() != fields.size()
                || !CATALOG_FIELDS.containsAll(fields)) {
            throw invalid("AUTOMATION_CATALOG_FIELDS_INVALID");
        }
        Set<String> allowedTypes = Set.of(
                "AUTOMATION_DEFINITION", "AUTOMATION_VERSION", "AUTOMATION_SCHEDULE");
        Set<String> types = resourceTypes == null || resourceTypes.isEmpty()
                ? allowedTypes : Set.copyOf(resourceTypes);
        if (!List.of("FRESH_ONLY", "ALLOW_STALE").contains(freshness)
                || limit < 1 || limit > 100
                || resourceTypes != null && (resourceTypes.isEmpty()
                || resourceTypes.size() > 3
                || resourceTypes.stream().distinct().count() != resourceTypes.size()
                || !allowedTypes.containsAll(resourceTypes))
                || (filter != null && (filter.isBlank() || filter.length() > 1000))) {
            throw invalid("AUTOMATION_CATALOG_QUERY_INVALID");
        }
        String queryDigest = canonical.digest(Map.of(
                "fields", fields.stream().sorted().toList(),
                "filter", filter == null ? "" : filter,
                "freshness", freshness,
                "resourceTypes", types.stream().sorted().toList()));
        AutomationCursorCodec.Binding binding = new AutomationCursorCodec.Binding(
                authority.tenantId(), authority.subjectPrincipalPublicId(),
                "SYS-API-017.GET", queryDigest, authority.purposeCode(),
                authority.authorizationRevision(), authority.fieldPolicyRevision(), asOf,
                "CATALOG", "-", limit, authority.revalidateAt());
        String after = cursor == null ? null : cursors.decode(cursor, binding).sortTuple();
        CatalogPage page = repository.catalog(
                authority.tenantId(), asOf, limit, types, filter, after);
        List<CatalogItem> projected = page.items()
                .stream().map(item -> actions(authority, item, Set.copyOf(fields)))
                .toList();
        String nextCursor = page.nextSortTuple() == null ? null : cursors.encode(
                new AutomationCursorCodec.Binding(
                        binding.tenantId(), binding.subjectPrincipalPublicId(),
                        binding.queryId(), binding.queryDigest(), binding.purposeCode(),
                        binding.authorizationRevision(), binding.fieldPolicyRevision(),
                        binding.asOf(), binding.collectionKind(), page.nextSortTuple(),
                        binding.pageLimit(), binding.expiresAt()));
        List<ActionDescriptor> collectionActions = authority.hasRole(AUTHOR)
                ? List.of(action(CREATE_DEFINITION,
                "AUTOMATION_DEFINITION_COLLECTION", null, null, null,
                "/api/platform/v1/admin/hris/automations")) : List.of();
        return new CatalogResponse(
                asOf, projected, nextCursor, authority.revalidateAt(),
                authority.revalidateAt(), collectionActions,
                new FieldDecisionSummary(
                        fields.stream().filter(field ->
                                !authority.maskedFields().contains(field)
                                        && !authority.omittedFields().contains(field))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        fields.stream().filter(authority.maskedFields()::contains)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        CATALOG_FIELDS.stream().filter(field -> !fields.contains(field)
                                        || authority.omittedFields().contains(field))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        authority.fieldPolicyRevision()),
                authority.authorizationRevision(), authority.correlationId());
    }

    @Transactional
    CommandReceipt createDefinition(
            Authority authority, CreateDefinitionCommand command) {
        bind(authority);
        CommandSeal seal = seal(authority, "CREATE_AUTOMATION_DEFINITION",
                CREATE_DEFINITION, "AUTOMATION_DEFINITION_COLLECTION", command, null);
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        DefinitionRecord definition = repository.insertDefinition(
                authority.tenantId(), authority.subjectPrincipalPublicId(), command, now);
        return repository.completeCommand(
                seal, accepted.receiptId(), definition.publicId(),
                definition.objectRevision(), definitionRef(definition.publicId()), now);
    }

    @Transactional
    CommandReceipt createVersion(
            Authority authority,
            UUID definitionPublicId,
            CreateVersionCommand command) {
        bind(authority);
        DefinitionRecord definition = repository.lockDefinition(
                authority.tenantId(), definitionPublicId);
        requireRevision(definition.objectRevision(), authority.expectedObjectRevision());
        VerifiedArtifact artifact = verifier.verifyDraft(command);
        requireDraftArtifact(command, artifact, now());
        CommandSeal seal = seal(authority, "CREATE_AUTOMATION_VERSION",
                CREATE_VERSION, target(definitionPublicId, null,
                        definition.objectRevision(), artifact.signedManifest().identity()),
                command, definition.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        VersionRecord version = repository.insertVersion(
                definition, authority.subjectPrincipalPublicId(), command, artifact, now);
        return repository.completeCommand(
                seal, accepted.receiptId(), definitionPublicId,
                version.objectRevision(), versionRef(definitionPublicId,
                        version.versionNumber()), now);
    }

    @Transactional
    CommandReceipt transitionVersion(
            Authority authority,
            UUID definitionPublicId,
            long versionNumber,
            LifecycleCommand command,
            String operation) {
        bind(authority);
        VersionRecord version = repository.lockVersion(
                authority.tenantId(), definitionPublicId, versionNumber);
        requireRevision(version.objectRevision(), authority.expectedObjectRevision());
        VersionState target = AutomationDomainPolicy.nextVersionState(
                version.state(), operation);
        if (target == VersionState.APPROVED) {
            AutomationDomainPolicy.requireMakerChecker(
                    version.authorActorRef(), authority.subjectPrincipalPublicId());
            requireStepUp(authority);
        }
        VerifiedArtifact current = null;
        if (target == VersionState.VALIDATED || target == VersionState.ACTIVE) {
            current = revalidate(version, now());
        }
        String action = switch (operation) {
            case "VALIDATE" -> VALIDATE_VERSION;
            case "SUBMIT" -> SUBMIT_VERSION;
            case "APPROVE" -> APPROVE_VERSION;
            case "ACTIVATE" -> ACTIVATE_VERSION;
            default -> throw invalid("AUTOMATION_VERSION_OPERATION_INVALID");
        };
        String type = switch (operation) {
            case "VALIDATE" -> "VALIDATE_AUTOMATION_VERSION";
            case "SUBMIT" -> "SUBMIT_AUTOMATION_VERSION";
            case "APPROVE" -> "APPROVE_AUTOMATION_VERSION";
            case "ACTIVATE" -> "ACTIVATE_AUTOMATION_VERSION";
            default -> throw invalid("AUTOMATION_VERSION_OPERATION_INVALID");
        };
        CommandSeal seal = seal(authority, type, action,
                target(definitionPublicId, versionNumber,
                        version.objectRevision(), version.approvedManifest().identity()),
                command, version.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        VersionRecord changed = repository.transitionVersion(
                version, target, authority.subjectPrincipalPublicId(),
                accepted.receiptId(), command.evidenceDigest(),
                authority.stepUpEvidenceDigest(), current, now);
        return repository.completeCommand(
                seal, accepted.receiptId(), definitionPublicId,
                changed.objectRevision(), versionRef(
                        definitionPublicId, versionNumber), now);
    }

    @Transactional
    CommandReceipt createSchedule(
            Authority authority,
            UUID definitionPublicId,
            CreateScheduleCommand command) {
        bind(authority);
        AutomationDomainPolicy.requireScheduleDefinition(
                command.expression(), command.timezone(),
                command.timezoneDatabaseRevision());
        DefinitionRecord definition = repository.lockDefinition(
                authority.tenantId(), definitionPublicId);
        requireRevision(definition.objectRevision(), authority.expectedObjectRevision());
        VersionRecord version = repository.lockVersion(
                authority.tenantId(), definitionPublicId,
                command.pinnedVersionNumber());
        CommandSeal seal = seal(authority, "CREATE_AUTOMATION_SCHEDULE",
                CREATE_SCHEDULE, target(definitionPublicId,
                        command.pinnedVersionNumber(), definition.objectRevision(),
                        version.approvedManifest().identity()), command,
                definition.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        ScheduleRecord schedule = repository.insertSchedule(
                definition, version, authority.subjectPrincipalPublicId(), command, now);
        return repository.completeCommand(
                seal, accepted.receiptId(), schedule.publicId(),
                schedule.objectRevision(), scheduleRef(schedule.publicId()), now);
    }

    @Transactional
    CommandReceipt transitionSchedule(
            Authority authority,
            UUID schedulePublicId,
            LifecycleCommand command,
            String operation) {
        bind(authority);
        ScheduleRecord schedule = repository.lockSchedule(
                authority.tenantId(), schedulePublicId);
        requireRevision(schedule.objectRevision(), authority.expectedObjectRevision());
        ScheduleState target = AutomationDomainPolicy.nextScheduleState(
                schedule.state(), operation);
        if (target == ScheduleState.APPROVED) {
            AutomationDomainPolicy.requireMakerChecker(
                    schedule.authorActorRef(), authority.subjectPrincipalPublicId());
            requireStepUp(authority);
        }
        if (target == ScheduleState.ACTIVE) {
            VersionRecord version = repository.lockVersion(
                    authority.tenantId(), schedule.definitionPublicId(),
                    schedule.pinnedVersionNumber());
            revalidate(version, now());
        }
        String action = switch (operation) {
            case "SUBMIT" -> SUBMIT_SCHEDULE;
            case "APPROVE" -> APPROVE_SCHEDULE;
            case "ACTIVATE" -> ACTIVATE_SCHEDULE;
            case "SUSPEND" -> SUSPEND_SCHEDULE;
            default -> throw invalid("AUTOMATION_SCHEDULE_OPERATION_INVALID");
        };
        String type = switch (operation) {
            case "SUBMIT" -> "SUBMIT_AUTOMATION_SCHEDULE";
            case "APPROVE" -> "APPROVE_AUTOMATION_SCHEDULE";
            case "ACTIVATE" -> "ACTIVATE_AUTOMATION_SCHEDULE";
            case "SUSPEND" -> "SUSPEND_AUTOMATION_SCHEDULE";
            default -> throw invalid("AUTOMATION_SCHEDULE_OPERATION_INVALID");
        };
        CommandSeal seal = seal(authority, type, action,
                target(schedulePublicId, null, schedule.objectRevision(), null),
                command, schedule.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        ScheduleRecord changed = repository.transitionSchedule(
                schedule, target, authority.subjectPrincipalPublicId(),
                accepted.receiptId(), command.evidenceDigest(),
                authority.stepUpEvidenceDigest(), now);
        return repository.completeCommand(
                seal, accepted.receiptId(), schedulePublicId,
                changed.objectRevision(), scheduleRef(schedulePublicId), now);
    }

    @Transactional
    CommandReceipt queueRun(
            Authority authority,
            UUID definitionPublicId,
            QueueRunCommand command) {
        bind(authority);
        DefinitionRecord definition = repository.lockDefinition(
                authority.tenantId(), definitionPublicId);
        requireRevision(definition.objectRevision(), authority.expectedObjectRevision());
        if (!"ACTIVE".equals(definition.lifecycleState())
                || definition.activeVersionNumber() == null) {
            throw conflict("AUTOMATION_DEFINITION_NOT_EXECUTABLE");
        }
        VersionRecord version = repository.lockVersion(
                authority.tenantId(), definitionPublicId,
                definition.activeVersionNumber());
        VerifiedArtifact artifact = revalidate(version, now());
        AutomationHandlerManifestV1 manifest = artifact.signedManifest().manifest();
        if (!manifest.allowedScopeTypes().contains(command.scopeType())) {
            throw forbidden("AUTOMATION_SCOPE_NOT_ALLOWED");
        }
        String concurrencyDigest = AutomationDomainPolicy.renderConcurrencyKey(
                version.manualConcurrencyKeyTemplate(), authority.tenantId(),
                definitionPublicId, command.scopeType(), command.scopeId());
        Map<String, Object> target = target(
                definitionPublicId, version.versionNumber(),
                definition.objectRevision(), artifact.signedManifest().identity());
        target.put("concurrencyKeyDigest", concurrencyDigest);
        CommandSeal seal = seal(authority, "QUEUE_AUTOMATION_RUN",
                QUEUE_RUN, target, command, definition.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        RunRecord run = repository.insertRun(
                definition, version, authority.subjectPrincipalPublicId(),
                accepted.receiptId(), command, concurrencyDigest, now);
        repository.appendRunEventAndAudit(seal, run, "QUEUED", now);
        return repository.completeCommand(
                seal, accepted.receiptId(), run.publicId(), run.objectRevision(),
                runRef(run.publicId()), now);
    }

    @Transactional
    CommandReceipt retryRun(
            Authority authority, UUID runPublicId, RetryRunCommand command) {
        bind(authority);
        RunRecord run = repository.lockRun(authority.tenantId(), runPublicId);
        requireRevision(run.objectRevision(), authority.expectedObjectRevision());
        VersionRecord version = repository.lockVersion(
                authority.tenantId(), run.definitionPublicId(), run.versionNumber());
        revalidate(version, now());
        RetrySelection selection = repository.retrySelection(run);
        if (!selection.selectedItemDigest().equals(command.evidenceDigest())) {
            throw conflict("AUTOMATION_RETRY_SELECTION_MISMATCH");
        }
        CommandSeal seal = seal(authority, "RETRY_AUTOMATION_RUN",
                RETRY_RUN, target(runPublicId, null, run.objectRevision(),
                        version.approvedManifest().identity()), command,
                run.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        RunRecord changed = repository.retryRun(
                run, authority.subjectPrincipalPublicId(), accepted.receiptId(),
                selection.mode(), selection.selectedItemCount(),
                selection.selectedItemDigest(), now);
        repository.appendRunEventAndAudit(seal, changed, "RETRY_QUEUED", now);
        return repository.completeCommand(
                seal, accepted.receiptId(), runPublicId, changed.objectRevision(),
                runRef(runPublicId), now);
    }

    @Transactional
    CommandReceipt cancelRun(
            Authority authority, UUID runPublicId, CancelRunCommand command) {
        bind(authority);
        RunRecord run = repository.lockRun(authority.tenantId(), runPublicId);
        requireRevision(run.objectRevision(), authority.expectedObjectRevision());
        CommandSeal seal = seal(authority, "CANCEL_AUTOMATION_RUN",
                CANCEL_RUN, target(runPublicId, null, run.objectRevision(), null),
                command, run.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        RunRecord changed = repository.cancelRun(run, command.reason(), now);
        repository.appendRunEventAndAudit(seal, changed, "CANCEL_ACCEPTED", now);
        return repository.completeCommand(
                seal, accepted.receiptId(), runPublicId, changed.objectRevision(),
                runRef(runPublicId), now);
    }

    @Transactional(readOnly = true)
    RunDetailResponse runDetail(
            Authority authority,
            UUID runPublicId,
            OffsetDateTime asOf,
            String freshness,
            int limit,
            String attemptCursor,
            String itemCursor,
            String checkpointCursor) {
        bind(authority);
        if (asOf == null || asOf.isAfter(now().plusMinutes(1))
                || !List.of("FRESH_ONLY", "ALLOW_STALE").contains(freshness)
                || limit < 1 || limit > 100) {
            throw invalid("AUTOMATION_RUN_DETAIL_QUERY_INVALID");
        }
        String queryDigest = canonical.digest(Map.of(
                "runPublicId", runPublicId,
                "freshness", freshness,
                "projectionVersion", "SYS-API-019.v1"));
        java.util.function.Function<String, AutomationCursorCodec.Binding> expected = kind ->
                new AutomationCursorCodec.Binding(
                        authority.tenantId(), authority.subjectPrincipalPublicId(),
                        "SYS-API-019.GET", queryDigest, authority.purposeCode(),
                        authority.authorizationRevision(), null, asOf, kind, "-", limit,
                        authority.revalidateAt());
        String attemptAfter = attemptCursor == null ? null
                : cursors.decode(attemptCursor, expected.apply("ATTEMPT")).sortTuple();
        String itemAfter = itemCursor == null ? null
                : cursors.decode(itemCursor, expected.apply("ITEM")).sortTuple();
        String checkpointAfter = checkpointCursor == null ? null
                : cursors.decode(checkpointCursor,
                expected.apply("CHECKPOINT")).sortTuple();
        RunDetailResponse response = repository.runDetail(
                authority.tenantId(), runPublicId, asOf, limit,
                attemptAfter == null ? null : parsePositiveInt(attemptAfter),
                itemAfter == null ? null : parsePositiveLong(itemAfter), checkpointAfter,
                authority.authorizationRevision(), authority.correlationId());
        List<ActionDescriptor> actions = new ArrayList<>();
        boolean retryManifestCurrent = response.retryEligibleItemCount() > 0
                && repository.readVersion(authority.tenantId(),
                        response.definitionPublicId(), response.versionNumber())
                .filter(version -> version.state() == VersionState.ACTIVE)
                .map(this::currentVersion)
                .orElse(false);
        long retryEligibleItemCount = retryManifestCurrent
                ? response.retryEligibleItemCount() : 0;
        if (authority.hasRole(EXECUTOR)
                && List.of(RunState.PARTIAL, RunState.FAILED).contains(response.state())
                && response.retryBudgetRemaining() > 0
                && retryEligibleItemCount > 0) {
            actions.add(action(RETRY_RUN, "AUTOMATION_RUN", runPublicId,
                    null, response.objectRevision(),
                    "/api/platform/v1/admin/hris/automation-runs/"
                            + runPublicId + "/retry"));
        }
        if (authority.hasRole(EXECUTOR)
                && response.cancelEligibility() != CancelEligibility.INELIGIBLE
                && List.of(RunState.QUEUED, RunState.LEASED, RunState.RUNNING)
                .contains(response.state())) {
            actions.add(action(CANCEL_RUN, "AUTOMATION_RUN", runPublicId,
                    null, response.objectRevision(),
                    "/api/platform/v1/admin/hris/automation-runs/"
                            + runPublicId + "/cancel"));
        }
        return new RunDetailResponse(
                response.runPublicId(), response.definitionPublicId(),
                response.versionNumber(), response.state(), response.objectRevision(),
                response.selectionMode(), response.itemTotal(),
                response.itemSucceeded(), response.itemFailed(), response.outputDigest(),
                response.retryBudgetRemaining(), retryEligibleItemCount,
                response.cancelEligibility(), response.attempts(), response.items(),
                response.checkpoints(), encodeCursor(response.nextAttemptCursor(),
                expected.apply("ATTEMPT")), encodeCursor(response.nextItemCursor(),
                expected.apply("ITEM")), encodeCursor(response.nextCheckpointCursor(),
                expected.apply("CHECKPOINT")), actions,
                response.authorizationRevision(), response.correlationId());
    }

    private String encodeCursor(
            String sortTuple, AutomationCursorCodec.Binding binding) {
        if (sortTuple == null) return null;
        return cursors.encode(new AutomationCursorCodec.Binding(
                binding.tenantId(), binding.subjectPrincipalPublicId(), binding.queryId(),
                binding.queryDigest(), binding.purposeCode(),
                binding.authorizationRevision(), binding.fieldPolicyRevision(),
                binding.asOf(), binding.collectionKind(), sortTuple,
                binding.pageLimit(), binding.expiresAt()));
    }

    private static int parsePositiveInt(String value) {
        long parsed = parsePositiveLong(value);
        if (parsed > Integer.MAX_VALUE) throw invalid("AUTOMATION_CURSOR_INVALID");
        return (int) parsed;
    }

    private static long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw invalid("AUTOMATION_CURSOR_INVALID");
        }
    }

    private CatalogItem actions(
            Authority authority, CatalogItem item, Set<String> requestedFields) {
        List<ActionDescriptor> actions = new ArrayList<>();
        Set<String> persisted = java.util.Optional.ofNullable(
                        repository.eligibleCatalogActions(
                                authority.tenantId(),
                                authority.subjectPrincipalPublicId(), item))
                .orElse(Set.of());
        if (item instanceof DefinitionItem definition) {
            UUID definitionId = (UUID) definition.definitionPublicId();
            long revision = ((Number) definition.objectRevision()).longValue();
            if (authority.hasRole(AUTHOR)
                    && persisted.contains(CREATE_VERSION)) {
                actions.add(action(CREATE_VERSION, "AUTOMATION_DEFINITION",
                        definitionId, null, revision,
                        "/api/platform/v1/admin/hris/automations/"
                                + definitionId + "/versions"));
            }
            if (authority.hasRole(AUTHOR)
                    && persisted.contains(CREATE_SCHEDULE)
                    && currentDefinitionVersion(authority, definitionId, false)) {
                actions.add(action(CREATE_SCHEDULE, "AUTOMATION_DEFINITION",
                        definitionId, null, revision,
                        "/api/platform/v1/admin/hris/automations/"
                                + definitionId + "/schedules"));
            }
            if (authority.hasRole(EXECUTOR) && persisted.contains(QUEUE_RUN)
                    && currentDefinitionVersion(authority, definitionId, true)) {
                actions.add(action(QUEUE_RUN, "AUTOMATION_DEFINITION",
                        definitionId, null, revision,
                        "/api/platform/v1/admin/hris/automations/"
                                + definitionId + "/runs"));
            }
            Object projectedId = project("definitionPublicId", definitionId,
                    requestedFields, authority);
            Object projectedKey = project("automationKey", definition.automationKey(),
                    requestedFields, authority);
            Object projectedOwner = project("ownerDomain", definition.ownerDomain(),
                    requestedFields, authority);
            Object projectedState = project("derivedLifecycleState",
                    definition.derivedLifecycleState(), requestedFields, authority);
            Object projectedActive = project("activeVersionNumber",
                    definition.activeVersionNumber(), requestedFields, authority);
            Object projectedRevision = project("objectRevision", revision,
                    requestedFields, authority);
            if (hidden(projectedId, projectedKey, projectedOwner, projectedState,
                    projectedActive, projectedRevision)) actions.clear();
            return new DefinitionItem(
                    definition.resourceType(), projectedId, projectedKey, projectedOwner,
                    projectedState, projectedActive, projectedRevision, actions);
        }
        if (item instanceof VersionItem version) {
            UUID definitionId = (UUID) version.definitionPublicId();
            long versionNumber = ((Number) version.versionNumber()).longValue();
            long revision = ((Number) version.objectRevision()).longValue();
            VersionState lifecycle = (VersionState) version.lifecycleState();
            String action = null;
            if (authority.hasRole(AUTHOR) && lifecycle == VersionState.DRAFT) {
                action = VALIDATE_VERSION;
            } else if (authority.hasRole(AUTHOR)
                    && lifecycle == VersionState.VALIDATED) {
                action = SUBMIT_VERSION;
            } else if (authority.hasRole(PUBLISHER)
                    && lifecycle == VersionState.PENDING_APPROVAL) {
                action = APPROVE_VERSION;
            } else if (authority.hasRole(EXECUTOR)
                    && lifecycle == VersionState.APPROVED) {
                action = ACTIVATE_VERSION;
            }
            if (action != null && persisted.contains(action)
                    && currentVersion(authority, definitionId, versionNumber)) {
                actions.add(action(action, "AUTOMATION_VERSION",
                        definitionId, versionNumber, revision,
                        versionHref(definitionId, versionNumber, action)));
            }
            Object projectedId = project("definitionPublicId", definitionId,
                    requestedFields, authority);
            Object projectedNumber = project("versionNumber", versionNumber,
                    requestedFields, authority);
            Object projectedState = project("lifecycleState", lifecycle,
                    requestedFields, authority);
            Object projectedKey = project("handlerKey", version.handlerKey(),
                    requestedFields, authority);
            Object projectedContract = project("handlerContractVersion",
                    version.handlerContractVersion(), requestedFields, authority);
            Object projectedRevision = project("objectRevision", revision,
                    requestedFields, authority);
            if (hidden(projectedId, projectedNumber, projectedState, projectedKey,
                    projectedContract, projectedRevision)) actions.clear();
            return new VersionItem(
                    version.resourceType(), projectedId, projectedNumber, projectedState,
                    projectedKey, projectedContract, projectedRevision, actions);
        }
        ScheduleItem schedule = (ScheduleItem) item;
        UUID scheduleId = (UUID) schedule.schedulePublicId();
        UUID definitionId = (UUID) schedule.definitionPublicId();
        long pinnedVersion = ((Number) schedule.pinnedVersionNumber()).longValue();
        long revision = ((Number) schedule.objectRevision()).longValue();
        ScheduleState lifecycle = (ScheduleState) schedule.lifecycleState();
        String action = null;
        if (authority.hasRole(AUTHOR) && lifecycle == ScheduleState.DRAFT) {
            action = SUBMIT_SCHEDULE;
        } else if (authority.hasRole(PUBLISHER)
                && lifecycle == ScheduleState.PENDING_APPROVAL) {
            action = APPROVE_SCHEDULE;
        } else if (authority.hasRole(EXECUTOR)
                && lifecycle == ScheduleState.APPROVED) {
            action = ACTIVATE_SCHEDULE;
        } else if (authority.hasRole(EXECUTOR)
                && lifecycle == ScheduleState.ACTIVE) {
            action = SUSPEND_SCHEDULE;
        }
        boolean safetySuspend = SUSPEND_SCHEDULE.equals(action);
        if (action != null && persisted.contains(action)
                && (safetySuspend
                || currentVersion(authority, definitionId, pinnedVersion))) {
            actions.add(action(action, "AUTOMATION_SCHEDULE",
                    scheduleId, null, revision, scheduleHref(scheduleId, action)));
        }
        Object projectedSchedule = project("schedulePublicId", scheduleId,
                requestedFields, authority);
        Object projectedDefinition = project("definitionPublicId", definitionId,
                requestedFields, authority);
        Object projectedVersion = project("pinnedVersionNumber", pinnedVersion,
                requestedFields, authority);
        Object projectedState = project("lifecycleState", lifecycle,
                requestedFields, authority);
        Object projectedTimezone = project("timezone", schedule.timezone(),
                requestedFields, authority);
        Object projectedNext = project("nextFireAt", schedule.nextFireAt(),
                requestedFields, authority);
        Object projectedRevision = project("objectRevision", revision,
                requestedFields, authority);
        if (hidden(projectedSchedule, projectedDefinition, projectedVersion,
                projectedState, projectedTimezone, projectedNext, projectedRevision)) {
            actions.clear();
        }
        return new ScheduleItem(
                schedule.resourceType(), projectedSchedule, projectedDefinition,
                projectedVersion, projectedState, projectedTimezone, projectedNext,
                projectedRevision, actions);
    }

    private boolean currentDefinitionVersion(
            Authority authority, UUID definitionId, boolean activeOnly) {
        return repository.findEligibleVersion(
                        authority.tenantId(), definitionId, activeOnly)
                .filter(version -> !activeOnly || version.state() == VersionState.ACTIVE)
                .map(this::currentVersion)
                .orElse(false);
    }

    private boolean currentVersion(
            Authority authority, UUID definitionId, long versionNumber) {
        return repository.readVersion(
                        authority.tenantId(), definitionId, versionNumber)
                .map(this::currentVersion)
                .orElse(false);
    }

    private boolean currentVersion(VersionRecord version) {
        try {
            revalidate(version, now());
            return true;
        } catch (RuntimeException staleOrUnavailable) {
            return false;
        }
    }

    private static Object project(
            String field, Object value, Set<String> requested, Authority authority) {
        if (!requested.contains(field) || authority.omittedFields().contains(field)) {
            return new OmittedField();
        }
        if (authority.maskedFields().contains(field)) return new MaskedField();
        return value;
    }

    private static boolean hidden(Object... values) {
        return java.util.Arrays.stream(values).anyMatch(value ->
                value instanceof MaskedField || value instanceof OmittedField);
    }

    private VerifiedArtifact revalidate(VersionRecord version, OffsetDateTime now) {
        VerifiedArtifact current = verifier.revalidate(
                version.approvedManifest(), version.definitionDigest(),
                version.executionPolicyDigest(), version.compatibilityBaselineDigest());
        AutomationRuntimePorts.requireFresh(
                registry, current.signedManifest(), current.freshUntil(), now);
        if (!current.signedManifest().equals(version.approvedManifest())) {
            throw conflict("AUTOMATION_HANDLER_EVIDENCE_MISMATCH");
        }
        return current;
    }

    private void requireDraftArtifact(
            CreateVersionCommand command,
            VerifiedArtifact artifact,
            OffsetDateTime now) {
        AutomationHandlerManifestV1 manifest = artifact == null
                ? null : artifact.signedManifest().manifest();
        if (artifact == null || !artifact.freshUntil().isAfter(now)
                || !artifact.signedManifest().identity().equals(
                new AutomationHandlerRegistryPort.HandlerIdentity(
                        command.handlerOwnerService(), command.handlerKey(),
                        command.handlerContractVersion()))
                || artifact.signedManifest().manifestRevision()
                != command.handlerManifestRevision()
                || !artifact.signedManifest().signatureKeyId()
                .equals(command.signatureKeyId())
                || !artifact.signedManifest().inputSchemaDigest()
                .equals(command.inputSchemaDigest())
                || !artifact.signedManifest().receiptSchemaDigest()
                .equals(command.receiptSchemaDigest())
                || !artifact.signedManifest().compensationPolicyDigest()
                .equals(command.compensationPolicyDigest())
                || !manifest.inputSchemaRef().equals(command.inputSchemaRef())
                || !manifest.receiptSchemaRef().equals(command.receiptSchemaRef())
                || !manifest.signatureDigest().equals(command.signatureDigest())
                || !manifest.allowedScopeTypes().equals(command.allowedScopeTypes())
                || !manifest.retryMode().equals(command.retryMode().name())
                || manifest.maxAttempts() != command.maxAttempts()
                || !artifact.canonicalDocumentDigest()
                .equals(command.handlerManifestDigest())) {
            throw conflict("AUTOMATION_SIGNED_ARTIFACT_MISMATCH");
        }
    }

    private void bind(Authority authority) {
        if (authority == null || !authority.revalidateAt().isAfter(now())) {
            throw forbidden("AUTOMATION_CURRENT_AUTHORITY_REQUIRED");
        }
        repository.bindTenantPrincipal(
                authority.tenantId(), authority.subjectPrincipalPublicId());
    }

    private CommandReceipt replay(CommandSeal seal) {
        return repository.findReplay(seal).orElse(null);
    }

    private CommandSeal seal(
            Authority authority,
            String commandType,
            String originatingAction,
            Object target,
            Object body,
            Long expectedRevision) {
        Map<String, Object> digestMaterial = new LinkedHashMap<>();
        digestMaterial.put("originatingAction", originatingAction);
        digestMaterial.put("routeContractKey", authority.routeContractKey());
        digestMaterial.put("target", target);
        digestMaterial.put("expectedObjectRevision", expectedRevision);
        digestMaterial.put("body", body);
        return new CommandSeal(
                authority.tenantId(), authority.subjectPrincipalPublicId(),
                commandType, originatingAction, authority.routeContractKey(),
                authority.contextKey(), authority.contextScopeKey(),
                authority.decisionRevision(), authority.populationScopeDigest(),
                authority.fieldPolicyRevision(), authority.authorizationRevision(),
                authority.purposeCode(), authority.idempotencyKey(),
                canonical.digest(digestMaterial), authority.correlationId());
    }

    private static Map<String, Object> target(
            UUID publicId,
            Long version,
            long revision,
            AutomationHandlerRegistryPort.HandlerIdentity handler) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("publicId", publicId);
        target.put("version", version);
        target.put("objectRevision", revision);
        target.put("handler", handler);
        return target;
    }

    private static ActionDescriptor action(
            String key,
            String targetType,
            UUID publicId,
            Long version,
            Long revision,
            String href) {
        return new ActionDescriptor(
                "POST", key, targetType, publicId, version, revision,
                href, IDEMPOTENCY_POLICY, STEP_UP_POLICY);
    }

    private static String versionHref(UUID definition, long version, String action) {
        String suffix = action.substring(action.lastIndexOf('.') + 1);
        return "/api/platform/v1/admin/hris/automations/" + definition
                + "/versions/" + version + "/" + suffix;
    }

    private static String scheduleHref(UUID schedule, String action) {
        return "/api/platform/v1/admin/hris/automation-schedules/" + schedule
                + "/" + action.substring(action.lastIndexOf('.') + 1);
    }

    private static String definitionRef(UUID id) {
        return "/v1/admin/hris/automations/" + id;
    }

    private static String versionRef(UUID id, long version) {
        return definitionRef(id) + "/versions/" + version;
    }

    private static String scheduleRef(UUID id) {
        return "/v1/admin/hris/automation-schedules/" + id;
    }

    private static String runRef(UUID id) {
        return "/v1/admin/hris/automation-runs/" + id;
    }

    private static void requireRevision(long actual, Long expected) {
        if (expected == null || actual != expected) {
            throw conflict("AUTOMATION_OBJECT_REVISION_CONFLICT");
        }
    }

    private static void requireStepUp(Authority authority) {
        if (authority.stepUpEvidenceDigest() == null) {
            throw forbidden("AUTOMATION_STEP_UP_REQUIRED");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
