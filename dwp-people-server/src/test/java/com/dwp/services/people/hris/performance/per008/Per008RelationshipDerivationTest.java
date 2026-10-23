package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Per008RelationshipDerivationTest {
    @Test
    void rejectsRefereeWorkerAssignmentCrossPairAndDoesNotFabricateMembership() {
        UUID subjectWorker = id(1);
        UUID subjectAssignment = id(2);
        UUID actualWorker = id(3);
        UUID actualAssignment = id(4);
        WorkforceEvidence subject = new WorkforceEvidence(id(9), 3, 5,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), "a".repeat(64),
                "b".repeat(64), subjectWorker, subjectAssignment, null, "ACTIVE");
        AudiencePolicy policy = new AudiencePolicy(id(10), "r1", "c".repeat(64),
                Set.of(RelationshipType.REFEREE),
                Set.of(new SubjectBinding(subjectWorker, actualAssignment)));
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        JdbcPer008Repository repository = new JdbcPer008Repository(null, null, null,
                empty.getBeanProvider(JdbcPer008Repository.PrivacyPolicyPort.class),
                empty.getBeanProvider(JdbcPer008Repository.AudiencePolicyPort.class));

        assertThatThrownBy(() -> repository.deriveAudience(subject, List.of(
                new JdbcPer008Repository.Member(actualWorker, actualAssignment, null, "ACTIVE")),
                AnonymityMode.ANONYMOUS_AGGREGATE, policy))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("PER008_AUDIENCE_POLICY_INVALID");
    }

    private static UUID id(long value) { return new UUID(0, value); }
}
