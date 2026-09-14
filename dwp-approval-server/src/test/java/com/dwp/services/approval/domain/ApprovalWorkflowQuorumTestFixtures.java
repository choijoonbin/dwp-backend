package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

final class ApprovalWorkflowQuorumTestFixtures {
    static final Instant NOW = Instant.parse("2026-09-14T02:00:00Z");
    static final UUID WORKFLOW = UUID.fromString("00000000-0000-0000-0000-000000000100");
    static final String ROLE = "APPROVAL_OPERATOR";
    static final String PAYLOAD_SHA = "b".repeat(64);
    static final Pins PINS = new Pins(1, WORKFLOW, 2, "a".repeat(64), "c".repeat(64), 3, "d".repeat(64));

    private ApprovalWorkflowQuorumTestFixtures() { }

    static UUID person(long user) { return new UUID(0, user); }
    static Subject subject(long user) { return subject(user, ROLE); }
    static Subject subject(long user, String role) { return new Subject(1, user, person(user), IdentityPlane.TENANT, true, Set.of(role), true); }
    static CurrentAuthority authority(long user) { return authority(subject(user), subject(user), null); }
    static CurrentAuthority authority(Subject actor, Subject principal, Delegation delegation) {
        return new CurrentAuthority(AccessMode.NORMAL, "auth-2", NOW.minusSeconds(1), NOW.plusSeconds(300), actor, principal, delegation);
    }
    static Delegation delegation(long actor, long principal) {
        return new Delegation(person(1000 + principal), 1, WORKFLOW, principal, actor, ROLE, true, NOW.minusSeconds(10), NOW.plusSeconds(60));
    }
    static State state(Rule rule, int count) {
        List<Candidate> candidates = IntStream.rangeClosed(1, count).mapToObj(user -> new Candidate(user, person(user))).toList();
        Snapshot snapshot = new Snapshot(PINS, person(2000), person(2001), 1, 99, person(99), 2,
                PAYLOAD_SHA, 8, ROLE, rule, candidates, "auth-1", NOW.minusSeconds(60));
        return new State(snapshot, 0, List.of());
    }
    static CandidatePool pool(List<Subject> subjects, boolean complete, boolean truncated) {
        return new CandidatePool(1, WORKFLOW, ROLE, subjects, "auth-2", complete, truncated, NOW.minusSeconds(1), NOW.plusSeconds(300));
    }
    static Vote copy(Vote vote, UUID stepId, long generation, int revision, String hash, long version) {
        return new Vote(vote.pins(), vote.requestId(), stepId, generation, version, vote.actorUserId(), vote.actorPersonPublicId(),
                vote.principalUserId(), vote.principalPersonPublicId(), vote.decision(), vote.reason(), vote.delegationId(), vote.activeAccessMode(), vote.authorityRevision(),
                revision, hash, vote.acceptedAt());
    }
}
