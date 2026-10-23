package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

class Per008SubmissionReplayAfterCloseAndKeyRotationTest {
    private static final UUID REQUEST = new UUID(0, 10);
    private static final UUID ASSIGNMENT = new UUID(0, 11);
    private static final UUID RESPONSE = new UUID(0, 12);
    private static final UUID RECEIPT = new UUID(0, 13);
    private static final UUID QUESTION = new UUID(0, 14);
    private static final String HASH = "b".repeat(64);
    private static final String KEY = "submission-key-001";
    private static final long VERSION = 4;

    @Test
    void replaysFromReceiptAndHistoricalHmacBeforeClosedRequestStateIsReevaluated() {
        Per008ProtectedNarrativeTest.RotatingDigest digests =
                new Per008ProtectedNarrativeTest.RotatingDigest();
        Per008ProtectedNarrativeTest.InMemoryKeyProvider encryption =
                new Per008ProtectedNarrativeTest.InMemoryKeyProvider();
        Per008Protection protection = Per008ProtectedNarrativeTest.protection(
                digests, encryption);
        FeedbackResponse response = response(BigDecimal.valueOf(4.5));
        byte[] canonical = protection.canonicalResponseForReplay(response);
        ProtectedContent content = protection.protect(7, ASSIGNMENT, REQUEST, HASH,
                "PERFORMANCE_FEEDBACK_RESPONSE", canonical);
        AuthorityEvidence evidence = authority();
        String intent = intent(protection, evidence, content);
        ReplayRepository repository = new ReplayRepository(new ReceiptRecord(RECEIPT, intent,
                REQUEST, ASSIGNMENT, VERSION, OffsetDateTime.parse("2026-10-22T00:00:00Z"),
                "SUCCEEDED"), new Per008Repository.StoredResponse(RESPONSE, ASSIGNMENT, HASH,
                content, OffsetDateTime.parse("2026-10-22T00:00:00Z")));
        digests.rotate();
        Per008Service service = new Per008Service(ignored -> evidence, repository, protection, null,
                new ObjectMapper(), (Per008Service.OwnerLifecycleAuthorityPort) null,
                Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC));

        FeedbackSubmissionReceipt replay = service.submit(ASSIGNMENT,
                new SubmitFeedbackResponseCommand(VERSION, HASH, response), KEY, "auth-1");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receiptId()).isEqualTo(RECEIPT);
        assertThat(repository.assignmentReads).isZero();
        assertThat(encryption.unwrapCalls()).isZero();
        assertThat(digests.lastVerifiedVersion())
                .isEqualTo(content.digestKeyVersionPublicId());
    }

    @Test
    void sameKeyWithDifferentCanonicalResponseIsRejectedBeforeMutableLookup() {
        Per008ProtectedNarrativeTest.RotatingDigest digests =
                new Per008ProtectedNarrativeTest.RotatingDigest();
        Per008Protection protection = Per008ProtectedNarrativeTest.protection(digests);
        FeedbackResponse original = response(BigDecimal.valueOf(4.5));
        ProtectedContent content = protection.protect(7, ASSIGNMENT, REQUEST, HASH,
                "PERFORMANCE_FEEDBACK_RESPONSE",
                protection.canonicalResponseForReplay(original));
        AuthorityEvidence evidence = authority();
        ReplayRepository repository = new ReplayRepository(new ReceiptRecord(RECEIPT,
                intent(protection, evidence, content), REQUEST, ASSIGNMENT, VERSION,
                OffsetDateTime.parse("2026-10-22T00:00:00Z"), "SUCCEEDED"),
                new Per008Repository.StoredResponse(RESPONSE, ASSIGNMENT, HASH, content,
                        OffsetDateTime.parse("2026-10-22T00:00:00Z")));
        Per008Service service = new Per008Service(ignored -> evidence, repository, protection, null,
                new ObjectMapper(), (Per008Service.OwnerLifecycleAuthorityPort) null,
                Clock.systemUTC());

        assertThatThrownBy(() -> service.submit(ASSIGNMENT,
                new SubmitFeedbackResponseCommand(VERSION, HASH, response(BigDecimal.ONE)),
                KEY, "auth-1"))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("PER008_IDEMPOTENCY_KEY_REUSED");
        assertThat(repository.assignmentReads).isZero();
    }

    private FeedbackResponse response(BigDecimal score) {
        return new FeedbackResponse("PerformanceFeedbackResponse.v1", List.of(
                new FeedbackAnswer(QUESTION, AnswerType.NUMBER, null, score, List.of())));
    }

    private AuthorityEvidence authority() {
        return Per008TestSupport.evidence("assigned-feedback-provider",
                "per.feedback.assigned.submit", "PERFORMANCE_FEEDBACK",
                Map.of("PERFORMANCE_FEEDBACK_ASSIGNED", FieldDecision.VIEW), Set.of());
    }

    private String intent(Per008Protection protection, AuthorityEvidence evidence,
            ProtectedContent content) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : List.of(evidence.tenantId(), evidence.actorId(),
                evidence.authorizationPrincipalPublicId(), "PEP-PER-022", evidence.capabilityKey(),
                evidence.purposeCode(), ASSIGNMENT, REQUEST, VERSION, HASH,
                content.canonicalContentHmac(), content.digestKeyVersionPublicId())) {
            String text = String.valueOf(value);
            canonical.append(text.getBytes(StandardCharsets.UTF_8).length)
                    .append(':').append(text).append('|');
        }
        return protection.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final class ReplayRepository extends Per008TestSupport.RepositoryStub {
        private final ReceiptRecord receipt;
        private final StoredResponse response;
        private int assignmentReads;
        private ReplayRepository(ReceiptRecord receipt, StoredResponse response) {
            this.receipt = receipt;
            this.response = response;
        }
        @Override public void lockIdempotency(AuthorityEvidence authority, String action,
                String key) { }
        @Override public Optional<ReceiptRecord> receipt(AuthorityEvidence authority,
                String action, String key) { return Optional.of(receipt); }
        @Override public StoredResponse storedResponseForReplay(AuthorityEvidence authority,
                UUID receiptId, UUID assignmentId) { return response; }
        @Override public AssignmentDetailRecord assignment(AuthorityEvidence authority,
                UUID assignmentId, boolean forUpdate, OffsetDateTime now) {
            assignmentReads++;
            throw new AssertionError("mutable assignment must not be resolved on replay");
        }
    }
}
