package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.HrisConfigurationPublishedV1;
import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySeals;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationRepository.*;

@Service
public class ReferenceConfigurationService {

    static final String PUBLISHED_EVENT = "dwp.hris.configuration.published.v1";
    static final String INVALIDATION_EVENT = "dwp.hris.access.assignment.changed.v1";
    static final String WORKBENCH_ROUTE = "/hr/settings/reference-data";
    static final String PURPOSE = "HRIS_SYSTEM_ADMIN";
    static final String UPDATE_PERMISSION = "CONFIG.HRIS_POLICY:UPDATE";
    static final String PUBLISH_PERMISSION = "ACTION.HRIS_POLICY_PUBLISH:PUBLISH";
    static final String VIEW_PERMISSION = "CONFIG.HRIS_WORKBENCH:VIEW";
    static final Set<String> FIELD_GROUPS = Set.of(
            "POLICY_VERSION", "REFERENCE_DATA", "EFFECTIVE_STATUS");
    static final Set<String> CATALOG_FIELDS = Set.of(
            "publicId", "key", "type", "state", "version", "updatedAt");
    static final Set<String> ALLOWED_SORTS = Set.of(
            "policyVersion:ASC", "referenceData:ASC", "effectiveStatus:ASC",
            "policyVersion:DESC", "referenceData:DESC", "effectiveStatus:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");

    private static final List<AvailableAction> AUTHOR_ACTIONS = List.of(
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.validate",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/validate"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.simulate",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/simulate"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.submit",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/submit"));
    private static final List<AvailableAction> PUBLISHER_ACTIONS = List.of(
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.approve",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/approve"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.publish",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/publish"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.schedule",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/schedule"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.rollback",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/rollback"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.retire",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/retire"));

    private final ReferenceConfigurationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ReferenceConfigurationService(
            ReferenceConfigurationRepository repository,
            ObjectMapper objectMapper) {
        this(repository, objectMapper, Clock.systemUTC());
    }

    ReferenceConfigurationService(
            ReferenceConfigurationRepository repository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CatalogResponse configurations(AccessContext access, int limit) {
        return configurations(access, new CatalogQuery(
                now(), null, null, List.copyOf(CATALOG_FIELDS),
                FreshnessPreference.FRESH_ONLY, limit));
    }

    @Transactional(readOnly = true)
    public CatalogResponse configurations(AccessContext access, CatalogQuery query) {
        requireManager(access);
        validateCatalogQuery(query);
        CatalogFilter filter = parseCatalogFilter(query.filter());
        authorizeCatalogQuery(access, query, filter);
        CursorState cursor = decodeCursor(query.cursor());
        OffsetDateTime asOf = effectiveAsOf(query.asOf(), cursor);
        String scopeRevision = scopeRevision(access);
        String queryDigest = digest(Map.of(
                "asOf", asOf,
                "filter", filter == null ? "" : filter,
                "fields", query.fields(),
                "freshness", query.freshness()));
        UUID seek = validateCursor(
                cursor, "CATALOG", access.tenantId(), scopeRevision, queryDigest, asOf);
        CatalogPage page = repository.queryCatalog(
                access.tenantId(), access.scope(), asOf, filter, seek, query.limit());
        if (!page.seekFound()) throw conflict("REFERENCE_CONFIGURATION_CURSOR_STALE");
        String nextCursor = page.hasNext() && !page.items().isEmpty()
                ? encodeCursor(new CursorState(
                        "CATALOG", access.tenantId(), scopeRevision, queryDigest, asOf,
                        page.items().getLast().publicId()))
                : null;
        boolean stale = asOf.isBefore(now().minusMinutes(5));
        if (stale && query.freshness() == FreshnessPreference.FRESH_ONLY) {
            throw conflict("REFERENCE_CONFIGURATION_FRESH_SNAPSHOT_REQUIRED");
        }
        List<CatalogItem> projectedItems = page.items().stream()
                .map(item -> projectCatalogItem(query.fields(), item))
                .toList();
        return new CatalogResponse(
                asOf,
                nextCursor,
                projectedItems,
                stale ? asOf : now().plusSeconds(30),
                asOf.plusMinutes(5));
    }

    @Transactional(readOnly = true)
    public VersionResponse version(
            AccessContext access,
            UUID configurationId,
            long version) {
        requireManager(access);
        return response(requiredVersion(access, configurationId, version));
    }

    @Transactional
    public CommandReceipt createVersion(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            CreateVersionCommand body) {
        requireAuthor(access);
        requireEffectiveWindow(body.effectiveFrom(), body.effectiveTo());
        if (!body.payload().reference().matches("^ref_[A-Za-z0-9_-]{8,248}$")) {
            throw invalid("REFERENCE_CONFIGURATION_OWNER_REFERENCE_INVALID");
        }
        return execute(
                access,
                command,
                configurationId,
                "CREATE_VERSION",
                "configuration.version.create",
                null,
                body,
                receiptId -> {
                    long version = repository.createVersion(
                            access.tenantId(), access.scope(), configurationId,
                            access.subjectId(), body, now());
                    return result(configurationId, version, 0);
                });
    }

    @Transactional
    public CommandReceipt validate(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            long version,
            ValidationCommand body) {
        requireAuthor(access);
        return transition(
                access, command, configurationId, version,
                "VALIDATE_VERSION", "configuration.version.validate", body,
                LifecycleState.DRAFT, LifecycleState.VALIDATED,
                (receiptId, detail) -> {
                    requireEffectiveWindow(detail.effectiveFrom(), detail.effectiveTo());
                    if (body.schemaRevision() != detail.schemaVersion()) {
                        throw conflict("REFERENCE_CONFIGURATION_SCHEMA_REVISION_MISMATCH");
                    }
                    if (!normalizeDigest(body.inputScopeDigest()).equals(access.scope().digest())) {
                        throw forbidden("REFERENCE_CONFIGURATION_SCOPE_EVIDENCE_MISMATCH");
                    }
                    OffsetDateTime producedAt = now();
                    String evidenceDigest = digest(Map.of(
                            "schemaKey", detail.schemaKey(),
                            "schemaRevision", detail.schemaVersion(),
                            "payloadDigest", detail.payloadDigest(),
                            "scopeDigest", access.scope().digest(),
                            "effectiveFrom", detail.effectiveFrom(),
                            "effectiveTo", nullable(detail.effectiveTo())));
                    return TransitionEvidence.validation(
                            access.subjectId(),
                            new ValidationFact(
                                    receiptId, evidenceDigest, body.schemaRevision(),
                                    access.scope().digest(), producedAt));
                },
                false);
    }

    @Transactional
    public CommandReceipt simulate(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            long version,
            SimulationCommand body) {
        requireAuthor(access);
        return transition(
                access, command, configurationId, version,
                "SIMULATE_VERSION", "configuration.version.simulate", body,
                LifecycleState.VALIDATED, LifecycleState.SIMULATED,
                (receiptId, detail) -> {
                    ValidationFact validation = requireValidation(detail, body.validationReceiptId());
                    OffsetDateTime producedAt = now();
                    String evidenceDigest = digest(Map.of(
                            "validationEvidence", validation.evidenceDigest(),
                            "sampleScopeRefDigest", digest(body.sampleScopeRef()),
                            "sampleScopeDigest", normalizeDigest(body.sampleScopeDigest()),
                            "payloadDigest", detail.payloadDigest(),
                            "aggregateVersion", detail.aggregateVersion()));
                    String impactDigest = digest(Map.of(
                            "simulationEvidence", evidenceDigest,
                            "effectiveFrom", detail.effectiveFrom(),
                            "effectiveTo", nullable(detail.effectiveTo()),
                            "domain", detail.domain().name()));
                    return TransitionEvidence.simulation(
                            access.subjectId(),
                            new SimulationFact(
                                    receiptId, validation.receiptId(), evidenceDigest, impactDigest,
                                    producedAt, producedAt.plusMinutes(15)));
                },
                false);
    }

    @Transactional
    public CommandReceipt submit(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            long version,
            SubmitCommand body) {
        requireAuthor(access);
        return transition(
                access, command, configurationId, version,
                "SUBMIT_VERSION", "configuration.version.submit", body,
                LifecycleState.SIMULATED, LifecycleState.PENDING_APPROVAL,
                (receiptId, detail) -> {
                    ValidationFact validation = requireValidation(detail, body.validationReceiptId());
                    SimulationFact simulation = requireSimulation(
                            detail, body.simulationReceiptId(), validation.receiptId());
                    if (now().isAfter(simulation.freshUntil())) {
                        throw conflict("REFERENCE_CONFIGURATION_SIMULATION_STALE");
                    }
                    if (!simulation.impactDigest().equals(normalizeDigest(body.impactDigest()))) {
                        throw conflict("REFERENCE_CONFIGURATION_IMPACT_EVIDENCE_MISMATCH");
                    }
                    OffsetDateTime producedAt = now();
                    String evidenceDigest = digest(Map.of(
                            "validationReceiptId", validation.receiptId(),
                            "validationEvidence", validation.evidenceDigest(),
                            "simulationReceiptId", simulation.receiptId(),
                            "simulationEvidence", simulation.evidenceDigest(),
                            "impactDigest", simulation.impactDigest(),
                            "approvalWorkflowKey", body.approvalWorkflowKey()));
                    return TransitionEvidence.submission(
                            access.subjectId(),
                            new SubmissionFact(
                                    receiptId, validation.receiptId(), simulation.receiptId(),
                                    evidenceDigest, body.approvalWorkflowKey(), producedAt));
                },
                false);
    }

    @Transactional
    public CommandReceipt approve(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            long version,
            ApprovalCommand body) {
        requirePublisher(access, command);
        return transition(
                access, command, configurationId, version,
                "APPROVE_VERSION", "configuration.version.approve", body,
                LifecycleState.PENDING_APPROVAL, LifecycleState.APPROVED,
                (receiptId, detail) -> {
                    requireDifferentActor(detail, access.subjectId());
                    SubmissionFact submission = requireSubmission(detail, body.approvalReceiptId());
                    requireStoredReceipt(
                            access.tenantId(), submission.receiptId(), "SUBMIT_VERSION",
                            configurationId, version);
                    OffsetDateTime producedAt = now();
                    String decisionDigest = normalizeDigest(body.approvalDecisionDigest());
                    String evidenceDigest = digest(Map.of(
                            "submissionReceiptId", submission.receiptId(),
                            "submittedEvidence", submission.evidenceDigest(),
                            "decisionDigest", decisionDigest,
                            "approver", access.subjectPublicId(),
                            "decisionRevision", access.scope().decisionRevision()));
                    return TransitionEvidence.approval(
                            access.subjectId(),
                            new ApprovalFact(
                                    receiptId, submission.receiptId(), decisionDigest,
                                    evidenceDigest, producedAt));
                },
                false);
    }

    @Transactional
    public CommandReceipt schedule(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            long version,
            ScheduleCommand body) {
        requirePublisher(access, command);
        requireEffectiveWindow(body.effectiveFrom(), body.effectiveTo());
        if (body.publishAt().isBefore(now()) || body.effectiveFrom().isBefore(body.publishAt())) {
            throw invalid("REFERENCE_CONFIGURATION_SCHEDULE_CLOCK_INVALID");
        }
        return transition(
                access, command, configurationId, version,
                "SCHEDULE_VERSION", "configuration.version.schedule", body,
                LifecycleState.APPROVED, LifecycleState.SCHEDULED,
                (receiptId, detail) -> {
                    requireDifferentActor(detail, access.subjectId());
                    requirePublishEvidence(detail, body.approvalReceiptId(), body.impactDigest());
                    return TransitionEvidence.schedule(
                            access.subjectId(), body.publishAt(),
                            body.effectiveFrom(), body.effectiveTo());
                },
                false);
    }

    @Transactional
    public CommandReceipt publish(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            long version,
            PublishCommand body) {
        requirePublisher(access, command);
        requireEffectiveWindow(body.effectiveFrom(), body.effectiveTo());
        VersionDetail detail = requiredVersion(access, configurationId, version);
        LifecycleState expected = detail.state() == LifecycleState.SCHEDULED
                ? LifecycleState.SCHEDULED
                : LifecycleState.APPROVED;
        return transition(
                access, command, configurationId, version,
                "PUBLISH_VERSION", "configuration.version.publish", body,
                expected, LifecycleState.PUBLISHED,
                (receiptId, fresh) -> {
                    requireDifferentActor(fresh, access.subjectId());
                    requirePublishEvidence(fresh, body.approvalReceiptId(), body.impactDigest());
                    if (now().isBefore(body.effectiveFrom())) {
                        throw conflict("REFERENCE_CONFIGURATION_EFFECTIVE_CLOCK_NOT_REACHED");
                    }
                    if (fresh.state() == LifecycleState.SCHEDULED) {
                        if (fresh.scheduledAt() == null || now().isBefore(fresh.scheduledAt())) {
                            throw conflict("REFERENCE_CONFIGURATION_SCHEDULE_NOT_DUE");
                        }
                        if (!fresh.effectiveFrom().isEqual(body.effectiveFrom())
                                || !java.util.Objects.equals(fresh.effectiveTo(), body.effectiveTo())) {
                            throw conflict("REFERENCE_CONFIGURATION_SCHEDULE_WINDOW_MISMATCH");
                        }
                    }
                    return TransitionEvidence.publish(
                            access.subjectId(), body.effectiveFrom(), body.effectiveTo());
                },
                true);
    }

    @Transactional
    public CommandReceipt rollback(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            long version,
            RollbackCommand body) {
        requirePublisher(access, command);
        if (body.targetPublishedVersion() >= version || body.effectiveFrom().isAfter(now())) {
            throw invalid("REFERENCE_CONFIGURATION_ROLLBACK_TARGET_INVALID");
        }
        return execute(
                access, command, configurationId,
                "ROLLBACK_VERSION", "configuration.version.rollback", version, body,
                receiptId -> {
                    OffsetDateTime activationTime = now();
                    VersionDetail current = requiredVersion(access, configurationId, version);
                    requireExpectedVersion(command, current.aggregateVersion());
                    requireDifferentActor(current, access.subjectId());
                    if (current.state() != LifecycleState.PUBLISHED) {
                        throw conflict("REFERENCE_CONFIGURATION_ROLLBACK_SOURCE_NOT_PUBLISHED");
                    }
                    requireActiveAt(current, activationTime,
                            "REFERENCE_CONFIGURATION_ROLLBACK_SOURCE_NOT_ACTIVE");
                    if (!activationTime.isAfter(current.effectiveFrom())) {
                        throw conflict("REFERENCE_CONFIGURATION_ROLLBACK_SOURCE_NOT_ACTIVE");
                    }
                    VersionDetail target = requiredVersion(
                            access, configurationId, body.targetPublishedVersion());
                    requireCompatibility(current, target);
                    if (target.state() != LifecycleState.PUBLISHED
                            && target.state() != LifecycleState.RETIRED) {
                        throw conflict("REFERENCE_CONFIGURATION_ROLLBACK_TARGET_NOT_PUBLISHED");
                    }
                    requireApproval(current, body.approvalReceiptId());
                    SimulationFact targetSimulation = target.simulation();
                    if (targetSimulation == null
                            || !targetSimulation.impactDigest().equals(
                                    normalizeDigest(body.impactDigest()))) {
                        throw conflict("REFERENCE_CONFIGURATION_ROLLBACK_IMPACT_MISMATCH");
                    }
                    long rollbackVersion;
                    try {
                        rollbackVersion = repository.rollback(
                                access.tenantId(), access.scope(), configurationId, version,
                                body.targetPublishedVersion(), current.aggregateVersion(),
                                access.subjectId(), body, targetSimulation.impactDigest(), activationTime);
                    } catch (IllegalStateException exception) {
                        throw conflict(exception.getMessage());
                    }
                    VersionDetail rolledBack = requiredVersion(
                            access, configurationId, rollbackVersion);
                    appendPublishedEvent(access, rolledBack);
                    return result(configurationId, rollbackVersion, rolledBack.aggregateVersion());
                });
    }

    @Transactional
    public CommandReceipt retire(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            long version,
            RetireCommand body) {
        requirePublisher(access, command);
        if (body.replacementVersion() == null || body.replacementVersion() == version) {
            throw invalid("REFERENCE_CONFIGURATION_SUCCESSOR_REQUIRED");
        }
        if (body.effectiveTo().isAfter(now())) {
            throw invalid("REFERENCE_CONFIGURATION_RETIRE_BOUNDARY_INVALID");
        }
        return execute(
                access, command, configurationId,
                "RETIRE_VERSION", "configuration.version.retire", version, body,
                receiptId -> {
                    OffsetDateTime activationTime = now();
                    VersionDetail current = requiredVersion(access, configurationId, version);
                    requireExpectedVersion(command, current.aggregateVersion());
                    requireDifferentActor(current, access.subjectId());
                    requireApproval(current, body.approvalReceiptId());
                    if (current.state() != LifecycleState.PUBLISHED
                            || !body.effectiveTo().isAfter(current.effectiveFrom())) {
                        throw conflict("REFERENCE_CONFIGURATION_RETIRE_SOURCE_NOT_PUBLISHED");
                    }
                    requireActiveAt(current, activationTime,
                            "REFERENCE_CONFIGURATION_RETIRE_SOURCE_NOT_ACTIVE");

                    VersionDetail successor = requiredVersion(
                            access, configurationId, body.replacementVersion());
                    requireCompatibility(current, successor);
                    requireDifferentActor(successor, access.subjectId());
                    if (successor.state() != LifecycleState.SCHEDULED
                            || successor.approval() == null
                            || successor.scheduledAt() == null
                            || successor.scheduledAt().isAfter(activationTime)
                            || !successor.effectiveFrom().isEqual(body.effectiveTo())
                            || (successor.effectiveTo() != null
                            && !successor.effectiveTo().isAfter(activationTime))) {
                        throw conflict("REFERENCE_CONFIGURATION_SUCCESSOR_BOUNDARY_MISMATCH");
                    }
                    boolean changed;
                    try {
                        changed = repository.retireAndActivateSuccessor(
                                access.tenantId(), access.scope(), configurationId,
                                version, current.aggregateVersion(), successor.version(),
                                successor.aggregateVersion(), body.effectiveTo(),
                                body.reasonCode(), activationTime);
                    } catch (IllegalStateException exception) {
                        throw conflict(exception.getMessage());
                    }
                    if (!changed) throw conflict("REFERENCE_CONFIGURATION_VERSION_CONFLICT");
                    VersionDetail activated = requiredVersion(
                            access, configurationId, successor.version());
                    appendPublishedEvent(access, activated);
                    VersionDetail retired = requiredVersion(access, configurationId, version);
                    return result(configurationId, version, retired.aggregateVersion());
                });
    }

    @Transactional(readOnly = true)
    public WorkbenchResponse workbench(AccessContext access, WorkbenchQuery query) {
        requireView(access);
        validateQuery(query);
        authorizeWorkbenchQuery(access, query);
        CursorState cursor = decodeCursor(query.cursor());
        OffsetDateTime asOf = effectiveAsOf(query.asOf(), cursor);
        String scopeRevision = scopeRevision(access);
        String queryDigest = workbenchQueryDigest(query, asOf);
        UUID seek = validateCursor(
                cursor, "WORKBENCH", access.tenantId(), scopeRevision, queryDigest, asOf);
        Freshness freshness = asOf.isBefore(now().minusSeconds(30))
                ? Freshness.STALE
                : Freshness.CURRENT;
        if (freshness == Freshness.STALE && query.freshness() == FreshnessPreference.FRESH_ONLY) {
            throw conflict("REFERENCE_CONFIGURATION_FRESH_SNAPSHOT_REQUIRED");
        }

        WorkbenchPage page;
        try {
            page = repository.queryWorkbench(
                    access.tenantId(), access.scope(), query, asOf, seek, query.limit());
        } catch (RuntimeException exception) {
            PartialFailure failure = new PartialFailure(
                    "reference-configuration",
                    "REFERENCE_CONFIGURATION_SOURCE_UNAVAILABLE",
                    query.correlationId(),
                    true);
            WorkbenchQueryState unavailable = new WorkbenchQueryState(
                    QueryState.UNAVAILABLE, scopeRevision, Freshness.UNAVAILABLE, null,
                    new FieldDecisionSummary(0, 0, 0), List.of(failure), query.correlationId(),
                    new CachePolicy(scopeRevision, INVALIDATION_EVENT, 0));
            return new WorkbenchResponse("1", asOf, "REFERENCE", unavailable, List.of());
        }
        if (!page.seekFound()) throw conflict("REFERENCE_CONFIGURATION_CURSOR_STALE");

        List<PartialFailure> failures = new ArrayList<>();
        List<WorkbenchItem> items = new ArrayList<>();
        for (WorkbenchRow row : page.rows()) {
            try {
                items.add(workbenchItem(access, query.fields(), row.item(), row.detail()));
            } catch (RuntimeException exception) {
                failures.add(new PartialFailure(
                        "reference-configuration",
                        "REFERENCE_CONFIGURATION_ITEM_UNAVAILABLE",
                        query.correlationId(),
                        true));
            }
        }
        String nextCursor = page.hasNext() && !page.rows().isEmpty()
                ? encodeCursor(new CursorState(
                        "WORKBENCH", access.tenantId(), scopeRevision, queryDigest, asOf,
                        page.rows().getLast().item().publicId()))
                : null;
        long viewCount = items.stream().flatMap(item -> item.fieldGroups().values().stream())
                .filter(value -> value.decision() == FieldDecision.VIEW).count();
        long maskedCount = items.stream().flatMap(item -> item.fieldGroups().values().stream())
                .filter(value -> value.decision() == FieldDecision.MASK).count();
        long omittedCount = (long) items.size() * FIELD_GROUPS.size() - viewCount - maskedCount;
        QueryState state = !failures.isEmpty()
                ? QueryState.PARTIAL
                : items.isEmpty() ? QueryState.EMPTY : QueryState.COMPLETE;
        WorkbenchQueryState queryState = new WorkbenchQueryState(
                state, scopeRevision, freshness, nextCursor,
                new FieldDecisionSummary(viewCount, maskedCount, omittedCount),
                List.copyOf(failures), query.correlationId(),
                new CachePolicy(scopeRevision, INVALIDATION_EVENT,
                        freshness == Freshness.CURRENT ? 30 : 0));
        return new WorkbenchResponse("1", asOf, "REFERENCE", queryState, List.copyOf(items));
    }

    private CommandReceipt transition(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            long version,
            String commandType,
            String action,
            Object body,
            LifecycleState expected,
            LifecycleState target,
            TransitionEvidenceFactory evidenceFactory,
            boolean emitPublishedEvent) {
        return execute(
                access, command, configurationId, commandType, action, version, body,
                receiptId -> {
                    VersionDetail detail = requiredVersion(access, configurationId, version);
                    requireExpectedVersion(command, detail.aggregateVersion());
                    TransitionEvidence evidence = evidenceFactory.create(receiptId, detail);
                    boolean changed = repository.transition(
                            access.tenantId(), access.scope(), configurationId, version,
                            detail.aggregateVersion(), expected, target, evidence, now());
                    if (!changed) throw conflict("REFERENCE_CONFIGURATION_VERSION_CONFLICT");
                    VersionDetail updated = requiredVersion(access, configurationId, version);
                    if (emitPublishedEvent) appendPublishedEvent(access, updated);
                    return result(configurationId, version, updated.aggregateVersion());
                });
    }

    private CommandReceipt execute(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            String commandType,
            String action,
            Long targetVersion,
            Object body,
            Function<UUID, MutationResult> mutation) {
        validateAccess(access);
        validateLegacyCommand(command);
        Map<String, Object> requestIdentity = new LinkedHashMap<>();
        requestIdentity.put("commandType", commandType);
        requestIdentity.put("configurationId", configurationId);
        if (targetVersion != null) requestIdentity.put("targetVersion", targetVersion);
        requestIdentity.put("body", body);
        requestIdentity.put("subjectPrincipalPublicId", access.subjectPublicId());
        requestIdentity.put("purposeCode", command.purposeCode());
        requestIdentity.put("populationScopeDigest", access.scope().digest());
        if (command.expectedObjectVersion() != null) {
            requestIdentity.put("expectedObjectVersion", command.expectedObjectVersion());
        }
        requestIdentity.put("authorizationContextDigest", authorizationContextDigest(access, command));
        String requestDigest = digest(requestIdentity);
        repository.lockIdempotency(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey());
        var prior = repository.findReceipt(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey());
        if (prior.isPresent()) {
            if (!prior.get().requestDigest().equals(requestDigest)) {
                throw conflict("IDEMPOTENCY_PAYLOAD_MISMATCH");
            }
            return prior.get().receipt();
        }

        validateCanonicalSeal(access, command, action);
        String authorityPopulationScopeDigest =
                HrisExplorerAuthoritySeals.populationScopeDigest(
                        access.contextScopeKey());
        UUID receiptId = UUID.randomUUID();
        MutationResult result = mutation.apply(receiptId);
        OffsetDateTime createdAt = now();
        long publicFieldPolicyRevision = HrisExplorerAuthoritySeals.publicRevision(
                access.fieldPolicyRevision());
        long publicAuthorizationRevision = HrisExplorerAuthoritySeals.publicRevision(
                access.authorizationRevision());
        CommandReceipt receipt = new CommandReceipt(
                receiptId, commandType, action, access.subjectPublicId(),
                access.scope().digest(), publicFieldPolicyRevision, command.purposeCode(),
                publicAuthorizationRevision, "SUCCEEDED", command.correlationId(),
                requestDigest, createdAt, result.aggregateVersion(),
                result.configurationId() + "/" + result.version());
        repository.saveReceipt(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey(),
                requestDigest, receipt);
        repository.saveCanonicalReceipt(access.tenantId(), new CanonicalReceipt(
                receipt.receiptId(), receipt.commandType(), receipt.originatingAction(),
                receipt.subjectPrincipalPublicId(),
                access.scope().digest(),
                authorityPopulationScopeDigest,
                publicFieldPolicyRevision, access.fieldPolicyRevision(),
                receipt.purposeCode(), publicAuthorizationRevision,
                access.authorizationRevision(), command.originRouteContractKey(),
                command.originContextKey(), command.originContextScopeKey(),
                command.originDecisionRevision(), command.idempotencyKey(),
                requestDigest, result.configurationId(), receipt.aggregateVersion(),
                receipt.status(), receipt.resultRef(), receipt.correlationId(),
                receipt.createdAt()));
        repository.appendAudit(
                access.tenantId(), configurationId, result.version(), action,
                access.subjectId(), command.correlationId().toString(), requestDigest, createdAt);
        return receipt;
    }

    private void appendPublishedEvent(AccessContext access, VersionDetail detail) {
        if (detail.approval() == null) {
            throw conflict("REFERENCE_CONFIGURATION_APPROVAL_EVIDENCE_MISSING");
        }
        OffsetDateTime publishedAt = now();
        HrisConfigurationPublishedV1 event = new HrisConfigurationPublishedV1(
                detail.configurationId(),
                "HRIS_REFERENCE_" + detail.domain().name(),
                detail.scopeType() == ScopeType.POLICY_NODE ? "POPULATION" : "LEGAL_ENTITY",
                detail.scopeId(),
                detail.version(),
                detail.effectiveFrom().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                detail.effectiveTo() == null
                        ? null
                        : detail.effectiveTo().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                payloadSchemaVersion(detail),
                detail.payloadDigest(),
                detail.approval().receiptId(),
                publishedAt.toInstant(),
                detail.version());
        String payload = json(event);
        try {
            HrisConfigurationPublishedV1 roundTrip = objectMapper.readValue(
                    payload, HrisConfigurationPublishedV1.class);
            if (!event.equals(roundTrip)) {
                throw new IllegalStateException("REFERENCE_CONFIGURATION_EVENT_ROUND_TRIP_MISMATCH");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "REFERENCE_CONFIGURATION_EVENT_ROUND_TRIP_FAILED", exception);
        }
        repository.appendOutbox(
                access.tenantId(), detail.configurationId(), detail.version(),
                PUBLISHED_EVENT, payload, publishedAt);
    }

    private WorkbenchItem workbenchItem(
            AccessContext access,
            List<String> requestedFields,
            CatalogItem item,
            VersionDetail detail) {
        Map<String, FieldValue<?>> groups = new LinkedHashMap<>();
        boolean identityVisible = fieldVisible(access, requestedFields, "POLICY_VERSION")
                && fieldVisible(access, requestedFields, "REFERENCE_DATA");
        boolean statusVisible = fieldVisible(access, requestedFields, "EFFECTIVE_STATUS");
        addField(access, requestedFields, groups, "POLICY_VERSION", new PolicyValue(
                identityVisible ? detail.schemaKey() : "RESTRICTED",
                detail.version(), detail.effectiveFrom().toLocalDate(),
                detail.effectiveTo() == null ? null : detail.effectiveTo().toLocalDate(),
                statusVisible ? detail.state().name() : "RESTRICTED"));
        addField(access, requestedFields, groups, "REFERENCE_DATA", new ReferenceValue(
                item.publicId().toString(), item.domain().name(),
                identityVisible ? item.key() : "RESTRICTED", item.version()));
        addField(access, requestedFields, groups, "EFFECTIVE_STATUS", new StatusValue(
                item.state().name(), item.version()));
        if (groups.isEmpty()) {
            throw forbidden("REFERENCE_CONFIGURATION_ALL_FIELDS_OMITTED");
        }
        return new WorkbenchItem(
                item.publicId().toString(),
                identityVisible ? item.key() : "Restricted reference configuration",
                statusVisible ? item.state().name() : "RESTRICTED",
                Map.copyOf(groups),
                new DeepLink(WORKBENCH_ROUTE, item.publicId().toString()),
                actions(access, groups));
    }

    private void addField(
            AccessContext access,
            List<String> requestedFields,
            Map<String, FieldValue<?>> groups,
            String key,
            Object value) {
        if (!requestedFields.contains(key) || access.omittedFieldGroups().contains(key)) return;
        if (access.maskedFieldGroups().contains(key)) {
            groups.put(key, FieldValue.masked("FIELD_POLICY", "hris.field.masked"));
        } else {
            groups.put(key, FieldValue.view(value));
        }
    }

    private List<AvailableAction> actions(
            AccessContext access,
            Map<String, FieldValue<?>> groups) {
        boolean configurationVisible = decision(groups, "POLICY_VERSION") == FieldDecision.VIEW
                && decision(groups, "REFERENCE_DATA") == FieldDecision.VIEW;
        boolean impactVisible = decision(groups, "EFFECTIVE_STATUS") == FieldDecision.VIEW;
        if (!configurationVisible) return List.of();
        List<AvailableAction> result = new ArrayList<>();
        Set<String> permissions = permissions(access.permissions());
        if (permissions.contains(UPDATE_PERMISSION)) result.addAll(AUTHOR_ACTIONS);
        if (impactVisible && permissions.contains(PUBLISH_PERMISSION)) result.addAll(PUBLISHER_ACTIONS);
        return List.copyOf(result);
    }

    private FieldDecision decision(Map<String, FieldValue<?>> groups, String key) {
        FieldValue<?> value = groups.get(key);
        return value == null ? FieldDecision.OMIT : value.decision();
    }

    private boolean fieldVisible(
            AccessContext access,
            List<String> requestedFields,
            String fieldGroup) {
        return requestedFields.contains(fieldGroup)
                && !access.maskedFieldGroups().contains(fieldGroup)
                && !access.omittedFieldGroups().contains(fieldGroup);
    }

    private CatalogItem projectCatalogItem(
            List<String> fields,
            CatalogItem item) {
        return new CatalogItem(
                item.publicId(),
                fields.contains("key") ? item.key() : null,
                fields.contains("type") ? item.domain() : null,
                fields.contains("state") ? item.state() : null,
                fields.contains("version") ? item.version() : null,
                fields.contains("updatedAt") ? item.updatedAt() : null);
    }

    private void authorizeCatalogQuery(
            AccessContext access,
            CatalogQuery query,
            CatalogFilter filter) {
        if (query.fields().contains("key")) {
            requireQueryFieldView(access, query.fields(), "key", "POLICY_VERSION",
                    "REFERENCE_CONFIGURATION_CATALOG_FIELD_POLICY_DENIED");
            requireQueryFieldView(access, query.fields(), "key", "REFERENCE_DATA",
                    "REFERENCE_CONFIGURATION_CATALOG_FIELD_POLICY_DENIED");
        }
        if (query.fields().contains("type")) {
            requireQueryFieldView(access, query.fields(), "type", "REFERENCE_DATA",
                    "REFERENCE_CONFIGURATION_CATALOG_FIELD_POLICY_DENIED");
        }
        if (query.fields().contains("state") || query.fields().contains("updatedAt")) {
            requirePolicyView(access, "EFFECTIVE_STATUS",
                    "REFERENCE_CONFIGURATION_CATALOG_FIELD_POLICY_DENIED");
        }
        if (query.fields().contains("version")) {
            requirePolicyView(access, "POLICY_VERSION",
                    "REFERENCE_CONFIGURATION_CATALOG_FIELD_POLICY_DENIED");
        }
        if (filter == null) return;
        if (filter.key() != null) {
            requireCatalogFilterView(access, query.fields(), "key", "POLICY_VERSION");
            requireCatalogFilterView(access, query.fields(), "key", "REFERENCE_DATA");
        }
        if (filter.domain() != null) {
            requireCatalogFilterView(access, query.fields(), "type", "REFERENCE_DATA");
        }
        if (filter.state() != null) {
            requireCatalogFilterView(access, query.fields(), "state", "EFFECTIVE_STATUS");
        }
        if (filter.minimumVersion() != null) {
            requireCatalogFilterView(access, query.fields(), "version", "POLICY_VERSION");
        }
    }

    private void requireCatalogFilterView(
            AccessContext access,
            List<String> fields,
            String wireField,
            String fieldGroup) {
        requireQueryFieldView(access, fields, wireField, fieldGroup,
                "REFERENCE_CONFIGURATION_CATALOG_FILTER_POLICY_DENIED");
    }

    private void authorizeWorkbenchQuery(AccessContext access, WorkbenchQuery query) {
        WorkbenchFilter filter = query.filter();
        if (notEmpty(filter.policyKeys())) {
            requireQueryFieldView(access, query.fields(), "POLICY_VERSION", "POLICY_VERSION",
                    "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
            requireQueryFieldView(access, query.fields(), "REFERENCE_DATA", "REFERENCE_DATA",
                    "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
        }
        if (filter.minimumPolicyRevision() != null) {
            requireQueryFieldView(access, query.fields(), "POLICY_VERSION", "POLICY_VERSION",
                    "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
        }
        if (notEmpty(filter.referencePublicIds()) || notEmpty(filter.referenceCodes())) {
            requireQueryFieldView(access, query.fields(), "REFERENCE_DATA", "REFERENCE_DATA",
                    "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
        }
        if (notEmpty(filter.effectiveStatuses()) || filter.minimumStatusRevision() != null
                || filter.effectiveOn() != null) {
            requireQueryFieldView(access, query.fields(), "EFFECTIVE_STATUS", "EFFECTIVE_STATUS",
                    "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
        }
        for (String sort : query.sort()) {
            if (sort.startsWith("policyVersion:")) {
                requireQueryFieldView(access, query.fields(), "POLICY_VERSION", "POLICY_VERSION",
                        "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
            } else if (sort.startsWith("referenceData:")) {
                requireQueryFieldView(access, query.fields(), "POLICY_VERSION", "POLICY_VERSION",
                        "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
                requireQueryFieldView(access, query.fields(), "REFERENCE_DATA", "REFERENCE_DATA",
                        "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
            } else if (sort.startsWith("effectiveStatus:")) {
                requireQueryFieldView(access, query.fields(), "EFFECTIVE_STATUS", "EFFECTIVE_STATUS",
                        "REFERENCE_CONFIGURATION_QUERY_FIELD_POLICY_DENIED");
            }
        }
    }

    private void requireQueryFieldView(
            AccessContext access,
            List<String> requestedFields,
            String requestedField,
            String fieldGroup,
            String error) {
        if (!requestedFields.contains(requestedField)) throw forbidden(error);
        requirePolicyView(access, fieldGroup, error);
    }

    private void requirePolicyView(AccessContext access, String fieldGroup, String error) {
        if (access.maskedFieldGroups().contains(fieldGroup)
                || access.omittedFieldGroups().contains(fieldGroup)) {
            throw forbidden(error);
        }
    }

    private void validateCatalogQuery(CatalogQuery query) {
        if (query == null || query.limit() < 1 || query.limit() > 100
                || query.fields() == null || query.fields().isEmpty()
                || !query.fields().contains("publicId")
                || query.freshness() == null
                || !CATALOG_FIELDS.containsAll(query.fields())
                || new LinkedHashSet<>(query.fields()).size() != query.fields().size()) {
            throw invalid("REFERENCE_CONFIGURATION_CATALOG_QUERY_INVALID");
        }
        if (query.filter() != null && query.filter().length() > 1000) {
            throw invalid("REFERENCE_CONFIGURATION_CATALOG_FILTER_INVALID");
        }
    }

    private CatalogFilter parseCatalogFilter(String value) {
        if (value == null || value.isBlank()) return null;
        String key = null;
        Domain domain = null;
        LifecycleState state = null;
        Long minimumVersion = null;
        Set<String> seen = new LinkedHashSet<>();
        for (String expression : value.split(";", -1)) {
            String[] pair = expression.split("==", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()
                    || !seen.add(pair[0])) {
                throw invalid("REFERENCE_CONFIGURATION_CATALOG_FILTER_INVALID");
            }
            try {
                switch (pair[0]) {
                    case "key" -> key = pair[1];
                    case "type" -> domain = Domain.valueOf(pair[1]);
                    case "state" -> state = LifecycleState.valueOf(pair[1]);
                    case "minimumVersion" -> minimumVersion = Long.parseLong(pair[1]);
                    default -> throw invalid("REFERENCE_CONFIGURATION_CATALOG_FILTER_INVALID");
                }
            } catch (IllegalArgumentException exception) {
                throw invalid("REFERENCE_CONFIGURATION_CATALOG_FILTER_INVALID");
            }
        }
        if (minimumVersion != null && minimumVersion < 1) {
            throw invalid("REFERENCE_CONFIGURATION_CATALOG_FILTER_INVALID");
        }
        return new CatalogFilter(key, domain, state, minimumVersion);
    }

    private void validateQuery(WorkbenchQuery query) {
        if (query == null || query.limit() == null || query.limit() < 1 || query.limit() > 100) {
            throw invalid("REFERENCE_CONFIGURATION_QUERY_INVALID");
        }
        if (query.filter() == null) throw invalid("REFERENCE_CONFIGURATION_FILTER_REQUIRED");
        validateFilterValues(query.filter());
        if (query.sort() == null || query.sort().isEmpty() || query.sort().size() > 3
                || new LinkedHashSet<>(query.sort()).size() != query.sort().size()
                || !ALLOWED_SORTS.containsAll(query.sort())) {
            throw invalid("REFERENCE_CONFIGURATION_SORT_INVALID");
        }
        if (query.fields() == null || query.fields().isEmpty()
                || !FIELD_GROUPS.containsAll(query.fields())
                || new LinkedHashSet<>(query.fields()).size() != query.fields().size()
                || query.freshness() == null) {
            throw invalid("REFERENCE_CONFIGURATION_FIELD_SELECTION_INVALID");
        }
        Locale locale = Locale.forLanguageTag(query.locale());
        if (locale.getLanguage().isBlank()) throw invalid("REFERENCE_CONFIGURATION_LOCALE_INVALID");
        try {
            ZoneId.of(query.timeZone());
        } catch (RuntimeException exception) {
            throw invalid("REFERENCE_CONFIGURATION_TIME_ZONE_INVALID");
        }
        if (query.correlationId() == null) {
            throw invalid("REFERENCE_CONFIGURATION_CORRELATION_REQUIRED");
        }
    }

    private void validateFilterValues(WorkbenchFilter filter) {
        validateList(filter.policyKeys(), 50, "^[A-Za-z0-9._:-]{1,128}$");
        validateList(filter.referencePublicIds(), 50, "^[A-Za-z0-9._:-]{1,128}$");
        validateList(filter.referenceCodes(), 50, "^[A-Z][A-Z0-9_]{1,63}$");
        validateList(filter.effectiveStatuses(), 50, "^[A-Z][A-Z0-9_]{1,63}$");
        if ((filter.minimumPolicyRevision() != null && filter.minimumPolicyRevision() < 1)
                || (filter.minimumStatusRevision() != null && filter.minimumStatusRevision() < 1)) {
            throw invalid("REFERENCE_CONFIGURATION_FILTER_INVALID");
        }
    }

    private void validateList(List<String> values, int max, String pattern) {
        if (values == null) return;
        if (values.size() > max || new LinkedHashSet<>(values).size() != values.size()
                || values.stream().anyMatch(value -> value == null || !value.matches(pattern))) {
            throw invalid("REFERENCE_CONFIGURATION_FILTER_INVALID");
        }
    }

    private String workbenchQueryDigest(WorkbenchQuery query, OffsetDateTime asOf) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("asOf", asOf);
        identity.put("filter", query.filter());
        identity.put("fields", query.fields());
        identity.put("freshness", query.freshness());
        identity.put("sort", query.sort());
        identity.put("locale", query.locale());
        identity.put("timeZone", query.timeZone());
        return digest(identity);
    }

    private OffsetDateTime effectiveAsOf(OffsetDateTime requested, CursorState cursor) {
        OffsetDateTime value = requested != null ? requested : cursor != null ? cursor.asOf() : now();
        if (value.isAfter(now().plusSeconds(1))) {
            throw invalid("REFERENCE_CONFIGURATION_AS_OF_INVALID");
        }
        return value;
    }

    private UUID validateCursor(
            CursorState cursor,
            String kind,
            long tenantId,
            String scopeRevision,
            String queryDigest,
            OffsetDateTime asOf) {
        if (cursor == null) return null;
        if (!kind.equals(cursor.kind())
                || cursor.tenantId() != tenantId
                || !scopeRevision.equals(cursor.scopeRevision())
                || !queryDigest.equals(cursor.queryDigest())
                || !asOf.isEqual(cursor.asOf())) {
            throw conflict("REFERENCE_CONFIGURATION_CURSOR_STALE");
        }
        return cursor.seekAfter();
    }

    private CursorState decodeCursor(String cursor) {
        if (cursor == null) return null;
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 7);
            if (parts.length != 7) throw invalid("REFERENCE_CONFIGURATION_CURSOR_INVALID");
            String unsigned = String.join("|", parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
            if (!digest(unsigned).equals(parts[6])) {
                throw invalid("REFERENCE_CONFIGURATION_CURSOR_INVALID");
            }
            return new CursorState(
                    parts[0], Long.parseLong(parts[1]), parts[2], parts[3],
                    OffsetDateTime.parse(parts[4]), UUID.fromString(parts[5]));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("REFERENCE_CONFIGURATION_CURSOR_INVALID");
        }
    }

    private String encodeCursor(CursorState state) {
        String unsigned = String.join("|",
                state.kind(), Long.toString(state.tenantId()), state.scopeRevision(),
                state.queryDigest(), state.asOf().toString(), state.seekAfter().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (unsigned + "|" + digest(unsigned)).getBytes(StandardCharsets.UTF_8));
    }

    String encodeCursor(
            long tenantId,
            String scopeRevision,
            String queryDigest,
            OffsetDateTime asOf,
            UUID itemPublicId) {
        return encodeCursor(new CursorState(
                "WORKBENCH", tenantId, scopeRevision, queryDigest, asOf, itemPublicId));
    }

    private ValidationFact requireValidation(VersionDetail detail, UUID receiptId) {
        if (detail.validation() == null || !detail.validation().receiptId().equals(receiptId)) {
            throw conflict("REFERENCE_CONFIGURATION_VALIDATION_EVIDENCE_MISMATCH");
        }
        return detail.validation();
    }

    private SimulationFact requireSimulation(
            VersionDetail detail,
            UUID receiptId,
            UUID validationReceiptId) {
        if (detail.simulation() == null
                || !detail.simulation().receiptId().equals(receiptId)
                || !detail.simulation().validationReceiptId().equals(validationReceiptId)) {
            throw conflict("REFERENCE_CONFIGURATION_SIMULATION_EVIDENCE_MISMATCH");
        }
        return detail.simulation();
    }

    private SubmissionFact requireSubmission(VersionDetail detail, UUID receiptId) {
        if (detail.submission() == null || !detail.submission().receiptId().equals(receiptId)) {
            throw conflict("REFERENCE_CONFIGURATION_SUBMITTED_EVIDENCE_MISMATCH");
        }
        return detail.submission();
    }

    private void requireStoredReceipt(
            long tenantId,
            UUID receiptId,
            String commandType,
            UUID configurationId,
            long version) {
        StoredReceipt stored = repository.findReceiptById(tenantId, receiptId)
                .orElseThrow(() -> conflict("REFERENCE_CONFIGURATION_RECEIPT_NOT_FOUND"));
        if (!commandType.equals(stored.receipt().commandType())
                || !(configurationId + "/" + version).equals(stored.receipt().resultRef())) {
            throw conflict("REFERENCE_CONFIGURATION_RECEIPT_EVIDENCE_MISMATCH");
        }
    }

    private void requirePublishEvidence(
            VersionDetail detail,
            UUID approvalReceiptId,
            String impactDigest) {
        requireApproval(detail, approvalReceiptId);
        if (detail.simulation() == null
                || !detail.simulation().impactDigest().equals(normalizeDigest(impactDigest))) {
            throw conflict("REFERENCE_CONFIGURATION_IMPACT_EVIDENCE_MISMATCH");
        }
    }

    private void requireApproval(VersionDetail detail, UUID approvalReceiptId) {
        if (detail.approval() == null
                || !detail.approval().receiptId().equals(approvalReceiptId)) {
            throw conflict("REFERENCE_CONFIGURATION_APPROVAL_EVIDENCE_MISMATCH");
        }
    }

    private void requireCompatibility(VersionDetail current, VersionDetail candidate) {
        if (current.domain() != candidate.domain()
                || current.scopeType() != candidate.scopeType()
                || !current.scopeId().equals(candidate.scopeId())
                || !current.schemaKey().equals(candidate.schemaKey())
                || current.schemaVersion() != candidate.schemaVersion()) {
            throw conflict("REFERENCE_CONFIGURATION_SCHEMA_INCOMPATIBLE");
        }
    }

    private void requireEffectiveWindow(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || (to != null && !to.isAfter(from))) {
            throw invalid("REFERENCE_CONFIGURATION_EFFECTIVE_WINDOW_INVALID");
        }
    }

    private void requireActiveAt(VersionDetail detail, OffsetDateTime instant, String error) {
        if (detail.effectiveFrom().isAfter(instant)
                || (detail.effectiveTo() != null && !detail.effectiveTo().isAfter(instant))) {
            throw conflict(error);
        }
    }

    private void requireDifferentActor(VersionDetail detail, long actorId) {
        if (detail.authoredBy() != null && detail.authoredBy() == actorId) {
            throw new BaseException(ErrorCode.SOD_CONFLICT, "NO_PUBLISH_OWN_POLICY");
        }
    }

    private void requireExpectedVersion(CommandContext command, long aggregateVersion) {
        if (command.expectedObjectVersion() == null
                || command.expectedObjectVersion() != aggregateVersion) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "REFERENCE_CONFIGURATION_OBJECT_VERSION_CONFLICT");
        }
    }

    private void requireView(AccessContext access) {
        validateAccess(access);
        Set<String> permissions = permissions(access.permissions());
        if (!permissions.contains(VIEW_PERMISSION)
                && !permissions.contains(UPDATE_PERMISSION)
                && !permissions.contains(PUBLISH_PERMISSION)) {
            throw forbidden("REFERENCE_CONFIGURATION_VIEW_DENIED");
        }
    }

    private void requireManager(AccessContext access) {
        validateAccess(access);
        Set<String> permissions = permissions(access.permissions());
        if (!permissions.contains(UPDATE_PERMISSION) && !permissions.contains(PUBLISH_PERMISSION)) {
            throw forbidden("REFERENCE_CONFIGURATION_MANAGER_DENIED");
        }
    }

    private void requireAuthor(AccessContext access) {
        validateAccess(access);
        if (!permissions(access.permissions()).contains(UPDATE_PERMISSION)) {
            throw forbidden("REFERENCE_CONFIGURATION_AUTHOR_DENIED");
        }
    }

    private void requirePublisher(AccessContext access, CommandContext command) {
        validateAccess(access);
        if (!permissions(access.permissions()).contains(PUBLISH_PERMISSION)) {
            throw forbidden("REFERENCE_CONFIGURATION_PUBLISHER_DENIED");
        }
        if (command.stepUpChallenge() == null || command.stepUpChallenge().isBlank()) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED, "REFERENCE_CONFIGURATION_STEP_UP_REQUIRED");
        }
        if (command.expectedDecisionRevision() == null
                || !command.expectedDecisionRevision().equals(access.scope().decisionRevision())) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "REFERENCE_CONFIGURATION_DECISION_REVISION_MISMATCH");
        }
    }

    private void validateAccess(AccessContext access) {
        if (access == null || access.tenantId() <= 0 || access.subjectId() <= 0
                || access.subjectPublicId() == null || access.scope() == null
                || access.scope().type() == null || access.scope().id() == null
                || access.scope().digest() == null
                || !access.scope().digest().matches("^[a-f0-9]{64}$")
                || access.scope().decisionRevision() == null
                || access.scope().decisionRevision().isBlank()
                || access.fieldPolicyRevision() < 1 || access.authorizationRevision() < 1
                || access.revalidateAt() == null || !access.revalidateAt().isAfter(now())
                || !PURPOSE.equals(access.purposeCode())) {
            throw forbidden("REFERENCE_CONFIGURATION_TRUSTED_CONTEXT_REQUIRED");
        }
        Set<String> roles = permissions(access.roles());
        if (roles.stream().anyMatch(value -> value.startsWith("PROVIDER"))) {
            throw forbidden("REFERENCE_CONFIGURATION_PROVIDER_IDENTITY_DENIED");
        }
        Set<String> permissions = permissions(access.permissions());
        if (!permissions.contains("APP.HCM:VIEW") && !permissions.contains("APP.HRIS:VIEW")) {
            throw forbidden("REFERENCE_CONFIGURATION_ENTITLEMENT_REQUIRED");
        }
        if (!FIELD_GROUPS.containsAll(access.maskedFieldGroups())
                || !FIELD_GROUPS.containsAll(access.omittedFieldGroups())
                || !java.util.Collections.disjoint(
                        access.maskedFieldGroups(), access.omittedFieldGroups())
                || access.omittedFieldGroups().containsAll(FIELD_GROUPS)) {
            throw forbidden("REFERENCE_CONFIGURATION_FIELD_POLICY_INVALID");
        }
    }

    private void validateLegacyCommand(CommandContext command) {
        if (command == null
                || command.idempotencyKey() == null
                || command.idempotencyKey().length() < 16
                || command.idempotencyKey().length() > 128
                || command.correlationId() == null
                || !PURPOSE.equals(command.purposeCode())) {
            throw invalid("REFERENCE_CONFIGURATION_COMMAND_CONTEXT_INVALID");
        }
    }

    private void validateCanonicalSeal(
            AccessContext access, CommandContext command, String action) {
        if (!originRoute(action).equals(command.originRouteContractKey())
                || access.contextKey() == null
                || !access.contextKey().equals(command.originContextKey())
                || access.contextScopeKey() == null
                || !access.contextScopeKey().equals(command.originContextScopeKey())
                || !access.scope().decisionRevision().equals(
                command.originDecisionRevision())) {
            throw invalid("REFERENCE_CONFIGURATION_COMMAND_CONTEXT_INVALID");
        }
    }

    private String originRoute(String action) {
        return switch (action) {
            case "configuration.version.create" ->
                    "route.hcm.configuration.configuration-version-create.action";
            case "configuration.version.validate" ->
                    "route.hcm.configuration.configuration-version-validate.action";
            case "configuration.version.simulate" ->
                    "route.hcm.configuration.configuration-version-simulate.action";
            case "configuration.version.submit" ->
                    "route.hcm.configuration.configuration-version-submit.action";
            case "configuration.version.approve" ->
                    "route.hcm.configuration.configuration-version-approve.action";
            case "configuration.version.schedule" ->
                    "route.hcm.configuration.configuration-version-schedule.action";
            case "configuration.version.publish" ->
                    "route.hcm.configuration.configuration-version-publish.action";
            case "configuration.version.rollback" ->
                    "route.hcm.configuration.configuration-version-rollback.action";
            case "configuration.version.retire" ->
                    "route.hcm.configuration.configuration-version-retire.action";
            default -> "";
        };
    }

    private VersionDetail requiredVersion(
            AccessContext access,
            UUID configurationId,
            long version) {
        return repository.findVersion(
                        access.tenantId(), access.scope(), configurationId, version)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_NOT_AVAILABLE,
                        "REFERENCE_CONFIGURATION_NOT_FOUND"));
    }

    private VersionResponse response(VersionDetail detail) {
        return new VersionResponse(
                detail.configurationId(), detail.version(), detail.state(),
                detail.effectiveFrom(), detail.effectiveTo(), detail.schemaKey(),
                detail.schemaVersion(), detail.payloadDigest(),
                detail.approval() == null ? null : detail.approval().receiptId(),
                detail.aggregateVersion());
    }

    private MutationResult result(
            UUID configurationId,
            long version,
            long aggregateVersion) {
        return new MutationResult(configurationId, version, aggregateVersion);
    }

    private String scopeRevision(AccessContext access) {
        return "scope-" + digest(Map.of(
                "scopeDecision", access.scope().decisionRevision(),
                "scopeDigest", access.scope().digest(),
                "fieldPolicyRevision", access.fieldPolicyRevision(),
                "authorizationRevision", access.authorizationRevision(),
                "masked", access.maskedFieldGroups().stream().sorted().toList(),
                "omitted", access.omittedFieldGroups().stream().sorted().toList()))
                .substring(0, 40);
    }

    private String authorizationContextDigest(AccessContext access, CommandContext command) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", access.tenantId());
        context.put("subjectPrincipalPublicId", access.subjectPublicId());
        context.put("populationScopeDigest", access.scope().digest());
        context.put("permissions", permissions(access.permissions()).stream().sorted().toList());
        context.put("roles", permissions(access.roles()).stream().sorted().toList());
        context.put("fieldPolicyRevision", access.fieldPolicyRevision());
        context.put("authorizationRevision", access.authorizationRevision());
        if (command.expectedDecisionRevision() != null) {
            context.put("decisionRevision", command.expectedDecisionRevision());
        }
        if (command.stepUpChallenge() != null) {
            context.put("stepUpProofDigest", digest(command.stepUpChallenge()));
        }
        return digest(context);
    }

    private Set<String> permissions(String value) {
        if (value == null || value.isBlank() || value.length() > 32_000
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.trim())) return Set.of();
            result.add(token.toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private boolean notEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private String payloadSchemaVersion(VersionDetail detail) {
        String candidate = detail.schemaKey();
        if (candidate.matches("^[A-Za-z][A-Za-z0-9._-]{0,127}\\.v[1-9][0-9]*$")) {
            return candidate;
        }
        String prefix = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
        if (prefix.isBlank() || !Character.isLetter(prefix.charAt(0))) prefix = "Hris_" + prefix;
        int maximumPrefix = 124 - Integer.toString(detail.schemaVersion()).length();
        if (prefix.length() > maximumPrefix) prefix = prefix.substring(0, maximumPrefix);
        return prefix + ".v" + detail.schemaVersion();
    }

    private String normalizeDigest(String value) {
        return value.startsWith("sha256:")
                ? value.substring(7).toLowerCase(Locale.ROOT)
                : value.toLowerCase(Locale.ROOT);
    }

    private Object nullable(Object value) {
        return value == null ? "<null>" : value;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String digest(Object value) {
        byte[] bytes;
        try {
            bytes = value instanceof String string
                    ? string.getBytes(StandardCharsets.UTF_8)
                    : objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_DIGEST_FAILED", exception);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_EVENT_SERIALIZATION_FAILED", exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static AvailableAction action(String key, String href) {
        return new AvailableAction(
                key, "POST", href, "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
    }

    public record AccessContext(
            long tenantId,
            long subjectId,
            UUID subjectPublicId,
            String permissions,
            String roles,
            String contextKey,
            String contextScopeKey,
            PopulationScope scope,
            long fieldPolicyRevision,
            long authorizationRevision,
            OffsetDateTime revalidateAt,
            Set<String> maskedFieldGroups,
            Set<String> omittedFieldGroups,
            String purposeCode) {

        public AccessContext {
            maskedFieldGroups = maskedFieldGroups == null ? Set.of() : Set.copyOf(maskedFieldGroups);
            omittedFieldGroups = omittedFieldGroups == null ? Set.of() : Set.copyOf(omittedFieldGroups);
        }
    }

    public record CommandContext(
            String idempotencyKey,
            UUID correlationId,
            String purposeCode,
            Long expectedObjectVersion,
            String expectedDecisionRevision,
            String stepUpChallenge,
            String originRouteContractKey,
            String originContextKey,
            String originContextScopeKey,
            String originDecisionRevision) {
    }

    private record MutationResult(UUID configurationId, long version, long aggregateVersion) {
    }

    private record CursorState(
            String kind,
            long tenantId,
            String scopeRevision,
            String queryDigest,
            OffsetDateTime asOf,
            UUID seekAfter) {
    }

    @FunctionalInterface
    private interface TransitionEvidenceFactory {
        TransitionEvidence create(UUID receiptId, VersionDetail detail);
    }
}
