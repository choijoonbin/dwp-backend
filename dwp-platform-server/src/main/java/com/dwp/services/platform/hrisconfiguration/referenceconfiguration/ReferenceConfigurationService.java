package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySeals;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.typedOwner;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationGenericActions.*;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationRepository.*;

@Service
public class ReferenceConfigurationService {

    static final String PUBLISHED_EVENT = ReferenceConfigurationSupport.PUBLISHED_EVENT;
    static final String INVALIDATION_EVENT = ReferenceConfigurationSupport.INVALIDATION_EVENT;
    static final String WORKBENCH_ROUTE = ReferenceConfigurationSupport.WORKBENCH_ROUTE;
    static final String PURPOSE = ReferenceConfigurationSupport.PURPOSE;
    static final String UPDATE_PERMISSION = ReferenceConfigurationSupport.UPDATE_PERMISSION;
    static final String PUBLISH_PERMISSION = ReferenceConfigurationSupport.PUBLISH_PERMISSION;
    static final String VIEW_PERMISSION = ReferenceConfigurationSupport.VIEW_PERMISSION;
    static final Set<String> FIELD_GROUPS = ReferenceConfigurationSupport.FIELD_GROUPS;
    static final Set<String> CATALOG_FIELDS = ReferenceConfigurationSupport.CATALOG_FIELDS;
    static final Set<String> ALLOWED_SORTS = ReferenceConfigurationSupport.ALLOWED_SORTS;

    private final Per004ConfigurationRuntime per004;

    private final ReferenceConfigurationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ReferenceConfigurationSupport support;
    private final ReferenceConfigurationQueries queries;

    @Autowired
    public ReferenceConfigurationService(
            ReferenceConfigurationRepository repository,
            ObjectMapper objectMapper, Per004ConfigurationRuntime per004) {
        this(repository, objectMapper, Clock.systemUTC(), per004);
    }

    ReferenceConfigurationService(
            ReferenceConfigurationRepository repository,
            ObjectMapper objectMapper,
            Clock clock) {
        this(repository, objectMapper, clock, Per004ConfigurationRuntime.unavailable(objectMapper, clock));
    }

    ReferenceConfigurationService(ReferenceConfigurationRepository repository, ObjectMapper objectMapper,
                                  Clock clock, Per004ConfigurationRuntime per004) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.per004 = per004;
        this.support = new ReferenceConfigurationSupport(repository, objectMapper, clock);
        this.queries = new ReferenceConfigurationQueries(repository, support, CursorState::new);
    }

    @Transactional(readOnly = true)
    public CatalogResponse configurations(AccessContext access, int limit) {
        return configurations(access, new CatalogQuery(
                support.now(), null, null, List.copyOf(CATALOG_FIELDS),
                FreshnessPreference.FRESH_ONLY, limit));
    }

    @Transactional(readOnly = true)
    public CatalogResponse configurations(AccessContext access, CatalogQuery query) {
        return queries.configurations(access, query);
    }

    @Transactional(readOnly = true)
    public VersionResponse version(
            AccessContext access,
            UUID configurationId,
            long version) {
        support.requireManager(access);
        return support.response(support.requiredVersion(access, configurationId, version));
    }

    @Transactional
    public CommandReceipt createVersion(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            CreateVersionCommand body) {
        support.requireAuthor(access);
        support.requireEffectiveWindow(body.effectiveFrom(), body.effectiveTo());
        per004.validateCreate(body);
        return execute(
                access,
                command,
                configurationId,
                "CREATE_VERSION",
                "configuration.version.create",
                null,
                body,
                (receiptId, requestDigest) -> {
                    boolean typed = typedOwner(body.domain().name(), body.schemaKey());
                    long version = typed ? per004.create(repository, access, command, configurationId, body, receiptId, requestDigest)
                            : repository.createVersion(
                            access.tenantId(), access.scope(), configurationId,
                            access.subjectId(), body, support.now());
                    return result(configurationId, version, typed ? 1 : 0);
                });
    }

    @Transactional
    public CommandReceipt validate(
            AccessContext access,
            CommandContext command,
            UUID configurationId,
            long version,
            ValidationCommand body) {
        support.requireAuthor(access);
        return transition(
                access, command, configurationId, version,
                "VALIDATE_VERSION", "configuration.version.validate", body,
                LifecycleState.DRAFT, LifecycleState.VALIDATED,
                (receiptId, detail) -> {
                    support.requireEffectiveWindow(detail.effectiveFrom(), detail.effectiveTo());
                    if (body.schemaRevision() != detail.schemaVersion()) {
                        throw support.conflict("REFERENCE_CONFIGURATION_SCHEMA_REVISION_MISMATCH");
                    }
                    if (!support.normalizeDigest(body.inputScopeDigest()).equals(access.scope().digest())) {
                        throw support.forbidden("REFERENCE_CONFIGURATION_SCOPE_EVIDENCE_MISMATCH");
                    }
                    OffsetDateTime producedAt = support.now();
                    String evidenceDigest = support.digest(Map.of(
                            "schemaKey", detail.schemaKey(),
                            "schemaRevision", detail.schemaVersion(),
                            "payloadDigest", detail.payloadDigest(),
                            "scopeDigest", access.scope().digest(),
                            "effectiveFrom", detail.effectiveFrom(),
                            "effectiveTo", support.nullable(detail.effectiveTo())));
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
        support.requireAuthor(access);
        return transition(
                access, command, configurationId, version,
                "SIMULATE_VERSION", "configuration.version.simulate", body,
                LifecycleState.VALIDATED, LifecycleState.SIMULATED,
                (receiptId, detail) -> {
                    ValidationFact validation = support.requireValidation(detail, body.validationReceiptId());
                    OffsetDateTime producedAt = support.now();
                    String evidenceDigest = support.digest(Map.of(
                            "validationEvidence", validation.evidenceDigest(),
                            "sampleScopeRefDigest", support.digest(body.sampleScopeRef()),
                            "sampleScopeDigest", support.normalizeDigest(body.sampleScopeDigest()),
                            "payloadDigest", detail.payloadDigest(),
                            "aggregateVersion", detail.aggregateVersion()));
                    String impactDigest = support.digest(Map.of(
                            "simulationEvidence", evidenceDigest,
                            "effectiveFrom", detail.effectiveFrom(),
                            "effectiveTo", support.nullable(detail.effectiveTo()),
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
        support.requireAuthor(access);
        return transition(
                access, command, configurationId, version,
                "SUBMIT_VERSION", "configuration.version.submit", body,
                LifecycleState.SIMULATED, LifecycleState.PENDING_APPROVAL,
                (receiptId, detail) -> {
                    ValidationFact validation = support.requireValidation(detail, body.validationReceiptId());
                    SimulationFact simulation = support.requireSimulation(
                            detail, body.simulationReceiptId(), validation.receiptId());
                    if (support.now().isAfter(simulation.freshUntil())) {
                        throw support.conflict("REFERENCE_CONFIGURATION_SIMULATION_STALE");
                    }
                    if (!simulation.impactDigest().equals(support.normalizeDigest(body.impactDigest()))) {
                        throw support.conflict("REFERENCE_CONFIGURATION_IMPACT_EVIDENCE_MISMATCH");
                    }
                    OffsetDateTime producedAt = support.now();
                    String evidenceDigest = support.digest(Map.of(
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
        support.requirePublisher(access, command);
        return transition(
                access, command, configurationId, version,
                "APPROVE_VERSION", "configuration.version.approve", body,
                LifecycleState.PENDING_APPROVAL, LifecycleState.APPROVED,
                (receiptId, detail) -> {
                    support.requireDifferentActor(detail, access.subjectId());
                    SubmissionFact submission = support.requireSubmission(detail, body.approvalReceiptId());
                    support.requireStoredReceipt(
                            access.tenantId(), submission.receiptId(), "SUBMIT_VERSION",
                            configurationId, version);
                    OffsetDateTime producedAt = support.now();
                    String decisionDigest = support.normalizeDigest(body.approvalDecisionDigest());
                    String evidenceDigest = support.digest(Map.of(
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
        support.requirePublisher(access, command);
        support.requireEffectiveWindow(body.effectiveFrom(), body.effectiveTo());
        if (body.publishAt().isBefore(support.now()) || body.effectiveFrom().isBefore(body.publishAt())) {
            throw support.invalid("REFERENCE_CONFIGURATION_SCHEDULE_CLOCK_INVALID");
        }
        return transition(
                access, command, configurationId, version,
                "SCHEDULE_VERSION", "configuration.version.schedule", body,
                LifecycleState.APPROVED, LifecycleState.SCHEDULED,
                (receiptId, detail) -> {
                    support.requireDifferentActor(detail, access.subjectId());
                    support.requirePublishEvidence(detail, body.approvalReceiptId(), body.impactDigest());
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
        support.requirePublisher(access, command);
        support.requireEffectiveWindow(body.effectiveFrom(), body.effectiveTo());
        VersionDetail detail = support.requiredVersion(access, configurationId, version);
        LifecycleState expected = detail.state() == LifecycleState.SCHEDULED
                ? LifecycleState.SCHEDULED
                : LifecycleState.APPROVED;
        return transition(
                access, command, configurationId, version,
                "PUBLISH_VERSION", "configuration.version.publish", body,
                expected, LifecycleState.PUBLISHED,
                (receiptId, fresh) -> {
                    support.requireDifferentActor(fresh, access.subjectId());
                    support.requirePublishEvidence(fresh, body.approvalReceiptId(), body.impactDigest());
                    if (support.now().isBefore(body.effectiveFrom())) {
                        throw support.conflict("REFERENCE_CONFIGURATION_EFFECTIVE_CLOCK_NOT_REACHED");
                    }
                    if (fresh.state() == LifecycleState.SCHEDULED) {
                        if (fresh.scheduledAt() == null || support.now().isBefore(fresh.scheduledAt())) {
                            throw support.conflict("REFERENCE_CONFIGURATION_SCHEDULE_NOT_DUE");
                        }
                        if (!fresh.effectiveFrom().isEqual(body.effectiveFrom())
                                || !java.util.Objects.equals(fresh.effectiveTo(), body.effectiveTo())) {
                            throw support.conflict("REFERENCE_CONFIGURATION_SCHEDULE_WINDOW_MISMATCH");
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
        support.requirePublisher(access, command);
        if (body.targetPublishedVersion() >= version || body.effectiveFrom().isAfter(support.now())) {
            throw support.invalid("REFERENCE_CONFIGURATION_ROLLBACK_TARGET_INVALID");
        }
        return execute(
                access, command, configurationId,
                "ROLLBACK_VERSION", "configuration.version.rollback", version, body,
                (receiptId, requestDigest) -> {
                    OffsetDateTime activationTime = support.now();
                    VersionDetail current = support.requiredVersion(access, configurationId, version);
                    support.requireExpectedVersion(command, current.aggregateVersion());
                    support.requireDifferentActor(current, access.subjectId());
                    if (current.state() != LifecycleState.PUBLISHED) {
                        throw support.conflict("REFERENCE_CONFIGURATION_ROLLBACK_SOURCE_NOT_PUBLISHED");
                    }
                    support.requireActiveAt(current, activationTime,
                            "REFERENCE_CONFIGURATION_ROLLBACK_SOURCE_NOT_ACTIVE");
                    if (!activationTime.isAfter(current.effectiveFrom())) {
                        throw support.conflict("REFERENCE_CONFIGURATION_ROLLBACK_SOURCE_NOT_ACTIVE");
                    }
                    VersionDetail target = support.requiredVersion(
                            access, configurationId, body.targetPublishedVersion());
                    support.requireCompatibility(current, target);
                    if (target.state() != LifecycleState.PUBLISHED
                            && target.state() != LifecycleState.RETIRED) {
                        throw support.conflict("REFERENCE_CONFIGURATION_ROLLBACK_TARGET_NOT_PUBLISHED");
                    }
                    support.requireApproval(current, body.approvalReceiptId());
                    SimulationFact targetSimulation = target.simulation();
                    if (targetSimulation == null
                            || !targetSimulation.impactDigest().equals(
                                    support.normalizeDigest(body.impactDigest()))) {
                        throw support.conflict("REFERENCE_CONFIGURATION_ROLLBACK_IMPACT_MISMATCH");
                    }
                    long rollbackVersion;
                    try {
                        rollbackVersion = repository.rollback(
                                access.tenantId(), access.scope(), configurationId, version,
                                body.targetPublishedVersion(), current.aggregateVersion(),
                                access.subjectId(), body, targetSimulation.impactDigest(), activationTime);
                    } catch (IllegalStateException exception) {
                        throw support.conflict(exception.getMessage());
                    }
                    VersionDetail rolledBack = support.requiredVersion(
                            access, configurationId, rollbackVersion);
                    support.appendPublishedEvent(access, rolledBack);
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
        support.requirePublisher(access, command);
        if (body.replacementVersion() == null || body.replacementVersion() == version) {
            throw support.invalid("REFERENCE_CONFIGURATION_SUCCESSOR_REQUIRED");
        }
        if (body.effectiveTo().isAfter(support.now())) {
            throw support.invalid("REFERENCE_CONFIGURATION_RETIRE_BOUNDARY_INVALID");
        }
        return execute(
                access, command, configurationId,
                "RETIRE_VERSION", "configuration.version.retire", version, body,
                (receiptId, requestDigest) -> {
                    OffsetDateTime activationTime = support.now();
                    VersionDetail current = support.requiredVersion(access, configurationId, version);
                    support.requireExpectedVersion(command, current.aggregateVersion());
                    support.requireDifferentActor(current, access.subjectId());
                    support.requireApproval(current, body.approvalReceiptId());
                    if (current.state() != LifecycleState.PUBLISHED
                            || !body.effectiveTo().isAfter(current.effectiveFrom())) {
                        throw support.conflict("REFERENCE_CONFIGURATION_RETIRE_SOURCE_NOT_PUBLISHED");
                    }
                    support.requireActiveAt(current, activationTime,
                            "REFERENCE_CONFIGURATION_RETIRE_SOURCE_NOT_ACTIVE");

                    VersionDetail successor = support.requiredVersion(
                            access, configurationId, body.replacementVersion());
                    support.requireCompatibility(current, successor);
                    support.requireDifferentActor(successor, access.subjectId());
                    if (successor.state() != LifecycleState.SCHEDULED
                            || successor.approval() == null
                            || successor.scheduledAt() == null
                            || successor.scheduledAt().isAfter(activationTime)
                            || !successor.effectiveFrom().isEqual(body.effectiveTo())
                            || (successor.effectiveTo() != null
                            && !successor.effectiveTo().isAfter(activationTime))) {
                        throw support.conflict("REFERENCE_CONFIGURATION_SUCCESSOR_BOUNDARY_MISMATCH");
                    }
                    boolean changed;
                    try {
                        changed = repository.retireAndActivateSuccessor(
                                access.tenantId(), access.scope(), configurationId,
                                version, current.aggregateVersion(), successor.version(),
                                successor.aggregateVersion(), body.effectiveTo(),
                                body.reasonCode(), activationTime);
                    } catch (IllegalStateException exception) {
                        throw support.conflict(exception.getMessage());
                    }
                    if (!changed) throw support.conflict("REFERENCE_CONFIGURATION_VERSION_CONFLICT");
                    VersionDetail activated = support.requiredVersion(
                            access, configurationId, successor.version());
                    support.appendPublishedEvent(access, activated);
                    VersionDetail retired = support.requiredVersion(access, configurationId, version);
                    return result(configurationId, version, retired.aggregateVersion());
                });
    }

    @Transactional(readOnly = true)
    public WorkbenchResponse workbench(AccessContext access, WorkbenchQuery query) {
        return queries.workbench(access, query);
    }

    String encodeCursor(long tenantId, String scopeRevision, String queryDigest,
            OffsetDateTime asOf, UUID itemPublicId) {
        return queries.encodeCursor(tenantId, scopeRevision, queryDigest, asOf, itemPublicId);
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
                (receiptId, requestDigest) -> {
                    VersionDetail detail = support.requiredVersion(access, configurationId, version);
                    support.requireExpectedVersion(command, detail.aggregateVersion());
                    boolean owned = per004.ownerTransition(access, detail, commandType, body);
                    TransitionEvidence evidence = owned ? null : evidenceFactory.create(receiptId, detail);
                    boolean changed = owned || repository.transition(
                            access.tenantId(), access.scope(), configurationId, version,
                            detail.aggregateVersion(), expected, target, evidence, support.now());
                    if (!changed) throw support.conflict("REFERENCE_CONFIGURATION_VERSION_CONFLICT");
                    VersionDetail updated = support.requiredVersion(access, configurationId, version);
                    if (emitPublishedEvent) support.appendPublishedEvent(access, updated);
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
            BiFunction<UUID, String, MutationResult> mutation) {
        support.validateAccess(access);
        support.validateLegacyCommand(command);
        Domain domain = body instanceof CreateVersionCommand create ? create.domain()
                : support.requiredVersion(access, configurationId, targetVersion).domain();
        String schema = body instanceof CreateVersionCommand create ? create.schemaKey()
                : support.requiredVersion(access, configurationId, targetVersion).schemaKey();
        per004.guard(access, command, configurationId, domain, action, schema);
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
        requestIdentity.put("authorizationContextDigest", support.authorizationContextDigest(access, command));
        String requestDigest = support.digest(requestIdentity);
        repository.lockIdempotency(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey());
        var prior = repository.findReceipt(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey());
        if (prior.isPresent()) {
            if (!prior.get().requestDigest().equals(requestDigest)) {
                throw support.conflict("IDEMPOTENCY_PAYLOAD_MISMATCH");
            }
            return prior.get().receipt();
        }

        support.validateCanonicalSeal(access, command, action);
        String authorityPopulationScopeDigest =
                HrisExplorerAuthoritySeals.populationScopeDigest(
                        access.contextScopeKey());
        UUID receiptId = UUID.randomUUID();
        MutationResult result = mutation.apply(receiptId, requestDigest);
        OffsetDateTime createdAt = support.now();
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

    private MutationResult result(
            UUID configurationId,
            long version,
            long aggregateVersion) {
        return new MutationResult(configurationId, version, aggregateVersion);
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
            String purposeCode) implements ReferenceConfigurationContext.Access {

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
            String originDecisionRevision) implements ReferenceConfigurationContext.Command {
    }

    private record MutationResult(UUID configurationId, long version, long aggregateVersion) {
    }

    private record CursorState(
            String kind,
            long tenantId,
            String scopeRevision,
            String queryDigest,
            OffsetDateTime asOf,
            UUID seekAfter) implements ReferenceConfigurationContext.Cursor {
    }

    @FunctionalInterface
    private interface TransitionEvidenceFactory {
        TransitionEvidence create(UUID receiptId, VersionDetail detail);
    }
}
