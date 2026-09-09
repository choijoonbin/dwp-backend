package com.dwp.services.notification.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.domain.NotificationModels.DraftDecisionRequest;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicy;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateRevision;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.notification.api.NotificationVersionCodec.positive;

@Service
public class NotificationDraftGovernanceService {

    private final NotificationDatabaseScope databaseScope;
    private final NotificationAdminRepository policyReader;
    private final NotificationPolicyDraftRepository policyDrafts;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationIdempotencyRepository idempotencyRepository;
    private final AuditOutboxRecorder audit;

    public NotificationDraftGovernanceService(
            NotificationDatabaseScope databaseScope,
            NotificationAdminRepository policyReader,
            NotificationPolicyDraftRepository policyDrafts,
            NotificationTemplateRepository templateRepository,
            NotificationIdempotencyRepository idempotencyRepository,
            AuditOutboxRecorder audit) {
        this.databaseScope = databaseScope;
        this.policyReader = policyReader;
        this.policyDrafts = policyDrafts;
        this.templateRepository = templateRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.audit = audit;
    }

    @Transactional
    public TenantPolicy withdrawPolicyDraft(
            NotificationRequestContext.Actor actor,
            UUID policyId,
            DraftDecisionRequest request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        Request receipt = begin(
                actor,
                idempotencyKey,
                "TENANT_NOTIFICATION_POLICY_DRAFT_WITHDRAW",
                policyId,
                request);
        TenantPolicy replay = idempotencyRepository.replay(receipt, TenantPolicy.class);
        if (replay != null) return replay;
        TenantPolicy draft = requirePolicyDraft(actor.tenantId(), policyId);
        requireAuthor(actor, draft.createdBy(), "Only the policy draft author can withdraw it.");
        long expected = positive(request.expectedVersion(), "expectedVersion");
        if (!policyDrafts.withdraw(actor.tenantId(), actor.userId(), policyId, expected)) {
            throw stale();
        }
        TenantPolicy result = policyReader.policy(actor.tenantId(), policyId).orElseThrow();
        recordPolicy(actor, "notification.policy.draft.withdrawn", "WITHDRAWN", result, request.reason());
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    @Transactional
    public TenantPolicy rejectPolicyDraft(
            NotificationRequestContext.Actor actor,
            UUID policyId,
            DraftDecisionRequest request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        Request receipt = begin(
                actor,
                idempotencyKey,
                "TENANT_NOTIFICATION_POLICY_DRAFT_REJECT",
                policyId,
                request);
        TenantPolicy replay = idempotencyRepository.replay(receipt, TenantPolicy.class);
        if (replay != null) return replay;
        TenantPolicy draft = requirePolicyDraft(actor.tenantId(), policyId);
        requireIndependentReviewer(
                actor,
                draft.createdBy(),
                "A notification policy author cannot reject the same version.");
        long expected = positive(request.expectedVersion(), "expectedVersion");
        if (!policyDrafts.reject(actor.tenantId(), actor.userId(), policyId, expected)) {
            throw stale();
        }
        TenantPolicy result = policyReader.policy(actor.tenantId(), policyId).orElseThrow();
        recordPolicy(actor, "notification.policy.draft.rejected", "REJECTED", result, request.reason());
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    @Transactional
    public TemplateRevision withdrawTemplateDraft(
            NotificationRequestContext.Actor actor,
            UUID revisionId,
            DraftDecisionRequest request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        Request receipt = begin(
                actor,
                idempotencyKey,
                "TENANT_NOTIFICATION_TEMPLATE_DRAFT_RETIRE",
                revisionId,
                request);
        TemplateRevision replay = idempotencyRepository.replay(receipt, TemplateRevision.class);
        if (replay != null) return replay;
        TemplateRevision draft = requireTemplateDraft(actor.tenantId(), revisionId);
        requireAuthor(actor, draft.createdBy(), "Only the template draft author can withdraw it.");
        int expected = Math.toIntExact(positive(request.expectedVersion(), "expectedVersion"));
        if (!templateRepository.retireDraft(
                actor.tenantId(), actor.userId(), revisionId, expected)) {
            throw stale();
        }
        TemplateRevision result = templateRepository.revision(
                actor.tenantId(), revisionId).orElseThrow();
        recordTemplate(
                actor,
                "notification.template.draft.withdrawn",
                "WITHDRAWN",
                result,
                request.reason());
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    @Transactional
    public TemplateRevision rejectTemplateDraft(
            NotificationRequestContext.Actor actor,
            UUID revisionId,
            DraftDecisionRequest request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        Request receipt = begin(
                actor,
                idempotencyKey,
                "TENANT_NOTIFICATION_TEMPLATE_DRAFT_REJECT",
                revisionId,
                request);
        TemplateRevision replay = idempotencyRepository.replay(receipt, TemplateRevision.class);
        if (replay != null) return replay;
        TemplateRevision draft = requireTemplateDraft(actor.tenantId(), revisionId);
        requireIndependentReviewer(
                actor,
                draft.createdBy(),
                "A notification template author cannot reject the same revision.");
        int expected = Math.toIntExact(positive(request.expectedVersion(), "expectedVersion"));
        if (!templateRepository.rejectDraft(
                actor.tenantId(), actor.userId(), revisionId, expected)) {
            throw stale();
        }
        TemplateRevision result = templateRepository.revision(
                actor.tenantId(), revisionId).orElseThrow();
        recordTemplate(
                actor,
                "notification.template.draft.rejected",
                "REJECTED",
                result,
                request.reason());
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    private Request begin(
            NotificationRequestContext.Actor actor,
            String idempotencyKey,
            String operation,
            UUID draftId,
            DraftDecisionRequest request) {
        return idempotencyRepository.begin(
                actor, idempotencyKey, operation, Map.of("draftId", draftId, "request", request));
    }

    private TenantPolicy requirePolicyDraft(long tenantId, UUID policyId) {
        TenantPolicy draft = policyReader.policy(tenantId, policyId)
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        if (!"DRAFT".equals(draft.state())) throw stale();
        return draft;
    }

    private TemplateRevision requireTemplateDraft(long tenantId, UUID revisionId) {
        TemplateRevision draft = templateRepository.revision(tenantId, revisionId)
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        if (!"DRAFT".equals(draft.state())) throw stale();
        return draft;
    }

    private void requireAuthor(
            NotificationRequestContext.Actor actor,
            Long authorId,
            String message) {
        if (authorId == null || !authorId.equals(actor.userId())) {
            throw new NotificationException(NotificationErrorCode.FORBIDDEN, message);
        }
    }

    private void requireIndependentReviewer(
            NotificationRequestContext.Actor actor,
            Long authorId,
            String message) {
        if (authorId == null || authorId.equals(actor.userId())) {
            throw new NotificationException(NotificationErrorCode.FORBIDDEN, message);
        }
    }

    private NotificationException stale() {
        return new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
    }

    private void recordPolicy(
            NotificationRequestContext.Actor actor,
            String action,
            String decision,
            TenantPolicy policy,
            String reason) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("MEDIUM")
                .riskScore("REJECTED".equals(decision) ? 45 : 30)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-notification-server")
                .sourceModule("notification-policy-governance")
                .targetType("NOTIFICATION_POLICY")
                .targetId(policy.policyId().toString())
                .targetDisplayName(policy.scopeType() + ":" + policy.scopeKey())
                .reason(reason.trim())
                .policyId(policy.policyId().toString())
                .policyDecision("REJECTED".equals(decision) ? "DENY" : "NOT_APPLICABLE")
                .afterState(Map.of(
                        "scopeType", policy.scopeType(),
                        "scopeKey", policy.scopeKey(),
                        "state", policy.state(),
                        "version", policy.version(),
                        "draftDecision", decision))
                .retentionClass("EXTENDED")
                .build());
    }

    private void recordTemplate(
            NotificationRequestContext.Actor actor,
            String action,
            String decision,
            TemplateRevision revision,
            String reason) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("MEDIUM")
                .riskScore("REJECTED".equals(decision) ? 45 : 30)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-notification-server")
                .sourceModule("notification-template-governance")
                .targetType("NOTIFICATION_TEMPLATE_REVISION")
                .targetId(revision.revisionId().toString())
                .targetDisplayName(revision.typeKey() + ":" + revision.locale())
                .reason(reason.trim())
                .policyDecision("REJECTED".equals(decision) ? "DENY" : "NOT_APPLICABLE")
                .afterState(Map.of(
                        "typeKey", revision.typeKey(),
                        "channel", revision.channel(),
                        "locale", revision.locale(),
                        "state", revision.state(),
                        "version", revision.version(),
                        "checksum", revision.checksum(),
                        "draftDecision", decision))
                .retentionClass("EXTENDED")
                .build());
    }
}
