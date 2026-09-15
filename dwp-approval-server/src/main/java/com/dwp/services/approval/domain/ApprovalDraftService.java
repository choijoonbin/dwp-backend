package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class ApprovalDraftService {
    private static final String READ = "ACTION.APPROVAL_REQUEST:VIEW";
    private static final String UPDATE = "ACTION.APPROVAL_REQUEST:UPDATE";
    private final ApprovalDraftRepository repository;
    private final ApprovalWorkAuthority authority;
    private final ApprovalService approvals;
    private final ApprovalCommandRepository commands;
    private final AuditOutboxRecorder audit;

    public ApprovalDraftService(ApprovalDraftRepository repository, ApprovalWorkAuthority authority,
                                ApprovalService approvals, ApprovalCommandRepository commands, AuditOutboxRecorder audit) {
        this.repository = repository;
        this.authority = authority;
        this.approvals = approvals;
        this.commands = commands;
        this.audit = audit;
    }

    @Transactional
    public ApprovalDtos.RequestSummary create(ApprovalDtos.CreateRequest body, String key, String correlationId) {
        validateKey(key);
        var actor = authority.requireCurrent("ACTION.APPROVAL_REQUEST:CREATE");
        var receipt = repository.begin(actor, "POST /v1/requests", "CREATE", null, key, body, 0, null, correlationId);
        if (receipt.replay()) {
            repository.lock(actor, receipt.requestId());
            authority.requireCurrent("ACTION.APPROVAL_REQUEST:CREATE");
            return repository.read(receipt.result(), ApprovalDtos.RequestSummary.class);
        }
        var response = approvals.create(body, correlationId);
        authority.requireCurrent("ACTION.APPROVAL_REQUEST:CREATE");
        repository.complete(receipt, response, repository.lock(actor, response.requestId()));
        return response;
    }

    @Transactional
    public ApprovalDtos.RequestDetail update(UUID id, ApprovalDtos.UpdateDraftRequest body,
                                             String key, String correlationId) {
        validateKey(key);
        var actor = authority.requireCurrent(UPDATE);
        var state = repository.lock(actor, id);
        authority.requireCurrent(UPDATE);
        var receipt = repository.begin(actor, "PUT /v1/requests/" + id + "/draft", "UPDATE", id,
                key, body, body.expectedVersion(), null, correlationId);
        if (receipt.replay()) return repository.read(receipt.result(), ApprovalDtos.RequestDetail.class);
        requireMutable(actor, id, state, body.expectedVersion(), false);
        var response = approvals.updateDraft(id, body, correlationId);
        authority.requireCurrent(UPDATE);
        repository.complete(receipt, response, repository.state(actor, id, false));
        return response;
    }

    @Transactional
    public ApprovalResubmitDraftDtos.Response resubmit(
            UUID sourceRequestId,
            ApprovalResubmitDraftDtos.Request body,
            String key,
            String correlationId) {
        if (sourceRequestId == null || body == null || body.expectedVersion() == null
                || body.expectedVersion() < 0
                || body.expectedVersion() > 9_007_199_254_740_991L) {
            throw input();
        }
        validateKey(key);
        var actor = authority.require(READ, true);
        var source = repository.lockResubmitSource(actor, sourceRequestId, body.expectedVersion());
        authority.requireCurrent("ACTION.APPROVAL_REQUEST:CREATE");
        String route = "POST /v1/requests/" + sourceRequestId + "/resubmit-draft";
        var receipt = repository.begin(actor, route, "CREATE", sourceRequestId, key, body,
                body.expectedVersion(), null, correlationId);
        if (receipt.replay()) {
            authority.require(READ, true);
            authority.requireCurrent("ACTION.APPROVAL_REQUEST:CREATE");
            return repository.read(receipt.result(), ApprovalResubmitDraftDtos.Response.class);
        }
        final UUID draftId;
        try {
            draftId = commands.createResubmitDraft(actor, source, correlationId);
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.INVALID_INPUT_VALUE) {
                throw new IncompatibleSourcePayload(exception);
            }
            throw exception;
        }
        authority.require(READ, true);
        authority.requireCurrent("ACTION.APPROVAL_REQUEST:CREATE");
        ApprovalDtos.RequestSummary draft = approvals.request(draftId);
        var response = new ApprovalResubmitDraftDtos.Response(draft, sourceRequestId, source.version());
        var state = repository.state(actor, draftId, false);
        recordResubmission(actor, source, state, correlationId);
        repository.complete(receipt, response, state);
        return response;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApprovalWorkDtos.Page<ApprovalWorkDtos.DraftRevision> revisions(UUID id, int page, int size) {
        validatePage(page, size);
        var actor = authority.require(READ, true);
        repository.state(actor, id, false);
        var result = repository.revisions(actor, id, page, size);
        authority.require(READ, true);
        return result;
    }

    @Transactional(readOnly = true)
    public ApprovalWorkDtos.DraftRevisionDetail revision(UUID id, int revision) {
        if (revision < 1) throw input();
        var actor = authority.require(READ, true);
        repository.state(actor, id, false);
        var result = repository.revision(actor, id, revision);
        authority.require(READ, true);
        return result;
    }

    @Transactional
    public ApprovalWorkDtos.DraftState recover(UUID id, ApprovalWorkDtos.RecoverDraft body, String correlationId) {
        if (body.expectedVersion() == null || body.revision() == null || body.revision() < 1) throw input();
        validateKey(body.idempotencyKey());
        var actor = authority.require(UPDATE, true);
        var state = repository.lock(actor, id);
        authority.require(UPDATE, true);
        var receipt = repository.begin(actor, "POST /v1/requests/" + id + "/draft/recover", "RECOVER", id,
                body.idempotencyKey(), body, body.expectedVersion(), body.revision(), correlationId);
        if (receipt.replay()) return repository.read(receipt.result(), ApprovalWorkDtos.DraftState.class);
        requireMutable(actor, id, state, body.expectedVersion(), false);
        var history = repository.revision(actor, id, body.revision());
        if (!history.revision().recoverable()) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                "This legacy revision has no provable draft metadata snapshot.");
        Map<String, Object> snapshot = history.draftSnapshot();
        var update = new ApprovalDtos.UpdateDraftRequest(UUID.fromString((String) snapshot.get("workflowId")),
                UUID.fromString((String) snapshot.get("formId")), (String) snapshot.get("title"),
                (String) snapshot.get("summary"), (String) snapshot.get("priority"), history.payload(), body.expectedVersion());
        // Existing draft validation resolves current ACTIVE assets, never reopens decisions.
        var attachmentPin = commands.attachmentLifecycleBinding().prepareRecovery(id, body.expectedVersion(), body.revision());
        commands.updateDraft(actor, id, update, correlationId, attachmentPin);
        authority.require(UPDATE, true);
        var result = repository.state(actor, id, false);
        repository.event(actor, id, "REQUEST_DRAFT_RECOVERED", body.reason(), correlationId,
                Map.of("sourceRevision", body.revision(), "payloadRevision", result.payloadRevision(), "version", result.version()));
        record(actor, id, "approval.request.draft.recovered", result, correlationId);
        repository.complete(receipt, result, result);
        return result;
    }

    @Transactional
    public ApprovalWorkDtos.DraftState delete(UUID id, ApprovalWorkDtos.DraftCommand body, String correlationId) {
        return changeDeletion(id, body, correlationId, true);
    }

    @Transactional
    public ApprovalWorkDtos.DraftState restore(UUID id, ApprovalWorkDtos.DraftCommand body, String correlationId) {
        return changeDeletion(id, body, correlationId, false);
    }

    @Transactional(readOnly = true)
    public ApprovalWorkDtos.DraftReconciliation reconcile(String key) {
        validateKey(key);
        if (ApprovalDecisionRevisionContext.current().isPresent()
                && !ApprovalPilotAuthorizationContext.requiresPredicate("predicate.approval.own-draft-receipt.v1")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The exact owned draft receipt route is required.");
        }
        var actor = authority.requireCurrent(READ);
        var result = repository.reconcile(actor, key);
        authority.requireCurrent(READ);
        return result;
    }

    private ApprovalWorkDtos.DraftState changeDeletion(UUID id, ApprovalWorkDtos.DraftCommand body,
                                                       String correlationId, boolean deleted) {
        if (body.expectedVersion() == null) throw input();
        validateKey(body.idempotencyKey());
        String type = deleted ? "DELETE" : "RESTORE";
        var actor = authority.require(UPDATE, true);
        var state = repository.lock(actor, id);
        authority.require(UPDATE, true);
        var receipt = repository.begin(actor, "POST /v1/requests/" + id + "/draft/" + type.toLowerCase(java.util.Locale.ROOT),
                type, id, body.idempotencyKey(), body, body.expectedVersion(), null, correlationId);
        if (receipt.replay()) return repository.read(receipt.result(), ApprovalWorkDtos.DraftState.class);
        requireMutable(actor, id, state, body.expectedVersion(), !deleted);
        repository.setDeleted(actor, id, body.expectedVersion(), deleted, body.reason());
        authority.require(UPDATE, true);
        var result = repository.state(actor, id, false);
        repository.event(actor, id, deleted ? "REQUEST_DRAFT_DELETED" : "REQUEST_DRAFT_RESTORED", body.reason(), correlationId,
                Map.of("version", result.version(), "payloadRevision", result.payloadRevision()));
        record(actor, id, deleted ? "approval.request.draft.deleted" : "approval.request.draft.restored", result, correlationId);
        repository.complete(receipt, result, result);
        return result;
    }

    private void requireMutable(ApprovalRequestContext.Actor actor, UUID id, ApprovalWorkDtos.DraftState state,
                                 long version, boolean deleted) {
        if (state.version() != version || !repository.isDraft(actor, id)
                || (state.deletedAt() != null) != deleted) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                "Draft version, lifecycle, or deletion state changed.");
    }

    private void record(ApprovalRequestContext.Actor actor, UUID id, String action,
                         ApprovalWorkDtos.DraftState state, String correlationId) {
        audit.record(AuditEvent.builder().tenantId(actor.tenantId()).category("SYSTEM_EVENT")
                .action(action).outcome("SUCCESS").severity("INFO").actorType("USER")
                .actorId(actor.userId().toString()).actorRoles(java.util.List.copyOf(actor.roles()))
                .sourceService("dwp-approval-server").sourceModule("approval-draft-lifecycle")
                .targetType("APPROVAL_REQUEST").targetId(id.toString()).approvalId(id.toString())
                .correlationId(correlationId).retentionClass("EXTENDED")
                .afterState(Map.of("version", state.version(), "payloadRevision", state.payloadRevision()))
                .build());
    }

    private void recordResubmission(
            ApprovalRequestContext.Actor actor,
            ApprovalDraftRepository.ResubmitSource source,
            ApprovalWorkDtos.DraftState draft,
            String correlationId) {
        audit.record(AuditEvent.builder().tenantId(actor.tenantId()).category("SYSTEM_EVENT")
                .action("approval.request.resubmit-draft.created").outcome("SUCCESS").severity("INFO")
                .actorType("USER").actorId(actor.userId().toString())
                .actorRoles(java.util.List.copyOf(actor.roles()))
                .sourceService("dwp-approval-server").sourceModule("approval-resubmit-draft")
                .targetType("APPROVAL_REQUEST").targetId(draft.requestId().toString())
                .approvalId(draft.requestId().toString()).correlationId(correlationId)
                .retentionClass("EXTENDED")
                .afterState(Map.of(
                        "sourceRequestId", source.requestId().toString(),
                        "sourceVersion", source.version(),
                        "sourceStatus", source.status(),
                        "draftVersion", draft.version(),
                        "payloadRevision", draft.payloadRevision()))
                .build());
    }

    public static final class IncompatibleSourcePayload extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IncompatibleSourcePayload(Throwable cause) {
            super("The source approval payload is incompatible with the current published form schema.", cause);
        }
    }

    public static void validatePage(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) throw input();
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,120}")) throw input();
    }

    private static BaseException input() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid draft command or pagination."); }
}
