package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.CommandRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.GovernanceRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.ProposalRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.RequestRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantSuggestionProvider.SuggestedItem;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantSuggestionProvider.SuggestionContext;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantSuggestionProvider.SuggestionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;

@Service
public class WorkplaceAssistantRequestService {
    private final WorkplaceAssistantRepository repository;
    private final WorkplaceAssistantAuditRepository auditRepository;
    private final WorkplaceAssistantRedactor redactor;
    private final ObjectMapper mapper;
    private final WorkplaceAssistantSupport support;

    WorkplaceAssistantRequestService(
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
    public AssistantCommandResult create(
            long tenantId,
            long actorId,
            UUID actorPersonPublicId,
            String actorDisplayName,
            String verifiedGroupRefs,
            String locale,
            String idempotencyKey,
            String correlationId,
            CreateAssistantRequest request) {
        WorkplaceAssistantSupport.requireActor(tenantId, actorId);
        if (!request.requestProcessingConsent()) {
            throw WorkplaceAssistantSupport.invalid(
                    "Workplace Assistant request processing requires explicit consent.");
        }
        support.validateRequestedItems(request.requestedItems());
        String key = WorkplaceAssistantSupport.requireKey(idempotencyKey);
        String correlation = WorkplaceAssistantSupport.correlation(correlationId);
        WorkplaceAssistantRedactor.RedactedText text = redactor.redact(request.requestText());
        String redactedReason = redactor.redact(request.reason()).value();
        String fingerprint = support.fingerprint(Map.of(
                "redactedRequestText", text.value(),
                "requestedItems", request.requestedItems(),
                "requestProcessingConsent", request.requestProcessingConsent(),
                "feedbackUseConsent", request.feedbackUseConsent(),
                "reason", redactedReason));
        auditRepository.lockCommand(tenantId, actorId, "CREATE_REQUEST", key);
        RequestRow existing = repository.requestByIdempotency(tenantId, actorId, key).orElse(null);
        if (existing != null) {
            support.requireFingerprint(existing.requestFingerprint(), fingerprint);
            CommandRow command = auditRepository.command(
                    tenantId, actorId, "CREATE_REQUEST", key).orElse(null);
            return new AssistantCommandResult(support.view(existing), support.receipt(command, true,
                    WorkplaceAssistantSupport.requestHref(existing.requestId()),
                    existing.createdAt(), correlation));
        }

        GovernanceRow governance = support.requireEnabledGovernance(tenantId);
        WorkplaceAssistantSuggestionProvider provider = support.provider(
                governance.providerReference());
        OffsetDateTime now = support.now();
        UUID requestId = UUID.randomUUID();
        CommandRow accepted = support.acceptedCommand(tenantId, actorId, null,
                "CREATE_REQUEST", key, fingerprint,
                WorkplaceAssistantSupport.requestHref(requestId),
                redactedReason, correlation, now);
        auditRepository.createCommand(accepted);
        SuggestionResult suggestion = provider.suggest(new SuggestionContext(
                tenantId, actorId, text.value(), request.requestedItems(),
                governance.modelVersion(), governance.promptVersion(), governance.toolVersion(),
                WorkplaceAssistantSupport.localeValue(locale)));
        WorkplaceAssistantSupport.ValidatedSuggestion validatedSuggestion =
                support.validateSuggestion(request.requestedItems(), suggestion, redactor);
        Map<String, SuggestedItem> suggestedByKey = validatedSuggestion.itemsByKey();
        JsonNode structuredProposal = mapper.valueToTree(validatedSuggestion.orderedItems());
        JsonNode limitations = mapper.valueToTree(validatedSuggestion.limitations());
        RequestRow row = new RequestRow(
                requestId, tenantId, actorId, RequestState.SUGGESTED, text.value(), text.state(),
                true, request.feedbackUseConsent(), governance.providerReference(),
                governance.modelVersion(), governance.promptVersion(), governance.toolVersion(),
                structuredProposal, null, null, null, false, null, limitations,
                key, fingerprint, correlation, 1,
                now.plusDays(governance.retentionDays()), null, now, now);
        repository.createRequest(row);
        int index = 0;
        for (RequestedBookingItem item : request.requestedItems()) {
            SuggestedItem suggested = suggestedByKey.get(item.clientItemKey());
            repository.createProposal(new ProposalRow(
                    UUID.randomUUID(), tenantId, requestId, index++, item.clientItemKey(), item,
                    suggested.rationale(), suggested.constraintsUsed(), suggested.exclusions(),
                    PolicyResult.UNVALIDATED, List.of(), List.of(), null, null,
                    null, null, null, 1, now, now));
        }
        auditRepository.auditAndOutbox(tenantId, actorId, requestId,
                "WorkplaceAssistantSuggestionCreated", mapper.valueToTree(Map.of(
                        "requestId", requestId,
                        "proposalCount", request.requestedItems().size(),
                        "providerReference", governance.providerReference(),
                        "modelVersion", governance.modelVersion(),
                        "promptVersion", governance.promptVersion(),
                        "toolVersion", governance.toolVersion(),
                        "redactionState", text.state().name())),
                correlation, 1, now, UUID.randomUUID());
        auditRepository.attachCommandRequest(tenantId, accepted.commandId(), requestId);
        auditRepository.finishCommand(tenantId, accepted.commandId(), CommandState.SUCCEEDED,
                "SUCCEEDED", now);
        CommandRow command = auditRepository.command(
                tenantId, actorId, "CREATE_REQUEST", key).orElse(accepted);
        return new AssistantCommandResult(
                request(tenantId, actorId, requestId), support.receipt(command, false));
    }

    @Transactional(readOnly = true)
    public AssistantRequest request(long tenantId, long actorId, UUID requestId) {
        WorkplaceAssistantSupport.requireActor(tenantId, actorId);
        return support.view(support.requireRequest(tenantId, actorId, requestId));
    }
}
