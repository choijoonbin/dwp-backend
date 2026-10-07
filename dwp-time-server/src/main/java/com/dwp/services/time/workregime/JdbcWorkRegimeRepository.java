package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeModels.ArrangementKind;
import com.dwp.services.time.workregime.WorkRegimeModels.AssignmentPlan;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LocalSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePack;
import com.dwp.services.time.workregime.WorkRegimeModels.WorkRegimeRevision;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetBindingEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetAuthorizationGuard;
import com.dwp.services.time.workregime.JdbcWorkRegimeTargetAuthorizationSupport.LockedTarget;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL owner adapter for the default-off work-regime authoring slice.
 *
 * <p>Every database interaction is performed inside a transaction after binding the exact tenant
 * to PostgreSQL's transaction-local RLS setting. Callers never supply internal database ids.
 */
@Repository
@ConditionalOnProperty(
        name = "dwp.time.work-regime-api.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class JdbcWorkRegimeRepository implements WorkRegimeRepository {

    private static final String SET_TENANT_SQL =
            "SELECT set_config('dwp.tenant_id', ?, true)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcWorkRegimeReadSupport reads;
    private final JdbcWorkRegimeSimulationPersistence simulations;
    private final JdbcWorkRegimeEvidencePersistence evidence;
    private final JdbcWorkRegimeRecoverySupport recovery;
    private final JdbcWorkRegimeTargetAuthorizationSupport targetAuthorization;

    public JdbcWorkRegimeRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(20);
        this.reads = new JdbcWorkRegimeReadSupport(jdbc);
        this.simulations = new JdbcWorkRegimeSimulationPersistence(jdbc);
        this.evidence = new JdbcWorkRegimeEvidencePersistence(jdbc);
        this.recovery = new JdbcWorkRegimeRecoverySupport(jdbc);
        this.targetAuthorization = new JdbcWorkRegimeTargetAuthorizationSupport(jdbc);
    }

    @Override
    public List<WorkPlanRecord> findEffective(long tenantId, LocalDate effectiveOn) {
        requireTenant(tenantId);
        Objects.requireNonNull(effectiveOn, "effectiveOn must not be null");
        return inTenantTransaction(tenantId, () -> reads.findEffective(tenantId, effectiveOn));
    }

    @Override
    public List<WorkPlanRecord> findEffective(
            TargetAuthorizationGuard guard, LocalDate effectiveOn) {
        Objects.requireNonNull(guard, "guard must not be null");
        Objects.requireNonNull(effectiveOn, "effectiveOn must not be null");
        return inTenantTransaction(guard.tenantId(), () -> {
            targetAuthorization.lockActor(guard);
            return reads.findEffective(
                    guard.tenantId(), guard.populationPublicId(), effectiveOn);
        });
    }

    @Override
    public Optional<WorkPlanRecord> findByPublicId(long tenantId, UUID publicId) {
        requireTenant(tenantId);
        Objects.requireNonNull(publicId, "publicId must not be null");
        return inTenantTransaction(tenantId, () -> reads.findByPublicId(tenantId, publicId));
    }

    @Override
    public Optional<WorkPlanRecord> findByPublicId(
            TargetAuthorizationGuard guard, UUID publicId) {
        Objects.requireNonNull(guard, "guard must not be null");
        Objects.requireNonNull(publicId, "publicId must not be null");
        return inTenantTransaction(guard.tenantId(), () -> {
            if (targetAuthorization.tryLockPlanAccess(guard, publicId).isEmpty()) {
                return Optional.empty();
            }
            return reads.findByPublicId(
                    guard.tenantId(), publicId, guard.populationPublicId());
        });
    }

    @Override
    public List<RulePack> findRulePacks(
            long tenantId, String jurisdiction, long policyRevision) {
        requireResolutionKey(tenantId, jurisdiction, policyRevision);
        return inTenantTransaction(
                tenantId, () -> reads.findRulePacks(tenantId, jurisdiction, policyRevision));
    }

    @Override
    public List<PolicyCandidate> findPolicyCandidates(
            long tenantId, String jurisdiction, long policyRevision) {
        requireResolutionKey(tenantId, jurisdiction, policyRevision);
        return inTenantTransaction(
                tenantId,
                () -> reads.findPolicyCandidates(tenantId, jurisdiction, policyRevision));
    }

    @Override
    public List<PolicyCandidate> findPolicyCandidates(
            TargetAuthorizationGuard guard, String jurisdiction, long policyRevision) {
        Objects.requireNonNull(guard, "guard must not be null");
        requireResolutionKey(guard.tenantId(), jurisdiction, policyRevision);
        return inTenantTransaction(guard.tenantId(), () -> {
            targetAuthorization.lockActor(guard);
            return reads.findPolicyCandidates(
                    guard.tenantId(), guard.populationPublicId(), guard.populationRevision(),
                    guard.populationDigest(), jurisdiction, policyRevision);
        });
    }

    @Override
    public List<AssignmentPlan> findSimulationAssignments(
            long tenantId, UUID workRegimePublicId, EffectivePeriod requestedPeriod) {
        requireTenant(tenantId);
        Objects.requireNonNull(workRegimePublicId, "workRegimePublicId must not be null");
        requireClosedPeriod(requestedPeriod);
        return inTenantTransaction(
                tenantId,
                () -> reads.findSimulationAssignments(
                        tenantId, workRegimePublicId, requestedPeriod));
    }

    @Override
    public List<AssignmentPlan> findSimulationAssignments(
            TargetAuthorizationGuard guard,
            UUID workRegimePublicId,
            EffectivePeriod requestedPeriod) {
        Objects.requireNonNull(guard, "guard must not be null");
        Objects.requireNonNull(workRegimePublicId, "workRegimePublicId must not be null");
        requireClosedPeriod(requestedPeriod);
        return inTenantTransaction(guard.tenantId(), () -> {
            targetAuthorization.tryLockPlanAccess(guard, workRegimePublicId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Authorized work plan is unavailable"));
            return reads.findSimulationAssignments(
                    guard.tenantId(), workRegimePublicId,
                    guard.populationPublicId(), requestedPeriod);
        });
    }

    @Override
    public WorkPlanRecord createDraft(DraftWrite draft) {
        validateDraft(draft);
        return inTenantTransaction(draft.tenantId(), () -> {
            LockedTarget lockedTarget = targetAuthorization.authorizeCreate(draft);
            TenantExtensionWrite extension = draft.tenantExtension();
            Long regimeId = jdbc.queryForObject("""
                    INSERT INTO tim_work_regime_versions (
                        public_id, tenant_id, regime_key, revision, display_name,
                        lifecycle_state, arrangement_kind,
                        extension_schema_ref, extension_schema_version,
                        extension_payload, extension_payload_digest,
                        scope_type, scope_public_ref, precedence_priority,
                        effective_from, effective_to, default_zone_id,
                        rule_pack_public_id, policy_revision, resolution_digest,
                        template_schema_version, template_digest, author_actor_id,
                        correlation_id, created_by, updated_by
                    ) VALUES (
                        ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, CAST(? AS jsonb), ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    RETURNING work_regime_version_id
                    """, Long.class,
                    draft.publicId(),
                    draft.tenantId(),
                    draft.regimeKey(),
                    draft.revision(),
                    draft.displayName(),
                    draft.arrangementKind().name(),
                    extension == null ? null : extension.schemaRef(),
                    extension == null ? null : extension.schemaVersion(),
                    extension == null ? null : extension.payloadJson(),
                    extension == null ? null : extension.payloadDigest(),
                    draft.scopeType().name(),
                    draft.scopeRef(),
                    draft.priority(),
                    draft.period().from(),
                    draft.period().to(),
                    draft.zoneId(),
                    draft.rulePackPublicId(),
                    draft.policyRevision(),
                    draft.resolutionDigest(),
                    draft.templateSchemaVersion(),
                    draft.templateDigest(),
                    draft.authorActorId(),
                    draft.correlationId(),
                    draft.authorActorId(),
                    draft.authorActorId());
            long workRegimeVersionId = requireInternalId(regimeId, "work regime");

            insertTerms(draft, workRegimeVersionId);
            insertSegments(draft, workRegimeVersionId);
            Long assignmentInternalId = jdbc.queryForObject("""
                    INSERT INTO tim_work_plan_assignments (
                        public_id, tenant_id, worker_public_id,
                        people_assignment_public_id, people_assignment_revision,
                        work_regime_version_id, rule_pack_public_id,
                        effective_from, effective_to, zone_id,
                        jurisdiction_country, jurisdiction_subdivision, policy_revision,
                        lifecycle_state, source_context_digest, created_by, updated_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                    RETURNING work_plan_assignment_id
                    """,
                    Long.class,
                    draft.assignmentPublicId(),
                    draft.tenantId(),
                    draft.workerPublicId(),
                    draft.peopleAssignmentPublicId(),
                    draft.peopleAssignmentRevision(),
                    workRegimeVersionId,
                    draft.rulePackPublicId(),
                    draft.period().from(),
                    draft.period().to(),
                    draft.zoneId(),
                    draft.jurisdiction(),
                    draft.jurisdictionSubdivision(),
                    draft.policyRevision(),
                    draft.sourceContextDigest(),
                    draft.authorActorId(),
                    draft.authorActorId());
            long workPlanAssignmentId = requireInternalId(
                    assignmentInternalId, "work plan assignment");
            TargetBindingEvidence target = lockedTarget.binding();
            jdbc.update("""
                    INSERT INTO tim_work_plan_target_evidence (
                        tenant_id, work_plan_assignment_id,
                        population_public_id, population_revision,
                        membership_revision, people_assignment_revision,
                        author_actor_id, author_gateway_scope_key,
                        author_grant_revision, population_digest,
                        membership_digest, grant_digest, verified_at, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    draft.tenantId(),
                    workPlanAssignmentId,
                    target.populationPublicId(),
                    target.populationRevision(),
                    target.membershipRevision(),
                    target.peopleAssignmentRevision(),
                    target.authorActorId(),
                    target.authorGatewayScopeKey(),
                    target.authorGrantRevision(),
                    target.populationDigest(),
                    target.membershipDigest(),
                    target.grantDigest(),
                    java.sql.Timestamp.from(target.verifiedAt()),
                    target.authorActorId());

            targetAuthorization.appendCommandEvidence(
                    draft.commandEvidence(), draft.publicId(), lockedTarget);
            evidence.created(draft);

            return reads.findByPublicId(
                            draft.tenantId(), draft.publicId(),
                            lockedTarget.binding().populationPublicId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Created work regime could not be read back"));
        });
    }

    @Override
    public WorkRegimeRevision transition(TransitionWrite transition) {
        validateTransition(transition);
        return inTenantTransaction(
                transition.tenantId(), () -> transitionInTenantTransaction(transition));
    }

    @Override
    public void saveSimulation(SimulationWrite simulation) {
        JdbcWorkRegimeSimulationPersistence.validate(simulation);
        inTenantTransaction(simulation.tenantId(), () -> {
            saveSimulationInTenantTransaction(simulation);
            return Boolean.TRUE;
        });
    }

    @Override
    public WorkRegimeRevision saveSimulationAndTransition(
            SimulationWrite simulation, TransitionWrite transition) {
        validateSuccessfulSimulationTransition(simulation, transition);
        return inTenantTransaction(simulation.tenantId(), () -> {
            saveSimulationInTenantTransaction(simulation);
            return transitionInTenantTransaction(transition);
        });
    }

    @Override
    public Optional<StoredSimulation> findSimulationByReceipt(long tenantId, UUID receiptId) {
        requireTenant(tenantId);
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        return inTenantTransaction(
                tenantId, () -> simulations.findByReceipt(tenantId, receiptId));
    }

    @Override
    public Optional<StoredSimulation> findSimulationByReceipt(
            TargetAuthorizationGuard guard, UUID receiptId, UUID aggregateId) {
        Objects.requireNonNull(guard, "guard must not be null");
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        return inTenantTransaction(guard.tenantId(), () -> {
            if (targetAuthorization.tryLockPlanAccess(guard, aggregateId).isEmpty()) {
                return Optional.empty();
            }
            return simulations.findByReceipt(guard.tenantId(), receiptId).filter(
                    stored -> stored.workRegimePublicId().equals(aggregateId));
        });
    }

    @Override
    public Optional<CommandOutcomeEvidence> findCommandOutcome(
            long tenantId,
            UUID receiptId,
            UUID aggregateId,
            LifecycleAction operation) {
        requireTenant(tenantId);
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        return inTenantTransaction(
                tenantId,
                () -> recovery.findOutcome(tenantId, receiptId, aggregateId, operation));
    }

    @Override
    public Optional<CommandOutcomeEvidence> findCommandOutcome(
            TargetAuthorizationGuard guard,
            UUID receiptId,
            UUID aggregateId,
            LifecycleAction operation) {
        Objects.requireNonNull(guard, "guard must not be null");
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        return inTenantTransaction(guard.tenantId(), () -> {
            if (targetAuthorization.tryLockPlanAccess(guard, aggregateId).isEmpty()) {
                return Optional.empty();
            }
            return recovery.findOutcome(
                    guard.tenantId(), receiptId, aggregateId, operation);
        });
    }

    private void insertTerms(DraftWrite draft, long regimeId) {
        for (PolicyTermWrite term : draft.terms()) {
            Objects.requireNonNull(term, "policy term must not be null");
            jdbc.update("""
                    INSERT INTO tim_work_regime_policy_terms (
                        tenant_id, work_regime_version_id, extension_kind,
                        parameter_name, value_type, string_value, integer_value,
                        decimal_value, boolean_value, date_value, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    draft.tenantId(),
                    regimeId,
                    term.extensionKind().name(),
                    term.parameterName(),
                    term.valueType(),
                    term.stringValue(),
                    term.integerValue(),
                    term.decimalValue(),
                    term.booleanValue(),
                    term.dateValue(),
                    draft.authorActorId());
        }
    }

    private void insertSegments(DraftWrite draft, long regimeId) {
        Map<DayOfWeek, Integer> sequenceByDay = new HashMap<>();
        for (LocalSegment segment : draft.segments()) {
            Objects.requireNonNull(segment, "local segment must not be null");
            int sequence = sequenceByDay.merge(segment.dayOfWeek(), 1, Integer::sum);
            jdbc.update("""
                    INSERT INTO tim_work_regime_segments (
                        tenant_id, work_regime_version_id, iso_day_of_week,
                        segment_key, segment_sequence, segment_kind, local_start_time,
                        local_end_time, end_day_offset, dst_overlap_policy, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    draft.tenantId(),
                    regimeId,
                    segment.dayOfWeek().getValue(),
                    segment.key(),
                    sequence,
                    segment.kind().name(),
                    segment.start(),
                    segment.end(),
                    segment.endDayOffset(),
                    segment.overlapPolicy().name(),
                    draft.authorActorId());
        }
    }

    private WorkRegimeRevision transitionInTenantTransaction(TransitionWrite transition) {
        PolicyState expectedState = expectedStateBefore(transition.nextState());
        LockedTarget lockedTarget = targetAuthorization.authorizeExisting(
                transition.commandEvidence(), transition.publicId(),
                transition.expectedVersion(), expectedState);
        TransitionRow stored = exactlyOne(jdbc.query("""
                SELECT work_regime_version_id,
                       lifecycle_state,
                       version,
                       approval_actor_id,
                       approval_receipt_public_id,
                       published_by_actor_id,
                       publication_receipt_public_id
                  FROM tim_work_regime_versions
                 WHERE tenant_id = ?
                   AND public_id = ?
                 FOR UPDATE
                """, JdbcWorkRegimeRepository::mapTransitionRow,
                transition.tenantId(),
                transition.publicId()), "work regime", transition.publicId());
        if (stored.version() != transition.expectedVersion()) {
            throw new OptimisticLockingFailureException(
                    "Expected work-regime version " + transition.expectedVersion()
                            + " but found " + stored.version());
        }

        ApprovalEvidence approvalEvidence = transitionEvidence(stored, transition);
        int changed = jdbc.update("""
                UPDATE tim_work_regime_versions
                   SET lifecycle_state = ?,
                       approval_actor_id = ?,
                       approval_receipt_public_id = ?,
                       published_by_actor_id = ?,
                       publication_receipt_public_id = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ?
                   AND work_regime_version_id = ?
                   AND version = ?
                """,
                transition.nextState().name(),
                approvalEvidence.approvalActorId(),
                approvalEvidence.approvalReceiptId(),
                approvalEvidence.publisherActorId(),
                approvalEvidence.publicationReceiptId(),
                transition.updatedBy(),
                transition.tenantId(),
                stored.internalId(),
                transition.expectedVersion());
        if (changed != 1) {
            throw new OptimisticLockingFailureException(
                    "Work-regime transition lost its optimistic lock");
        }
        transitionAssignmentLifecycle(transition, stored.internalId());
        WorkRegimeRevision changedRevision = reads.loadRevision(
                transition.tenantId(), transition.publicId());
        targetAuthorization.appendCommandEvidence(
                transition.commandEvidence(), transition.publicId(), lockedTarget);
        evidence.transitioned(
                transition, changedRevision.revision(), stored.state(), stored.version(),
                changedRevision.version());
        return changedRevision;
    }

    private void saveSimulationInTenantTransaction(SimulationWrite simulation) {
        long expectedVersion = Objects.requireNonNull(
                simulation.commandEvidence().expectedVersion(),
                "simulation expectedVersion must not be null");
        LockedTarget lockedTarget = targetAuthorization.authorizeExisting(
                simulation.commandEvidence(), simulation.workRegimePublicId(),
                expectedVersion, PolicyState.VALIDATED);
        targetAuthorization.authorizeSimulationResultAssignments(
                simulation.commandEvidence().targetAuthorization(), lockedTarget,
                simulation.period(), simulation.result());
        simulations.save(simulation);
        targetAuthorization.appendCommandEvidence(
                simulation.commandEvidence(), simulation.workRegimePublicId(), lockedTarget);
        evidence.simulated(simulation);
    }

    private ApprovalEvidence transitionEvidence(
            TransitionRow stored, TransitionWrite transition) {
        if (transition.nextState() == PolicyState.APPROVED) {
            if (transition.approvalActorId() == null || transition.approvalReceiptId() == null
                    || transition.publisherActorId() != null
                    || transition.publicationReceiptId() != null) {
                throw new IllegalArgumentException(
                        "Approval transition requires only approval evidence");
            }
            return new ApprovalEvidence(
                    transition.approvalActorId(), transition.approvalReceiptId(), null, null);
        }
        if (transition.nextState() == PolicyState.PUBLISHED) {
            if (transition.publisherActorId() == null
                    || transition.publicationReceiptId() == null) {
                throw new IllegalArgumentException(
                        "Publication transition requires publisher evidence");
            }
            if (transition.approvalActorId() != null
                    && !transition.approvalActorId().equals(stored.approvalActorId())) {
                throw new IllegalArgumentException("Approval actor evidence does not match");
            }
            if (transition.approvalReceiptId() != null
                    && !transition.approvalReceiptId().equals(stored.approvalReceiptId())) {
                throw new IllegalArgumentException("Approval receipt evidence does not match");
            }
            return new ApprovalEvidence(
                    stored.approvalActorId(),
                    stored.approvalReceiptId(),
                    transition.publisherActorId(),
                    transition.publicationReceiptId());
        }
        if (transition.approvalActorId() != null || transition.approvalReceiptId() != null
                || transition.publisherActorId() != null
                || transition.publicationReceiptId() != null) {
            throw new IllegalArgumentException(
                    "Lifecycle transition carries evidence before its governed step");
        }
        return new ApprovalEvidence(
                stored.approvalActorId(),
                stored.approvalReceiptId(),
                stored.publisherActorId(),
                stored.publicationReceiptId());
    }

    private void transitionAssignmentLifecycle(TransitionWrite transition, long regimeId) {
        String nextAssignmentState = switch (transition.nextState()) {
            case PUBLISHED -> "PUBLISHED";
            case REJECTED -> "CANCELLED";
            default -> null;
        };
        if (nextAssignmentState == null) return;
        jdbc.update("""
                UPDATE tim_work_plan_assignments
                   SET lifecycle_state = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ?
                   AND work_regime_version_id = ?
                   AND lifecycle_state = 'DRAFT'
                """,
                nextAssignmentState,
                transition.updatedBy(),
                transition.tenantId(),
                regimeId);
    }

    private <T> T inTenantTransaction(long tenantId, Supplier<T> work) {
        requireTenant(tenantId);
        Objects.requireNonNull(work, "work must not be null");
        T value = transactions.execute(status -> {
            String expected = Long.toString(tenantId);
            String configured = jdbc.queryForObject(SET_TENANT_SQL, String.class, expected);
            if (!expected.equals(configured)) {
                throw new IllegalStateException("Database tenant context was not established");
            }
            return work.get();
        });
        return Objects.requireNonNull(value, "transaction returned null");
    }

    private static TransitionRow mapTransitionRow(ResultSet row, int number) throws SQLException {
        return new TransitionRow(
                row.getLong("work_regime_version_id"),
                PolicyState.valueOf(row.getString("lifecycle_state")),
                row.getLong("version"),
                nullableLong(row, "approval_actor_id"),
                nullableUuid(row, "approval_receipt_public_id"),
                nullableLong(row, "published_by_actor_id"),
                nullableUuid(row, "publication_receipt_public_id"));
    }

    private static void validateDraft(DraftWrite draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        requireTenant(draft.tenantId());
        Objects.requireNonNull(draft.publicId(), "publicId must not be null");
        Objects.requireNonNull(draft.assignmentPublicId(), "assignmentPublicId must not be null");
        Objects.requireNonNull(draft.workerPublicId(), "workerPublicId must not be null");
        Objects.requireNonNull(
                draft.peopleAssignmentPublicId(), "peopleAssignmentPublicId must not be null");
        Objects.requireNonNull(draft.rulePackPublicId(), "rulePackPublicId must not be null");
        Objects.requireNonNull(draft.arrangementKind(), "arrangementKind must not be null");
        Objects.requireNonNull(draft.scopeType(), "scopeType must not be null");
        Objects.requireNonNull(
                draft.targetBindingEvidence(), "targetBindingEvidence must not be null");
        Objects.requireNonNull(draft.correlationId(), "correlationId must not be null");
        Objects.requireNonNull(draft.period(), "period must not be null");
        if (draft.revision() < 1 || draft.policyRevision() < 1
                || draft.templateSchemaVersion() < 1 || draft.authorActorId() <= 0
                || draft.peopleAssignmentRevision() < 0) {
            throw new IllegalArgumentException("draft revisions and actor evidence are invalid");
        }
        if (draft.scopeType() != WorkRegimeModels.ScopeType.POPULATION
                || !draft.scopeRef().equals(WorkRegimeTargetPopulationResolver.stableScopeRef(
                        draft.targetBindingEvidence().populationPublicId()))) {
            throw new IllegalArgumentException(
                    "draft scope must be the resolved stable target population");
        }
        requireCountry(draft.jurisdiction());
        if (draft.jurisdictionSubdivision() == null
                || draft.jurisdictionSubdivision().length() > 12
                || !draft.jurisdictionSubdivision().equals(
                        draft.jurisdictionSubdivision().trim())) {
            throw new IllegalArgumentException("jurisdiction subdivision must be canonical");
        }
        if ((draft.arrangementKind() == ArrangementKind.TENANT_EXTENSION)
                != (draft.tenantExtension() != null)) {
            throw new IllegalArgumentException(
                    "tenant extension evidence must match the arrangement kind");
        }
        Objects.requireNonNull(draft.terms(), "terms must not be null");
        Objects.requireNonNull(draft.segments(), "segments must not be null");
    }

    private static void validateTransition(TransitionWrite transition) {
        Objects.requireNonNull(transition, "transition must not be null");
        requireTenant(transition.tenantId());
        Objects.requireNonNull(transition.publicId(), "publicId must not be null");
        Objects.requireNonNull(transition.nextState(), "nextState must not be null");
        Objects.requireNonNull(
                transition.commandEvidence(), "commandEvidence must not be null");
        if (transition.expectedVersion() < 1 || transition.updatedBy() <= 0) {
            throw new IllegalArgumentException("transition version and actor must be positive");
        }
        if (transition.commandEvidence().operation()
                != operationFor(transition.nextState())) {
            throw new IllegalArgumentException(
                    "transition state does not match its command operation");
        }
    }

    private static void validateSuccessfulSimulationTransition(
            SimulationWrite simulation, TransitionWrite transition) {
        JdbcWorkRegimeSimulationPersistence.validate(simulation);
        validateTransition(transition);
        if (simulation.result().state() != WorkRegimeModels.SimulationState.SUCCEEDED
                || transition.nextState() != PolicyState.SIMULATED) {
            throw new IllegalArgumentException(
                    "Atomic simulation transition requires a successful SIMULATED result");
        }
        if (simulation.tenantId() != transition.tenantId()
                || !simulation.workRegimePublicId().equals(transition.publicId())
                || simulation.actorId() != transition.updatedBy()
                || !simulation.commandEvidence().equals(transition.commandEvidence())) {
            throw new IllegalArgumentException(
                    "Simulation and transition must carry one aggregate command identity");
        }
    }

    private static void requireResolutionKey(
            long tenantId, String jurisdiction, long policyRevision) {
        requireTenant(tenantId);
        requireCountry(jurisdiction);
        if (policyRevision < 1) {
            throw new IllegalArgumentException("policyRevision must be at least one");
        }
    }

    private static void requireCountry(String jurisdiction) {
        if (jurisdiction == null || !jurisdiction.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("jurisdiction must be an ISO alpha-2 country");
        }
    }

    private static void requireClosedPeriod(EffectivePeriod period) {
        Objects.requireNonNull(period, "period must not be null");
        if (period.to() == null) {
            throw new IllegalArgumentException("operation requires a closed half-open period");
        }
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }

    private static LifecycleAction operationFor(PolicyState state) {
        return switch (state) {
            case VALIDATED -> LifecycleAction.VALIDATE;
            case SIMULATED -> LifecycleAction.SIMULATE;
            case IN_REVIEW -> LifecycleAction.SUBMIT_REVIEW;
            case APPROVED -> LifecycleAction.APPLY_APPROVAL;
            case PUBLISHED -> LifecycleAction.PUBLISH;
            default -> throw new IllegalArgumentException(
                    "No command operation exists for transition to " + state);
        };
    }

    private static PolicyState expectedStateBefore(PolicyState state) {
        return switch (state) {
            case VALIDATED -> PolicyState.DRAFT;
            case SIMULATED -> PolicyState.VALIDATED;
            case IN_REVIEW -> PolicyState.SIMULATED;
            case APPROVED -> PolicyState.IN_REVIEW;
            case PUBLISHED -> PolicyState.APPROVED;
            default -> throw new IllegalArgumentException(
                    "No governed predecessor exists for transition to " + state);
        };
    }

    private static long requireInternalId(Long id, String label) {
        if (id == null || id <= 0) {
            throw new IllegalStateException(label + " insert did not return an internal id");
        }
        return id;
    }

    private static UUID nullableUuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static <T> T exactlyOne(List<T> rows, String label, Object publicId) {
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one " + label + " for " + publicId + "; found " + rows.size());
        }
        return rows.getFirst();
    }

    private record TransitionRow(
            long internalId,
            PolicyState state,
            long version,
            Long approvalActorId,
            UUID approvalReceiptId,
            Long publisherActorId,
            UUID publicationReceiptId) {
    }

    private record ApprovalEvidence(
            Long approvalActorId,
            UUID approvalReceiptId,
            Long publisherActorId,
            UUID publicationReceiptId) {
    }

}
