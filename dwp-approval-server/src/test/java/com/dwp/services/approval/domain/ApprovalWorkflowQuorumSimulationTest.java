package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition.Stage;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumSimulation.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalWorkflowQuorumSimulationTest {

    private final ApprovalWorkflowQuorumSimulation simulation = new ApprovalWorkflowQuorumSimulation();
    private final ApprovalWorkflowQuorumDefinition definition = ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(
            new Stage("FINANCE_REVIEW", "Finance", "FINANCE_REVIEWER", new Rule(Mode.COUNT, 2), 30, List.of()),
            new Stage("SECURITY_REVIEW", "Security", "SECURITY_REVIEWER", new Rule(Mode.ALL, null), 45, List.of()),
            new Stage("FINAL_REVIEW", "Final", ROLE, new Rule(Mode.ANY, null), 15, List.of("FINANCE_REVIEW", "SECURITY_REVIEW"))));
    private final Pins pins = new Pins(1, WORKFLOW, 2, definition.sha256(), PINS.formSchemaSha256(), 3, PINS.policySha256());

    @Test
    void unvotedParallelRootsAreReadyButNeverReportedAsCompleted() {
        Resolver resolver = new Resolver();
        Result result = simulate(List.of(), resolver);
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.stages()).extracting(StageResult::status).containsExactly(Status.READY, Status.READY, Status.WAITING);
        assertThat(result.readOnly()).isTrue();
        assertThat(result.pins()).isEqualTo(pins);
        assertThat(result.schemaContract()).isEqualTo(CONTRACT);
        assertThat(result.validUntil()).isEqualTo(NOW.plusSeconds(300));
        assertThat(resolver.voterCalls).isZero();
    }

    @Test
    void parallelStagesJoinOnlyAfterBothQuorumsAreSatisfied() {
        List<ScenarioVote> votes = List.of(vote("SECURITY_REVIEW", 4), vote("FINANCE_REVIEW", 1),
                vote("FINANCE_REVIEW", 2), vote("SECURITY_REVIEW", 5), vote("FINAL_REVIEW", 6));
        Resolver resolver = new Resolver();
        Result result = simulate(votes, resolver);
        assertThat(result.status()).isEqualTo(Status.APPROVED);
        assertThat(result.stages()).extracting(StageResult::status).containsOnly(Status.APPROVED);
        assertThat(result.stages()).extracting(StageResult::approved).containsExactly(2, 2, 1);
        assertThat(resolver.voterCalls).isEqualTo(5);
        assertThat(simulate(votes, new Resolver())).isEqualTo(result);
    }

    @Test
    void aJoinCannotAcceptAVoteBeforeEveryPredecessorCompletes() {
        Result result = simulate(List.of(vote("FINANCE_REVIEW", 1), vote("FINANCE_REVIEW", 2), vote("FINAL_REVIEW", 6)), new Resolver());
        assertThat(result.status()).isEqualTo(Status.BLOCKED);
        assertThat(result.issues()).containsExactly(new Issue("FINAL_REVIEW", "STAGE_NOT_ACTIVE"));
        assertThat(result.stages()).extracting(StageResult::approved).containsOnlyNulls();
    }

    @Test
    void rejectionBlocksOtherParallelBranchesAndDescendantsWithoutAutomaticApproval() {
        Result result = simulate(List.of(new ScenarioVote("FINANCE_REVIEW", 1, 1, Decision.REJECT, "Policy conflict")), new Resolver());
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.stages()).extracting(StageResult::status).containsExactly(Status.REJECTED, Status.BLOCKED, Status.BLOCKED);
        assertThat(simulate(List.of(new ScenarioVote("FINANCE_REVIEW", 1, 1, Decision.REJECT, "Policy conflict"), vote("SECURITY_REVIEW", 4)), new Resolver()).status())
                .isEqualTo(Status.BLOCKED);
    }

    @Test
    void candidateAuthUnknownMakesTheWholePredictionUnknownEvenIfOtherPoolsAreKnown() {
        Resolver resolver = new Resolver();
        resolver.incomplete = "SECURITY_REVIEW";
        Result result = simulate(List.of(vote("FINANCE_REVIEW", 1), vote("FINANCE_REVIEW", 2)), resolver);
        assertUnknown(result);
        assertThat(result.issues()).contains(new Issue("SECURITY_REVIEW", "AUTHORITY_UNKNOWN"));
        assertThat(resolver.voterCalls).isZero();
        assertUnknown(simulate(List.of(), null));
    }

    @Test
    void truncatedAndExpiredPoolEvidenceCannotProduceAnyCountsOrThresholds() {
        for (boolean expired : List.of(false, true)) {
            Resolver resolver = new Resolver();
            resolver.truncated = !expired;
            resolver.expired = expired;
            assertUnknown(simulate(List.of(), resolver));
        }
    }

    @Test
    void exactKnownEmptyAndImpossibleCountAreBlockedNotInventedReady() {
        Resolver resolver = new Resolver();
        resolver.members.put("FINANCE_REVIEW", List.of(1L));
        Result result = simulate(List.of(), resolver);
        assertThat(result.status()).isEqualTo(Status.BLOCKED);
        assertThat(result.issues()).containsExactly(new Issue("FINANCE_REVIEW", "QUORUM_NOT_STAFFED"));
        resolver.members.put("FINANCE_REVIEW", List.of());
        assertThat(simulate(List.of(), resolver).status()).isEqualTo(Status.BLOCKED);
    }

    @Test
    void currentVoterUnknownCannotLeaveAPartiallyApprovedPrediction() {
        Resolver resolver = new Resolver();
        resolver.unknownVoter = 2;
        Result result = simulate(List.of(vote("FINANCE_REVIEW", 1), vote("FINANCE_REVIEW", 2)), resolver);
        assertUnknown(result);
        assertThat(result.issues()).containsExactly(new Issue("FINANCE_REVIEW", "AUTHORITY_UNKNOWN"));
    }

    @Test
    void versionHashPolicyAndSchemaMismatchAreExactConflictsNotFallbackSimulation() {
        for (Pins stale : List.of(new Pins(1, WORKFLOW, 3, definition.sha256(), pins.formSchemaSha256(), 3, pins.policySha256()),
                new Pins(1, WORKFLOW, 2, "e".repeat(64), pins.formSchemaSha256(), 3, pins.policySha256()),
                new Pins(1, WORKFLOW, 2, definition.sha256(), "e".repeat(64), 3, pins.policySha256()),
                new Pins(1, WORKFLOW, 2, definition.sha256(), pins.formSchemaSha256(), 4, pins.policySha256()))) {
            assertThatThrownBy(() -> simulation.simulate(definition, pins, 8, new Input(stale, 99, person(99), 2, PAYLOAD_SHA, List.of()), new Resolver(), NOW))
                    .isInstanceOfSatisfying(BaseException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        }
        assertThatThrownBy(() -> simulation.simulate(definition, PINS, 8, new Input(PINS, 99, person(99), 2, PAYLOAD_SHA, List.of()), new Resolver(), NOW))
                .isInstanceOfSatisfying(BaseException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
    }

    @Test
    void duplicateAndSelfApprovalVotesAreExplicitlyBlocked() {
        Result duplicate = simulate(List.of(vote("FINANCE_REVIEW", 1), vote("FINANCE_REVIEW", 1)), new Resolver());
        assertThat(duplicate.status()).isEqualTo(Status.BLOCKED);
        assertThat(duplicate.issues()).containsExactly(new Issue("FINANCE_REVIEW", "RESOURCE_CONFLICT"));
        Resolver resolver = new Resolver();
        resolver.delegate = true;
        Result self = simulate(List.of(new ScenarioVote("FINANCE_REVIEW", 99, 1, Decision.APPROVE, "")), resolver);
        assertThat(self.status()).isEqualTo(Status.BLOCKED);
        assertThat(self.issues()).containsExactly(new Issue("FINANCE_REVIEW", "SOD_CONFLICT"));
    }

    @Test
    void aHypotheticalRejectionStillRequiresTheActualPinnedPolicyReason() {
        Result result = simulate(List.of(new ScenarioVote("FINANCE_REVIEW", 1, 1, Decision.REJECT, "short")), new Resolver());
        assertThat(result.status()).isEqualTo(Status.BLOCKED);
        assertThat(result.issues()).containsExactly(new Issue("FINANCE_REVIEW", "INVALID_INPUT_VALUE"));
    }

    @Test
    void delegatedVotesAreCurrentAndLimitThePredictionValidityWindow() {
        Resolver resolver = new Resolver();
        resolver.delegate = true;
        Result result = simulate(List.of(new ScenarioVote("FINANCE_REVIEW", 8, 1, Decision.APPROVE, "")), resolver);
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.validUntil()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void callerCannotSubstituteAnActorProofForAnotherScenarioUser() {
        Resolver resolver = new Resolver();
        resolver.wrongActor = true;
        assertThatThrownBy(() -> simulate(List.of(vote("FINANCE_REVIEW", 1)), resolver))
                .isInstanceOfSatisfying(BaseException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void scenarioAndResultListsAreImmutableAndDoNotExposeRuntimeMutationCommands() {
        List<ScenarioVote> votes = new ArrayList<>(List.of(vote("FINANCE_REVIEW", 1)));
        Input input = new Input(pins, 99, person(99), 2, PAYLOAD_SHA, votes);
        votes.clear();
        assertThat(input.votes()).hasSize(1);
        Result result = simulation.simulate(definition, pins, 8, input, new Resolver(), NOW);
        assertThatThrownBy(() -> result.stages().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.issues().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ApprovalWorkflowQuorumSimulation.class.getDeclaredFields()).allMatch(field -> !field.getType().getName().contains("Repository"));
    }

    private Result simulate(List<ScenarioVote> votes, Resolver resolver) {
        return simulation.simulate(definition, pins, 8, new Input(pins, 99, person(99), 2, PAYLOAD_SHA, votes), resolver, NOW);
    }
    private ScenarioVote vote(String stage, long actor) { return new ScenarioVote(stage, actor, actor, Decision.APPROVE, ""); }
    private void assertUnknown(Result result) {
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.stages()).extracting(StageResult::status).containsOnly(Status.UNKNOWN);
        assertThat(result.stages()).extracting(StageResult::eligibleCandidates).containsOnlyNulls();
        assertThat(result.stages()).extracting(StageResult::threshold).containsOnlyNulls();
        assertThat(result.validUntil()).isEqualTo(result.evaluatedAt());
    }

    private static final class Resolver implements AuthorityResolver {
        final Map<String, List<Long>> members = new HashMap<>(Map.of("FINANCE_REVIEW", List.of(1L, 2L, 3L),
                "SECURITY_REVIEW", List.of(4L, 5L), "FINAL_REVIEW", List.of(6L)));
        String incomplete;
        boolean truncated;
        boolean expired;
        boolean delegate;
        boolean wrongActor;
        long unknownVoter;
        int voterCalls;

        @Override
        public CandidatePool candidates(Pins pins, Stage stage, Instant now) {
            return new CandidatePool(pins.tenantId(), pins.workflowVersionId(), stage.candidateRole(),
                    members.get(stage.key()).stream().map(user -> subject(user, stage.candidateRole())).toList(),
                    "auth-2", !stage.key().equals(incomplete), truncated, now.minusSeconds(1), expired ? now : now.plusSeconds(300));
        }
        @Override
        public CurrentAuthority voter(Pins pins, Stage stage, long actorUserId, long principalUserId, Instant now) {
            voterCalls++;
            if (actorUserId == unknownVoter) throw unavailable("Auth is unavailable.");
            Subject actor = subject(wrongActor ? 7 : actorUserId, stage.candidateRole());
            Subject principal = subject(principalUserId, stage.candidateRole());
            Delegation grant = delegate && actorUserId != principalUserId
                    ? new Delegation(person(1000 + principalUserId), 1, WORKFLOW, principalUserId, actorUserId,
                    stage.candidateRole(), true, now.minusSeconds(10), now.plusSeconds(60)) : null;
            return new CurrentAuthority(AccessMode.NORMAL, "auth-2", now.minusSeconds(1), now.plusSeconds(300), actor, principal, grant);
        }
    }
}
