package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApprovalWorkflowQuorumEvaluatorTest {

    private final ApprovalWorkflowQuorumEvaluator evaluator = new ApprovalWorkflowQuorumEvaluator();

    @ParameterizedTest
    @CsvSource({"1,1,1", "3,50,2", "7,66,5", "1000,1,10", "1000,99,990", "999,100,999"})
    void percentageAlwaysRoundsUpRatherThanUnderCounting(int candidates, int percent, int required) {
        assertThat(new Rule(Mode.PERCENT, percent).threshold(candidates)).isEqualTo(required);
    }

    @Test
    void thresholdsHaveNoZeroOrFloatingPointBoundaryForAllSupportedPools() {
        for (int candidates = 1; candidates <= MAX_CANDIDATES; candidates++) {
            assertThat(new Rule(Mode.ANY, null).threshold(candidates)).isEqualTo(1);
            assertThat(new Rule(Mode.ALL, null).threshold(candidates)).isEqualTo(candidates);
            for (int percent = 1; percent <= 100; percent++) {
                int result = new Rule(Mode.PERCENT, percent).threshold(candidates);
                assertThat(result).isBetween(1, candidates);
                assertThat((long) result * 100).isGreaterThanOrEqualTo((long) candidates * percent);
                assertThat((long) (result - 1) * 100).isLessThan((long) candidates * percent);
            }
        }
    }

    @Test
    void rejectsMissingOrIrrelevantArgumentsAndUnstaffedQuorums() {
        for (Mode mode : List.of(Mode.ANY, Mode.ALL)) rejected(() -> new Rule(mode, 1), ErrorCode.INVALID_INPUT_VALUE);
        for (Mode mode : List.of(Mode.COUNT, Mode.PERCENT)) {
            rejected(() -> new Rule(mode, null), ErrorCode.INVALID_INPUT_VALUE);
            rejected(() -> new Rule(mode, 0), ErrorCode.INVALID_INPUT_VALUE);
        }
        rejected(() -> new Rule(Mode.PERCENT, 101), ErrorCode.INVALID_INPUT_VALUE);
        rejected(() -> new Rule(Mode.COUNT, 1001), ErrorCode.INVALID_INPUT_VALUE);
        rejected(() -> new Rule(Mode.ALL, null).threshold(0), ErrorCode.INVALID_INPUT_VALUE);
        rejected(() -> new Rule(Mode.ANY, null).threshold(1001), ErrorCode.INVALID_INPUT_VALUE);
        rejected(() -> new Rule(Mode.COUNT, 4).threshold(3), ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void preservesAnyOneApprovalAndAllRequiresEveryFrozenCandidate() {
        AcceptedVote any = accept(state(new Rule(Mode.ANY, null), 3), authority(1), Decision.APPROVE);
        assertThat(any.evaluation().outcome()).isEqualTo(Outcome.APPROVED);
        State all = state(new Rule(Mode.ALL, null), 3);
        for (int user = 1; user <= 3; user++) {
            AcceptedVote result = accept(all, authority(user), Decision.APPROVE);
            assertThat(result.evaluation().outcome()).isEqualTo(user == 3 ? Outcome.APPROVED : Outcome.IN_PROGRESS);
            all = result.state();
        }
        assertThat(evaluator.evaluate(all).threshold()).isEqualTo(3);
    }

    @Test
    void countAndPercentCompleteAtExactlyTheComputedThreshold() {
        for (Rule rule : List.of(new Rule(Mode.COUNT, 2), new Rule(Mode.PERCENT, 50))) {
            AcceptedVote first = accept(state(rule, 3), authority(1), Decision.APPROVE);
            assertThat(first.evaluation().outcome()).isEqualTo(Outcome.IN_PROGRESS);
            AcceptedVote second = accept(first.state(), authority(2), Decision.APPROVE);
            assertThat(second.evaluation().outcome()).isEqualTo(Outcome.APPROVED);
            assertThat(second.evaluation().remaining()).isEqualTo(1);
            rejected(() -> accept(second.state(), authority(3), Decision.REJECT), ErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Test
    void anyRejectionIsAVetoAndNeverBecomesAMajorityShortcut() {
        for (Rule rule : List.of(new Rule(Mode.ANY, null), new Rule(Mode.ALL, null), new Rule(Mode.COUNT, 2), new Rule(Mode.PERCENT, 50))) {
            AcceptedVote rejected = accept(state(rule, 3), authority(1), Decision.REJECT);
            assertThat(rejected.evaluation().outcome()).isEqualTo(Outcome.REJECTED);
            rejected(() -> accept(rejected.state(), authority(2), Decision.APPROVE), ErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Test
    void rejectionReasonIsPinnedValidatedAndPreservedInTheVoteReceipt() {
        State state = state(new Rule(Mode.ANY, null), 1);
        for (String reason : new String[] {null, "", "   ", "short", "x".repeat(2001)}) {
            rejected(() -> evaluator.accept(state, 0, PINS, authority(1), Decision.REJECT, reason, NOW), ErrorCode.INVALID_INPUT_VALUE);
        }
        AcceptedVote accepted = evaluator.accept(state, 0, PINS, authority(1), Decision.REJECT, "  Policy conflict  ", NOW);
        assertThat(accepted.vote().reason()).isEqualTo("Policy conflict");
        assertThat(accepted.evaluation().outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void providerPlaneProviderRolesAndSupportModeCannotBorrowTenantApprovalAuthority() {
        State state = state(new Rule(Mode.ALL, null), 3);
        Subject provider = new Subject(1, 1, person(1), IdentityPlane.PROVIDER, true, Set.of(ROLE), true);
        rejected(() -> accept(state, authority(provider, provider, null), Decision.APPROVE), ErrorCode.FORBIDDEN);
        Subject providerRole = new Subject(1, 1, person(1), IdentityPlane.TENANT, true, Set.of(ROLE, "PROVIDER_ADMIN"), true);
        rejected(() -> accept(state, authority(providerRole, providerRole, null), Decision.APPROVE), ErrorCode.FORBIDDEN);
        CurrentAuthority support = new CurrentAuthority(AccessMode.PROVIDER_SUPPORT, "auth-2", NOW.minusSeconds(1), NOW.plusSeconds(300),
                subject(1), subject(1), null);
        rejected(() -> accept(state, support, Decision.APPROVE), ErrorCode.FORBIDDEN);
        CurrentAuthority elevated = new CurrentAuthority(AccessMode.ELEVATED, "auth-2", NOW.minusSeconds(1), NOW.plusSeconds(300),
                subject(1), subject(1), null);
        AcceptedVote accepted = accept(state, elevated, Decision.APPROVE);
        assertThat(accepted.evaluation().approved()).isEqualTo(1);
        assertThat(accepted.vote().activeAccessMode()).isEqualTo(AccessMode.ELEVATED);
    }

    @Test
    void aPureProposalDoesNotMutateItsSourceAndAConcurrentLosingVersionCannotBeRebasedSilently() {
        State original = state(new Rule(Mode.COUNT, 2), 3);
        AcceptedVote firstProposal = accept(original, authority(1), Decision.APPROVE);
        AcceptedVote competingProposal = accept(original, authority(2), Decision.APPROVE);
        assertThat(original.version()).isZero();
        assertThat(original.votes()).isEmpty();
        assertThat(firstProposal.vote().stageVersion()).isEqualTo(competingProposal.vote().stageVersion()).isEqualTo(1);
        rejected(() -> evaluator.accept(firstProposal.state(), 0, PINS, authority(2), Decision.APPROVE, "", NOW), ErrorCode.OBJECT_VERSION_CONFLICT);
        assertThat(firstProposal.evaluation().outcome()).isEqualTo(Outcome.IN_PROGRESS);
        assertThat(accept(firstProposal.state(), authority(2), Decision.APPROVE).evaluation().outcome()).isEqualTo(Outcome.APPROVED);
    }

    @Test
    void aDelegateCannotFillTwoSeatsOrReuseADirectCandidateVote() {
        State start = state(new Rule(Mode.ALL, null), 3);
        CurrentAuthority delegateOne = authority(subject(8), subject(1), delegation(8, 1));
        State first = accept(start, delegateOne, Decision.APPROVE).state();
        rejected(() -> accept(first, authority(subject(8), subject(2), delegation(8, 2)), Decision.APPROVE), ErrorCode.RESOURCE_CONFLICT);
        rejected(() -> accept(first, authority(1), Decision.APPROVE), ErrorCode.RESOURCE_CONFLICT);
        State direct = accept(start, authority(1), Decision.APPROVE).state();
        rejected(() -> accept(direct, delegateOne, Decision.APPROVE), ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    void personAliasesAndCrossTenantSubjectsCannotBorrowAQuorumSeat() {
        State state = state(new Rule(Mode.ALL, null), 3);
        Subject alias = new Subject(1, 8, person(1), IdentityPlane.TENANT, true, Set.of(ROLE), true);
        rejected(() -> accept(state, authority(alias, subject(2), delegation(8, 2)), Decision.APPROVE), ErrorCode.FORBIDDEN);
        Subject crossTenant = new Subject(2, 1, person(1), IdentityPlane.TENANT, true, Set.of(ROLE), true);
        rejected(() -> accept(state, authority(crossTenant, crossTenant, null), Decision.APPROVE), ErrorCode.FORBIDDEN);
        Subject changedPerson = new Subject(1, 1, person(101), IdentityPlane.TENANT, true, Set.of(ROLE), true);
        rejected(() -> accept(state, authority(changedPerson, changedPerson, null), Decision.APPROVE), ErrorCode.FORBIDDEN);
    }

    @Test
    void requesterUserAndPersonAreBlockedIncludingDelegatedVotes() {
        State state = state(new Rule(Mode.ALL, null), 3);
        rejected(() -> accept(state, authority(subject(99), subject(1), delegation(99, 1)), Decision.APPROVE), ErrorCode.SOD_CONFLICT);
        Subject alias = new Subject(1, 8, person(99), IdentityPlane.TENANT, true, Set.of(ROLE), true);
        rejected(() -> accept(state, authority(alias, subject(1), delegation(8, 1)), Decision.REJECT), ErrorCode.SOD_CONFLICT);
    }

    @Test
    void revokedOrMissingCandidateAndActorAuthorityFailsClosed() {
        State state = state(new Rule(Mode.ALL, null), 3);
        for (Subject revoked : List.of(new Subject(1, 1, person(1), IdentityPlane.TENANT, false, Set.of(ROLE), true),
                new Subject(1, 1, person(1), IdentityPlane.TENANT, true, Set.of(), true),
                new Subject(1, 1, person(1), IdentityPlane.TENANT, true, Set.of(ROLE), false))) {
            rejected(() -> accept(state, authority(revoked, revoked, null), Decision.APPROVE), ErrorCode.FORBIDDEN);
        }
        Subject deniedDelegate = new Subject(1, 8, person(8), IdentityPlane.TENANT, true, Set.of(), false);
        rejected(() -> accept(state, authority(deniedDelegate, subject(1), delegation(8, 1)), Decision.APPROVE), ErrorCode.FORBIDDEN);
        rejected(() -> accept(state, null, Decision.APPROVE), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void delegationIsHalfOpenAndBoundToExactWorkflowTenantRoleAndParties() {
        State state = state(new Rule(Mode.ALL, null), 3);
        Delegation baseline = delegation(8, 1);
        List<Delegation> invalid = List.of(
                new Delegation(baseline.delegationId(), 1, WORKFLOW, 1, 8, ROLE, true, NOW.minusSeconds(60), NOW),
                new Delegation(baseline.delegationId(), 1, WORKFLOW, 1, 8, ROLE, true, NOW.plusSeconds(1), NOW.plusSeconds(60)),
                new Delegation(baseline.delegationId(), 2, WORKFLOW, 1, 8, ROLE, true, baseline.startsAt(), baseline.endsAt()),
                new Delegation(baseline.delegationId(), 1, person(101), 1, 8, ROLE, true, baseline.startsAt(), baseline.endsAt()),
                new Delegation(baseline.delegationId(), 1, WORKFLOW, 1, 8, "OTHER_ROLE", true, baseline.startsAt(), baseline.endsAt()),
                new Delegation(baseline.delegationId(), 1, WORKFLOW, 2, 8, ROLE, true, baseline.startsAt(), baseline.endsAt()),
                new Delegation(baseline.delegationId(), 1, WORKFLOW, 1, 9, ROLE, true, baseline.startsAt(), baseline.endsAt()),
                new Delegation(baseline.delegationId(), 1, WORKFLOW, 1, 8, ROLE, false, baseline.startsAt(), baseline.endsAt()));
        for (Delegation value : invalid) rejected(() -> accept(state, authority(subject(8), subject(1), value), Decision.APPROVE), ErrorCode.FORBIDDEN);
        rejected(() -> accept(state, authority(subject(8), subject(1), null), Decision.APPROVE), ErrorCode.FORBIDDEN);
        rejected(() -> accept(state, authority(subject(1), subject(1), baseline), Decision.APPROVE), ErrorCode.FORBIDDEN);
        assertThat(accept(state, authority(subject(8), subject(1),
                new Delegation(baseline.delegationId(), 1, WORKFLOW, 1, 8, ROLE, true, NOW, baseline.endsAt())), Decision.APPROVE).vote().delegationId())
                .isEqualTo(baseline.delegationId());
    }

    @Test
    void currentAuthorityMustBeFreshButUnrelatedAuthRevisionDoesNotShrinkTheFrozenPool() {
        State state = state(new Rule(Mode.ALL, null), 3);
        CurrentAuthority expired = new CurrentAuthority(AccessMode.NORMAL, "auth-2", NOW.minusSeconds(60), NOW, subject(1), subject(1), null);
        rejected(() -> accept(state, expired, Decision.APPROVE), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        CurrentAuthority future = new CurrentAuthority(AccessMode.NORMAL, "auth-2", NOW.plusSeconds(1), NOW.plusSeconds(60), subject(1), subject(1), null);
        rejected(() -> accept(state, future, Decision.APPROVE), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        AcceptedVote accepted = accept(state, authority(1), Decision.APPROVE);
        assertThat(accepted.vote().authorityRevision()).isEqualTo("auth-2");
        assertThat(accepted.state().snapshot().authorityRevision()).isEqualTo("auth-1");
        assertThat(accepted.evaluation().threshold()).isEqualTo(3);
        rejected(() -> accept(accepted.state(), authority(4), Decision.APPROVE), ErrorCode.FORBIDDEN);
    }

    @Test
    void staleStageAndChangedWorkflowSchemaPolicyPinsConflictBeforePlanningAVote() {
        State state = state(new Rule(Mode.ANY, null), 1);
        rejected(() -> evaluator.accept(state, 1, PINS, authority(1), Decision.APPROVE, "", NOW), ErrorCode.OBJECT_VERSION_CONFLICT);
        for (Pins changed : List.of(new Pins(1, WORKFLOW, 3, PINS.workflowDefinitionSha256(), PINS.formSchemaSha256(), 3, PINS.policySha256()),
                new Pins(1, WORKFLOW, 2, "e".repeat(64), PINS.formSchemaSha256(), 3, PINS.policySha256()),
                new Pins(1, WORKFLOW, 2, PINS.workflowDefinitionSha256(), "e".repeat(64), 3, PINS.policySha256()),
                new Pins(1, WORKFLOW, 2, PINS.workflowDefinitionSha256(), PINS.formSchemaSha256(), 4, PINS.policySha256()),
                new Pins(1, WORKFLOW, 2, PINS.workflowDefinitionSha256(), PINS.formSchemaSha256(), 3, "e".repeat(64)))) {
            rejected(() -> evaluator.accept(state, 0, changed, authority(1), Decision.APPROVE, "", NOW), ErrorCode.OBJECT_VERSION_CONFLICT);
        }
    }

    @Test
    void historyIsBoundToRequestStageGenerationAndPayloadRevision() {
        AcceptedVote accepted = accept(state(new Rule(Mode.ALL, null), 3), authority(1), Decision.APPROVE);
        Vote vote = accepted.vote();
        for (Vote changed : List.of(copy(vote, person(3000), vote.generation(), vote.payloadRevision(), vote.payloadSha256(), vote.stageVersion()),
                copy(vote, vote.stepId(), 2, vote.payloadRevision(), vote.payloadSha256(), vote.stageVersion()),
                copy(vote, vote.stepId(), vote.generation(), 3, vote.payloadSha256(), vote.stageVersion()),
                copy(vote, vote.stepId(), vote.generation(), vote.payloadRevision(), "e".repeat(64), vote.stageVersion()),
                copy(vote, vote.stepId(), vote.generation(), vote.payloadRevision(), vote.payloadSha256(), 2))) {
            rejected(() -> evaluator.evaluate(new State(accepted.state().snapshot(), 1, List.of(changed))), ErrorCode.INVALID_STATE);
        }
    }

    @Test
    void committedDelegateVotesDoNotDisappearMerelyBecauseTheirGrantLaterExpires() {
        AcceptedVote accepted = accept(state(new Rule(Mode.COUNT, 2), 3), authority(subject(8), subject(1), delegation(8, 1)), Decision.APPROVE);
        CurrentAuthority later = new CurrentAuthority(AccessMode.NORMAL, "auth-3", NOW.plusSeconds(120), NOW.plusSeconds(300), subject(2), subject(2), null);
        AcceptedVote completed = evaluator.accept(accepted.state(), 1, PINS, later, Decision.APPROVE, "", NOW.plusSeconds(120));
        assertThat(completed.evaluation().outcome()).isEqualTo(Outcome.APPROVED);
        assertThat(completed.evaluation().approved()).isEqualTo(2);
    }

    @Test
    void candidateEnumerationRejectsTruncationUnknownCrossTenantAndDuplicatePeople() {
        for (CandidatePool incomplete : List.of(pool(List.of(subject(1)), false, false), pool(List.of(subject(1)), true, true))) {
            rejected(() -> evaluator.eligibleCandidates(PINS, ROLE, incomplete, 99, person(99), NOW), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
        CandidatePool duplicate = pool(List.of(subject(1), new Subject(1, 2, person(1), IdentityPlane.TENANT, true, Set.of(ROLE), true)), true, false);
        rejected(() -> evaluator.eligibleCandidates(PINS, ROLE, duplicate, 99, person(99), NOW), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        CandidatePool cross = pool(List.of(new Subject(2, 1, person(1), IdentityPlane.TENANT, true, Set.of(ROLE), true)), true, false);
        rejected(() -> evaluator.eligibleCandidates(PINS, ROLE, cross, 99, person(99), NOW), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void eligibleEnumerationIsSortedFilteredImmutableAndNeverIncludesTheRequester() {
        List<Subject> subjects = new ArrayList<>(List.of(subject(3), subject(99), subject(1),
                new Subject(1, 2, person(2), IdentityPlane.TENANT, false, Set.of(ROLE), true),
                new Subject(1, 4, person(4), IdentityPlane.TENANT, true, Set.of("OTHER_ROLE"), true),
                new Subject(1, 5, person(5), IdentityPlane.TENANT, true, Set.of(ROLE), false),
                new Subject(1, 6, person(6), IdentityPlane.PROVIDER, true, Set.of(ROLE), true),
                new Subject(1, 7, person(7), IdentityPlane.TENANT, true, Set.of(ROLE, "PROVIDER_ADMIN"), true)));
        CandidatePool pool = pool(subjects, true, false);
        subjects.clear();
        List<Candidate> eligible = evaluator.eligibleCandidates(PINS, ROLE, pool, 99, person(99), NOW);
        assertThat(eligible).extracting(Candidate::userId).containsExactly(1L, 3L);
        assertThatThrownBy(() -> eligible.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private AcceptedVote accept(State state, CurrentAuthority authority, Decision decision) {
        return evaluator.accept(state, state.version(), state.snapshot().pins(), authority, decision,
                decision == Decision.REJECT ? "Rejected for policy conflict" : "", NOW);
    }

    private void rejected(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BaseException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
