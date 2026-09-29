package com.dwp.services.time.workregime;

import static com.dwp.core.common.ErrorCode.NOT_FOUND;
import static com.dwp.services.time.workregime.WorkRegimeApiModels.SIMULATION_PURPOSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.dwp.core.exception.BaseException;
import com.dwp.services.time.workregime.WorkRegimeApiModels.CreateDraftRequest;
import com.dwp.services.time.workregime.WorkRegimeApiModels.CreateDraftView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SimulationCommandView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SimulationRequest;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Command;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.IdempotencyConflictException;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptPhase;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptStore;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.RecoveryClaim;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Reservation;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.StoredReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.ArrangementKind;
import com.dwp.services.time.workregime.WorkRegimeModels.AssignmentPlan;
import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import com.dwp.services.time.workregime.WorkRegimeModels.CommandReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.Duty;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.ReceiptState;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePack;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePackState;
import com.dwp.services.time.workregime.WorkRegimeModels.ScheduleTemplate;
import com.dwp.services.time.workregime.WorkRegimeModels.ScopeType;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationResult;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationState;
import com.dwp.services.time.workregime.WorkRegimeModels.WorkRegimeRevision;
import com.dwp.services.time.workregime.WorkRegimeOwnerAuthoritySource.VerifiedRequest;
import com.dwp.services.time.workregime.WorkRegimeRepository.CommandOutcomeEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.DraftWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.SimulationWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.StoredSimulation;
import com.dwp.services.time.workregime.WorkRegimeRepository.TransitionWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.WorkPlanRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkRegimeApplicationServiceIdempotencyReplayTest {

    private static final long TENANT_ID = 29L;
    private static final long ACTOR_ID = 701L;
    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");
    private static final LocalDate START = LocalDate.parse("2026-10-01");
    private static final LocalDate END = LocalDate.parse("2026-11-01");
    private static final String SCOPE = "LEGAL_ENTITY:SEOUL";
    private static final String OTHER_SCOPE = "LEGAL_ENTITY:BUSAN";
    private static final String PURPOSE = WorkRegimeLifecycleGuard.REQUIRED_PURPOSE;
    private static final String DECISION_ID = "psr-" + "a".repeat(64);
    private static final UUID PLAN_ID =
            UUID.fromString("97e82c58-8f47-4af5-99d3-d033642e4d70");
    private static final UUID RULE_PACK_ID =
            UUID.fromString("7ab5e1b0-d005-4a8d-a824-7fc37928eb7f");
    private static final UUID ASSIGNMENT_ID =
            UUID.fromString("dc8c9f43-670d-4efb-abbe-a4a8f669915c");
    private static final UUID WORKER_ID =
            UUID.fromString("f4dad8b5-b617-481d-bbdf-dc06dbf52ec8");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("1df15f79-f38d-466f-8290-4b84f4e52e70");

    @Test
    void simulationRetryReturnsOriginalReceiptAndSimulationBeforeStaleVersionChecks() {
        FakeRepository repository = new FakeRepository(
                workPlan(PLAN_ID, SCOPE, PolicyState.VALIDATED, 1L));
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        WorkRegimeApplicationService service = service(repository, receipts);
        SimulationRequest request = new SimulationRequest(
                1L, "1", "1", SIMULATION_PURPOSE);

        SimulationCommandView first = service.simulate(
                verified(ACTOR_ID, SCOPE, Set.of(SCOPE)),
                PLAN_ID,
                IDEMPOTENCY_KEY,
                request);
        assertThat(repository.plan.revision().version()).isEqualTo(2L);

        SimulationCommandView replay = service.simulate(
                verified(ACTOR_ID, SCOPE, Set.of(SCOPE)),
                PLAN_ID,
                IDEMPOTENCY_KEY,
                request);

        assertThat(first.receipt().status()).isEqualTo(ReceiptState.SUCCEEDED.name());
        assertThat(first.simulation()).isNotNull();
        assertThat(replay).isEqualTo(first);
        assertThat(repository.simulationTransitionMutations).isOne();
        assertThat(repository.assignmentLookups).isOne();
        assertThat(repository.packLookups).isOne();
        assertThat(repository.candidateLookups).isOne();
        assertThat(receipts.reserveCalls).isOne();
    }

    @Test
    void lifecycleRetryReturnsOriginalReceiptBeforeStaleVersionCheck() {
        FakeRepository repository = new FakeRepository(
                workPlan(PLAN_ID, SCOPE, PolicyState.SIMULATED, 2L));
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        WorkRegimeApplicationService service = service(repository, receipts);
        VerifiedRequest verified = verified(ACTOR_ID, SCOPE, Set.of(SCOPE));

        var first = service.transition(
                verified, PLAN_ID, IDEMPOTENCY_KEY, LifecycleAction.SUBMIT_REVIEW, 2L);
        assertThat(repository.plan.revision().version()).isEqualTo(3L);

        var replay = service.transition(
                verified, PLAN_ID, IDEMPOTENCY_KEY, LifecycleAction.SUBMIT_REVIEW, 2L);

        assertThat(first.status()).isEqualTo(ReceiptState.SUCCEEDED.name());
        assertThat(replay).isEqualTo(first);
        assertThat(repository.transitionMutations).isOne();
        assertThat(receipts.reserveCalls).isOne();
    }

    @Test
    void createRetryReturnsOriginalReceiptBeforeRulePackAvailabilityCheck() {
        FakeRepository repository = new FakeRepository(null);
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        WorkRegimeApplicationService service = service(repository, receipts);
        VerifiedRequest verified = verified(ACTOR_ID, SCOPE, Set.of(SCOPE));
        CreateDraftRequest request = draft(SCOPE, "Standard week");

        CreateDraftView first = service.createDraft(
                verified, IDEMPOTENCY_KEY, request);
        repository.rulePackAvailable = false;

        CreateDraftView replay = service.createDraft(
                verified, IDEMPOTENCY_KEY, request);

        assertThat(first.receipt().status()).isEqualTo(ReceiptState.SUCCEEDED.name());
        assertThat(replay).isEqualTo(first);
        assertThat(repository.createMutations).isOne();
        assertThat(repository.packLookups).isOne();
        assertThat(receipts.reserveCalls).isOne();
    }

    @Test
    void createRetryRejectsActorScopeAndBodyMismatchesForTheSameKey() {
        FakeRepository repository = new FakeRepository(null);
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        WorkRegimeApplicationService service = service(repository, receipts);
        Set<String> coveredScopes = Set.of(SCOPE, OTHER_SCOPE);
        CreateDraftRequest original = draft(SCOPE, "Standard week");

        service.createDraft(
                verified(ACTOR_ID, SCOPE, coveredScopes),
                IDEMPOTENCY_KEY,
                original);

        assertThatThrownBy(() -> service.createDraft(
                        verified(ACTOR_ID + 1, SCOPE, coveredScopes),
                        IDEMPOTENCY_KEY,
                        original))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.createDraft(
                        verified(ACTOR_ID, OTHER_SCOPE, coveredScopes),
                        IDEMPOTENCY_KEY,
                        draft(OTHER_SCOPE, "Standard week")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.createDraft(
                        verified(ACTOR_ID, SCOPE, coveredScopes),
                        IDEMPOTENCY_KEY,
                        draft(SCOPE, "Changed body")))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(repository.createMutations).isOne();
        assertThat(repository.packLookups).isOne();
        assertThat(receipts.reserveCalls).isOne();
        assertThat(receipts.size()).isOne();
    }

    @Test
    void receiptLookupMakesActorScopePurposeAndDutyMismatchesOpaque() {
        FakeRepository repository = new FakeRepository(null);
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        UUID receiptId = UUID.fromString("8f334fb5-fef5-4ccf-85b7-a126a2721652");
        receipts.seed(receipt(
                receiptId,
                LifecycleAction.SIMULATE,
                ReceiptState.SUCCEEDED,
                "SIMULATION_SUCCEEDED",
                "e".repeat(64),
                NOW));
        WorkRegimeApplicationService service = service(repository, receipts);

        assertOpaqueNotFound(service, receiptId, verified(
                ACTOR_ID + 1,
                SCOPE,
                Set.of(SCOPE),
                Set.of(Duty.TIME_CONFIG_AUTHOR),
                PURPOSE));
        assertOpaqueNotFound(service, receiptId, verified(
                ACTOR_ID,
                OTHER_SCOPE,
                Set.of(OTHER_SCOPE),
                Set.of(Duty.TIME_CONFIG_AUTHOR),
                PURPOSE));
        assertOpaqueNotFound(service, receiptId, verified(
                ACTOR_ID,
                SCOPE,
                Set.of(SCOPE),
                Set.of(Duty.TIME_CONFIG_AUTHOR),
                "TIME_REPORTING"));
        assertOpaqueNotFound(service, receiptId, verified(
                ACTOR_ID,
                SCOPE,
                Set.of(SCOPE),
                Set.of(Duty.TIME_AUDITOR),
                PURPOSE));

        assertThat(receipts.get(receiptId).receipt().state())
                .isEqualTo(ReceiptState.SUCCEEDED);
        assertThat(repository.outcomeLookups).isZero();
        assertThat(repository.simulationLookups).isZero();
    }

    @Test
    void resultUnknownSimulationRecoversOriginalReceiptFromStoredEvidence() {
        FakeRepository repository = new FakeRepository(
                workPlan(PLAN_ID, SCOPE, PolicyState.SIMULATED, 2L));
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        UUID receiptId = UUID.fromString("d0e91ecf-0941-4d4f-8106-b65a14a8fb72");
        String resultDigest = "f".repeat(64);
        receipts.seed(receipt(
                receiptId,
                LifecycleAction.SIMULATE,
                ReceiptState.RESULT_UNKNOWN,
                "RESULT_UNKNOWN",
                null,
                NOW));
        repository.seedSimulation(new StoredSimulation(
                receiptId,
                PLAN_ID,
                1L,
                successfulSimulation(),
                resultDigest));
        WorkRegimeApplicationService service = service(repository, receipts);

        SimulationCommandView recovered = service.receipt(
                verified(ACTOR_ID, SCOPE, Set.of(SCOPE)), receiptId);

        assertThat(recovered.receipt().receiptId()).isEqualTo(receiptId.toString());
        assertThat(recovered.receipt().status()).isEqualTo(ReceiptState.SUCCEEDED.name());
        assertThat(recovered.simulation()).isNotNull();
        assertThat(receipts.get(receiptId).receipt().resultCode())
                .isEqualTo("SIMULATION_SUCCEEDED");
        assertThat(receipts.get(receiptId).receipt().resultDigest())
                .isEqualTo(resultDigest);
        assertThat(receipts.reserveCalls).isZero();
        assertThat(receipts.size()).isOne();
    }

    @Test
    void staleAndResultUnknownLifecycleReceiptsRecoverFromCommandOutcomeEvidence() {
        FakeRepository repository = new FakeRepository(null);
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        UUID staleReceiptId = UUID.fromString("87c88e8e-cc4b-4057-8426-7b8928490c42");
        UUID unknownReceiptId = UUID.fromString("fddce5dc-e657-49b5-937b-f70d1d8fa311");
        String staleDigest = "1".repeat(64);
        String unknownDigest = "2".repeat(64);
        receipts.seed(receipt(
                staleReceiptId,
                LifecycleAction.SUBMIT_REVIEW,
                ReceiptState.RUNNING,
                null,
                null,
                NOW.minus(Duration.ofMinutes(6))));
        receipts.seed(receipt(
                unknownReceiptId,
                LifecycleAction.VALIDATE,
                ReceiptState.RESULT_UNKNOWN,
                "RESULT_UNKNOWN",
                null,
                NOW));
        repository.seedOutcome(
                staleReceiptId,
                PLAN_ID,
                LifecycleAction.SUBMIT_REVIEW,
                new CommandOutcomeEvidence("SUBMIT_REVIEW_SUCCEEDED", staleDigest));
        repository.seedOutcome(
                unknownReceiptId,
                PLAN_ID,
                LifecycleAction.VALIDATE,
                new CommandOutcomeEvidence("VALIDATE_SUCCEEDED", unknownDigest));
        WorkRegimeApplicationService service = service(repository, receipts);
        VerifiedRequest verified = verified(ACTOR_ID, SCOPE, Set.of(SCOPE));

        var staleRecovered = service.receipt(verified, staleReceiptId);
        var unknownRecovered = service.receipt(verified, unknownReceiptId);

        assertThat(staleRecovered.receipt().receiptId())
                .isEqualTo(staleReceiptId.toString());
        assertThat(staleRecovered.receipt().status())
                .isEqualTo(ReceiptState.SUCCEEDED.name());
        assertThat(unknownRecovered.receipt().receiptId())
                .isEqualTo(unknownReceiptId.toString());
        assertThat(unknownRecovered.receipt().status())
                .isEqualTo(ReceiptState.SUCCEEDED.name());
        assertThat(receipts.get(staleReceiptId).receipt().resultDigest())
                .isEqualTo(staleDigest);
        assertThat(receipts.get(unknownReceiptId).receipt().resultDigest())
                .isEqualTo(unknownDigest);
        assertThat(repository.outcomeLookups).isEqualTo(2);
        assertThat(receipts.reserveCalls).isZero();
    }

    @Test
    void resultUnknownReceiptWithoutMutationEvidenceBecomesFailed() {
        FakeRepository repository = new FakeRepository(null);
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        UUID receiptId = UUID.fromString("f41aa033-a136-407f-931c-0ee3fc67ad29");
        receipts.seed(receipt(
                receiptId,
                LifecycleAction.VALIDATE,
                ReceiptState.RESULT_UNKNOWN,
                "RESULT_UNKNOWN",
                null,
                NOW));
        WorkRegimeApplicationService service = service(repository, receipts);

        var recovered = service.receipt(
                verified(ACTOR_ID, SCOPE, Set.of(SCOPE)), receiptId);

        assertThat(recovered.receipt().receiptId()).isEqualTo(receiptId.toString());
        assertThat(recovered.receipt().status()).isEqualTo(ReceiptState.FAILED.name());
        assertThat(recovered.simulation()).isNull();
        assertThat(receipts.get(receiptId).receipt().resultCode())
                .isEqualTo("COMMAND_NOT_APPLIED");
        assertThat(receipts.get(receiptId).receipt().resultDigest()).isNull();
        assertThat(repository.outcomeLookups).isOne();
        assertThat(receipts.size()).isOne();
    }

    private static WorkRegimeApplicationService service(
            WorkRegimeRepository repository, ReceiptStore receipts) {
        return new WorkRegimeApplicationService(
                repository,
                receipts,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static VerifiedRequest verified(
            long actorId, String contextScope, Set<String> scopes) {
        return verified(
                actorId,
                contextScope,
                scopes,
                Set.of(Duty.TIME_CONFIG_AUTHOR),
                PURPOSE);
    }

    private static VerifiedRequest verified(
            long actorId,
            String contextScope,
            Set<String> scopes,
            Set<Duty> duties,
            String purpose) {
        Authority authority = new Authority(
                TENANT_ID,
                actorId,
                duties,
                scopes,
                purpose,
                DECISION_ID,
                false,
                false);
        return new VerifiedRequest(
                authority,
                contextScope,
                DECISION_ID,
                NOW.plusSeconds(300),
                UUID.nameUUIDFromBytes(
                        (actorId + ":" + contextScope).getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertOpaqueNotFound(
            WorkRegimeApplicationService service,
            UUID receiptId,
            VerifiedRequest verified) {
        BaseException denied = catchThrowableOfType(
                BaseException.class,
                () -> service.receipt(verified, receiptId));
        assertThat(denied).isNotNull();
        assertThat(denied.getErrorCode()).isEqualTo(NOT_FOUND);
    }

    private static CommandReceipt receipt(
            UUID receiptId,
            LifecycleAction action,
            ReceiptState state,
            String resultCode,
            String resultDigest,
            Instant updatedAt) {
        return new CommandReceipt(
                TENANT_ID,
                receiptId,
                UUID.nameUUIDFromBytes(
                        (receiptId + ":key").getBytes(StandardCharsets.UTF_8)),
                action,
                PLAN_ID,
                SCOPE,
                1L,
                "9".repeat(64),
                ACTOR_ID,
                PURPOSE,
                state,
                resultCode,
                resultDigest,
                updatedAt);
    }

    private static SimulationResult successfulSimulation() {
        return new SimulationResult(
                SimulationState.SUCCEEDED,
                NOW,
                "2025a",
                1L,
                List.of(),
                List.of(),
                List.of());
    }

    private static CreateDraftRequest draft(String scope, String displayName) {
        return new CreateDraftRequest(
                "standard-week",
                displayName,
                ArrangementKind.FIXED,
                null,
                ScopeType.LEGAL_ENTITY,
                scope,
                100,
                START,
                END,
                "Asia/Seoul",
                RULE_PACK_ID,
                "KR",
                "11",
                1L,
                1,
                WORKER_ID,
                ASSIGNMENT_ID,
                1L,
                List.of(),
                List.of());
    }

    private static WorkPlanRecord workPlan(
            UUID publicId, String scope, PolicyState state, long version) {
        EffectivePeriod period = new EffectivePeriod(START, END);
        WorkRegimeRevision revision = new WorkRegimeRevision(
                TENANT_ID,
                publicId,
                1L,
                version,
                state,
                ACTOR_ID,
                null,
                scope,
                period,
                "b".repeat(64));
        return new WorkPlanRecord(
                revision,
                "Replay fixture",
                ArrangementKind.FIXED,
                null,
                ScopeType.LEGAL_ENTITY,
                RULE_PACK_ID,
                "KR",
                1L,
                "c".repeat(64),
                "Asia/Seoul",
                ASSIGNMENT_ID,
                WORKER_ID,
                1L,
                period,
                List.of());
    }

    private static RulePack rulePack() {
        return new RulePack(
                TENANT_ID,
                RULE_PACK_ID,
                "KR",
                1L,
                RulePackState.PUBLISHED,
                new EffectivePeriod(START, END),
                true,
                "d".repeat(64));
    }

    private static final class FakeRepository implements WorkRegimeRepository {
        private final Map<UUID, StoredSimulation> simulations = new HashMap<>();
        private final Map<OutcomeKey, CommandOutcomeEvidence> outcomes = new HashMap<>();
        private WorkPlanRecord plan;
        private boolean rulePackAvailable = true;
        private int packLookups;
        private int candidateLookups;
        private int assignmentLookups;
        private int simulationLookups;
        private int outcomeLookups;
        private int createMutations;
        private int transitionMutations;
        private int simulationTransitionMutations;

        private FakeRepository(WorkPlanRecord plan) {
            this.plan = plan;
        }

        @Override
        public List<WorkPlanRecord> findEffective(long tenantId, LocalDate effectiveOn) {
            return plan == null ? List.of() : List.of(plan);
        }

        @Override
        public Optional<WorkPlanRecord> findByPublicId(long tenantId, UUID publicId) {
            return plan != null
                    && plan.revision().tenantId() == tenantId
                    && plan.revision().publicId().equals(publicId)
                    ? Optional.of(plan) : Optional.empty();
        }

        @Override
        public List<RulePack> findRulePacks(
                long tenantId, String jurisdiction, long policyRevision) {
            packLookups++;
            return rulePackAvailable ? List.of(rulePack()) : List.of();
        }

        @Override
        public List<PolicyCandidate> findPolicyCandidates(
                long tenantId, String jurisdiction, long policyRevision) {
            candidateLookups++;
            if (plan == null) return List.of();
            WorkRegimeRevision revision = plan.revision();
            return List.of(new PolicyCandidate(
                    tenantId,
                    revision.publicId(),
                    revision.revision(),
                    plan.arrangementKind(),
                    plan.scopeType(),
                    revision.scopeRef(),
                    100,
                    revision.period(),
                    plan.rulePackPublicId(),
                    plan.jurisdiction(),
                    plan.policyRevision(),
                    revision.state(),
                    revision.authorActorId(),
                    revision.artifactDigest()));
        }

        @Override
        public List<AssignmentPlan> findSimulationAssignments(
                long tenantId, UUID workRegimePublicId, EffectivePeriod period) {
            assignmentLookups++;
            ScheduleTemplate empty = new ScheduleTemplate("EMPTY", 1L, List.of());
            return List.of(new AssignmentPlan(
                    tenantId,
                    plan.assignmentPublicId(),
                    plan.workerPublicId(),
                    plan.peopleAssignmentRevision(),
                    plan.assignmentPeriod(),
                    plan.zoneId(),
                    empty,
                    empty));
        }

        @Override
        public WorkPlanRecord createDraft(DraftWrite draft) {
            createMutations++;
            WorkRegimeRevision revision = new WorkRegimeRevision(
                    draft.tenantId(),
                    draft.publicId(),
                    draft.revision(),
                    1L,
                    PolicyState.DRAFT,
                    draft.authorActorId(),
                    null,
                    draft.scopeRef(),
                    draft.period(),
                    draft.templateDigest());
            plan = new WorkPlanRecord(
                    revision,
                    draft.displayName(),
                    draft.arrangementKind(),
                    null,
                    draft.scopeType(),
                    draft.rulePackPublicId(),
                    draft.jurisdiction(),
                    draft.policyRevision(),
                    draft.resolutionDigest(),
                    draft.zoneId(),
                    draft.assignmentPublicId(),
                    draft.workerPublicId(),
                    draft.peopleAssignmentRevision(),
                    draft.period(),
                    draft.segments());
            return plan;
        }

        @Override
        public WorkRegimeRevision transition(TransitionWrite transition) {
            transitionMutations++;
            plan = advance(plan, transition);
            return plan.revision();
        }

        @Override
        public void saveSimulation(SimulationWrite simulation) {
            simulations.put(simulation.receiptId(), stored(simulation, plan.revision().version()));
        }

        @Override
        public WorkRegimeRevision saveSimulationAndTransition(
                SimulationWrite simulation, TransitionWrite transition) {
            simulationTransitionMutations++;
            simulations.put(
                    simulation.receiptId(), stored(simulation, transition.expectedVersion()));
            plan = advance(plan, transition);
            return plan.revision();
        }

        @Override
        public Optional<StoredSimulation> findSimulationByReceipt(
                long tenantId, UUID receiptId) {
            simulationLookups++;
            StoredSimulation stored = simulations.get(receiptId);
            return stored != null && tenantId == TENANT_ID
                    ? Optional.of(stored) : Optional.empty();
        }

        @Override
        public Optional<CommandOutcomeEvidence> findCommandOutcome(
                long tenantId,
                UUID receiptId,
                UUID aggregateId,
                LifecycleAction operation) {
            outcomeLookups++;
            if (tenantId != TENANT_ID) return Optional.empty();
            return Optional.ofNullable(outcomes.get(
                    new OutcomeKey(receiptId, aggregateId, operation)));
        }

        private void seedSimulation(StoredSimulation simulation) {
            simulations.put(simulation.receiptId(), simulation);
        }

        private void seedOutcome(
                UUID receiptId,
                UUID aggregateId,
                LifecycleAction operation,
                CommandOutcomeEvidence outcome) {
            outcomes.put(new OutcomeKey(receiptId, aggregateId, operation), outcome);
        }

        private static StoredSimulation stored(SimulationWrite write, long baseVersion) {
            return new StoredSimulation(
                    write.receiptId(),
                    write.workRegimePublicId(),
                    baseVersion,
                    write.result(),
                    write.resultDigest());
        }

        private static WorkPlanRecord advance(
                WorkPlanRecord current, TransitionWrite transition) {
            WorkRegimeRevision previous = current.revision();
            if (previous.version() != transition.expectedVersion()) {
                throw new IllegalStateException("stale fake repository transition");
            }
            WorkRegimeRevision next = new WorkRegimeRevision(
                    previous.tenantId(),
                    previous.publicId(),
                    previous.revision(),
                    previous.version() + 1,
                    transition.nextState(),
                    previous.authorActorId(),
                    transition.approvalActorId(),
                    previous.scopeRef(),
                    previous.period(),
                    previous.artifactDigest());
            return new WorkPlanRecord(
                    next,
                    current.displayName(),
                    current.arrangementKind(),
                    current.extensionCode(),
                    current.scopeType(),
                    current.rulePackPublicId(),
                    current.jurisdiction(),
                    current.policyRevision(),
                    current.resolutionDigest(),
                    current.zoneId(),
                    current.assignmentPublicId(),
                    current.workerPublicId(),
                    current.peopleAssignmentRevision(),
                    current.assignmentPeriod(),
                    current.segments());
        }

        private record OutcomeKey(
                UUID receiptId, UUID aggregateId, LifecycleAction operation) {
        }
    }

    private static final class InMemoryReceiptStore implements ReceiptStore {
        private final Map<Origin, UUID> origins = new HashMap<>();
        private final Map<UUID, StoredReceipt> receipts = new HashMap<>();
        private int reserveCalls;

        @Override
        public synchronized Reservation reserve(Command command, Instant at) {
            reserveCalls++;
            Origin origin = new Origin(
                    command.tenantId(), command.operation(), command.idempotencyKey());
            UUID existingId = origins.get(origin);
            if (existingId != null) {
                StoredReceipt existing = receipts.get(existingId);
                WorkRegimeCommandCoordinator.requireExactBinding(command, existing.receipt());
                return new Reservation(existing, false);
            }
            UUID receiptId = UUID.nameUUIDFromBytes(
                    (origin + ":receipt").getBytes(StandardCharsets.UTF_8));
            CommandReceipt receipt = new CommandReceipt(
                    command.tenantId(),
                    receiptId,
                    command.idempotencyKey(),
                    command.operation(),
                    command.aggregateId(),
                    command.scopePublicRef(),
                    command.expectedVersion(),
                    command.requestDigest(),
                    command.authority().actorId(),
                    command.authority().purpose(),
                    ReceiptState.ACCEPTED,
                    null,
                    null,
                    at);
            StoredReceipt stored = new StoredReceipt(receipt, ReceiptPhase.ACCEPTED);
            origins.put(origin, receiptId);
            receipts.put(receiptId, stored);
            return new Reservation(stored, true);
        }

        @Override
        public synchronized Optional<StoredReceipt> findByOrigin(Command command) {
            UUID receiptId = origins.get(new Origin(
                    command.tenantId(), command.operation(), command.idempotencyKey()));
            return receiptId == null ? Optional.empty() : Optional.of(receipts.get(receiptId));
        }

        @Override
        public synchronized Optional<StoredReceipt> find(long tenantId, UUID receiptId) {
            StoredReceipt stored = receipts.get(receiptId);
            return stored != null && stored.receipt().tenantId() == tenantId
                    ? Optional.of(stored) : Optional.empty();
        }

        @Override
        public synchronized RecoveryClaim claimStaleForRecovery(
                long tenantId,
                UUID receiptId,
                Instant staleBefore,
                Instant at) {
            StoredReceipt stored = find(tenantId, receiptId).orElseThrow();
            boolean stale = (stored.phase() == ReceiptPhase.ACCEPTED
                    || stored.phase() == ReceiptPhase.RUNNING)
                    && !stored.receipt().updatedAt().isAfter(staleBefore);
            if (!stale) return new RecoveryClaim(stored, false);
            CommandReceipt changed = stored.receipt().withState(
                    ReceiptState.RESULT_UNKNOWN, "RESULT_UNKNOWN", null, at);
            StoredReceipt claimed = new StoredReceipt(changed, ReceiptPhase.RESULT_UNKNOWN);
            receipts.put(receiptId, claimed);
            return new RecoveryClaim(claimed, true);
        }

        @Override
        public synchronized StoredReceipt transition(
                long tenantId,
                UUID receiptId,
                ReceiptPhase expected,
                ReceiptPhase next,
                String resultCode,
                String resultDigest,
                Instant at) {
            StoredReceipt current = find(tenantId, receiptId).orElseThrow();
            if (current.phase() != expected || current.phase().terminal()) {
                throw new IllegalStateException("invalid fake receipt transition");
            }
            CommandReceipt changed = current.receipt().withState(
                    next.publicState(), resultCode, resultDigest, at);
            StoredReceipt updated = new StoredReceipt(changed, next);
            receipts.put(receiptId, updated);
            return updated;
        }

        private synchronized void seed(CommandReceipt receipt) {
            ReceiptPhase phase = ReceiptPhase.valueOf(receipt.state().name());
            StoredReceipt stored = new StoredReceipt(receipt, phase);
            origins.put(
                    new Origin(
                            receipt.tenantId(),
                            receipt.operation(),
                            receipt.idempotencyKey()),
                    receipt.receiptId());
            receipts.put(receipt.receiptId(), stored);
        }

        private synchronized StoredReceipt get(UUID receiptId) {
            return receipts.get(receiptId);
        }

        int size() {
            return receipts.size();
        }

        private record Origin(long tenantId, LifecycleAction operation, UUID idempotencyKey) {
        }
    }
}
