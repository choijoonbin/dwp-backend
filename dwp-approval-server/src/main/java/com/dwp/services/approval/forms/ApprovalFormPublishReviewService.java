package com.dwp.services.approval.forms;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory.Subject;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalFormPublishReviewService {
    private final ApprovalFormWorkspaceRepository forms;
    private final ApprovalFormLifecycleStore store;
    private final ApprovalFormPublishReviewRepository reviews;
    private final ApprovalFormLifecycleAuthority authority;
    private final ApprovalIdentityDirectory identities;
    private final AuditOutboxRecorder audit;

    public ApprovalFormPublishReviewService(ApprovalFormWorkspaceRepository forms,
            ApprovalFormLifecycleStore store, ApprovalFormPublishReviewRepository reviews,
            ApprovalFormLifecycleAuthority authority, ApprovalIdentityDirectory identities,
            AuditOutboxRecorder audit) {
        this.forms = forms;
        this.store = store;
        this.reviews = reviews;
        this.authority = authority;
        this.identities = identities;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PublishReviewCandidates candidates(String query, int size) {
        String leaf = "form-publish-review-candidates.data";
        var window = authority.require(leaf, "VIEW", "UPDATE");
        if (query == null || !query.equals(query.strip()) || query.length() < 2 || query.length() > 100
                || query.codePoints().anyMatch(Character::isISOControl) || size < 1 || size > 30) {
            throw invalid();
        }
        List<Subject> found = identities.search(window.actor().tenantId(), query, size);
        if (found == null || found.size() > size) throw unavailable();
        var users = new HashSet<Long>();
        var people = new HashSet<UUID>();
        var result = found.stream().filter(subject -> eligible(window.actor(), subject))
                .map(subject -> {
                    if (!users.add(subject.userId()) || !people.add(subject.personPublicId())) throw unavailable();
                    return new PublishReviewCandidate(subject.userId(), subject.personPublicId(),
                            text(subject.displayName(), 200), optional(subject.email(), 320),
                            optional(subject.jobTitle(), 200));
                }).toList();
        authority.unchanged(window, leaf, "VIEW", "UPDATE");
        return new PublishReviewCandidates(result, found.size() == size,
                window.evidence().revision(), window.evidence().validUntil());
    }

    @Transactional(readOnly = true)
    public PublishReviewQueue queue(int size) {
        String leaf = "form-publish-review-queue.data";
        var window = authority.require(leaf, "VIEW", "PUBLISH");
        if (size < 1 || size > 100) throw invalid();
        var result = reviews.assigned(window.actor(), size);
        authority.unchanged(window, leaf, "VIEW", "PUBLISH");
        return result;
    }

    @Transactional(readOnly = true)
    public PublishReviewRequest latest(UUID formId) {
        String leaf = "form-publish-review-request.data";
        var window = authority.require(leaf, "VIEW");
        var head = forms.head(window.actor(), formId, false);
        var result = reviews.latest(window.actor(), formId);
        forms.unchanged(window.actor(), head);
        authority.unchanged(window, leaf, "VIEW");
        return result;
    }

    @Transactional
    public PublishReviewRequest request(UUID formId, RequestPublishReview input,
            String idempotencyKey, String correlationId) {
        String leaf = "form-publish-review-request.action";
        var window = authority.require(leaf, "VIEW", "UPDATE");
        validateKey(idempotencyKey);
        validateReason(input.reason());
        String requestDigest = digest(formId, leaf, input);
        PublishReviewRequest replay = store.prior(window.actor(), formId,
                window.evidence().contextScopeKey(), leaf, idempotencyKey,
                requestDigest, PublishReviewRequest.class);
        if (replay != null) {
            authority.unchanged(window, leaf, "VIEW", "UPDATE");
            return replay;
        }
        var head = forms.head(window.actor(), formId, true);
        store.expected(head, input.expectedFormRevision(), input.expectedWorkspaceRevision());
        if (head.workspaceRevision() == null || head.draft() == null
                || !head.draft().equals(input.draftFormVersionId())
                || !java.util.Objects.equals(head.published(), input.basePublishedVersionId())
                || !window.actor().userId().equals(head.editor())) throw conflict();
        var draft = forms.version(window.actor(), formId, head.draft());
        String reviewDigest = ApprovalFormReviewMaterial.digest(forms, head, draft);
        if (!draft.schemaSha256().equals(input.schemaSha256())) throw conflict();
        PublishReviewRequest current = reviews.latest(window.actor(), formId, true);
        if ((current == null && (input.expectedReviewRequestId() != null
                    || input.expectedReviewRequestVersion() != null))
                || (current != null && (!current.reviewRequestId().equals(input.expectedReviewRequestId())
                    || !Long.valueOf(current.version()).equals(input.expectedReviewRequestVersion())))) {
            throw conflict();
        }
        Subject reviewer = requireReviewer(window.actor(), input.reviewerUserId(),
                input.reviewerPersonPublicId(), draft.createdBy(), head.editor());
        reviews.supersedePending(window.actor(), formId);
        UUID requestId = UUID.randomUUID();
        PublishReviewRequest result = reviews.insert(window.actor(), head, draft, requestId,
                reviewer.userId(), reviewer.personPublicId(), reviewDigest, input.reason());
        sameReviewer(reviewer, requireReviewer(window.actor(), input.reviewerUserId(),
                input.reviewerPersonPublicId(), draft.createdBy(), head.editor()));
        authority.unchanged(window, leaf, "VIEW", "UPDATE");
        record(window.actor(), "approval.form.publish-review.requested", requestId,
                formId, correlationId, Map.of("reviewerUserId", reviewer.userId(),
                        "draftFormVersionId", draft.formVersionId(),
                        "reviewContentDigest", reviewDigest));
        store.journal(window.actor(), formId, "REVIEW_REQUEST", draft.formVersionId(),
                Map.of("reviewRequestId", requestId, "reviewerUserId", reviewer.userId(),
                        "reviewContentDigest", reviewDigest));
        store.receipt(window.actor(), formId, window.evidence().contextScopeKey(), leaf,
                idempotencyKey, requestDigest, result);
        return result;
    }

    @Transactional
    public PublishReviewRequest reject(UUID formId, UUID requestId, RejectPublishReview input,
            String idempotencyKey, String correlationId) {
        String leaf = "form-publish-review-reject.action";
        var window = authority.require(leaf, "VIEW", "PUBLISH");
        validateKey(idempotencyKey);
        validateReason(input.reason());
        String requestDigest = digest(formId, leaf, Map.of("reviewRequestId", requestId, "input", input));
        PublishReviewRequest replay = store.prior(window.actor(), formId,
                window.evidence().contextScopeKey(), leaf, idempotencyKey,
                requestDigest, PublishReviewRequest.class);
        if (replay != null) {
            authority.unchanged(window, leaf, "VIEW", "PUBLISH");
            return replay;
        }
        var head = forms.head(window.actor(), formId, true);
        store.expected(head, input.expectedFormRevision(), input.expectedWorkspaceRevision());
        PublishReviewRequest pending = reviews.pending(window.actor(), formId, requestId, true);
        requireCurrent(window.actor(), head, pending, input.expectedReviewRequestVersion());
        PublishReviewRequest result = reviews.transition(window.actor(), pending, "REJECTED", input.reason());
        authority.unchanged(window, leaf, "VIEW", "PUBLISH");
        record(window.actor(), "approval.form.publish-review.rejected", requestId,
                formId, correlationId, Map.of("reason", input.reason(),
                        "draftFormVersionId", pending.draftFormVersionId()));
        store.journal(window.actor(), formId, "REVIEW_REJECT", pending.draftFormVersionId(),
                Map.of("reviewRequestId", requestId, "reason", input.reason()));
        store.receipt(window.actor(), formId, window.evidence().contextScopeKey(), leaf,
                idempotencyKey, requestDigest, result);
        return result;
    }

    void requireCurrent(Actor actor, ApprovalFormWorkspaceRepository.Head head,
            PublishReviewRequest request, long expectedVersion) {
        if (request.version() != expectedVersion || !actor.userId().equals(request.reviewerUserId())
                || !head.formId().equals(request.formId()) || head.workspaceRevision() == null
                || !head.workspaceRevision().equals(request.workspaceRevision())
                || head.revision() != request.formRevision()
                || !head.draft().equals(request.draftFormVersionId())
                || !java.util.Objects.equals(head.published(), request.basePublishedFormVersionId())) {
            throw conflict();
        }
        var draft = forms.version(actor, head.formId(), head.draft());
        if (!draft.schemaSha256().equals(request.schemaSha256())
                || !ApprovalFormReviewMaterial.digest(forms, head, draft)
                        .equals(request.reviewContentDigest())) throw conflict();
    }

    private Subject requireReviewer(Actor actor, long userId, UUID personId,
            Long maker, Long editor) {
        Subject reviewer = identities.require(actor.tenantId(), userId);
        if (!eligible(actor, reviewer) || !personId.equals(reviewer.personPublicId())
                || Long.valueOf(userId).equals(maker) || Long.valueOf(userId).equals(editor)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return reviewer;
    }

    private boolean eligible(Actor actor, Subject subject) {
        return subject != null && subject.active() && actor.tenantId().equals(subject.tenantId())
                && subject.userId() != null && subject.userId() > 0
                && !actor.userId().equals(subject.userId()) && subject.personPublicId() != null
                && subject.hasPermission("ADMIN.APPROVAL_DESIGN:PUBLISH");
    }

    private void sameReviewer(Subject expected, Subject actual) {
        if (!expected.equals(actual)) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT);
    }

    private String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw unavailable();
        return value;
    }

    private String optional(String value, int max) {
        if (value != null && (value.length() > max || value.codePoints().anyMatch(Character::isISOControl)))
            throw unavailable();
        return value;
    }

    private String digest(UUID formId, String leaf, Object body) {
        return forms.codec.sha(forms.codec.json(Map.of(
                "formId", formId.toString(), "route", leaf, "body", body)));
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) throw invalid();
    }

    private void record(Actor actor, String action, UUID requestId, UUID formId,
            String correlationId, Map<String, Object> state) {
        var after = new java.util.LinkedHashMap<String, Object>(state);
        after.put("formId", formId);
        audit.record(AuditEvent.builder().tenantId(actor.tenantId()).category("ADMIN_CHANGE")
                .action(action).outcome("SUCCESS").severity("INFO").actorType("USER")
                .actorId(actor.userId().toString()).actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-approval-server").sourceModule("approval-forms")
                .targetType("APPROVAL_FORM_REVIEW_REQUEST").targetId(requestId.toString())
                .correlationId(correlationId).afterState(after)
                .retentionClass("EXTENDED").build());
    }

    private void validateReason(String value) {
        if (value == null || !value.equals(value.strip()) || value.length() < 10 || value.length() > 1000
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
    }

    private BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE);
    }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    private BaseException conflict() {
        return ApprovalFormWorkspaceRepository.conflict();
    }
}
