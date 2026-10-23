package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class Per008TestSupport {
    private Per008TestSupport() { }

    static AuthorityEvidence evidence(String profile, String capability, String purpose,
            Map<String, FieldDecision> fields, Set<SubjectBinding> population) {
        UUID worker = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID assignment = UUID.fromString("20000000-0000-0000-0000-000000000002");
        return new AuthorityEvidence(7, 9,
                UUID.fromString("30000000-0000-0000-0000-000000000003"), worker, assignment,
                "route", profile, capability, purpose, "scope-1", "auth-1", "field-1",
                "a".repeat(64), fields, population, Set.of(PepLayer.values()));
    }

    static RequestRecord request(UUID requestId, RequestState state, long version,
            UUID subjectWorker, UUID subjectAssignment) {
        return new RequestRecord(7, requestId, version, UUID.randomUUID(), subjectWorker,
                subjectAssignment, UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64),
                "Feedback", state, AnonymityMode.ANONYMOUS_AGGREGATE, 2, UUID.randomUUID(),
                "privacy-7", "c".repeat(64), OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                OffsetDateTime.parse("2026-11-01T00:00:00Z"),
                OffsetDateTime.parse("2026-09-01T00:00:00Z"),
                state == RequestState.AGGREGATED || state == RequestState.RELEASED
                        ? OffsetDateTime.parse("2026-11-02T00:00:00Z") : null,
                state == RequestState.RELEASED
                        ? OffsetDateTime.parse("2026-11-03T00:00:00Z") : null);
    }

    abstract static class RepositoryStub implements Per008Repository {
        protected UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by test");
        }
        @Override public void lockIdempotency(AuthorityEvidence a, String b, String c) {
            throw unsupported();
        }
        @Override public ReceiptRecord claimCreateReceipt(AuthorityEvidence a, String b, String c,
                UUID d, OffsetDateTime e) { throw unsupported(); }
        @Override public Optional<ReceiptRecord> receipt(AuthorityEvidence a, String b, String c) {
            throw unsupported();
        }
        @Override public WorkforceEvidence subject(AuthorityEvidence a, UUID b, UUID c, UUID d) {
            throw unsupported();
        }
        @Override public List<TemplateAuthority> eligibleTemplates(AuthorityEvidence a) {
            throw unsupported();
        }
        @Override public CreateResolution resolveCreate(AuthorityEvidence a,
                CreateFeedbackRequestCommand b, TemplateAuthority c) { throw unsupported(); }
        @Override public FeedbackRequestReceipt create(AuthorityEvidence a,
                CreateFeedbackRequestCommand b, CreateResolution c, ReceiptRecord d,
                OffsetDateTime e) { throw unsupported(); }
        @Override public List<AssignedListItem> assignmentsFor(AuthorityEvidence a, int b,
                String c, OffsetDateTime d) { throw unsupported(); }
        @Override public AssignmentDetailRecord assignment(AuthorityEvidence a, UUID b,
                boolean c, OffsetDateTime d) { throw unsupported(); }
        @Override public FeedbackSubmissionReceipt submit(AuthorityEvidence a,
                AssignmentDetailRecord b, ProtectedContent c, String d, String e,
                OffsetDateTime f) { throw unsupported(); }
        @Override public RequestRecord request(AuthorityEvidence a, UUID b, boolean c) {
            throw unsupported();
        }
        @Override public TemplateAuthority templateForRequest(AuthorityEvidence a, UUID b) {
            throw unsupported();
        }
        @Override public Optional<ReleaseSnapshot> releaseSnapshot(AuthorityEvidence a, UUID b) {
            throw unsupported();
        }
        @Override public StoredResponse storedResponseForReplay(AuthorityEvidence a, UUID b,
                UUID c) { throw unsupported(); }
        @Override public List<StoredResponse> submittedResponses(AuthorityEvidence a, UUID b) {
            throw unsupported();
        }
        @Override public UUID createLifecycleReceipt(AuthorityEvidence a, RequestRecord b,
                String c, String d, String e, OffsetDateTime f) { throw unsupported(); }
        @Override public void transitionRequest(AuthorityEvidence a, UUID b, RequestState c,
                RequestState d, long e, UUID f, OffsetDateTime g) { throw unsupported(); }
        @Override public void cancelOutstandingAssignments(AuthorityEvidence a, UUID b,
                OffsetDateTime c) { throw unsupported(); }
        @Override public void insertReleaseSnapshot(AuthorityEvidence a, RequestRecord b, UUID c,
                UUID d, ProtectedContent e, String f, String g, int h, OffsetDateTime i) {
            throw unsupported();
        }
    }
}
