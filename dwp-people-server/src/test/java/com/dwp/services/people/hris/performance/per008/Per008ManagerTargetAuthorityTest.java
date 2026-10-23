package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Per008ManagerTargetAuthorityTest {
    private static final UUID CURRENT_CYCLE = new UUID(0, 801);
    private static final UUID OTHER_CYCLE = new UUID(0, 802);
    private static final UUID WORKER = new UUID(0, 803);
    private static final UUID ASSIGNMENT = new UUID(0, 804);
    private static final UUID TEMPLATE = new UUID(0, 805);

    @Test
    void sameWorkerAssignmentTupleFromAnotherCycleIsDeniedBeforeSubjectLookup() {
        TargetRepository repository = new TargetRepository();
        Per008Service service = service(repository, request ->
                request.target().cycleVersionId().equals(CURRENT_CYCLE)
                        && request.target().subjectWorkerPublicId().equals(WORKER)
                        && request.target().subjectAssignmentPublicId().equals(ASSIGNMENT)
                        && request.target().scopeDecisionRevision().equals("scope-1")
                        && request.target().authorizationDecisionRevision().equals("auth-1"));

        assertThatThrownBy(() -> service.options(OTHER_CYCLE, WORKER, ASSIGNMENT))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("PER008_RESOURCE_NOT_FOUND");
        assertThat(repository.subjectReads).isZero();
    }

    @Test
    void staleManagerDecisionTupleIsDeniedBeforeDynamicCreateResolution() {
        TargetRepository repository = new TargetRepository();
        Per008Service service = service(repository, request ->
                request.target().scopeDecisionRevision().equals("scope-current")
                        && request.target().authorizationDecisionRevision().equals("auth-current"));
        OffsetDateTime opens = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        CreateFeedbackRequestCommand command = new CreateFeedbackRequestCommand(
                CURRENT_CYCLE, WORKER, ASSIGNMENT, TEMPLATE, AnonymityMode.MASKED,
                2, opens, opens.plusDays(10));

        assertThatThrownBy(() -> service.create(
                command, "manager-target-key-0001", "auth-1"))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("PER008_RESOURCE_NOT_FOUND");
        assertThat(repository.receiptClaims).isEqualTo(1);
        assertThat(repository.templateReads).isZero();
    }

    private Per008Service service(TargetRepository repository,
            Per008Service.ManagerTargetAuthorityPort targetAuthority) {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        Per008Protection protection = new Per008Protection(new ObjectMapper(),
                empty.getBeanProvider(com.dwp.core.crypto.EnvelopeEncryptionService.class),
                empty.getBeanProvider(Per008Protection.TenantDigestKeyProvider.class), "local");
        AuthorityEvidence evidence = Per008TestSupport.evidence("manager-feedback-requester",
                "hcm.team.performance.review", "PERFORMANCE_FEEDBACK", Map.of(
                        "PERFORMANCE_FEEDBACK_TEMPLATE_OPTION", FieldDecision.VIEW,
                        "PERFORMANCE_FEEDBACK_POLICY_LIMIT", FieldDecision.VIEW,
                        "LIMITED_PROFILE", FieldDecision.VIEW,
                        "PERFORMANCE_ASSIGNED", FieldDecision.VIEW,
                        "PERFORMANCE_FEEDBACK_ASSIGNED", FieldDecision.VIEW),
                Set.of(new SubjectBinding(WORKER, ASSIGNMENT)));
        return new Per008Service(ignored -> evidence, repository, protection, null,
                new ObjectMapper(), (Per008Service.OwnerLifecycleAuthorityPort) null,
                targetAuthority,
                Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
    }

    private static final class TargetRepository extends Per008TestSupport.RepositoryStub {
        private int subjectReads;
        private int receiptClaims;
        private int templateReads;

        @Override public WorkforceEvidence subject(AuthorityEvidence authority, UUID cycle,
                UUID worker, UUID assignment) {
            subjectReads++;
            return new WorkforceEvidence(new UUID(0, 806), 1, 1,
                    OffsetDateTime.parse("2026-10-01T00:00:00Z"), "a".repeat(64),
                    "b".repeat(64), worker, assignment, null, "ACTIVE");
        }

        @Override public List<TemplateAuthority> eligibleTemplates(AuthorityEvidence authority) {
            templateReads++;
            return List.of(new TemplateAuthority(TEMPLATE, TEMPLATE, new UUID(0, 807), 1,
                    "dwp.hris.performance.review-form.v1", 1, "c".repeat(64),
                    "d".repeat(64), "Feedback", List.of(), new byte[]{1}, new UUID(0, 808)));
        }

        @Override public ReceiptRecord claimCreateReceipt(AuthorityEvidence authority, String key,
                String digest, UUID proposedRequestId, OffsetDateTime now) {
            receiptClaims++;
            return new ReceiptRecord(new UUID(0, 809), digest, proposedRequestId,
                    null, 0, null, "ACCEPTED");
        }
    }
}
