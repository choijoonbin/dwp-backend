package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.CommandRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.FeedbackRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.GovernanceRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.RequestRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;

@Service
public class WorkplaceAssistantGovernanceService {
    private final WorkplaceAssistantRepository repository;
    private final WorkplaceAssistantAuditRepository auditRepository;
    private final WorkplaceAssistantRedactor redactor;
    private final ObjectMapper mapper;
    private final WorkplaceAssistantSupport support;

    WorkplaceAssistantGovernanceService(
            WorkplaceAssistantRepository repository,
            WorkplaceAssistantAuditRepository auditRepository,
            WorkplaceAssistantRedactor redactor,
            ObjectMapper mapper,
            WorkplaceAssistantSupport support) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.redactor = redactor;
        this.mapper = mapper;
        this.support = support;
    }

    @Transactional
    public FeedbackReceipt feedback(
            long tenantId,
            long actorId,
            UUID requestId,
            String idempotencyKey,
            String correlationId,
            FeedbackRequest request) {
        WorkplaceAssistantSupport.requireActor(tenantId, actorId);
        String key = WorkplaceAssistantSupport.requireKey(idempotencyKey);
        String correlation = WorkplaceAssistantSupport.correlation(correlationId);
        if (request.allowModelImprovementUse() && !request.explicitConfirmation()) {
            throw WorkplaceAssistantSupport.invalid(
                    "Feedback reuse requires explicit confirmation.");
        }
        WorkplaceAssistantRedactor.RedactedText comment = request.comment() == null
                ? new WorkplaceAssistantRedactor.RedactedText(null, RedactionState.NOT_REQUIRED)
                : redactor.redact(request.comment());
        String redactedReason = redactor.redact(request.reason()).value();
        String fingerprint = support.fingerprint(Map.of(
                "requestId", requestId,
                "expectedVersion", request.expectedVersion(),
                "rating", request.rating(),
                "comment", comment.value() == null ? "" : comment.value(),
                "allowModelImprovementUse", request.allowModelImprovementUse(),
                "reason", redactedReason));
        auditRepository.lockCommand(tenantId, actorId, "SUBMIT_FEEDBACK", key);
        CommandRow replay = auditRepository.command(
                tenantId, actorId, "SUBMIT_FEEDBACK", key).orElse(null);
        RequestRow current = support.requireRequestForUpdate(tenantId, actorId, requestId);
        OffsetDateTime now = support.now();
        if (current.retentionDeletedAt() != null
                || !current.retentionExpiresAt().isAfter(now)) {
            throw WorkplaceAssistantSupport.conflict(
                    "Feedback cannot be submitted after Assistant content retention expires.");
        }
        FeedbackRow existing = auditRepository.feedback(tenantId, requestId, actorId).orElse(null);
        if (replay != null) {
            support.requireFingerprint(replay.requestFingerprint(), fingerprint);
            if (existing == null) {
                throw WorkplaceAssistantSupport.conflict(
                        "The feedback receipt no longer resolves.");
            }
            return support.feedback(existing);
        }
        if (current.version() != request.expectedVersion()) {
            throw WorkplaceAssistantSupport.versionConflict(
                    "The Assistant request changed. Refresh before feedback.");
        }
        if (existing != null) {
            throw WorkplaceAssistantSupport.conflict(
                    "Feedback was already submitted for this Assistant request.");
        }
        GovernanceRow governance = support.governanceRow(tenantId);
        boolean eligible = eligibleForModelImprovement(governance, current, request);
        UUID feedbackId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        auditRepository.auditAndOutbox(tenantId, actorId, requestId,
                "WorkplaceAssistantFeedbackSubmitted", mapper.valueToTree(Map.of(
                        "requestId", requestId,
                        "feedbackId", feedbackId,
                        "rating", request.rating().name(),
                        "eligibleForModelImprovementUse", eligible,
                        "commentRedactionState", comment.state().name())),
                correlation, current.version(), now, auditId);
        FeedbackRow row = new FeedbackRow(feedbackId, tenantId, requestId, actorId,
                request.rating(), comment.value(), eligible, auditId, now);
        auditRepository.createFeedback(row);
        auditRepository.createCommand(support.completedCommand(
                tenantId, actorId, requestId, "SUBMIT_FEEDBACK", key, fingerprint,
                WorkplaceAssistantSupport.requestHref(requestId),
                redactedReason, correlation, now));
        return support.feedback(row);
    }

    static boolean eligibleForModelImprovement(
            GovernanceRow governance, RequestRow current, FeedbackRequest request) {
        return governance.tenantOptIn()
                && !governance.killSwitch()
                && governance.redactionState() == GovernanceRedactionState.READY
                && governance.feedbackUseEnabled()
                && current.requestProcessingConsent()
                && current.feedbackUseConsent()
                && request.allowModelImprovementUse()
                && request.explicitConfirmation();
    }

    @Transactional(readOnly = true)
    public AssistantGovernance governance(long tenantId) {
        WorkplaceAssistantSupport.requireTenant(tenantId);
        return support.governance(support.governanceRow(tenantId));
    }

    @Transactional
    public GovernanceCommandResult updateGovernance(
            long tenantId,
            long actorId,
            String idempotencyKey,
            String correlationId,
            GovernanceUpdateRequest request) {
        WorkplaceAssistantSupport.requireActor(tenantId, actorId);
        if (!request.explicitConfirmation()) {
            throw WorkplaceAssistantSupport.invalid(
                    "Assistant governance changes require explicit confirmation.");
        }
        String key = WorkplaceAssistantSupport.requireKey(idempotencyKey);
        String correlation = WorkplaceAssistantSupport.correlation(correlationId);
        String redactedReason = redactor.redact(request.reason()).value();
        String fingerprint = support.fingerprint(Map.ofEntries(
                Map.entry("tenantOptIn", request.tenantOptIn()),
                Map.entry("killSwitch", request.killSwitch()),
                Map.entry("modelProviderReference",
                        WorkplaceAssistantSupport.nullSafe(request.modelProviderReference())),
                Map.entry("modelVersion",
                        WorkplaceAssistantSupport.nullSafe(request.modelVersion())),
                Map.entry("promptVersion",
                        WorkplaceAssistantSupport.nullSafe(request.promptVersion())),
                Map.entry("toolVersion",
                        WorkplaceAssistantSupport.nullSafe(request.toolVersion())),
                Map.entry("retentionDays", request.retentionDays()),
                Map.entry("feedbackUseEnabled", request.feedbackUseEnabled()),
                Map.entry("redactionState", request.redactionState()),
                Map.entry("expectedVersion", request.expectedVersion()),
                Map.entry("reason", redactedReason)));
        auditRepository.lockCommand(tenantId, actorId, "UPDATE_GOVERNANCE", key);
        CommandRow replay = auditRepository.command(
                tenantId, actorId, "UPDATE_GOVERNANCE", key).orElse(null);
        if (replay != null) {
            support.requireFingerprint(replay.requestFingerprint(), fingerprint);
            return new GovernanceCommandResult(governance(tenantId),
                    support.receipt(replay, true));
        }
        support.validateGovernance(request);
        boolean changed = request.expectedVersion() == 0
                ? repository.createGovernance(tenantId, actorId, request, support.now())
                : repository.updateGovernance(tenantId, actorId, request, support.now());
        if (!changed) {
            throw WorkplaceAssistantSupport.versionConflict(
                    "Assistant governance changed. Refresh before updating.");
        }
        AssistantGovernance updated = governance(tenantId);
        OffsetDateTime now = support.now();
        auditRepository.auditAndOutbox(tenantId, actorId, null,
                "WorkplaceAssistantGovernanceUpdated", mapper.valueToTree(Map.of(
                        "tenantOptIn", updated.tenantOptIn(),
                        "killSwitch", updated.killSwitch(),
                        "providerReference", WorkplaceAssistantSupport.nullSafe(
                                updated.modelProviderReference()),
                        "modelVersion", WorkplaceAssistantSupport.nullSafe(
                                updated.modelVersion()),
                        "promptVersion", WorkplaceAssistantSupport.nullSafe(
                                updated.promptVersion()),
                        "toolVersion", WorkplaceAssistantSupport.nullSafe(
                                updated.toolVersion()),
                        "retentionDays", updated.retentionDays(),
                        "feedbackUseEnabled", updated.feedbackUseEnabled(),
                        "redactionState", updated.redactionState().name())),
                correlation, updated.version(), now, UUID.randomUUID());
        CommandRow command = support.completedCommand(
                tenantId, actorId, null, "UPDATE_GOVERNANCE", key, fingerprint,
                WorkplaceAssistantSupport.governanceHref(), redactedReason, correlation, now);
        auditRepository.createCommand(command);
        return new GovernanceCommandResult(updated, support.receipt(command, false));
    }

    @Transactional(readOnly = true)
    public AuditEvents auditEvents(long tenantId, UUID requestId, Integer limit) {
        WorkplaceAssistantSupport.requireTenant(tenantId);
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        List<AuditEvent> events = auditRepository.auditEvents(
                tenantId, requestId, safeLimit).stream().map(support::audit).toList();
        return new AuditEvents(events, support.now());
    }
}
