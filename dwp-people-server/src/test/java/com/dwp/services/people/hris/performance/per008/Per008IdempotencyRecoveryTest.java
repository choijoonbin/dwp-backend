package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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

class Per008IdempotencyRecoveryTest {
    private static final UUID CYCLE = new UUID(0, 401);
    private static final UUID WORKER = new UUID(0, 402);
    private static final UUID ASSIGNMENT = new UUID(0, 403);
    private static final UUID TEMPLATE = new UUID(0, 404);
    private static final String KEY = "timeout-recovery-0001";

    @Test
    void retryAfterAnUnknownClientOutcomeReturnsTheCompletedReceiptWithoutReresolution() {
        TimeoutRepository repository = new TimeoutRepository();
        Per008Service service = service(repository);

        assertThatThrownBy(() -> service.create(command(2), KEY, "auth-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated response loss after commit");

        FeedbackRequestReceipt replay = service.create(command(2), KEY, "auth-1");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.requestId()).isEqualTo(repository.requestId);
        assertThat(repository.resolutionCalls).isEqualTo(1);
        assertThat(repository.frozenExecutionBindingDigest).isEqualTo("9".repeat(64));
    }

    @Test
    void recoveredKeyStillRejectsADifferentClientIntentBeforeReresolution() {
        TimeoutRepository repository = new TimeoutRepository();
        Per008Service service = service(repository);
        assertThatThrownBy(() -> service.create(command(2), KEY, "auth-1"));

        assertThatThrownBy(() -> service.create(command(3), KEY, "auth-1"))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("PER008_IDEMPOTENCY_KEY_REUSED");
        assertThat(repository.resolutionCalls).isEqualTo(1);
    }

    private Per008Service service(TimeoutRepository repository) {
        AuthorityEvidence evidence = Per008TestSupport.evidence("manager-feedback-requester",
                "hcm.team.performance.review", "PERFORMANCE_FEEDBACK", Map.of(
                        "LIMITED_PROFILE", FieldDecision.VIEW,
                        "PERFORMANCE_ASSIGNED", FieldDecision.VIEW,
                        "PERFORMANCE_FEEDBACK_ASSIGNED", FieldDecision.VIEW),
                Set.of(new SubjectBinding(WORKER, ASSIGNMENT)));
        return new Per008Service(ignored -> evidence, repository,
                Per008ProtectedNarrativeTest.protection(
                        new Per008ProtectedNarrativeTest.RotatingDigest()), null,
                new ObjectMapper(), (Per008Service.OwnerLifecycleAuthorityPort) null,
                request -> true,
                Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
    }

    private CreateFeedbackRequestCommand command(int threshold) {
        OffsetDateTime opens = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        return new CreateFeedbackRequestCommand(CYCLE, WORKER, ASSIGNMENT, TEMPLATE,
                AnonymityMode.MASKED, threshold, opens, opens.plusDays(10));
    }

    private static final class TimeoutRepository extends Per008TestSupport.RepositoryStub {
        private final UUID receiptId = new UUID(0, 405);
        private UUID requestId;
        private ReceiptRecord stored;
        private int resolutionCalls;
        private String frozenExecutionBindingDigest;

        @Override public ReceiptRecord claimCreateReceipt(AuthorityEvidence authority, String key,
                String digest, UUID proposedRequestId, OffsetDateTime now) {
            if (stored != null) return stored;
            requestId = proposedRequestId;
            stored = new ReceiptRecord(receiptId, digest, requestId, null, 0, null, "ACCEPTED");
            return stored;
        }

        @Override public List<TemplateAuthority> eligibleTemplates(AuthorityEvidence authority) {
            resolutionCalls++;
            return List.of(template());
        }

        @Override public CreateResolution resolveCreate(AuthorityEvidence authority,
                CreateFeedbackRequestCommand command, TemplateAuthority selected) {
            WorkforceEvidence subject = new WorkforceEvidence(new UUID(0, 406), 1, 2,
                    OffsetDateTime.parse("2026-10-01T00:00:00Z"), "d".repeat(64),
                    "e".repeat(64), WORKER, ASSIGNMENT, null, "ACTIVE");
            return new CreateResolution(subject, selected,
                    new PrivacyPolicy(new UUID(0, 407), "privacy-1", "f".repeat(64), 2),
                    new AudiencePolicy(new UUID(0, 408), "audience-1", "8".repeat(64),
                            Set.of(RelationshipType.SELF), Set.of()),
                    List.of(new AudienceMember(WORKER, ASSIGNMENT, RelationshipType.SELF,
                            subject.projectionHeadId(), subject.localRevision(), "MASKED")),
                    "7".repeat(64), "9".repeat(64));
        }

        @Override public FeedbackRequestReceipt create(AuthorityEvidence authority,
                CreateFeedbackRequestCommand command, CreateResolution resolution,
                ReceiptRecord claimed, OffsetDateTime now) {
            frozenExecutionBindingDigest = resolution.executionBindingDigest();
            stored = new ReceiptRecord(receiptId, claimed.clientIntentDigest(), requestId,
                    requestId, 1, now, "SUCCEEDED");
            throw new IllegalStateException("simulated response loss after commit");
        }

        private TemplateAuthority template() {
            return new TemplateAuthority(TEMPLATE, TEMPLATE, new UUID(0, 409), 1,
                    "dwp.hris.performance.review-form.v1", 1, "b".repeat(64),
                    "c".repeat(64), "Feedback", List.of(), new byte[]{1}, new UUID(0, 410));
        }
    }
}
