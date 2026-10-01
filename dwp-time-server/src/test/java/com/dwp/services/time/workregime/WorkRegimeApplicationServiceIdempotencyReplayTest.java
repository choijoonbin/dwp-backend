package com.dwp.services.time.workregime;

import static com.dwp.core.common.ErrorCode.NOT_FOUND;
import static com.dwp.services.time.workregime.WorkRegimeApiModels.SIMULATION_PURPOSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static com.dwp.services.time.workregime.WorkRegimeTargetPopulationTestSupport.GATEWAY_SCOPE_A;
import static com.dwp.services.time.workregime.WorkRegimeTargetPopulationTestSupport.GATEWAY_SCOPE_B;
import static com.dwp.services.time.workregime.WorkRegimeTargetPopulationTestSupport.GATEWAY_SCOPE_C;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
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
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetAuthorizationGuard;
import com.dwp.services.time.workregime.WorkRegimeRepository.TransitionWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.WorkPlanRecord;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.PopulationAccess;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.TargetMembershipEvidence;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkRegimeApplicationServiceIdempotencyReplayTest {

    private static final long TENANT_ID = 29L;
    private static final long ACTOR_ID = 701L;
    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");
    private static final LocalDate START = LocalDate.parse("2026-10-01");
    private static final LocalDate END = LocalDate.parse("2026-11-01");
    private static final UUID POPULATION_ID =
            UUID.fromString("29e82c58-8f47-4af5-99d3-d033642e4d70");
    private static final UUID OTHER_POPULATION_ID =
            UUID.fromString("29e82c58-8f47-4af5-99d3-d033642e4d71");
    private static final String SCOPE =
            WorkRegimeTargetPopulationResolver.stableScopeRef(POPULATION_ID);
    private static final String OTHER_SCOPE =
            WorkRegimeTargetPopulationResolver.stableScopeRef(OTHER_POPULATION_ID);
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
    void threeIndependentActorsCanAuthorApproveAndPublishOneStablePopulation() {
        long approverId = ACTOR_ID + 1;
        long publisherId = ACTOR_ID + 2;
        WorkRegimeTargetPopulationResolver resolver =
                WorkRegimeTargetPopulationTestSupport.resolver(
                        TENANT_ID,
                        Map.of(
                                GATEWAY_SCOPE_A, POPULATION_ID,
                                GATEWAY_SCOPE_B, POPULATION_ID,
                                GATEWAY_SCOPE_C, POPULATION_ID),
                        WORKER_ID,
                        ASSIGNMENT_ID,
                        1L,
                        new EffectivePeriod(START, END),
                        NOW);
        FakeRepository repository = new FakeRepository(
                workPlan(PLAN_ID, SCOPE, PolicyState.SIMULATED, 2L));
        WorkRegimeApplicationService service = service(
                repository, new InMemoryReceiptStore(), resolver);

        VerifiedRequest author = verifiedExact(
                ACTOR_ID, GATEWAY_SCOPE_A, Duty.TIME_CONFIG_AUTHOR, false);
        VerifiedRequest approver = verifiedExact(
                approverId, GATEWAY_SCOPE_B, Duty.TIME_CONFIG_APPROVER, false);
        VerifiedRequest publisher = verifiedExact(
                publisherId, GATEWAY_SCOPE_C, Duty.TIME_CONFIG_APPROVER, true);

        CreateDraftView created = service.createDraft(
                author,
                UUID.fromString("29000000-0000-4000-8000-000000000001"),
                draft(SCOPE, "Three-person governed plan"));
        UUID createdPlanId = UUID.fromString(created.workPlanId());
        service.transition(
                author,
                createdPlanId,
                UUID.fromString("29000000-0000-4000-8000-000000000002"),
                LifecycleAction.VALIDATE,
                1L);
        service.simulate(
                author,
                createdPlanId,
                UUID.fromString("29000000-0000-4000-8000-000000000003"),
                new SimulationRequest(2L, "1", "1", SIMULATION_PURPOSE));
        service.transition(
                author,
                createdPlanId,
                UUID.fromString("29000000-0000-4000-8000-000000000004"),
                LifecycleAction.SUBMIT_REVIEW,
                3L);
        service.transition(
                approver,
                createdPlanId,
                UUID.fromString("29000000-0000-4000-8000-000000000005"),
                LifecycleAction.APPLY_APPROVAL,
                4L);
        var published = service.transition(
                publisher,
                createdPlanId,
                UUID.fromString("29000000-0000-4000-8000-000000000006"),
                LifecycleAction.PUBLISH,
                5L);

        assertThat(published.status()).isEqualTo(ReceiptState.SUCCEEDED.name());
        assertThat(repository.plan.revision().state()).isEqualTo(PolicyState.PUBLISHED);
        assertThat(repository.plan.revision().authorActorId()).isEqualTo(ACTOR_ID);
        assertThat(repository.plan.revision().approvalActorId()).isEqualTo(approverId);
        assertThat(publisherId).isNotEqualTo(ACTOR_ID).isNotEqualTo(approverId);
    }

    @Test
    void createFailsClosedForVictimCrossPopulationStaleUnmappedAndForeignTenantTargets() {
        WorkRegimeTargetPopulationResolver resolver =
                WorkRegimeTargetPopulationTestSupport.resolver(
                        TENANT_ID,
                        Map.of(GATEWAY_SCOPE_A, POPULATION_ID),
                        WORKER_ID,
                        ASSIGNMENT_ID,
                        1L,
                        new EffectivePeriod(START, END),
                        NOW);
        WorkRegimeApplicationService service = service(
                new FakeRepository(null), new InMemoryReceiptStore(), resolver);
        VerifiedRequest author = verifiedExact(
                ACTOR_ID, GATEWAY_SCOPE_A, Duty.TIME_CONFIG_AUTHOR, false);

        assertDenied(
                () -> service.createDraft(
                        author,
                        UUID.fromString("29100000-0000-4000-8000-000000000001"),
                        draftTarget(
                                SCOPE,
                                UUID.fromString("29100000-0000-4000-8000-000000000011"),
                                ASSIGNMENT_ID,
                                1L)),
                ErrorCode.FORBIDDEN);
        assertDenied(
                () -> service.createDraft(
                        author,
                        UUID.fromString("29100000-0000-4000-8000-000000000002"),
                        draftTarget(OTHER_SCOPE, WORKER_ID, ASSIGNMENT_ID, 1L)),
                ErrorCode.FORBIDDEN);
        assertDenied(
                () -> service.createDraft(
                        author,
                        UUID.fromString("29100000-0000-4000-8000-000000000003"),
                        draftTarget(SCOPE, WORKER_ID, ASSIGNMENT_ID, 2L)),
                ErrorCode.FORBIDDEN);
        assertDenied(
                () -> service.createDraft(
                        verifiedExact(
                                ACTOR_ID, GATEWAY_SCOPE_C,
                                Duty.TIME_CONFIG_AUTHOR, false),
                        UUID.fromString("29100000-0000-4000-8000-000000000004"),
                        draft(SCOPE, "Unmapped actor")),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);

        Authority foreignAuthority = new Authority(
                TENANT_ID + 1,
                ACTOR_ID,
                Set.of(Duty.TIME_CONFIG_AUTHOR),
                Set.of(SCOPE),
                PURPOSE,
                DECISION_ID,
                false,
                false);
        VerifiedRequest foreignTenant = new VerifiedRequest(
                foreignAuthority,
                GATEWAY_SCOPE_A,
                DECISION_ID,
                NOW.plusSeconds(300),
                UUID.fromString("29100000-0000-4000-8000-000000000099"));
        assertDenied(
                () -> service.createDraft(
                        foreignTenant,
                        UUID.fromString("29100000-0000-4000-8000-000000000005"),
                        draft(SCOPE, "Foreign tenant")),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void everyTransitionRevalidatesActorGrantAndTargetMembership() {
        AtomicBoolean actorActive = new AtomicBoolean(true);
        AtomicBoolean memberActive = new AtomicBoolean(false);
        WorkRegimeTargetPopulationResolver resolver = new WorkRegimeTargetPopulationResolver() {
            @Override
            public Optional<PopulationAccess> resolveActorAccess(
                    long tenantId, long actorId, String gatewayScopeKey, Instant checkedAt) {
                if (!actorActive.get() || tenantId != TENANT_ID
                        || !GATEWAY_SCOPE_A.equals(gatewayScopeKey)) {
                    return Optional.empty();
                }
                return Optional.of(new PopulationAccess(
                        TENANT_ID, actorId, gatewayScopeKey, POPULATION_ID, SCOPE,
                        1L, 1L, "a".repeat(64), "b".repeat(64),
                        NOW.plusSeconds(3_600)));
            }

            @Override
            public Optional<TargetMembershipEvidence> resolveTargetMembership(
                    long tenantId,
                    UUID populationPublicId,
                    UUID workerPublicId,
                    UUID peopleAssignmentPublicId,
                    long peopleAssignmentRevision,
                    EffectivePeriod requiredPeriod,
                    Instant checkedAt) {
                if (!memberActive.get()) return Optional.empty();
                return Optional.of(new TargetMembershipEvidence(
                        TENANT_ID, POPULATION_ID, 1L, WORKER_ID, ASSIGNMENT_ID,
                        1L, 1L, new EffectivePeriod(START, END),
                        "a".repeat(64), "c".repeat(64)));
            }
        };
        VerifiedRequest author = verifiedExact(
                ACTOR_ID, GATEWAY_SCOPE_A, Duty.TIME_CONFIG_AUTHOR, false);

        FakeRepository memberRepository = new FakeRepository(
                workPlan(PLAN_ID, SCOPE, PolicyState.DRAFT, 1L));
        WorkRegimeApplicationService memberService = service(
                memberRepository, new InMemoryReceiptStore(), resolver);
        assertDenied(
                () -> memberService.transition(
                        author,
                        PLAN_ID,
                        UUID.fromString("29200000-0000-4000-8000-000000000001"),
                        LifecycleAction.VALIDATE,
                        1L),
                ErrorCode.FORBIDDEN);
        assertThat(memberRepository.transitionMutations).isZero();

        memberActive.set(true);
        actorActive.set(false);
        FakeRepository actorRepository = new FakeRepository(
                workPlan(PLAN_ID, SCOPE, PolicyState.DRAFT, 1L));
        WorkRegimeApplicationService actorService = service(
                actorRepository, new InMemoryReceiptStore(), resolver);
        assertDenied(
                () -> actorService.transition(
                        author,
                        PLAN_ID,
                        UUID.fromString("29200000-0000-4000-8000-000000000002"),
                        LifecycleAction.VALIDATE,
                        1L),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        assertThat(actorRepository.transitionMutations).isZero();
    }

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
        CreateDraftRequest original = draft(SCOPE, "Standard week");

        service.createDraft(
                verified(ACTOR_ID, SCOPE, Set.of(SCOPE)),
                IDEMPOTENCY_KEY,
                original);

        assertThatThrownBy(() -> service.createDraft(
                        verified(ACTOR_ID + 1, SCOPE, Set.of(SCOPE)),
                        IDEMPOTENCY_KEY,
                        original))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.createDraft(
                        verified(ACTOR_ID, OTHER_SCOPE, Set.of(OTHER_SCOPE)),
                        IDEMPOTENCY_KEY,
                        draft(OTHER_SCOPE, "Standard week")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.createDraft(
                        verified(ACTOR_ID, SCOPE, Set.of(SCOPE)),
                        IDEMPOTENCY_KEY,
                        draft(SCOPE, "Changed body")))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(repository.createMutations).isOne();
        assertThat(repository.packLookups).isOne();
        assertThat(receipts.reserveCalls).isOne();
        assertThat(receipts.size()).isOne();
    }

    @Test
    void receiptLookupIsOpaqueForIdentityMismatchesButAllowsTheReadRouteDuty() {
        FakeRepository repository = new FakeRepository(
                workPlan(PLAN_ID, SCOPE, PolicyState.SIMULATED, 2L));
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
        SimulationCommandView read = service.receipt(verified(
                ACTOR_ID,
                SCOPE,
                Set.of(SCOPE),
                Set.of(Duty.TIME_AUDITOR),
                PURPOSE), receiptId);

        assertThat(read.receipt().receiptId()).isEqualTo(receiptId.toString());
        assertThat(read.receipt().status()).isEqualTo(ReceiptState.SUCCEEDED.name());
        assertThat(receipts.get(receiptId).receipt().state())
                .isEqualTo(ReceiptState.SUCCEEDED);
        assertThat(repository.outcomeLookups).isZero();
        assertThat(repository.simulationLookups).isOne();
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
        FakeRepository repository = new FakeRepository(
                workPlan(PLAN_ID, SCOPE, PolicyState.IN_REVIEW, 4L));
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
        FakeRepository repository = new FakeRepository(
                workPlan(PLAN_ID, SCOPE, PolicyState.VALIDATED, 2L));
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
        return service(
                repository,
                receipts,
                WorkRegimeTargetPopulationTestSupport.resolver(
                        TENANT_ID,
                        Map.of(
                                GATEWAY_SCOPE_A, POPULATION_ID,
                                GATEWAY_SCOPE_B, OTHER_POPULATION_ID),
                        WORKER_ID,
                        ASSIGNMENT_ID,
                        1L,
                        new EffectivePeriod(START, END),
                        NOW));
    }

    private static WorkRegimeApplicationService service(
            WorkRegimeRepository repository,
            ReceiptStore receipts,
            WorkRegimeTargetPopulationResolver resolver) {
        return new WorkRegimeApplicationService(
                repository,
                receipts,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                resolver);
    }

    private static VerifiedRequest verifiedExact(
            long actorId, String gatewayScope, Duty duty, boolean stepUp) {
        Authority authority = new Authority(
                TENANT_ID,
                actorId,
                Set.of(duty),
                Set.of(SCOPE),
                PURPOSE,
                DECISION_ID,
                stepUp,
                false);
        return new VerifiedRequest(
                authority,
                gatewayScope,
                DECISION_ID,
                NOW.plusSeconds(300),
                UUID.nameUUIDFromBytes(
                        (actorId + ":" + gatewayScope).getBytes(StandardCharsets.UTF_8)));
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
        String gatewayScope = SCOPE.equals(contextScope)
                ? GATEWAY_SCOPE_A : GATEWAY_SCOPE_B;
        return new VerifiedRequest(
                authority,
                gatewayScope,
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
        return draftTarget(scope, WORKER_ID, ASSIGNMENT_ID, 1L, displayName);
    }

    private static CreateDraftRequest draftTarget(
            String scope,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long assignmentRevision) {
        return draftTarget(
                scope,
                workerPublicId,
                peopleAssignmentPublicId,
                assignmentRevision,
                "Hostile target");
    }

    private static CreateDraftRequest draftTarget(
            String scope,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long assignmentRevision,
            String displayName) {
        return new CreateDraftRequest(
                "standard-week",
                displayName,
                ArrangementKind.FIXED,
                null,
                ScopeType.POPULATION,
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
                workerPublicId,
                peopleAssignmentPublicId,
                assignmentRevision,
                List.of(),
                List.of());
    }

    private static void assertDenied(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            ErrorCode expectedCode) {
        BaseException denied = catchThrowableOfType(BaseException.class, action);
        assertThat(denied).isNotNull();
        assertThat(denied.getErrorCode()).isEqualTo(expectedCode);
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
                ScopeType.POPULATION,
                RULE_PACK_ID,
                "KR",
                1L,
                "c".repeat(64),
                "Asia/Seoul",
                ASSIGNMENT_ID,
                WORKER_ID,
                ASSIGNMENT_ID,
                1L,
                period,
                WorkRegimeTargetPopulationTestSupport.evidence(
                        SCOPE.equals(scope) ? POPULATION_ID : OTHER_POPULATION_ID,
                        WORKER_ID,
                        ASSIGNMENT_ID,
                        1L,
                        ACTOR_ID,
                        SCOPE.equals(scope) ? GATEWAY_SCOPE_A : GATEWAY_SCOPE_B,
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
