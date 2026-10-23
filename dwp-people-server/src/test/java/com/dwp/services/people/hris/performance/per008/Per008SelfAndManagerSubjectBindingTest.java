package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.FieldDecision;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Per008SelfAndManagerSubjectBindingTest {
    private static final UUID ACTOR_WORKER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ASSIGNMENT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SUBJECT_WORKER = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID SUBJECT_ASSIGNMENT = UUID.fromString("40000000-0000-0000-0000-000000000004");

    @Test
    void selfRequiresTheExactAuthenticatedWorkerAssignmentTuple() {
        AuthorityEvidence self = evidence("self-feedback-requester", Set.of());

        assertThat(self.permits(ACTOR_WORKER, ACTOR_ASSIGNMENT)).isTrue();
        assertThat(self.permits(ACTOR_WORKER, SUBJECT_ASSIGNMENT)).isFalse();
        assertThat(self.permits(SUBJECT_WORKER, ACTOR_ASSIGNMENT)).isFalse();
    }

    @Test
    void selfReleaseReaderUsesTheSameExactAuthenticatedTuple() {
        AuthorityEvidence self = evidence("self-feedback-release-reader", Set.of());

        assertThat(self.permits(ACTOR_WORKER, ACTOR_ASSIGNMENT)).isTrue();
        assertThat(self.permits(ACTOR_WORKER, SUBJECT_ASSIGNMENT)).isFalse();
    }

    @Test
    void managerCannotSubstituteWorkerAndAssignmentAcrossAuthorizedTuples() {
        AuthorityEvidence manager = evidence("manager-feedback-requester", Set.of(
                new SubjectBinding(SUBJECT_WORKER, SUBJECT_ASSIGNMENT),
                new SubjectBinding(ACTOR_WORKER, ACTOR_ASSIGNMENT)));

        assertThat(manager.permits(SUBJECT_WORKER, SUBJECT_ASSIGNMENT)).isTrue();
        assertThat(manager.permits(SUBJECT_WORKER, ACTOR_ASSIGNMENT)).isFalse();
        assertThat(manager.permits(ACTOR_WORKER, SUBJECT_ASSIGNMENT)).isFalse();
    }

    @Test
    void emptyManagerPopulationDeniesInsteadOfFallingOpen() {
        AuthorityEvidence manager = evidence("manager-feedback-requester", Set.of());

        assertThat(manager.permits(SUBJECT_WORKER, SUBJECT_ASSIGNMENT)).isFalse();
    }

    private AuthorityEvidence evidence(String profile, Set<SubjectBinding> subjects) {
        return new AuthorityEvidence(1, 2, UUID.randomUUID(), ACTOR_WORKER, ACTOR_ASSIGNMENT,
                "route", profile, "capability", "PERFORMANCE_FEEDBACK", "scope", "auth",
                "field", "a".repeat(64), Map.of("GROUP", FieldDecision.VIEW), subjects,
                Set.of(PepLayer.values()));
    }
}
