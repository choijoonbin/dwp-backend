package com.dwp.services.time.workregime;

import static com.dwp.services.time.workregime.WorkRegimeApiModels.SIMULATION_PURPOSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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
import com.dwp.services.time.workregime.WorkRegimeModels.DstOverlapPolicy;
import com.dwp.services.time.workregime.WorkRegimeModels.Duty;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.LocalSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.ReceiptState;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePack;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePackState;
import com.dwp.services.time.workregime.WorkRegimeModels.ScheduleTemplate;
import com.dwp.services.time.workregime.WorkRegimeModels.ScopeType;
import com.dwp.services.time.workregime.WorkRegimeModels.SegmentKind;
import com.dwp.services.time.workregime.WorkRegimeOwnerAuthoritySource.VerifiedRequest;
import com.dwp.services.time.workregime.WorkRegimeRepository.StoredSimulation;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetAuthorizationGuard;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetBindingEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.WorkPlanRecord;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.PopulationAccess;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.TargetMembershipEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkRegimeApplicationServiceHostileBindingTest {

    private static final long TENANT_ID = 7L;
    private static final long ACTOR_ID = 41L;
    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 1);
    private static final UUID OWNED_POPULATION =
            UUID.fromString("07d5c896-7a4d-411b-9847-c4331de58ca4");
    private static final UUID FOREIGN_POPULATION =
            UUID.fromString("17d5c896-7a4d-411b-9847-c4331de58ca4");
    private static final String OWNED_SCOPE =
            WorkRegimeTargetPopulationResolver.stableScopeRef(OWNED_POPULATION);
    private static final String FOREIGN_SCOPE =
            WorkRegimeTargetPopulationResolver.stableScopeRef(FOREIGN_POPULATION);
    private static final String DECISION_REVISION = "psr-" + "a".repeat(64);
    private static final UUID PLAN_A = UUID.fromString("5b1ca21d-18a0-4bb5-a79d-fd116ca939f1");
    private static final UUID PLAN_B = UUID.fromString("f15df458-2696-4312-9ddc-9d772e27277b");
    private static final UUID RECEIPT_ID =
            UUID.fromString("97d5c896-7a4d-411b-9847-c4331de58ca4");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("8530078f-cd01-4ca7-8b8b-f28531bb9cf2");
    private static final UUID RULE_PACK_ID =
            UUID.fromString("7f7e6bc4-4a24-4ac2-b5d2-e085311b797c");

    @Test
    void receiptRejectsSameTenantForeignAggregateBeforeSimulationPayloadLookup() {
        WorkRegimeRepository repository = mock(WorkRegimeRepository.class);
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        receipts.seed(new CommandReceipt(
                TENANT_ID,
                RECEIPT_ID,
                IDEMPOTENCY_KEY,
                LifecycleAction.SIMULATE,
                PLAN_B,
                FOREIGN_SCOPE,
                1L,
                "c".repeat(64),
                ACTOR_ID,
                WorkRegimeLifecycleGuard.REQUIRED_PURPOSE,
                ReceiptState.RESULT_UNKNOWN,
                "RESULT_UNKNOWN",
                null,
                NOW));
        when(repository.findByPublicId(any(TargetAuthorizationGuard.class), eq(PLAN_B)))
                .thenReturn(Optional.of(workPlan(PLAN_B, FOREIGN_SCOPE)));
        WorkRegimeApplicationService service = service(repository, receipts);

        BaseException denied = catchThrowableOfType(
                BaseException.class,
                () -> service.receipt(verifiedRequest(), RECEIPT_ID));

        assertThat(denied).isNotNull();
        assertThat(denied.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(repository, never()).findByPublicId(
                any(TargetAuthorizationGuard.class), eq(PLAN_B));
        verify(repository, never()).findSimulationByReceipt(
                any(TargetAuthorizationGuard.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void sameSimulationKeyAndBodyCannotReplayAcrossDifferentWorkPlanIds() {
        WorkRegimeRepository repository = mock(WorkRegimeRepository.class);
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        when(repository.findByPublicId(
                any(TargetAuthorizationGuard.class), any(UUID.class)))
                .thenAnswer(invocation -> {
                    UUID planId = invocation.getArgument(1);
                    return Optional.of(workPlan(planId, OWNED_SCOPE));
                });
        when(repository.findRulePacks(TENANT_ID, "KR", 1L)).thenReturn(List.of());
        when(repository.findPolicyCandidates(
                any(TargetAuthorizationGuard.class), eq("KR"), eq(1L)))
                .thenReturn(List.of());
        when(repository.findSimulationByReceipt(
                any(TargetAuthorizationGuard.class), any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.<StoredSimulation>empty());
        WorkRegimeApplicationService service = service(repository, receipts);
        SimulationRequest body = new SimulationRequest(
                1L, "1", "1", SIMULATION_PURPOSE);

        SimulationCommandView first = service.simulate(
                verifiedRequest(), PLAN_A, IDEMPOTENCY_KEY, body);
        IdempotencyConflictException conflict = catchThrowableOfType(
                IdempotencyConflictException.class,
                () -> service.simulate(
                        verifiedRequest(), PLAN_B, IDEMPOTENCY_KEY, body));

        assertThat(first.receipt().workPlanId()).isEqualTo(PLAN_A.toString());
        assertThat(first.receipt().operation()).isEqualTo(LifecycleAction.SIMULATE.name());
        assertThat(first.receipt().idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY.toString());
        assertThat(first.receipt().status()).isEqualTo(ReceiptState.REJECTED.name());
        assertThat(first.simulation()).isNull();
        assertThat(conflict).isNotNull();

        assertThat(receipts.attemptedCommands())
                .extracting(Command::aggregateId)
                .containsExactly(PLAN_A);
        assertThat(receipts.receiptCount()).isOne();
        assertThat(receipts.onlyReceipt().aggregateId()).isEqualTo(PLAN_A);

        verify(repository, times(1)).findRulePacks(TENANT_ID, "KR", 1L);
        verify(repository, times(1)).findPolicyCandidates(
                any(TargetAuthorizationGuard.class), eq("KR"), eq(1L));
        verify(repository, times(1))
                .findSimulationByReceipt(
                        any(TargetAuthorizationGuard.class), any(UUID.class), eq(PLAN_A));
        verify(repository, never()).findSimulationAssignments(
                any(TargetAuthorizationGuard.class), any(), any());
        verify(repository, never()).saveSimulation(any());
    }

    @Test
    void successfulSimulationUsesAtomicPersistenceOperation() {
        WorkRegimeRepository repository = mock(WorkRegimeRepository.class);
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        WorkPlanRecord plan = workPlan(PLAN_A, OWNED_SCOPE);
        when(repository.findByPublicId(
                any(TargetAuthorizationGuard.class), eq(PLAN_A)))
                .thenReturn(Optional.of(plan));
        when(repository.findRulePacks(TENANT_ID, "KR", 1L)).thenReturn(List.of(rulePack()));
        when(repository.findPolicyCandidates(
                any(TargetAuthorizationGuard.class), eq("KR"), eq(1L)))
                .thenReturn(List.of(candidate()));
        ScheduleTemplate empty = new ScheduleTemplate("EMPTY", 1L, List.of());
        when(repository.findSimulationAssignments(
                any(TargetAuthorizationGuard.class), eq(PLAN_A), any()))
                .thenReturn(List.of(new AssignmentPlan(
                        TENANT_ID,
                        plan.assignmentPublicId(),
                        plan.workerPublicId(),
                        plan.peopleAssignmentPublicId(),
                        plan.peopleAssignmentRevision(),
                        plan.assignmentPeriod(),
                        plan.zoneId(),
                        empty,
                        empty)));
        when(repository.findSimulationByReceipt(
                any(TargetAuthorizationGuard.class), any(UUID.class), eq(PLAN_A)))
                .thenReturn(Optional.empty());
        WorkRegimeApplicationService service = service(repository, receipts);

        SimulationCommandView result = service.simulate(
                verifiedRequest(),
                PLAN_A,
                IDEMPOTENCY_KEY,
                new SimulationRequest(1L, "1", "1", SIMULATION_PURPOSE));

        assertThat(result.receipt().status()).isEqualTo(ReceiptState.SUCCEEDED.name());
        verify(repository, times(1)).saveSimulationAndTransition(any(), any());
        verify(repository, never()).saveSimulation(any());
        verify(repository, never()).transition(any());
    }

    @Test
    void blockedSimulationPersistsWithoutLifecycleTransition() {
        WorkRegimeRepository repository = mock(WorkRegimeRepository.class);
        InMemoryReceiptStore receipts = new InMemoryReceiptStore();
        WorkPlanRecord plan = workPlan(PLAN_A, OWNED_SCOPE);
        when(repository.findByPublicId(
                any(TargetAuthorizationGuard.class), eq(PLAN_A)))
                .thenReturn(Optional.of(plan));
        when(repository.findRulePacks(TENANT_ID, "KR", 1L)).thenReturn(List.of(rulePack()));
        when(repository.findPolicyCandidates(
                any(TargetAuthorizationGuard.class), eq("KR"), eq(1L)))
                .thenReturn(List.of(candidate()));
        ScheduleTemplate empty = new ScheduleTemplate("EMPTY", 1L, List.of());
        ScheduleTemplate overlapping = new ScheduleTemplate("OVERLAPPING", 1L, List.of(
                new LocalSegment(
                        "FIRST", DayOfWeek.MONDAY, SegmentKind.WORK,
                        LocalTime.of(9, 0), LocalTime.of(12, 0), 0,
                        DstOverlapPolicy.REJECT),
                new LocalSegment(
                        "SECOND", DayOfWeek.MONDAY, SegmentKind.WORK,
                        LocalTime.of(11, 0), LocalTime.of(14, 0), 0,
                        DstOverlapPolicy.REJECT)));
        when(repository.findSimulationAssignments(
                any(TargetAuthorizationGuard.class), eq(PLAN_A), any()))
                .thenReturn(List.of(new AssignmentPlan(
                        TENANT_ID,
                        plan.assignmentPublicId(),
                        plan.workerPublicId(),
                        plan.peopleAssignmentPublicId(),
                        plan.peopleAssignmentRevision(),
                        plan.assignmentPeriod(),
                        plan.zoneId(),
                        empty,
                        overlapping)));
        when(repository.findSimulationByReceipt(
                any(TargetAuthorizationGuard.class), any(UUID.class), eq(PLAN_A)))
                .thenReturn(Optional.empty());
        WorkRegimeApplicationService service = service(repository, receipts);

        SimulationCommandView result = service.simulate(
                verifiedRequest(),
                PLAN_A,
                IDEMPOTENCY_KEY,
                new SimulationRequest(1L, "1", "1", SIMULATION_PURPOSE));

        assertThat(result.receipt().status()).isEqualTo(ReceiptState.SUCCEEDED.name());
        verify(repository, times(1)).saveSimulation(any());
        verify(repository, never()).saveSimulationAndTransition(any(), any());
        verify(repository, never()).transition(any());
    }

    @Test
    void storedTargetBindingMustStillMatchTheCurrentOwnerProjection() {
        WorkRegimeRepository repository = mock(WorkRegimeRepository.class);
        WorkPlanRecord plan = workPlan(PLAN_A, OWNED_SCOPE);
        TargetBindingEvidence saved = plan.targetBindingEvidence();
        TargetBindingEvidence stale = new TargetBindingEvidence(
                saved.populationPublicId(),
                saved.populationRevision(),
                saved.workerPublicId(),
                saved.peopleAssignmentPublicId(),
                saved.peopleAssignmentRevision(),
                saved.membershipRevision() + 1,
                saved.authorActorId(),
                saved.authorGatewayScopeKey(),
                saved.authorGrantRevision(),
                saved.populationDigest(),
                "9".repeat(64),
                saved.grantDigest(),
                saved.verifiedAt());
        WorkPlanRecord stalePlan = new WorkPlanRecord(
                plan.revision(),
                plan.displayName(),
                plan.arrangementKind(),
                plan.extensionCode(),
                plan.scopeType(),
                plan.rulePackPublicId(),
                plan.jurisdiction(),
                plan.policyRevision(),
                plan.resolutionDigest(),
                plan.zoneId(),
                plan.assignmentPublicId(),
                plan.workerPublicId(),
                plan.peopleAssignmentPublicId(),
                plan.peopleAssignmentRevision(),
                plan.assignmentPeriod(),
                stale,
                plan.segments());
        when(repository.findByPublicId(
                any(TargetAuthorizationGuard.class), eq(PLAN_A)))
                .thenReturn(Optional.of(stalePlan));
        WorkRegimeApplicationService service = service(repository, new InMemoryReceiptStore());

        BaseException denied = catchThrowableOfType(
                BaseException.class,
                () -> service.simulate(
                        verifiedRequest(),
                        PLAN_A,
                        IDEMPOTENCY_KEY,
                        new SimulationRequest(1L, "1", "1", SIMULATION_PURPOSE)));

        assertThat(denied).isNotNull();
        assertThat(denied.getErrorCode())
                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        verify(repository, never()).findRulePacks(anyLong(), any(), anyLong());
        verify(repository, never()).findPolicyCandidates(
                any(TargetAuthorizationGuard.class), any(), anyLong());
        verify(repository, never()).saveSimulation(any());
        verify(repository, never()).transition(any());
    }

    @Test
    void ownerResolverResultCannotSubstituteAnotherWorkerForTheRequestedTarget() {
        WorkRegimeRepository repository = mock(WorkRegimeRepository.class);
        WorkPlanRecord plan = workPlan(PLAN_A, OWNED_SCOPE);
        when(repository.findByPublicId(
                any(TargetAuthorizationGuard.class), eq(PLAN_A)))
                .thenReturn(Optional.of(plan));
        WorkRegimeTargetPopulationResolver resolver =
                mock(WorkRegimeTargetPopulationResolver.class);
        when(resolver.resolveActorAccess(
                eq(TENANT_ID), eq(ACTOR_ID), any(String.class), any(Instant.class)))
                .thenReturn(Optional.of(new PopulationAccess(
                        TENANT_ID,
                        ACTOR_ID,
                        WorkRegimeTargetPopulationTestSupport.GATEWAY_SCOPE_A,
                        OWNED_POPULATION,
                        OWNED_SCOPE,
                        1L,
                        1L,
                        "a".repeat(64),
                        "b".repeat(64),
                        NOW.plusSeconds(3_600))));
        when(resolver.resolveTargetMembership(
                eq(TENANT_ID),
                eq(OWNED_POPULATION),
                eq(plan.workerPublicId()),
                eq(plan.peopleAssignmentPublicId()),
                eq(plan.peopleAssignmentRevision()),
                eq(plan.assignmentPeriod()),
                any(Instant.class)))
                .thenReturn(Optional.of(new TargetMembershipEvidence(
                        TENANT_ID,
                        OWNED_POPULATION,
                        1L,
                        UUID.fromString("27d5c896-7a4d-411b-9847-c4331de58ca4"),
                        plan.peopleAssignmentPublicId(),
                        plan.peopleAssignmentRevision(),
                        1L,
                        plan.assignmentPeriod(),
                        "a".repeat(64),
                        "c".repeat(64))));
        WorkRegimeApplicationService service = new WorkRegimeApplicationService(
                repository,
                new InMemoryReceiptStore(),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                resolver);

        BaseException denied = catchThrowableOfType(
                BaseException.class,
                () -> service.simulate(
                        verifiedRequest(),
                        PLAN_A,
                        IDEMPOTENCY_KEY,
                        new SimulationRequest(1L, "1", "1", SIMULATION_PURPOSE)));

        assertThat(denied).isNotNull();
        assertThat(denied.getErrorCode())
                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        verify(repository, never()).findRulePacks(anyLong(), any(), anyLong());
        verify(repository, never()).saveSimulation(any());
        verify(repository, never()).transition(any());
    }

    private static WorkRegimeApplicationService service(
            WorkRegimeRepository repository, ReceiptStore receipts) {
        return new WorkRegimeApplicationService(
                repository,
                receipts,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                WorkRegimeTargetPopulationTestSupport.resolverAnyMember(
                        TENANT_ID,
                        WorkRegimeTargetPopulationTestSupport.GATEWAY_SCOPE_A,
                        OWNED_POPULATION,
                        new EffectivePeriod(START, END),
                        NOW));
    }

    private static VerifiedRequest verifiedRequest() {
        Authority authority = new Authority(
                TENANT_ID,
                ACTOR_ID,
                Set.of(Duty.TIME_CONFIG_AUTHOR),
                Set.of(OWNED_SCOPE),
                WorkRegimeLifecycleGuard.REQUIRED_PURPOSE,
                DECISION_REVISION,
                false,
                false);
        return new VerifiedRequest(
                authority,
                WorkRegimeTargetPopulationTestSupport.GATEWAY_SCOPE_A,
                DECISION_REVISION,
                NOW.plusSeconds(300),
                UUID.fromString("669ea985-13f5-415f-8096-f6a4260d3c90"));
    }

    private static WorkPlanRecord workPlan(UUID publicId, String scopeRef) {
        EffectivePeriod period = new EffectivePeriod(START, END);
        var revision = new WorkRegimeModels.WorkRegimeRevision(
                TENANT_ID,
                publicId,
                1L,
                1L,
                PolicyState.VALIDATED,
                ACTOR_ID,
                null,
                scopeRef,
                period,
                "d".repeat(64));
        return new WorkPlanRecord(
                revision,
                "Hostile binding fixture",
                ArrangementKind.FIXED,
                null,
                ScopeType.POPULATION,
                RULE_PACK_ID,
                "KR",
                1L,
                "e".repeat(64),
                "Asia/Seoul",
                UUID.nameUUIDFromBytes((publicId + ":assignment").getBytes()),
                UUID.nameUUIDFromBytes((publicId + ":worker").getBytes()),
                UUID.nameUUIDFromBytes((publicId + ":assignment").getBytes()),
                1L,
                period,
                WorkRegimeTargetPopulationTestSupport.evidence(
                        OWNED_SCOPE.equals(scopeRef) ? OWNED_POPULATION : FOREIGN_POPULATION,
                        UUID.nameUUIDFromBytes((publicId + ":worker").getBytes()),
                        UUID.nameUUIDFromBytes((publicId + ":assignment").getBytes()),
                        1L,
                        ACTOR_ID,
                        WorkRegimeTargetPopulationTestSupport.GATEWAY_SCOPE_A,
                        NOW),
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
                "f".repeat(64));
    }

    private static PolicyCandidate candidate() {
        return new PolicyCandidate(
                TENANT_ID,
                PLAN_A,
                1L,
                ArrangementKind.FIXED,
                ScopeType.LEGAL_ENTITY,
                OWNED_SCOPE,
                1,
                new EffectivePeriod(START, END),
                RULE_PACK_ID,
                "KR",
                1L,
                PolicyState.VALIDATED,
                ACTOR_ID,
                "d".repeat(64));
    }

    private static final class InMemoryReceiptStore implements ReceiptStore {
        private final Map<Origin, UUID> origins = new HashMap<>();
        private final Map<UUID, StoredReceipt> receipts = new HashMap<>();
        private final List<Command> attempts = new ArrayList<>();

        @Override
        public synchronized Reservation reserve(Command command, Instant at) {
            attempts.add(command);
            Origin origin = new Origin(
                    command.tenantId(), command.operation(), command.idempotencyKey());
            UUID existingId = origins.get(origin);
            if (existingId != null) {
                StoredReceipt existing = receipts.get(existingId);
                WorkRegimeCommandCoordinator.requireExactBinding(command, existing.receipt());
                return new Reservation(existing, false);
            }
            UUID receiptId = UUID.nameUUIDFromBytes(
                    (origin + ":receipt").getBytes());
            CommandReceipt receipt = new CommandReceipt(
                    command.tenantId(), receiptId, command.idempotencyKey(), command.operation(),
                    command.aggregateId(), command.scopePublicRef(), command.expectedVersion(),
                    command.requestDigest(), command.authority().actorId(),
                    command.authority().purpose(), ReceiptState.ACCEPTED,
                    null, null, at);
            StoredReceipt stored = new StoredReceipt(receipt, ReceiptPhase.ACCEPTED);
            origins.put(origin, receiptId);
            receipts.put(receiptId, stored);
            return new Reservation(stored, true);
        }

        @Override
        public synchronized Optional<StoredReceipt> findByOrigin(Command command) {
            UUID receiptId = origins.get(new Origin(
                    command.tenantId(), command.operation(), command.idempotencyKey()));
            if (receiptId == null) return Optional.empty();
            StoredReceipt stored = receipts.get(receiptId);
            WorkRegimeCommandCoordinator.requireExactBinding(command, stored.receipt());
            return Optional.of(stored);
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
            StoredReceipt stored = find(tenantId, receiptId)
                    .orElseThrow(() -> new IllegalArgumentException("missing receipt"));
            boolean eligible = (stored.phase() == ReceiptPhase.ACCEPTED
                    || stored.phase() == ReceiptPhase.RUNNING)
                    && !stored.receipt().updatedAt().isAfter(staleBefore);
            if (!eligible) return new RecoveryClaim(stored, false);
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
            StoredReceipt stored = find(tenantId, receiptId)
                    .orElseThrow(() -> new IllegalArgumentException("missing receipt"));
            if (stored.phase() != expected || stored.phase().terminal()) {
                throw new IllegalStateException("invalid receipt transition");
            }
            CommandReceipt changed = stored.receipt().withState(
                    next.publicState(), resultCode, resultDigest, at);
            StoredReceipt updated = new StoredReceipt(changed, next);
            receipts.put(receiptId, updated);
            return updated;
        }

        void seed(CommandReceipt receipt) {
            ReceiptPhase phase = ReceiptPhase.valueOf(receipt.state().name());
            receipts.put(receipt.receiptId(), new StoredReceipt(receipt, phase));
        }

        List<Command> attemptedCommands() {
            return List.copyOf(attempts);
        }

        int receiptCount() {
            return receipts.size();
        }

        CommandReceipt onlyReceipt() {
            return receipts.values().iterator().next().receipt();
        }

        private record Origin(long tenantId, LifecycleAction operation, UUID idempotencyKey) {
        }
    }
}
