package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Per008AssignedSubmissionGuardTest {
    private static final UUID REQUEST = new UUID(0, 201);
    private static final UUID ASSIGNMENT = new UUID(0, 202);
    private static final UUID QUESTION = new UUID(0, 203);
    private static final UUID TEMPLATE = new UUID(0, 204);
    private static final UUID ACTOR_WORKER =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ASSIGNMENT =
            UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String HASH = "b".repeat(64);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-23T00:00:00Z");

    @Test
    void submitsOnlyOneAssignedOpenWindowTupleUnderTheOwnerRowLock() {
        GuardRepository repository = new GuardRepository(RequestState.OPEN,
                AssignmentState.ASSIGNED, OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                OffsetDateTime.parse("2026-11-01T00:00:00Z"));

        FeedbackSubmissionReceipt result = service(repository).submit(ASSIGNMENT, command(),
                "assigned-submit-0001", "auth-1");

        assertThat(result.assignmentId()).isEqualTo(ASSIGNMENT);
        assertThat(repository.assignmentForUpdate).isTrue();
        assertThat(repository.assignmentReadAt).isEqualTo(NOW);
        assertThat(repository.submitCalls).isEqualTo(1);
        assertThat(repository.protectedContent.encodedEnvelope()).isNotEmpty();
    }

    @Test
    void rejectsClosedExpiredFutureOrAlreadySubmittedAssignmentsBeforePersistence() {
        List<GuardRepository> rejected = List.of(
                new GuardRepository(RequestState.CLOSED, AssignmentState.ASSIGNED,
                        NOW.minusDays(1), NOW.plusDays(1)),
                new GuardRepository(RequestState.OPEN, AssignmentState.SUBMITTED,
                        NOW.minusDays(1), NOW.plusDays(1)),
                new GuardRepository(RequestState.OPEN, AssignmentState.ASSIGNED,
                        NOW.plusMinutes(1), NOW.plusDays(1)),
                new GuardRepository(RequestState.OPEN, AssignmentState.ASSIGNED,
                        NOW.minusDays(1), NOW));

        for (GuardRepository repository : rejected) {
            assertThatThrownBy(() -> service(repository).submit(ASSIGNMENT, command(),
                    "assigned-submit-0002", "auth-1"))
                    .isInstanceOf(Per008Problem.class)
                    .hasMessage("PER008_ASSIGNMENT_NOT_OPEN");
            assertThat(repository.submitCalls).isZero();
        }
    }

    private Per008Service service(GuardRepository repository) {
        AuthorityEvidence evidence = Per008TestSupport.evidence("assigned-feedback-provider",
                "per.feedback.assigned.submit", "PERFORMANCE_FEEDBACK",
                Map.of("PERFORMANCE_FEEDBACK_ASSIGNED", FieldDecision.VIEW), Set.of());
        Per008Protection protection = Per008ProtectedNarrativeTest.protection(
                new Per008ProtectedNarrativeTest.RotatingDigest());
        return new Per008Service(ignored -> evidence, repository, protection, null,
                new ObjectMapper(), (Per008Service.OwnerLifecycleAuthorityPort) null,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private SubmitFeedbackResponseCommand command() {
        FeedbackResponse response = new FeedbackResponse("PerformanceFeedbackResponse.v1",
                List.of(new FeedbackAnswer(QUESTION, AnswerType.NUMBER, null,
                        BigDecimal.valueOf(4.5), List.of())));
        return new SubmitFeedbackResponseCommand(4, HASH, response);
    }

    private static final class GuardRepository extends Per008TestSupport.RepositoryStub {
        private final AssignmentDetailRecord detail;
        private boolean assignmentForUpdate;
        private OffsetDateTime assignmentReadAt;
        private int submitCalls;
        private ProtectedContent protectedContent;

        private GuardRepository(RequestState requestState, AssignmentState assignmentState,
                OffsetDateTime opensAt, OffsetDateTime closesAt) {
            RequestRecord base = Per008TestSupport.request(REQUEST, requestState, 4,
                    new UUID(0, 301), new UUID(0, 302));
            RequestRecord request = new RequestRecord(base.tenantId(), base.publicId(),
                    base.aggregateVersion(), base.cycleVersionId(), base.subjectWorkerPublicId(),
                    base.subjectAssignmentPublicId(), base.authorizationPrincipalPublicId(),
                    base.templateArtifactPublicId(), HASH, base.requestTitle(), base.state(),
                    base.anonymityMode(), base.effectiveMinimumReleaseCount(),
                    base.privacyPolicyPublicId(), base.thresholdPolicyRevision(),
                    base.thresholdPolicyDigest(), opensAt, closesAt, base.createdAt(),
                    base.aggregatedAt(), base.releasedAt());
            AssignmentRecord assignment = new AssignmentRecord(7, ASSIGNMENT, REQUEST,
                    ACTOR_WORKER, ACTOR_ASSIGNMENT, RelationshipType.PEER, assignmentState,
                    assignmentState == AssignmentState.SUBMITTED ? NOW.minusMinutes(1) : null);
            TemplateAuthority template = new TemplateAuthority(TEMPLATE, TEMPLATE,
                    new UUID(0, 303), 1, "dwp.hris.performance.review-form.v1", 1, HASH,
                    "c".repeat(64), "Feedback", List.of(new Question(QUESTION, "점수",
                    AnswerType.NUMBER, true, List.of())), new byte[]{1}, new UUID(0, 304));
            detail = new AssignmentDetailRecord(request, assignment, template);
        }

        @Override public void lockIdempotency(AuthorityEvidence authority, String action,
                String key) { }

        @Override public Optional<ReceiptRecord> receipt(AuthorityEvidence authority,
                String action, String key) { return Optional.empty(); }

        @Override public AssignmentDetailRecord assignment(AuthorityEvidence authority,
                UUID assignmentId, boolean forUpdate, OffsetDateTime now) {
            assertThat(assignmentId).isEqualTo(ASSIGNMENT);
            assertThat(authority.actorWorkerPublicId()).isEqualTo(ACTOR_WORKER);
            assertThat(authority.actorAssignmentPublicId()).isEqualTo(ACTOR_ASSIGNMENT);
            assignmentForUpdate = forUpdate;
            assignmentReadAt = now;
            return detail;
        }

        @Override public FeedbackSubmissionReceipt submit(AuthorityEvidence authority,
                AssignmentDetailRecord selected, ProtectedContent response, String key,
                String intent, OffsetDateTime now) {
            submitCalls++;
            protectedContent = response;
            return new FeedbackSubmissionReceipt(new UUID(0, 305), REQUEST, ASSIGNMENT,
                    AssignmentState.SUBMITTED, 4, now, false);
        }
    }
}
