package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Typed runtime inputs; authority evidence must be resolved by the owner, not the client. */
public final class ApprovalWorkflowQuorum {

    public static final String CONTRACT = "DWP_APPROVAL_WORKFLOW_QUORUM_V2";
    public static final int MAX_CANDIDATES = 1000;
    public static final int MAX_STAGES = 64;

    private ApprovalWorkflowQuorum() { }

    public enum Mode { ANY, ALL, COUNT, PERCENT }
    public enum Decision { APPROVE, REJECT }
    public enum Outcome { IN_PROGRESS, APPROVED, REJECTED }
    public enum IdentityPlane { TENANT, PROVIDER }
    public enum AccessMode { NORMAL, ELEVATED, PROVIDER_SUPPORT }

    public record Rule(Mode mode, Integer value) {
        public Rule {
            if (mode == null
                    || ((mode == Mode.ANY || mode == Mode.ALL) && value != null)
                    || (mode == Mode.COUNT && (value == null || value < 1 || value > MAX_CANDIDATES))
                    || (mode == Mode.PERCENT && (value == null || value < 1 || value > 100))) {
                throw invalid("The quorum rule requires exact bounded mode arguments.");
            }
        }

        public int threshold(int candidateCount) {
            if (candidateCount < 1 || candidateCount > MAX_CANDIDATES) {
                throw invalid("A quorum requires a complete non-empty candidate pool.");
            }
            int required = switch (mode) {
                case ANY -> 1;
                case ALL -> candidateCount;
                case COUNT -> value;
                case PERCENT -> Math.toIntExact(((long) candidateCount * value + 99) / 100);
            };
            if (required > candidateCount) {
                throw invalid("The quorum threshold exceeds the frozen candidate pool.");
            }
            return required;
        }
    }

    public record Pins(
            long tenantId,
            UUID workflowVersionId,
            int workflowVersion,
            String workflowDefinitionSha256,
            String formSchemaSha256,
            long policyVersion,
            String policySha256) {
        public Pins {
            if (tenantId < 1 || workflowVersionId == null || workflowVersion < 1 || policyVersion < 1
                    || !sha256(workflowDefinitionSha256) || !sha256(formSchemaSha256)
                    || !sha256(policySha256)) {
                throw invalid("Canonical workflow, schema and policy version evidence is required.");
            }
        }
    }

    public record Candidate(long userId, UUID personPublicId) {
        public Candidate {
            if (userId < 1 || personPublicId == null) {
                throw invalid("A candidate requires a canonical user and person identity.");
            }
        }
    }

    public record Snapshot(
            Pins pins,
            UUID requestId,
            UUID stepId,
            long generation,
            long requesterUserId,
            UUID requesterPersonPublicId,
            int payloadRevision,
            String payloadSha256,
            int minimumRejectReasonLength,
            String candidateRole,
            Rule rule,
            List<Candidate> candidates,
            String authorityRevision,
            Instant openedAt) {
        public Snapshot {
            if (pins == null || requestId == null || stepId == null || generation < 1
                    || requesterUserId < 1 || requesterPersonPublicId == null || payloadRevision < 1 || !sha256(payloadSha256)
                    || minimumRejectReasonLength < 4 || minimumRejectReasonLength > 1000
                    || !role(candidateRole) || rule == null || !revision(authorityRevision)
                    || openedAt == null || candidates == null) {
                throw invalid("The stage snapshot is incomplete.");
            }
            candidates = List.copyOf(candidates);
            rule.threshold(candidates.size());
            Set<Long> users = new java.util.HashSet<>();
            Set<UUID> people = new java.util.HashSet<>();
            for (Candidate candidate : candidates) {
                if (candidate.userId() == requesterUserId || candidate.personPublicId().equals(requesterPersonPublicId)) {
                    throw new BaseException(ErrorCode.SOD_CONFLICT, "A requester cannot occupy a quorum seat.");
                }
                if (!users.add(candidate.userId()) || !people.add(candidate.personPublicId())) {
                    throw invalid("Each frozen candidate must have a unique user and person identity.");
                }
            }
        }
    }

    public record Subject(
            long tenantId,
            long userId,
            UUID personPublicId,
            IdentityPlane identityPlane,
            boolean active,
            Set<String> roles,
            boolean canApprove) {
        public Subject {
            if (tenantId < 1 || userId < 1 || personPublicId == null || identityPlane == null || roles == null) {
                throw invalid("Current authority must identify an exact tenant subject.");
            }
            roles = Set.copyOf(roles);
        }
    }

    public record CandidatePool(
            long tenantId,
            UUID workflowVersionId,
            String candidateRole,
            List<Subject> subjects,
            String authorityRevision,
            boolean complete,
            boolean truncated,
            Instant evaluatedAt,
            Instant expiresAt) {
        public CandidatePool {
            if (tenantId < 1 || workflowVersionId == null || !role(candidateRole) || subjects == null
                    || subjects.size() > MAX_CANDIDATES || !revision(authorityRevision)
                    || evaluatedAt == null || expiresAt == null || !expiresAt.isAfter(evaluatedAt)) {
                throw unavailable("Candidate enumeration did not return bounded canonical authority evidence.");
            }
            subjects = List.copyOf(subjects);
        }
    }

    public record Delegation(
            UUID delegationId,
            long tenantId,
            UUID workflowVersionId,
            long delegatorUserId,
            long delegateUserId,
            String authorityRole,
            boolean active,
            Instant startsAt,
            Instant endsAt) {
        public Delegation {
            if (delegationId == null || tenantId < 1 || workflowVersionId == null
                    || delegatorUserId < 1 || delegateUserId < 1 || delegatorUserId == delegateUserId
                    || !role(authorityRole) || startsAt == null || endsAt == null
                    || !endsAt.isAfter(startsAt)) {
                throw invalid("Delegation evidence is malformed.");
            }
        }
    }

    public record CurrentAuthority(
            AccessMode activeAccessMode,
            String revision,
            Instant evaluatedAt,
            Instant expiresAt,
            Subject actor,
            Subject principal,
            Delegation delegation) {
        public CurrentAuthority {
            if (activeAccessMode == null || !ApprovalWorkflowQuorum.revision(revision) || evaluatedAt == null || expiresAt == null
                    || !expiresAt.isAfter(evaluatedAt) || actor == null || principal == null) {
                throw unavailable("Current candidate authority evidence is incomplete.");
            }
        }
    }

    public record Vote(
            Pins pins,
            UUID requestId,
            UUID stepId,
            long generation,
            long stageVersion,
            long actorUserId,
            UUID actorPersonPublicId,
            long principalUserId,
            UUID principalPersonPublicId,
            Decision decision,
            String reason,
            UUID delegationId,
            AccessMode activeAccessMode,
            String authorityRevision,
            int payloadRevision,
            String payloadSha256,
            Instant acceptedAt) {
        public Vote {
            if (pins == null || requestId == null || stepId == null || generation < 1
                    || stageVersion < 1 || actorUserId < 1 || actorPersonPublicId == null
                    || principalUserId < 1 || principalPersonPublicId == null || decision == null
                    || reason == null || reason.length() > 2000 || !reason.equals(reason.strip())
                    || activeAccessMode == null || activeAccessMode == AccessMode.PROVIDER_SUPPORT
                    || !revision(authorityRevision) || payloadRevision < 1 || !sha256(payloadSha256)
                    || acceptedAt == null || (actorUserId == principalUserId) != (delegationId == null)) {
                throw invalid("A committed vote must contain complete decision evidence.");
            }
        }
    }

    public record State(Snapshot snapshot, long version, List<Vote> votes) {
        public State {
            if (snapshot == null || version < 0 || votes == null || votes.size() > MAX_CANDIDATES) {
                throw invalid("The stage state is malformed.");
            }
            votes = List.copyOf(votes);
        }
    }

    public record Evaluation(Outcome outcome, int threshold, int candidates, int approved, int rejected, int remaining) { }
    public record AcceptedVote(State state, Vote vote, Evaluation evaluation) { }

    static boolean sha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    static boolean revision(String value) { return value != null && value.matches("[A-Za-z0-9._:-]{1,120}"); }
    static boolean role(String value) { return value != null && value.matches("[A-Z][A-Z0-9_]{1,79}"); }
    static BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    static BaseException unavailable(String message) { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message); }
}
