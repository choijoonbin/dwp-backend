package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Per008ServerDerivedAudienceTest {
    private static final UUID SUBJECT_WORKER = id(1);
    private static final UUID SUBJECT_ASSIGNMENT = id(2);
    private static final UUID MANAGER_WORKER = id(3);
    private static final UUID MANAGER_ASSIGNMENT = id(4);
    private static final UUID PEER_WORKER = id(5);
    private static final UUID PEER_ASSIGNMENT = id(6);
    private static final UUID DIRECT_WORKER = id(7);
    private static final UUID DIRECT_ASSIGNMENT = id(8);

    @Test
    void derivesOnlyFrozenPolicyRelationshipsFromTheExactV16Revision() {
        AudiencePolicy policy = new AudiencePolicy(id(20), "audience-7", "a".repeat(64),
                Set.of(RelationshipType.MANAGER, RelationshipType.REFEREE),
                Set.of(new SubjectBinding(DIRECT_WORKER, DIRECT_ASSIGNMENT)));

        List<AudienceMember> result = repository().deriveAudience(subject(), members(),
                AnonymityMode.MASKED, policy);

        assertThat(result).extracting(AudienceMember::relationshipType)
                .containsExactly(RelationshipType.MANAGER, RelationshipType.REFEREE);
        assertThat(result).extracting(AudienceMember::assignmentPublicId)
                .containsExactly(MANAGER_ASSIGNMENT, DIRECT_ASSIGNMENT)
                .doesNotContain(PEER_ASSIGNMENT, SUBJECT_ASSIGNMENT);
        assertThat(result).allSatisfy(member -> {
            assertThat(member.projectionHeadId()).isEqualTo(id(30));
            assertThat(member.localRevision()).isEqualTo(11);
            assertThat(member.maskedLabel()).isEqualTo("MASKED");
        });
    }

    private JdbcPer008Repository repository() {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        return new JdbcPer008Repository(null, null, null,
                empty.getBeanProvider(JdbcPer008Repository.PrivacyPolicyPort.class),
                empty.getBeanProvider(JdbcPer008Repository.AudiencePolicyPort.class));
    }

    private WorkforceEvidence subject() {
        return new WorkforceEvidence(id(30), 11, 19,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), "b".repeat(64),
                "c".repeat(64), SUBJECT_WORKER, SUBJECT_ASSIGNMENT, MANAGER_ASSIGNMENT, "ACTIVE");
    }

    private List<JdbcPer008Repository.Member> members() {
        return List.of(
                new JdbcPer008Repository.Member(SUBJECT_WORKER, SUBJECT_ASSIGNMENT,
                        MANAGER_ASSIGNMENT, "ACTIVE"),
                new JdbcPer008Repository.Member(MANAGER_WORKER, MANAGER_ASSIGNMENT, null, "ACTIVE"),
                new JdbcPer008Repository.Member(PEER_WORKER, PEER_ASSIGNMENT,
                        MANAGER_ASSIGNMENT, "ACTIVE"),
                new JdbcPer008Repository.Member(DIRECT_WORKER, DIRECT_ASSIGNMENT,
                        SUBJECT_ASSIGNMENT, "ACTIVE"));
    }

    private static UUID id(long value) { return new UUID(0, value); }
}
