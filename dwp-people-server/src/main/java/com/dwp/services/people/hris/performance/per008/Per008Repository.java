package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface Per008Repository {
    void lockIdempotency(AuthorityEvidence authority, String action, String idempotencyKey);

    ReceiptRecord claimCreateReceipt(AuthorityEvidence authority, String idempotencyKey,
            String clientIntentDigest, UUID requestId, OffsetDateTime now);

    Optional<ReceiptRecord> receipt(AuthorityEvidence authority, String action,
            String idempotencyKey);

    WorkforceEvidence subject(AuthorityEvidence authority, UUID cycleVersionId,
            UUID workerPublicId, UUID assignmentPublicId);

    List<TemplateAuthority> eligibleTemplates(AuthorityEvidence authority);

    CreateResolution resolveCreate(AuthorityEvidence authority,
            CreateFeedbackRequestCommand command, TemplateAuthority template);

    FeedbackRequestReceipt create(AuthorityEvidence authority,
            CreateFeedbackRequestCommand command, CreateResolution resolution,
            ReceiptRecord claimedReceipt, OffsetDateTime now);

    List<AssignedListItem> assignmentsFor(AuthorityEvidence authority, int limit,
            String cursor, OffsetDateTime now);

    AssignmentDetailRecord assignment(AuthorityEvidence authority, UUID assignmentId,
            boolean forUpdate, OffsetDateTime now);

    FeedbackSubmissionReceipt submit(AuthorityEvidence authority,
            AssignmentDetailRecord detail, ProtectedContent response,
            String idempotencyKey, String clientIntentDigest, OffsetDateTime now);

    RequestRecord request(AuthorityEvidence authority, UUID requestId, boolean forUpdate);

    TemplateAuthority templateForRequest(AuthorityEvidence authority, UUID requestId);

    Optional<ReleaseSnapshot> releaseSnapshot(AuthorityEvidence authority, UUID requestId);

    StoredResponse storedResponseForReplay(AuthorityEvidence authority, UUID receiptId,
            UUID assignmentId);

    List<StoredResponse> submittedResponses(AuthorityEvidence authority, UUID requestId);

    UUID createLifecycleReceipt(AuthorityEvidence authority, RequestRecord request,
            String action, String idempotencyKey, String requestDigest, OffsetDateTime now);

    void transitionRequest(AuthorityEvidence authority, UUID requestId,
            RequestState expected, RequestState target, long expectedVersion,
            UUID lifecycleReceiptId, OffsetDateTime now);

    void cancelOutstandingAssignments(AuthorityEvidence authority, UUID requestId,
            OffsetDateTime now);

    void insertReleaseSnapshot(AuthorityEvidence authority, RequestRecord request,
            UUID snapshotId, UUID aggregationReceiptId, ProtectedContent content,
            String sourceSetDigest, String aggregationAlgorithmVersion,
            int submittedCount, OffsetDateTime now);

    record StoredResponse(
            UUID responseId,
            UUID assignmentId,
            String templateContentHash,
            ProtectedContent content,
            OffsetDateTime submittedAt) { }
}
