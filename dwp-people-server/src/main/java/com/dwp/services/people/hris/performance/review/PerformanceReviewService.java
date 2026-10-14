package com.dwp.services.people.hris.performance.review;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.AssignedReview;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.DraftCommand;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewAnswer;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewForm;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewSubmission;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.SubmitCommand;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class PerformanceReviewService {

    public static final String GET_OPERATION = "getAssignedReview";
    public static final String SAVE_OPERATION = "saveReviewDraft";
    public static final String SUBMIT_OPERATION = "submitReview";
    static final String SAVE_ACTION = "hris.action.per.save-review-draft";
    static final String SUBMIT_ACTION = "hris.action.per.submit-review";

    private final PerformanceReviewRepository repository;
    private final PerformanceReviewAuthorityPort authority;
    private final PerformanceReviewCanonicalizer canonicalizer;
    private final Clock clock;

    @Autowired
    public PerformanceReviewService(
            PerformanceReviewRepository repository,
            PerformanceReviewAuthorityPort authority,
            PerformanceReviewCanonicalizer canonicalizer) {
        this(repository, authority, canonicalizer, Clock.systemUTC());
    }

    PerformanceReviewService(
            PerformanceReviewRepository repository,
            PerformanceReviewAuthorityPort authority,
            PerformanceReviewCanonicalizer canonicalizer,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.authority = Objects.requireNonNull(authority);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public VersionedReview review(
            UUID reviewId,
            String purposeCode,
            String populationKind) {
        PerformanceReviewAuthorityPort.AuthorityEvidence decision = authorize(
                reviewId, GET_OPERATION, purposeCode, populationKind, null);
        repository.bindTenant(decision.tenantId());
        AssignedReview projection = repository.assigned(decision, false)
                .map(PerformanceReviewRepository.ReviewRow::view)
                .map(this::canonicalProjection)
                .orElseThrow(this::opaqueNotFound);
        return versioned(projection);
    }

    @Transactional
    public VersionedReview saveDraft(
            UUID reviewId,
            long expectedVersion,
            String idempotencyKey,
            String expectedDecisionRevision,
            String purposeCode,
            String populationKind,
            DraftCommand request) {
        requireCommandInput(
                reviewId, expectedVersion, idempotencyKey, expectedDecisionRevision, request);
        if (request.answers().isEmpty() || request.answers().size() > 500) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A review draft command must contain between one and 500 answers.");
        }
        PerformanceReviewAuthorityPort.AuthorityEvidence decision = authorize(
                reviewId, SAVE_OPERATION, purposeCode, populationKind,
                expectedDecisionRevision);
        repository.bindTenant(decision.tenantId());
        AssignedReview current = repository.assigned(decision, true)
                .map(PerformanceReviewRepository.ReviewRow::view)
                .map(this::canonicalProjection)
                .orElseThrow(this::opaqueNotFound);
        List<ReviewAnswer> answers = canonicalizer.canonicalAnswers(
                current.form(), request.answers(), false);
        DraftCommand canonicalRequest = new DraftCommand(answers);
        String requestHash = canonicalizer.commandHash(
                SAVE_OPERATION, reviewId, expectedVersion, canonicalRequest);
        var existing = repository.receiptByIdempotency(
                decision, SAVE_ACTION, idempotencyKey);
        if (existing.isPresent()) {
            return replayReview(decision, existing.orElseThrow(), expectedVersion, requestHash);
        }
        requireVersion(current, expectedVersion);
        requireWritable(current);

        UUID receiptId = UUID.randomUUID();
        boolean reserved = repository.reserveReceipt(
                decision, receiptId, idempotencyKey, "SAVE_REVIEW_DRAFT",
                SAVE_ACTION, expectedVersion, requestHash);
        if (!reserved) {
            PerformanceReviewRepository.ReceiptRow raced = repository.receiptByIdempotency(
                    decision, SAVE_ACTION, idempotencyKey).orElseThrow(this::retryConflict);
            return replayReview(decision, raced, expectedVersion, requestHash);
        }
        String contentHash = draftContentHash(current, answers);
        AssignedReview updated = canonicalProjection(repository.updateDraft(
                decision, expectedVersion, answers, contentHash));
        VersionedReview result = versioned(updated);
        repository.storeResult(
                decision, receiptId, SAVE_OPERATION, requestHash,
                result.review(), result.etag());
        repository.completeReceipt(
                decision, receiptId, reviewId, updated.aggregateVersion());
        return result;
    }

    @Transactional
    public ReviewSubmission submit(
            UUID reviewId,
            long expectedVersion,
            String idempotencyKey,
            String expectedDecisionRevision,
            String purposeCode,
            String populationKind,
            SubmitCommand request) {
        requireCommandInput(
                reviewId, expectedVersion, idempotencyKey, expectedDecisionRevision, request);
        if (request.draftContentHash() == null
                || !request.draftContentHash().matches(PerformanceReviewDtos.HASH_PATTERN)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The submitted draft hash is invalid.");
        }
        PerformanceReviewAuthorityPort.AuthorityEvidence decision = authorize(
                reviewId, SUBMIT_OPERATION, purposeCode, populationKind,
                expectedDecisionRevision);
        repository.bindTenant(decision.tenantId());
        PerformanceReviewRepository.ReviewRow row = repository.assigned(decision, true)
                .orElseThrow(this::opaqueNotFound);
        AssignedReview current = canonicalProjection(row.view());
        SubmitCommand canonicalRequest = new SubmitCommand(
                request.draftContentHash(), request.acknowledgement());
        String requestHash = canonicalizer.commandHash(
                SUBMIT_OPERATION, reviewId, expectedVersion, canonicalRequest);
        var existing = repository.receiptByIdempotency(
                decision, SUBMIT_ACTION, idempotencyKey);
        if (existing.isPresent()) {
            return replaySubmission(
                    decision, existing.orElseThrow(), expectedVersion, requestHash);
        }
        requireVersion(current, expectedVersion);
        requireWritable(current);
        if (!current.draftContentHash().equals(request.draftContentHash())) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The submitted draft hash no longer matches the assigned review.");
        }
        List<ReviewAnswer> complete;
        try {
            complete = canonicalizer.canonicalAnswers(
                    current.form(), current.answers(), true);
        } catch (BaseException exception) {
            if (exception.getErrorCode() != ErrorCode.INVALID_INPUT_VALUE) throw exception;
            throw new PerformanceReviewProblemException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_RULE_VIOLATION",
                    "The assigned review is incomplete or violates its typed form.",
                    current.aggregateVersion(), null, false);
        }
        String contentHash = draftContentHash(current, complete);
        if (!contentHash.equals(current.draftContentHash())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The persisted review draft does not match its content hash.");
        }

        UUID receiptId = UUID.randomUUID();
        boolean reserved = repository.reserveReceipt(
                decision, receiptId, idempotencyKey, "SUBMIT_REVIEW",
                SUBMIT_ACTION, expectedVersion, requestHash);
        if (!reserved) {
            PerformanceReviewRepository.ReceiptRow raced = repository.receiptByIdempotency(
                    decision, SUBMIT_ACTION, idempotencyKey).orElseThrow(this::retryConflict);
            return replaySubmission(decision, raced, expectedVersion, requestHash);
        }
        UUID submissionId = UUID.randomUUID();
        Instant now = clock.instant();
        PerformanceReviewRepository.ReviewRow canonicalRow = new PerformanceReviewRepository.ReviewRow(
                copy(current, current.form(), complete, current.draftContentHash()),
                row.reviewerPrincipalPublicId(), row.organizationPublicId());
        ReviewSubmission result = repository.submit(
                decision, canonicalRow, receiptId, submissionId, contentHash, now);
        repository.storeResult(
                decision, receiptId, SUBMIT_OPERATION, requestHash, result, null);
        repository.completeReceipt(
                decision, receiptId, result.submissionId(), current.aggregateVersion() + 1);
        return result;
    }

    private VersionedReview replayReview(
            PerformanceReviewAuthorityPort.AuthorityEvidence decision,
            PerformanceReviewRepository.ReceiptRow receipt,
            long expectedVersion,
            String requestHash) {
        validateReplay(decision, receipt, "SAVE_REVIEW_DRAFT",
                SAVE_ACTION, expectedVersion, requestHash);
        PerformanceReviewRepository.StoredResult stored = repository.storedResult(
                decision, receipt.receiptId(), SAVE_OPERATION, requestHash)
                .orElseThrow(this::retryConflict);
        AssignedReview review = repository.assignedResult(stored);
        return new VersionedReview(review, stored.etag());
    }

    private ReviewSubmission replaySubmission(
            PerformanceReviewAuthorityPort.AuthorityEvidence decision,
            PerformanceReviewRepository.ReceiptRow receipt,
            long expectedVersion,
            String requestHash) {
        validateReplay(decision, receipt, "SUBMIT_REVIEW",
                SUBMIT_ACTION, expectedVersion, requestHash);
        PerformanceReviewRepository.StoredResult stored = repository.storedResult(
                decision, receipt.receiptId(), SUBMIT_OPERATION, requestHash)
                .orElseThrow(this::retryConflict);
        return repository.submissionResult(stored);
    }

    private void validateReplay(
            PerformanceReviewAuthorityPort.AuthorityEvidence decision,
            PerformanceReviewRepository.ReceiptRow receipt,
            String commandType,
            String action,
            long expectedVersion,
            String requestHash) {
        if (!decision.reviewId().equals(receipt.aggregateId())
                || !commandType.equals(receipt.commandType())
                || !action.equals(receipt.action())
                || expectedVersion != receipt.expectedVersion()
                || !requestHash.equals(receipt.requestHash())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key is already bound to another review command.");
        }
        if (decision.actorId() != receipt.actorId()
                || !Objects.equals(decision.populationScopeDigest(),
                receipt.populationScopeDigest())
                || decision.fieldPolicyRevision() != receipt.fieldPolicyRevision()
                || !Objects.equals(decision.purposeCode(), receipt.purposeCode())
                || decision.authorizationRevision() != receipt.authorizationRevision()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Current authority no longer matches the review command receipt.");
        }
        if (!"SUCCEEDED".equals(receipt.state())
                || receipt.appliedVersion() == null || receipt.resultRef() == null) {
            throw retryConflict();
        }
    }

    private PerformanceReviewAuthorityPort.AuthorityEvidence authorize(
            UUID reviewId,
            String operation,
            String purpose,
            String populationKind,
            String expectedDecisionRevision) {
        PeopleRequestContext.Actor actor;
        try {
            actor = PeopleRequestContext.require();
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Assigned-review request context is unavailable.");
        }
        if (actor.tenantId() == null || actor.userId() == null
                || actor.personPublicId() == null) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Assigned-review request context is incomplete.");
        }
        return authority.authorize(new PerformanceReviewAuthorityPort.AuthorityRequest(
                actor.tenantId(), actor.userId(), actor.personPublicId(), reviewId,
                operation, purpose, populationKind, expectedDecisionRevision));
    }

    private AssignedReview canonicalProjection(AssignedReview review) {
        ReviewForm form = canonicalizer.canonicalForm(review.form());
        List<ReviewAnswer> answers = canonicalizer.canonicalAnswers(
                form, review.answers(), false);
        String hash = draftContentHash(review, answers);
        if (!hash.equals(review.draftContentHash())) {
            throw new IllegalStateException(
                    "Persisted assigned-review draft hash is not canonical.");
        }
        return copy(review, form, answers, hash);
    }

    private AssignedReview copy(
            AssignedReview review,
            ReviewForm form,
            List<ReviewAnswer> answers,
            String contentHash) {
        return new AssignedReview(
                review.reviewId(), review.participantId(), review.reviewerAssignmentId(),
                review.reviewerType(), review.stageKey(), review.state(),
                review.templateVersionId(), review.templateContentHash(),
                review.aggregateVersion(), review.writable(), contentHash,
                form, answers, review.dueAt());
    }

    private String draftContentHash(
            AssignedReview review,
            List<ReviewAnswer> canonicalAnswers) {
        return canonicalizer.draftContentHash(
                review.reviewId(), review.reviewerAssignmentId(),
                review.templateVersionId(), review.templateContentHash(),
                canonicalAnswers);
    }

    private VersionedReview versioned(AssignedReview review) {
        return new VersionedReview(
                review, PerformanceReviewEtags.format(
                review.reviewId(), review.aggregateVersion()));
    }

    private void requireVersion(AssignedReview review, long expectedVersion) {
        if (expectedVersion != review.aggregateVersion()) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The assigned review changed after it was loaded.");
        }
    }

    private void requireWritable(AssignedReview review) {
        if (!review.writable()
                || !SetHolder.WRITABLE_STATES.contains(review.state())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The assigned review is no longer writable.");
        }
    }

    private void requireCommandInput(
            UUID reviewId,
            long expectedVersion,
            String idempotencyKey,
            String expectedDecisionRevision,
            Object request) {
        if (reviewId == null || expectedVersion < 0 || request == null
                || idempotencyKey == null
                || idempotencyKey.length() < 16 || idempotencyKey.length() > 128
                || expectedDecisionRevision == null
                || expectedDecisionRevision.isEmpty()
                || expectedDecisionRevision.length() > 200) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Assigned-review command headers or body are invalid.");
        }
    }

    private BaseException opaqueNotFound() {
        return new BaseException(ErrorCode.NOT_FOUND, "The assigned review is not available.");
    }

    private BaseException retryConflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The prior assigned-review command has no stable completed result.");
    }

    public record VersionedReview(AssignedReview review, String etag) {
    }

    private static final class SetHolder {
        private static final java.util.Set<PerformanceReviewDtos.ReviewState> WRITABLE_STATES =
                java.util.Set.of(
                        PerformanceReviewDtos.ReviewState.NOT_STARTED,
                        PerformanceReviewDtos.ReviewState.IN_PROGRESS,
                        PerformanceReviewDtos.ReviewState.RETURNED);

        private SetHolder() {
        }
    }
}
