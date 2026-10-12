package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
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
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationRepository.TransitionEvidence;

@Service
public class ReferenceConfigurationService {

    static final String PUBLISHED_EVENT = "dwp.hris.configuration.published.v1";
    static final String INVALIDATION_EVENT = "dwp.hris.access.assignment.changed.v1";
    static final String WORKBENCH_ROUTE = "/hr/settings/reference-data";
    static final String UPDATE_PERMISSION = "CONFIG.HRIS_POLICY:UPDATE";
    static final String PUBLISH_PERMISSION = "ACTION.HRIS_POLICY_PUBLISH:PUBLISH";
    static final String VIEW_PERMISSION = "CONFIG.HRIS_WORKBENCH:VIEW";
    static final Set<String> ALLOWED_SORTS = Set.of(
            "policyVersion:ASC",
            "referenceData:ASC",
            "effectiveStatus:ASC",
            "policyVersion:DESC",
            "referenceData:DESC",
            "effectiveStatus:DESC",
            "itemPublicId:ASC",
            "itemPublicId:DESC");

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
        requireManager(access);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        OffsetDateTime asOf = now();
        return new CatalogResponse(
                asOf,
                null,
                repository.list(access.tenantId(), boundedLimit),
                asOf.plusSeconds(30),
                asOf.plusMinutes(5));
    }

    @Transactional(readOnly = true)
    public VersionResponse version(
            AccessContext access,
            UUID configurationId,
            long version) {
        requireManager(access);
        return response(requiredVersion(access.tenantId(), configurationId, version));
    }

    @Transactional
    public CommandReceipt createVersion(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            CreateVersionCommand body) {
        requireAuthor(access);
        requireEffectiveWindow(body.effectiveFrom(), body.effectiveTo());
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
                            access.tenantId(), configurationId, access.subjectId(), body, now());
                    return result(configurationId, version, 0, body.domain(), body.effectiveFrom(),
                            body.effectiveTo(), body.payload().digest());
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
                access,
                command,
                configurationId,
                version,
                "VALIDATE_VERSION",
                "configuration.version.validate",
                body,
                LifecycleState.DRAFT,
                LifecycleState.VALIDATED,
                (receiptId, ignored) -> new TransitionEvidence(
                        access.subjectId(), receiptId, null, null, null,
                        null, null, null, null, null, null),
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
                access,
                command,
                configurationId,
                version,
                "SIMULATE_VERSION",
                "configuration.version.simulate",
                body,
                LifecycleState.VALIDATED,
                LifecycleState.SIMULATED,
                (receiptId, ignored) -> new TransitionEvidence(
                        access.subjectId(), body.validationReceiptId(), receiptId, null, null,
                        null, null, null, null, null, null),
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
                access,
                command,
                configurationId,
                version,
                "SUBMIT_VERSION",
                "configuration.version.submit",
                body,
                LifecycleState.SIMULATED,
                LifecycleState.PENDING_APPROVAL,
                (receiptId, ignored) -> new TransitionEvidence(
                        access.subjectId(), body.validationReceiptId(), body.simulationReceiptId(),
                        null, body.impactDigest(), null, null, null, null, null, null),
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
                access,
                command,
                configurationId,
                version,
                "APPROVE_VERSION",
                "configuration.version.approve",
                body,
                LifecycleState.PENDING_APPROVAL,
                LifecycleState.APPROVED,
                (receiptId, detail) -> {
                    requireDifferentActor(detail, access.subjectId());
                    return new TransitionEvidence(
                            access.subjectId(), null, null, body.approvalReceiptId(), null,
                            body.approvalDecisionDigest(), null, null, null, null, null);
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
        if (body.publishAt().isBefore(now())) {
            throw invalid("REFERENCE_CONFIGURATION_SCHEDULE_IN_PAST");
        }
        return transition(
                access,
                command,
                configurationId,
                version,
                "SCHEDULE_VERSION",
                "configuration.version.schedule",
                body,
                LifecycleState.APPROVED,
                LifecycleState.SCHEDULED,
                (receiptId, detail) -> {
                    requireDifferentActor(detail, access.subjectId());
                    requireApproval(detail, body.approvalReceiptId());
                    return new TransitionEvidence(
                            access.subjectId(), null, null, body.approvalReceiptId(),
                            body.impactDigest(), null, body.publishAt(), body.effectiveFrom(),
                            body.effectiveTo(), null, null);
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
        VersionDetail detail = requiredVersion(access.tenantId(), configurationId, version);
        LifecycleState expected = detail.state() == LifecycleState.SCHEDULED
                ? LifecycleState.SCHEDULED
                : LifecycleState.APPROVED;
        return transition(
                access,
                command,
                configurationId,
                version,
                "PUBLISH_VERSION",
                "configuration.version.publish",
                body,
                expected,
                LifecycleState.PUBLISHED,
                (receiptId, fresh) -> {
                    requireDifferentActor(fresh, access.subjectId());
                    requireApproval(fresh, body.approvalReceiptId());
                    return new TransitionEvidence(
                            access.subjectId(), null, null, body.approvalReceiptId(),
                            body.impactDigest(), null, null, body.effectiveFrom(),
                            body.effectiveTo(), null, null);
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
        if (body.targetPublishedVersion() >= version) {
            throw invalid("REFERENCE_CONFIGURATION_ROLLBACK_TARGET_INVALID");
        }
        return execute(
                access,
                command,
                configurationId,
                "ROLLBACK_VERSION",
                "configuration.version.rollback",
                version,
                body,
                receiptId -> {
                    VersionDetail current = requiredVersion(access.tenantId(), configurationId, version);
                    requireExpectedVersion(command, current.aggregateVersion());
                    requireDifferentActor(current, access.subjectId());
                    VersionDetail target = requiredVersion(
                            access.tenantId(), configurationId, body.targetPublishedVersion());
                    requireDifferentActor(target, access.subjectId());
                    long rollbackVersion;
                    try {
                        rollbackVersion = repository.rollback(
                                access.tenantId(), configurationId, version,
                                body.targetPublishedVersion(), current.aggregateVersion(),
                                access.subjectId(), body, now());
                    } catch (IllegalStateException exception) {
                        throw conflict(exception.getMessage());
                    }
                    VersionDetail rolledBack = requiredVersion(
                            access.tenantId(), configurationId, rollbackVersion);
                    appendPublishedEvent(access, rolledBack);
                    return result(
                            configurationId, rollbackVersion, rolledBack.aggregateVersion(),
                            rolledBack.domain(), rolledBack.effectiveFrom(), rolledBack.effectiveTo(),
                            rolledBack.payloadDigest());
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
        return transition(
                access,
                command,
                configurationId,
                version,
                "RETIRE_VERSION",
                "configuration.version.retire",
                body,
                LifecycleState.PUBLISHED,
                LifecycleState.RETIRED,
                (receiptId, detail) -> {
                    requireDifferentActor(detail, access.subjectId());
                    if (!body.effectiveTo().isAfter(detail.effectiveFrom())) {
                        throw invalid("REFERENCE_CONFIGURATION_EFFECTIVE_WINDOW_INVALID");
                    }
                    return new TransitionEvidence(
                            access.subjectId(), null, null, body.approvalReceiptId(), null, null,
                            null, null, body.effectiveTo(), body.replacementVersion(), body.reasonCode());
                },
                false);
    }

    @Transactional(readOnly = true)
    public WorkbenchResponse workbench(AccessContext access, WorkbenchQuery query) {
        requireView(access);
        validateQuery(query);
        OffsetDateTime asOf = query.asOf() == null ? now() : query.asOf();
        String scopeRevision = scopeRevision(access);
        validateCursor(query.cursor(), access.tenantId(), scopeRevision);

        List<CatalogItem> catalog;
        try {
            catalog = repository.list(access.tenantId(), 100);
        } catch (RuntimeException exception) {
            PartialFailure failure = new PartialFailure(
                    "reference-configuration",
                    "REFERENCE_CONFIGURATION_SOURCE_UNAVAILABLE",
                    query.correlationId(),
                    true);
            WorkbenchQueryState unavailable = new WorkbenchQueryState(
                    QueryState.UNAVAILABLE,
                    scopeRevision,
                    Freshness.UNAVAILABLE,
                    null,
                    new FieldDecisionSummary(0, 0, 0),
                    List.of(failure),
                    query.correlationId(),
                    new CachePolicy(scopeRevision, INVALIDATION_EVENT, 0));
            return new WorkbenchResponse(
                    "1",
                    asOf,
                    "REFERENCE",
                    unavailable,
                    List.of());
        }
        List<PartialFailure> failures = new ArrayList<>();
        List<WorkbenchItem> items = new ArrayList<>();
        for (CatalogItem item : catalog) {
            try {
                VersionDetail detail = repository
                        .findVersion(access.tenantId(), item.publicId(), item.version())
                        .orElse(null);
                if (detail != null && matches(query.filter(), item, detail, asOf)) {
                    items.add(workbenchItem(access, item, detail));
                }
            } catch (RuntimeException exception) {
                failures.add(new PartialFailure(
                        "reference-configuration",
                        "REFERENCE_CONFIGURATION_ITEM_UNAVAILABLE",
                        query.correlationId(),
                        true));
            }
        }
        items.sort(comparator(query.sort()));
        if (items.size() > query.limit()) {
            items = new ArrayList<>(items.subList(0, query.limit()));
        }
        long viewCount = items.stream().mapToLong(item -> item.fieldGroups().size()).sum();
        QueryState state = !failures.isEmpty()
                ? QueryState.PARTIAL
                : items.isEmpty() ? QueryState.EMPTY : QueryState.COMPLETE;
        WorkbenchQueryState queryState = new WorkbenchQueryState(
                state,
                scopeRevision,
                Freshness.CURRENT,
                null,
                new FieldDecisionSummary(viewCount, 0, 0),
                List.copyOf(failures),
                query.correlationId(),
                new CachePolicy(scopeRevision, INVALIDATION_EVENT, 30));
        return new WorkbenchResponse(
                "1",
                asOf,
                "REFERENCE",
                queryState,
                List.copyOf(items));
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
                access,
                command,
                configurationId,
                commandType,
                action,
                version,
                body,
                receiptId -> {
                    VersionDetail detail = requiredVersion(access.tenantId(), configurationId, version);
                    requireExpectedVersion(command, detail.aggregateVersion());
                    TransitionEvidence evidence = evidenceFactory.create(receiptId, detail);
                    boolean changed = repository.transition(
                            access.tenantId(), configurationId, version, detail.aggregateVersion(),
                            expected, target, evidence, now());
                    if (!changed) {
                        throw conflict("REFERENCE_CONFIGURATION_VERSION_CONFLICT");
                    }
                    VersionDetail updated = requiredVersion(access.tenantId(), configurationId, version);
                    if (emitPublishedEvent) {
                        appendPublishedEvent(access, updated);
                    }
                    return result(
                            configurationId, version, updated.aggregateVersion(), updated.domain(),
                            updated.effectiveFrom(), updated.effectiveTo(), updated.payloadDigest());
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
        validateCommand(command);
        Map<String, Object> requestIdentity = new LinkedHashMap<>();
        requestIdentity.put("commandType", commandType);
        requestIdentity.put("configurationId", configurationId);
        if (targetVersion != null) requestIdentity.put("targetVersion", targetVersion);
        requestIdentity.put("body", body);
        requestIdentity.put("subjectPrincipalPublicId", subjectPublicId(access));
        requestIdentity.put("purposeCode", command.purposeCode());
        if (command.expectedObjectVersion() != null) {
            requestIdentity.put("expectedObjectVersion", command.expectedObjectVersion());
        }
        requestIdentity.put("authorizationContextDigest", authorizationContextDigest(access, command));
        String requestDigest = digest(requestIdentity);
        repository.lockIdempotency(access.tenantId(), command.idempotencyKey());
        var prior = repository.findReceipt(access.tenantId(), command.idempotencyKey());
        if (prior.isPresent()) {
            if (!prior.get().requestDigest().equals(requestDigest)) {
                throw conflict("IDEMPOTENCY_PAYLOAD_MISMATCH");
            }
            return prior.get().receipt();
        }

        UUID receiptId = UUID.randomUUID();
        MutationResult result = mutation.apply(receiptId);
        OffsetDateTime createdAt = now();
        CommandReceipt receipt = new CommandReceipt(
                receiptId,
                commandType,
                action,
                subjectPublicId(access),
                digest("tenant:" + access.tenantId()),
                revision(command.expectedDecisionRevision()),
                command.purposeCode(),
                revision(command.expectedDecisionRevision()),
                "SUCCEEDED",
                command.correlationId(),
                requestDigest,
                createdAt,
                result.aggregateVersion(),
                result.configurationId() + "/" + result.version());
        repository.saveReceipt(
                access.tenantId(), command.idempotencyKey(), requestDigest, receipt);
        repository.appendAudit(
                access.tenantId(), configurationId, result.version(), action,
                access.subjectId(), command.correlationId().toString(), requestDigest, createdAt);
        return receipt;
    }

    private void appendPublishedEvent(AccessContext access, VersionDetail detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("configurationPublicId", detail.configurationId());
        payload.put("domain", detail.domain());
        payload.put("version", detail.version());
        payload.put("effectiveFrom", detail.effectiveFrom());
        payload.put("effectiveTo", detail.effectiveTo());
        payload.put("payloadDigest", detail.payloadDigest());
        payload.put("occurredAt", now());
        repository.appendOutbox(
                access.tenantId(), detail.configurationId(), detail.version(),
                PUBLISHED_EVENT, json(payload), now());
    }

    private WorkbenchItem workbenchItem(
            AccessContext access,
            CatalogItem item,
            VersionDetail detail) {
        Map<String, FieldValue<?>> groups = new LinkedHashMap<>();
        groups.put("POLICY_VERSION", FieldValue.view(new PolicyValue(
                detail.schemaKey(), detail.version(), detail.effectiveFrom().toLocalDate(),
                detail.effectiveTo() == null ? null : detail.effectiveTo().toLocalDate(),
                detail.state().name())));
        groups.put("REFERENCE_DATA", FieldValue.view(new ReferenceValue(
                item.publicId().toString(), item.domain().name(), item.key(), item.version())));
        groups.put("EFFECTIVE_STATUS", FieldValue.view(new StatusValue(
                item.state().name(), item.version())));
        return new WorkbenchItem(
                item.publicId().toString(),
                item.key(),
                item.state().name(),
                Map.copyOf(groups),
                new DeepLink(WORKBENCH_ROUTE, item.publicId().toString()),
                actions(access));
    }

    private List<AvailableAction> actions(AccessContext access) {
        List<AvailableAction> result = new ArrayList<>();
        Set<String> permissions = permissions(access.permissions());
        if (permissions.contains(UPDATE_PERMISSION)) result.addAll(AUTHOR_ACTIONS);
        if (permissions.contains(PUBLISH_PERMISSION)) result.addAll(PUBLISHER_ACTIONS);
        return List.copyOf(result);
    }

    private boolean matches(
            WorkbenchFilter filter,
            CatalogItem item,
            VersionDetail detail,
            OffsetDateTime asOf) {
        if (filter == null) return true;
        if (notEmpty(filter.policyKeys()) && !filter.policyKeys().contains(detail.schemaKey())) return false;
        if (filter.minimumPolicyRevision() != null
                && detail.version() < filter.minimumPolicyRevision()) return false;
        if (notEmpty(filter.referencePublicIds())
                && !filter.referencePublicIds().contains(item.publicId().toString())) return false;
        if (notEmpty(filter.referenceCodes())
                && !filter.referenceCodes().contains(item.domain().name())) return false;
        if (notEmpty(filter.effectiveStatuses())
                && !filter.effectiveStatuses().contains(item.state().name())) return false;
        if (filter.minimumStatusRevision() != null
                && detail.version() < filter.minimumStatusRevision()) return false;
        if (filter.effectiveOn() != null) {
            var date = filter.effectiveOn();
            if (date.isBefore(detail.effectiveFrom().toLocalDate())) return false;
            if (detail.effectiveTo() != null && !date.isBefore(detail.effectiveTo().toLocalDate())) {
                return false;
            }
        }
        return !asOf.isBefore(detail.createdAt());
    }

    private Comparator<WorkbenchItem> comparator(List<String> sorts) {
        Comparator<WorkbenchItem> comparator = null;
        for (String sort : sorts) {
            Comparator<WorkbenchItem> next = switch (sort) {
                case "policyVersion:ASC" -> Comparator.comparingLong(this::policyVersion);
                case "policyVersion:DESC" -> Comparator.comparingLong(this::policyVersion).reversed();
                case "referenceData:ASC" -> Comparator.comparing(WorkbenchItem::title);
                case "referenceData:DESC" -> Comparator.comparing(WorkbenchItem::title).reversed();
                case "effectiveStatus:ASC" -> Comparator.comparing(WorkbenchItem::statusCode);
                case "effectiveStatus:DESC" -> Comparator.comparing(WorkbenchItem::statusCode).reversed();
                case "itemPublicId:ASC" -> Comparator.comparing(WorkbenchItem::itemPublicId);
                case "itemPublicId:DESC" -> Comparator.comparing(WorkbenchItem::itemPublicId).reversed();
                default -> throw invalid("REFERENCE_CONFIGURATION_SORT_INVALID");
            };
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator == null
                ? Comparator.comparing(WorkbenchItem::itemPublicId)
                : comparator.thenComparing(WorkbenchItem::itemPublicId);
    }

    private long policyVersion(WorkbenchItem item) {
        FieldValue<?> value = item.fieldGroups().get("POLICY_VERSION");
        return value != null && value.value() instanceof PolicyValue policy ? policy.revision() : 0;
    }

    private void validateQuery(WorkbenchQuery query) {
        if (query == null || query.limit() == null || query.limit() < 1 || query.limit() > 100) {
            throw invalid("REFERENCE_CONFIGURATION_QUERY_INVALID");
        }
        if (query.sort() == null || query.sort().isEmpty() || query.sort().size() > 3
                || new LinkedHashSet<>(query.sort()).size() != query.sort().size()
                || !ALLOWED_SORTS.containsAll(query.sort())) {
            throw invalid("REFERENCE_CONFIGURATION_SORT_INVALID");
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

    private void validateCursor(String cursor, long tenantId, String scopeRevision) {
        if (cursor == null) return;
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 4);
            if (parts.length != 4
                    || Long.parseLong(parts[0]) != tenantId
                    || !parts[1].equals(scopeRevision)
                    || !parts[3].equals(digest(parts[0] + ":" + parts[1] + ":" + parts[2]))) {
                throw conflict("REFERENCE_CONFIGURATION_CURSOR_STALE");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid("REFERENCE_CONFIGURATION_CURSOR_INVALID");
        }
    }

    String encodeCursor(long tenantId, String scopeRevision, String itemPublicId) {
        String value = tenantId + ":" + scopeRevision + ":" + itemPublicId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (value + ":" + digest(value)).getBytes(StandardCharsets.UTF_8));
    }

    private void requireEffectiveWindow(OffsetDateTime from, OffsetDateTime to) {
        if (to != null && !to.isAfter(from)) {
            throw invalid("REFERENCE_CONFIGURATION_EFFECTIVE_WINDOW_INVALID");
        }
    }

    private void requireApproval(VersionDetail detail, UUID approvalReceiptId) {
        if (detail.approvalReceiptId() == null
                || !detail.approvalReceiptId().equals(approvalReceiptId)) {
            throw conflict("REFERENCE_CONFIGURATION_APPROVAL_EVIDENCE_MISMATCH");
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
                || command.expectedDecisionRevision().isBlank()) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "REFERENCE_CONFIGURATION_DECISION_REVISION_REQUIRED");
        }
    }

    private void validateAccess(AccessContext access) {
        if (access == null || access.tenantId() <= 0 || access.subjectId() <= 0) {
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
    }

    private void validateCommand(CommandContext command) {
        if (command == null
                || command.idempotencyKey() == null
                || command.idempotencyKey().length() < 16
                || command.idempotencyKey().length() > 128
                || command.correlationId() == null
                || command.purposeCode() == null
                || command.purposeCode().length() < 3
                || command.purposeCode().length() > 64) {
            throw invalid("REFERENCE_CONFIGURATION_COMMAND_CONTEXT_INVALID");
        }
    }

    private VersionDetail requiredVersion(long tenantId, UUID configurationId, long version) {
        return repository.findVersion(tenantId, configurationId, version)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_NOT_AVAILABLE,
                        "REFERENCE_CONFIGURATION_NOT_FOUND"));
    }

    private VersionResponse response(VersionDetail detail) {
        return new VersionResponse(
                detail.configurationId(),
                detail.version(),
                detail.state(),
                detail.effectiveFrom(),
                detail.effectiveTo(),
                detail.schemaKey(),
                detail.schemaVersion(),
                detail.payloadDigest(),
                detail.approvalReceiptId(),
                detail.aggregateVersion());
    }

    private MutationResult result(
            UUID configurationId,
            long version,
            long aggregateVersion,
            Domain domain,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            String payloadDigest) {
        return new MutationResult(
                configurationId, version, aggregateVersion, domain,
                effectiveFrom, effectiveTo, payloadDigest);
    }

    private String scopeRevision(AccessContext access) {
        return "scope-" + digest(access.tenantId() + "|"
                + permissions(access.permissions()).stream().sorted().toList()).substring(0, 24);
    }

    private String authorizationContextDigest(AccessContext access, CommandContext command) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", access.tenantId());
        context.put("subjectPrincipalPublicId", subjectPublicId(access));
        context.put("permissions", permissions(access.permissions()).stream().sorted().toList());
        context.put("roles", permissions(access.roles()).stream().sorted().toList());
        if (command.expectedDecisionRevision() != null) {
            context.put("decisionRevision", command.expectedDecisionRevision());
        }
        if (command.stepUpChallenge() != null) {
            context.put("stepUpProofDigest", digest(command.stepUpChallenge()));
        }
        return digest(context);
    }

    private UUID subjectPublicId(AccessContext access) {
        return UUID.nameUUIDFromBytes(
                (access.tenantId() + ":" + access.subjectId()).getBytes(StandardCharsets.UTF_8));
    }

    private long revision(String value) {
        if (value == null) return 1;
        String digits = value.replaceAll("\\D+", "");
        if (digits.isBlank()) return 1;
        try {
            return Math.max(1, Long.parseLong(digits));
        } catch (NumberFormatException ignored) {
            return 1;
        }
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

    private boolean notEmpty(List<String> values) {
        return values != null && !values.isEmpty();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
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
                key,
                "POST",
                href,
                "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
    }

    public record AccessContext(long tenantId, long subjectId, String permissions, String roles) {
    }

    public record CommandContext(
            String idempotencyKey,
            UUID correlationId,
            String purposeCode,
            Long expectedObjectVersion,
            String expectedDecisionRevision,
            String stepUpChallenge) {
    }

    private record MutationResult(
            UUID configurationId,
            long version,
            long aggregateVersion,
            Domain domain,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            String payloadDigest) {
    }

    @FunctionalInterface
    private interface TransitionEvidenceFactory {
        TransitionEvidence create(UUID receiptId, VersionDetail detail);
    }
}
