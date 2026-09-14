package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition.Stage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only evaluation: no tasks, votes, timers or outbox events are persisted by this helper. */
public final class ApprovalWorkflowQuorumSimulation {

    public enum Status { READY, WAITING, APPROVED, REJECTED, BLOCKED, UNKNOWN, SKIPPED }

    public interface AuthorityResolver {
        CandidatePool candidates(Pins pins, Stage stage, Instant now);
        CurrentAuthority voter(Pins pins, Stage stage, long actorUserId, long principalUserId, Instant now);
        default boolean condition(Pins pins, Stage stage, Instant now) {
            if (stage.routeCondition() != null) throw unavailable("Pinned schema and payload condition evidence is unavailable.");
            return true;
        }
    }

    public record ScenarioVote(String stageKey, long actorUserId, long principalUserId, Decision decision, String reason) {
        public ScenarioVote {
            if (!role(stageKey) || actorUserId < 1 || principalUserId < 1 || decision == null || (reason != null && reason.length() > 2000)) {
                throw invalid("A simulation vote requires an exact stage, actor, principal and decision.");
            }
        }
    }

    public record Input(Pins expectedPins, long requesterUserId, UUID requesterPersonPublicId, int payloadRevision,
                        String payloadSha256, List<ScenarioVote> votes) {
        public Input {
            if (expectedPins == null || requesterUserId < 1 || requesterPersonPublicId == null
                    || payloadRevision < 1 || !sha256(payloadSha256) || votes == null || votes.size() > MAX_CANDIDATES) {
                throw invalid("A simulation must bind exact canonical versions and a bounded scenario.");
            }
            votes = List.copyOf(votes);
        }
    }

    public record StageResult(String stageKey, Status status, Integer eligibleCandidates, Integer threshold,
                              Integer approved, String authorityRevision) { }
    public record Issue(String stageKey, String code) { }
    public record Result(Pins pins, String schemaContract, boolean readOnly, Status status, List<StageResult> stages,
                         List<Issue> issues, Instant evaluatedAt, Instant validUntil) {
        public Result {
            stages = List.copyOf(stages);
            issues = List.copyOf(issues);
        }
    }

    private final ApprovalWorkflowQuorumEvaluator evaluator = new ApprovalWorkflowQuorumEvaluator();

    public Result simulate(ApprovalWorkflowQuorumDefinition definition, Pins canonicalPins, int minimumRejectReasonLength, Input input,
                           AuthorityResolver resolver, Instant now) {
        if (definition == null || canonicalPins == null || input == null || now == null
                || minimumRejectReasonLength < 4 || minimumRejectReasonLength > 1000) {
            throw invalid("The simulation requires a canonical definition and evaluation instant.");
        }
        if (!canonicalPins.equals(input.expectedPins()) || !definition.sha256().equals(canonicalPins.workflowDefinitionSha256())) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, "The canonical workflow, schema or policy evidence changed.");
        }
        if (resolver == null) return unavailableResult(definition, canonicalPins, now, List.of(new Issue(null, "AUTHORITY_UNKNOWN")));
        Map<String, State> states = new HashMap<>();
        var skipped = new java.util.HashSet<String>();
        List<Issue> issues = new ArrayList<>();
        Instant validUntil = Instant.MAX;
        boolean unknown = false;
        for (Stage stage : definition.topologicalStages()) {
            try {
                if ((!stage.predecessors().isEmpty() && skipped.containsAll(stage.predecessors()))
                        || !resolver.condition(canonicalPins, stage, now)) {
                    skipped.add(stage.key());
                    continue;
                }
                CandidatePool pool = resolver.candidates(canonicalPins, stage, now);
                List<Candidate> candidates = evaluator.eligibleCandidates(canonicalPins, stage.candidateRole(), pool,
                        input.requesterUserId(), input.requesterPersonPublicId(), now);
                if (candidates.isEmpty() || (stage.quorum().mode() == Mode.COUNT && stage.quorum().value() > candidates.size())) {
                    issues.add(new Issue(stage.key(), "QUORUM_NOT_STAFFED"));
                    continue;
                }
                Snapshot snapshot = new Snapshot(canonicalPins, simulationId(canonicalPins, "REQUEST"),
                        simulationId(canonicalPins, stage.key()), 1, input.requesterUserId(), input.requesterPersonPublicId(),
                        input.payloadRevision(), input.payloadSha256(), minimumRejectReasonLength, stage.candidateRole(), stage.quorum(), candidates,
                        pool.authorityRevision(), now);
                states.put(stage.key(), new State(snapshot, 0, List.of()));
                if (pool.expiresAt().isBefore(validUntil)) validUntil = pool.expiresAt();
            } catch (BaseException exception) {
                issues.add(new Issue(stage.key(), "AUTHORITY_UNKNOWN"));
                unknown = true;
            }
        }
        if (unknown) return unavailableResult(definition, canonicalPins, now, issues);
        if (!issues.isEmpty()) return stoppedResult(definition, canonicalPins, now, Status.BLOCKED, issues);
        if (states.isEmpty()) return stoppedResult(definition, canonicalPins, now, Status.BLOCKED,
                List.of(new Issue(null, "NO_EXECUTABLE_APPROVAL_PATH")));
        Map<String, Stage> byKey = new HashMap<>();
        definition.stages().forEach(stage -> byKey.put(stage.key(), stage));
        for (ScenarioVote vote : input.votes()) {
            Stage stage = byKey.get(vote.stageKey());
            if (stage == null) throw invalid("The scenario references an unknown workflow stage.");
            if (skipped.contains(stage.key()) || states.values().stream().anyMatch(state -> evaluator.evaluate(state).outcome() == Outcome.REJECTED)
                    || !stage.predecessors().stream().allMatch(key -> skipped.contains(key)
                            || evaluator.evaluate(states.get(key)).outcome() == Outcome.APPROVED)) {
                return stoppedResult(definition, canonicalPins, now, Status.BLOCKED, List.of(new Issue(stage.key(), "STAGE_NOT_ACTIVE")));
            }
            CurrentAuthority authority;
            try { authority = resolver.voter(canonicalPins, stage, vote.actorUserId(), vote.principalUserId(), now); }
            catch (BaseException exception) {
                return unavailableResult(definition, canonicalPins, now, List.of(new Issue(stage.key(), "AUTHORITY_UNKNOWN")));
            }
            if (authority == null) return unavailableResult(definition, canonicalPins, now, List.of(new Issue(stage.key(), "AUTHORITY_UNKNOWN")));
            if (authority.actor().userId() != vote.actorUserId() || authority.principal().userId() != vote.principalUserId()) {
                throw new BaseException(ErrorCode.FORBIDDEN, "The resolved voter does not match the requested simulation identity.");
            }
            try {
                State current = states.get(stage.key());
                AcceptedVote accepted = evaluator.accept(current, current.version(), canonicalPins, authority, vote.decision(), vote.reason(), now);
                states.put(stage.key(), accepted.state());
                if (authority.expiresAt().isBefore(validUntil)) validUntil = authority.expiresAt();
                if (authority.delegation() != null && authority.delegation().endsAt().isBefore(validUntil)) {
                    validUntil = authority.delegation().endsAt();
                }
            } catch (BaseException exception) {
                if (exception.getErrorCode() == ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE) {
                    return unavailableResult(definition, canonicalPins, now, List.of(new Issue(stage.key(), "AUTHORITY_UNKNOWN")));
                }
                return stoppedResult(definition, canonicalPins, now, Status.BLOCKED,
                        List.of(new Issue(stage.key(), exception.getErrorCode().name())));
            }
        }
        List<StageResult> results = definition.topologicalStages().stream().map(stage -> stageResult(stage, states, skipped)).toList();
        Status status = results.stream().anyMatch(stage -> stage.status() == Status.REJECTED) ? Status.REJECTED
                : results.stream().allMatch(stage -> stage.status() == Status.APPROVED || stage.status() == Status.SKIPPED)
                        ? Status.APPROVED : Status.READY;
        return new Result(canonicalPins, CONTRACT, true, status, results, List.of(), now, validUntil);
    }

    private StageResult stageResult(Stage stage, Map<String, State> states, java.util.Set<String> skipped) {
        if (skipped.contains(stage.key())) return new StageResult(stage.key(), Status.SKIPPED, null, null, null, null);
        State state = states.get(stage.key());
        Evaluation evaluation = evaluator.evaluate(state);
        boolean rejected = states.values().stream().anyMatch(current -> evaluator.evaluate(current).outcome() == Outcome.REJECTED);
        boolean active = stage.predecessors().stream().allMatch(key -> skipped.contains(key)
                || evaluator.evaluate(states.get(key)).outcome() == Outcome.APPROVED);
        Status status = switch (evaluation.outcome()) {
            case APPROVED -> Status.APPROVED;
            case REJECTED -> Status.REJECTED;
            case IN_PROGRESS -> rejected ? Status.BLOCKED : active ? Status.READY : Status.WAITING;
        };
        return new StageResult(stage.key(), status, evaluation.candidates(), evaluation.threshold(), evaluation.approved(), state.snapshot().authorityRevision());
    }

    private Result unavailableResult(ApprovalWorkflowQuorumDefinition definition, Pins pins, Instant now, List<Issue> issues) {
        return stoppedResult(definition, pins, now, Status.UNKNOWN, issues);
    }

    private Result stoppedResult(ApprovalWorkflowQuorumDefinition definition, Pins pins, Instant now, Status status, List<Issue> issues) {
        return new Result(pins, CONTRACT, true, status,
                definition.topologicalStages().stream().map(stage -> new StageResult(stage.key(), status, null, null, null, null)).toList(),
                issues, now, now);
    }

    private UUID simulationId(Pins pins, String stageKey) {
        return UUID.nameUUIDFromBytes(("simulation:" + pins.workflowVersionId() + ":" + stageKey).getBytes(StandardCharsets.UTF_8));
    }
}
