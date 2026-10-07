package com.dwp.services.people.hr.performance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotPage;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProjection;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQueryPort;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerformanceParticipantPreviewServiceTest {

    private static final long TENANT = 41L;
    private static final Instant AS_OF = Instant.parse("2027-02-01T00:00:00Z");
    private static final UUID RULE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final WorkforceSnapshotV1 SNAPSHOT = new WorkforceSnapshotV1(
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
            UUID.fromString("30000000-0000-0000-0000-000000000001"),
            "ACTIVE",
            UUID.fromString("40000000-0000-0000-0000-000000000001"),
            UUID.fromString("50000000-0000-0000-0000-000000000001"),
            UUID.fromString("60000000-0000-0000-0000-000000000001"),
            UUID.fromString("70000000-0000-0000-0000-000000000001"));

    @Test
    void failsClosedWhenWorkforceOrRuleProviderIsAbsent() {
        var noWorkforce = new PerformanceParticipantPreviewService(
                () -> null, () -> validEvaluator(RULE, AS_OF.minusSeconds(1), null));
        var noRule = new PerformanceParticipantPreviewService(
                this::port, () -> null);

        assertUnavailable(() -> noWorkforce.prepare(TENANT, AS_OF, RULE));
        assertUnavailable(() -> noRule.prepare(TENANT, AS_OF, RULE));
        assertUnavailable(() -> noRule.prepare(TENANT, AS_OF, null));
    }

    @Test
    void rejectsMismatchedAndExpiredPopulationRuleEvidence() {
        UUID otherRule = UUID.fromString("10000000-0000-0000-0000-000000000002");
        var mismatch = new PerformanceParticipantPreviewService(
                this::port, () -> validEvaluator(otherRule, AS_OF.minusSeconds(1), null));
        var expired = new PerformanceParticipantPreviewService(
                this::port, () -> validEvaluator(RULE,
                AS_OF.minusSeconds(60), AS_OF));

        assertUnavailable(() -> mismatch.prepare(TENANT, AS_OF, RULE));
        assertUnavailable(() -> expired.prepare(TENANT, AS_OF, RULE));
    }

    @Test
    void freezesAllSevenOwnerFieldsWithRuleDecision() {
        var service = new PerformanceParticipantPreviewService(
                this::port, () -> validEvaluator(RULE, AS_OF.minusSeconds(1), null));

        PerformanceParticipantPreviewService.PreparedPreview preview =
                service.prepare(TENANT, AS_OF, RULE);

        assertThat(preview.workforceSnapshotRevision()).isEqualTo(7L);
        assertThat(preview.populationRuleVersionId()).isEqualTo(RULE);
        assertThat(preview.participantCount()).isEqualTo(1);
        assertThat(preview.reviewerAssignmentCount()).isEqualTo(1);
        assertThat(preview.contentHash()).hasSize(64);
        assertThat(preview.members()).singleElement().satisfies(member -> {
            assertThat(member.participantRef()).isEqualTo(SNAPSHOT.workerPublicId());
            assertThat(member.primaryAssignmentRef()).isEqualTo(SNAPSHOT.assignmentPublicId());
            assertThat(member.workforceStatus()).isEqualTo("ACTIVE");
            assertThat(member.organizationRef()).isEqualTo(SNAPSHOT.organizationPublicId());
            assertThat(member.reviewerAssignmentRef())
                    .isEqualTo(SNAPSHOT.managerAssignmentPublicId());
            assertThat(member.jobProfileRef()).isEqualTo(SNAPSHOT.jobProfilePublicId());
            assertThat(member.gradeRef()).isEqualTo(SNAPSHOT.gradePublicId());
            assertThat(member.eligibilityCode()).isEqualTo("INCLUDED");
        });
    }

    @Test
    void retainsTwoAssignmentsForTheSameWorker() {
        WorkforceSnapshotV1 secondAssignment = new WorkforceSnapshotV1(
                SNAPSHOT.workerPublicId(),
                UUID.fromString("30000000-0000-0000-0000-000000000002"),
                "ACTIVE",
                UUID.fromString("40000000-0000-0000-0000-000000000002"),
                UUID.fromString("50000000-0000-0000-0000-000000000002"),
                UUID.fromString("60000000-0000-0000-0000-000000000002"),
                UUID.fromString("70000000-0000-0000-0000-000000000002"));
        WorkforceSnapshotQueryPort port = mock(WorkforceSnapshotQueryPort.class);
        when(port.query(any(WorkforceSnapshotQuery.class))).thenReturn(
                WorkforceSnapshotPage.verified(
                        TENANT,
                        "HRIS-PER",
                        WorkforceSnapshotQuery.PURPOSE_CODE,
                        WorkforceSnapshotProjection.PERFORMANCE_V1,
                        AS_OF,
                        7,
                        List.of(SNAPSHOT, secondAssignment),
                        null));
        PerformancePopulationRuleEvaluator evaluator = request ->
                new PerformancePopulationRuleEvaluator.PopulationRuleEvaluation(
                        RULE,
                        AS_OF.minusSeconds(1),
                        null,
                        List.of(
                                new PerformancePopulationRuleEvaluator.PopulationDecision(
                                        SNAPSHOT.workerPublicId(),
                                        SNAPSHOT.assignmentPublicId(),
                                        "INCLUDED"),
                                new PerformancePopulationRuleEvaluator.PopulationDecision(
                                        secondAssignment.workerPublicId(),
                                        secondAssignment.assignmentPublicId(),
                                        "REVIEW_REQUIRED")));
        var service = new PerformanceParticipantPreviewService(() -> port, () -> evaluator);

        PerformanceParticipantPreviewService.PreparedPreview preview =
                service.prepare(TENANT, AS_OF, RULE);

        assertThat(preview.members()).hasSize(2)
                .extracting(PerformanceCycleDtos.PreviewMember::primaryAssignmentRef)
                .containsExactly(
                        SNAPSHOT.assignmentPublicId(), secondAssignment.assignmentPublicId());
        assertThat(preview.participantCount()).isEqualTo(2);
        assertThat(preview.reviewerAssignmentCount()).isEqualTo(2);
    }

    private WorkforceSnapshotQueryPort port() {
        WorkforceSnapshotQueryPort port = mock(WorkforceSnapshotQueryPort.class);
        WorkforceSnapshotPage page = WorkforceSnapshotPage.verified(
                TENANT,
                "HRIS-PER",
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                AS_OF,
                7,
                List.of(SNAPSHOT),
                null);
        when(port.query(any(WorkforceSnapshotQuery.class))).thenReturn(page);
        return port;
    }

    private PerformancePopulationRuleEvaluator validEvaluator(
            UUID returnedRule,
            Instant effectiveFrom,
            Instant effectiveTo) {
        return request -> {
            assertThat(request.tenantId()).isEqualTo(TENANT);
            return new PerformancePopulationRuleEvaluator.PopulationRuleEvaluation(
                    returnedRule,
                    effectiveFrom,
                    effectiveTo,
                    List.of(new PerformancePopulationRuleEvaluator.PopulationDecision(
                            SNAPSHOT.workerPublicId(), SNAPSHOT.assignmentPublicId(),
                            "INCLUDED")));
        };
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }
}
