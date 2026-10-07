package com.dwp.services.time.workregime;

import static com.dwp.services.time.workregime.WorkRegimeApplicationServiceIdempotencyReplayTest.TENANT_ID;
import static com.dwp.services.time.workregime.WorkRegimeApplicationServiceIdempotencyReplayTest.rulePack;

import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Command;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.IdempotencyConflictException;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptPhase;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptStore;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.RecoveryClaim;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Reservation;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.StoredReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.AssignmentPlan;
import com.dwp.services.time.workregime.WorkRegimeModels.CommandReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.ReceiptState;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePack;
import com.dwp.services.time.workregime.WorkRegimeModels.ScheduleTemplate;
import com.dwp.services.time.workregime.WorkRegimeModels.WorkRegimeRevision;
import com.dwp.services.time.workregime.WorkRegimeRepository.CommandOutcomeEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.DraftWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.SimulationWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.StoredSimulation;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetAuthorizationGuard;
import com.dwp.services.time.workregime.WorkRegimeRepository.TransitionWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.WorkPlanRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class WorkRegimeReplayTestDoubles {
    private WorkRegimeReplayTestDoubles() {
    }

    static final class FakeRepository implements WorkRegimeRepository {
        final Map<UUID, StoredSimulation> simulations = new HashMap<>();
        final Map<OutcomeKey, CommandOutcomeEvidence> outcomes = new HashMap<>();
        WorkPlanRecord plan;
        boolean rulePackAvailable = true;
        int packLookups;
        int candidateLookups;
        int assignmentLookups;
        int simulationLookups;
        int outcomeLookups;
        int createMutations;
        int transitionMutations;
        int simulationTransitionMutations;

        FakeRepository(WorkPlanRecord plan) {
            this.plan = plan;
        }

        @Override
        public List<WorkPlanRecord> findEffective(long tenantId, LocalDate effectiveOn) {
            return plan == null ? List.of() : List.of(plan);
        }

        @Override
        public List<WorkPlanRecord> findEffective(
                TargetAuthorizationGuard guard, LocalDate effectiveOn) {
            return findEffective(guard.tenantId(), effectiveOn);
        }

        @Override
        public Optional<WorkPlanRecord> findByPublicId(long tenantId, UUID publicId) {
            return plan != null
                    && plan.revision().tenantId() == tenantId
                    && plan.revision().publicId().equals(publicId)
                    ? Optional.of(plan) : Optional.empty();
        }

        @Override
        public Optional<WorkPlanRecord> findByPublicId(
                TargetAuthorizationGuard guard, UUID publicId) {
            return findByPublicId(guard.tenantId(), publicId);
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
        public List<PolicyCandidate> findPolicyCandidates(
                TargetAuthorizationGuard guard, String jurisdiction, long policyRevision) {
            return findPolicyCandidates(guard.tenantId(), jurisdiction, policyRevision);
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
                    plan.peopleAssignmentPublicId(),
                    plan.peopleAssignmentRevision(),
                    plan.assignmentPeriod(),
                    plan.zoneId(),
                    empty,
                    empty));
        }

        @Override
        public List<AssignmentPlan> findSimulationAssignments(
                TargetAuthorizationGuard guard,
                UUID workRegimePublicId,
                EffectivePeriod period) {
            return findSimulationAssignments(
                    guard.tenantId(), workRegimePublicId, period);
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
                    draft.peopleAssignmentPublicId(),
                    draft.peopleAssignmentRevision(),
                    draft.period(),
                    draft.targetBindingEvidence(),
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
        public Optional<StoredSimulation> findSimulationByReceipt(
                TargetAuthorizationGuard guard, UUID receiptId, UUID aggregateId) {
            return findSimulationByReceipt(guard.tenantId(), receiptId).filter(
                    stored -> stored.workRegimePublicId().equals(aggregateId));
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

        @Override
        public Optional<CommandOutcomeEvidence> findCommandOutcome(
                TargetAuthorizationGuard guard,
                UUID receiptId,
                UUID aggregateId,
                LifecycleAction operation) {
            return findCommandOutcome(
                    guard.tenantId(), receiptId, aggregateId, operation);
        }

        void seedSimulation(StoredSimulation simulation) {
            simulations.put(simulation.receiptId(), simulation);
        }

        void seedOutcome(
                UUID receiptId,
                UUID aggregateId,
                LifecycleAction operation,
                CommandOutcomeEvidence outcome) {
            outcomes.put(new OutcomeKey(receiptId, aggregateId, operation), outcome);
        }

        static StoredSimulation stored(SimulationWrite write, long baseVersion) {
            return new StoredSimulation(
                    write.receiptId(),
                    write.workRegimePublicId(),
                    baseVersion,
                    write.result(),
                    write.resultDigest());
        }

        static WorkPlanRecord advance(
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
                    transition.approvalActorId() == null
                            ? previous.approvalActorId() : transition.approvalActorId(),
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
                    current.peopleAssignmentPublicId(),
                    current.peopleAssignmentRevision(),
                    current.assignmentPeriod(),
                    current.targetBindingEvidence(),
                    current.segments());
        }

        record OutcomeKey(
                UUID receiptId, UUID aggregateId, LifecycleAction operation) {
        }
    }

    static final class InMemoryReceiptStore implements ReceiptStore {
        final Map<Origin, UUID> origins = new HashMap<>();
        final Map<UUID, StoredReceipt> receipts = new HashMap<>();
        int reserveCalls;

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

        synchronized void seed(CommandReceipt receipt) {
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

        synchronized StoredReceipt get(UUID receiptId) {
            return receipts.get(receiptId);
        }

        int size() {
            return receipts.size();
        }

        record Origin(long tenantId, LifecycleAction operation, UUID idempotencyKey) {
        }
    }
}
