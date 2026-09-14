package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure decision planning. The repository must persist the returned vote and stage CAS atomically. */
public final class ApprovalWorkflowQuorumEvaluator {

    public List<Candidate> eligibleCandidates(
            Pins pins, String role, CandidatePool pool, long requesterUserId, UUID requesterPersonPublicId, Instant now) {
        if (pins == null || requesterUserId < 1 || requesterPersonPublicId == null
                || pool == null || now == null || !pool.complete() || pool.truncated()
                || pool.evaluatedAt().isAfter(now) || !pool.expiresAt().isAfter(now)
                || pool.tenantId() != pins.tenantId() || !pool.workflowVersionId().equals(pins.workflowVersionId())
                || !pool.candidateRole().equals(role)) {
            throw unavailable("A complete current candidate enumeration is required before a stage can start.");
        }
        Set<Long> users = new HashSet<>();
        Set<UUID> people = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Subject subject : pool.subjects()) {
            if (subject.tenantId() != pins.tenantId() || !users.add(subject.userId()) || !people.add(subject.personPublicId())) {
                throw unavailable("Candidate authority returned cross-tenant or duplicate canonical identities.");
            }
            if (subject.identityPlane() == IdentityPlane.TENANT
                    && subject.roles().stream().noneMatch(value -> value.startsWith("PROVIDER_"))
                    && subject.active() && subject.canApprove() && subject.roles().contains(role)
                    && subject.userId() != requesterUserId && !subject.personPublicId().equals(requesterPersonPublicId)) {
                candidates.add(new Candidate(subject.userId(), subject.personPublicId()));
            }
        }
        return candidates.stream().sorted(Comparator.comparingLong(Candidate::userId)).toList();
    }

    public Evaluation evaluate(State state) {
        Snapshot snapshot = state.snapshot();
        int threshold = snapshot.rule().threshold(snapshot.candidates().size());
        Map<Long, Candidate> candidates = candidates(snapshot);
        Set<Long> actors = new HashSet<>();
        Set<UUID> people = new HashSet<>();
        Set<Long> principals = new HashSet<>();
        int approved = 0;
        int rejected = 0;
        long previousVersion = 0;
        Instant previousTime = snapshot.openedAt();
        List<Vote> ordered = state.votes().stream().sorted(Comparator.comparingLong(Vote::stageVersion)).toList();
        for (Vote vote : ordered) {
            if (approved >= threshold || rejected > 0) {
                throw new BaseException(ErrorCode.INVALID_STATE, "A terminal stage cannot contain later votes.");
            }
            Candidate candidate = candidates.get(vote.principalUserId());
            if (vote.stageVersion() <= previousVersion || vote.stageVersion() > state.version()
                    || vote.acceptedAt().isBefore(previousTime)
                    || !vote.pins().equals(snapshot.pins()) || !vote.requestId().equals(snapshot.requestId())
                    || !vote.stepId().equals(snapshot.stepId()) || vote.generation() != snapshot.generation()
                    || (vote.decision() == Decision.REJECT && vote.reason().length() < snapshot.minimumRejectReasonLength())
                    || candidate == null || !candidate.personPublicId().equals(vote.principalPersonPublicId())
                    || vote.payloadRevision() != snapshot.payloadRevision()
                    || !vote.payloadSha256().equals(snapshot.payloadSha256())) {
                throw new BaseException(ErrorCode.INVALID_STATE, "A vote does not belong to the frozen stage evidence.");
            }
            if (vote.actorUserId() == snapshot.requesterUserId()
                    || vote.principalUserId() == snapshot.requesterUserId()
                    || vote.actorPersonPublicId().equals(snapshot.requesterPersonPublicId())) {
                throw new BaseException(ErrorCode.SOD_CONFLICT, "Self-approval cannot contribute to a quorum.");
            }
            if (!actors.add(vote.actorUserId()) || !people.add(vote.actorPersonPublicId())
                    || !principals.add(vote.principalUserId())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "A user, person or candidate seat voted more than once.");
            }
            Candidate actorSeat = candidates.get(vote.actorUserId());
            if ((actorSeat != null && !actorSeat.personPublicId().equals(vote.actorPersonPublicId()))
                    || (vote.actorUserId() == vote.principalUserId()
                        && !vote.actorPersonPublicId().equals(vote.principalPersonPublicId()))) {
                throw new BaseException(ErrorCode.INVALID_STATE, "The decision actor identity is inconsistent.");
            }
            if (vote.decision() == Decision.APPROVE) approved++;
            else rejected++;
            previousVersion = vote.stageVersion();
            previousTime = vote.acceptedAt();
        }
        Outcome outcome = rejected > 0 ? Outcome.REJECTED
                : approved >= threshold ? Outcome.APPROVED : Outcome.IN_PROGRESS;
        return new Evaluation(outcome, threshold, candidates.size(), approved, rejected,
                candidates.size() - principals.size());
    }

    public AcceptedVote accept(
            State state,
            long expectedVersion,
            Pins currentPins,
            CurrentAuthority authority,
            Decision decision,
            String reason,
            Instant now) {
        if (expectedVersion != state.version() || !state.snapshot().pins().equals(currentPins)) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, "The stage, workflow, schema or policy version changed.");
        }
        if (decision == null || now == null || now.isBefore(state.snapshot().openedAt())) {
            throw invalid("A decision must be evaluated at a valid stage instant.");
        }
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.length() > 2000
                || (decision == Decision.REJECT && normalizedReason.length() < state.snapshot().minimumRejectReasonLength())) {
            throw invalid("A rejection requires the reason length pinned by the stage policy.");
        }
        if (evaluate(state).outcome() != Outcome.IN_PROGRESS) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The stage is already terminal.");
        }
        verifyAuthority(state.snapshot(), authority, now);
        if (state.votes().stream().anyMatch(vote -> vote.acceptedAt().isAfter(now))) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The stage decision clock moved backwards.");
        }
        Subject actor = authority.actor();
        Subject principal = authority.principal();
        if (state.votes().stream().anyMatch(vote -> vote.actorUserId() == actor.userId()
                || vote.actorPersonPublicId().equals(actor.personPublicId())
                || vote.principalUserId() == principal.userId())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The actor or candidate seat already decided.");
        }
        long nextVersion;
        try { nextVersion = Math.addExact(state.version(), 1); }
        catch (ArithmeticException exception) { throw new BaseException(ErrorCode.INVALID_STATE, "The stage version is exhausted."); }
        Snapshot snapshot = state.snapshot();
        Vote vote = new Vote(snapshot.pins(), snapshot.requestId(), snapshot.stepId(), snapshot.generation(),
                nextVersion, actor.userId(), actor.personPublicId(), principal.userId(),
                principal.personPublicId(), decision, normalizedReason,
                authority.delegation() == null ? null : authority.delegation().delegationId(),
                authority.activeAccessMode(), authority.revision(), state.snapshot().payloadRevision(), state.snapshot().payloadSha256(), now);
        List<Vote> votes = new ArrayList<>(state.votes());
        votes.add(vote);
        State next = new State(state.snapshot(), nextVersion, votes);
        return new AcceptedVote(next, vote, evaluate(next));
    }

    void verifyAuthority(Snapshot snapshot, CurrentAuthority authority, Instant now) {
        if (authority == null || authority.evaluatedAt().isAfter(now) || !authority.expiresAt().isAfter(now)) {
            throw unavailable("Current candidate authority is absent or expired.");
        }
        Subject actor = authority.actor();
        Subject principal = authority.principal();
        if (authority.activeAccessMode() == AccessMode.PROVIDER_SUPPORT
                || actor.identityPlane() != IdentityPlane.TENANT || principal.identityPlane() != IdentityPlane.TENANT
                || actor.roles().stream().anyMatch(value -> value.startsWith("PROVIDER_"))
                || principal.roles().stream().anyMatch(value -> value.startsWith("PROVIDER_"))
                || actor.tenantId() != snapshot.pins().tenantId() || principal.tenantId() != snapshot.pins().tenantId()
                || !actor.active() || !actor.canApprove() || !principal.active() || !principal.canApprove()
                || !principal.roles().contains(snapshot.candidateRole())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The current actor or candidate authority cannot decide this stage.");
        }
        if (actor.userId() == snapshot.requesterUserId() || principal.userId() == snapshot.requesterUserId()
                || actor.personPublicId().equals(snapshot.requesterPersonPublicId())
                || principal.personPublicId().equals(snapshot.requesterPersonPublicId())) {
            throw new BaseException(ErrorCode.SOD_CONFLICT, "Delegation cannot bypass requester self-approval protection.");
        }
        Candidate seat = candidates(snapshot).get(principal.userId());
        Candidate actorSeat = candidates(snapshot).get(actor.userId());
        if (seat == null || !seat.personPublicId().equals(principal.personPublicId())
                || (actorSeat != null && !actorSeat.personPublicId().equals(actor.personPublicId()))
                || snapshot.candidates().stream().anyMatch(candidate -> candidate.personPublicId().equals(actor.personPublicId())
                    && candidate.userId() != actor.userId())
                || (actor.userId() != principal.userId() && actor.personPublicId().equals(principal.personPublicId()))) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The principal is not the exact frozen candidate identity.");
        }
        Delegation delegation = authority.delegation();
        if (actor.userId() == principal.userId()) {
            if (delegation != null || !actor.personPublicId().equals(principal.personPublicId())) {
                throw new BaseException(ErrorCode.FORBIDDEN, "A direct vote cannot borrow delegation identity.");
            }
        } else if (delegation == null || !delegation.active()
                || delegation.tenantId() != snapshot.pins().tenantId()
                || !delegation.workflowVersionId().equals(snapshot.pins().workflowVersionId())
                || delegation.delegatorUserId() != principal.userId() || delegation.delegateUserId() != actor.userId()
                || !delegation.authorityRole().equals(snapshot.candidateRole())
                || now.isBefore(delegation.startsAt()) || !now.isBefore(delegation.endsAt())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Delegated candidate authority is absent, mismatched or expired.");
        }
    }

    private Map<Long, Candidate> candidates(Snapshot snapshot) {
        Map<Long, Candidate> result = new HashMap<>();
        snapshot.candidates().forEach(candidate -> result.put(candidate.userId(), candidate));
        return result;
    }
}
