package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.AuditRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.CommandRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.FeedbackRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.GovernanceRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.ProposalRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.RequestRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantSuggestionProvider.SuggestedItem;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantSuggestionProvider.SuggestionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;

@Component
class WorkplaceAssistantSupport {
    static final int DEFAULT_RETENTION_DAYS = 30;
    static final int DEFAULT_HOLD_TTL_SECONDS = 120;
    private static final int MAX_PROVIDER_LIST_ITEMS = 50;
    private static final int MAX_PROVIDER_ITEM_TEXT = 1000;
    private static final int MAX_PROVIDER_TOTAL_TEXT = 20_000;

    private final WorkplaceAssistantRepository repository;
    private final List<WorkplaceAssistantSuggestionProvider> suggestionProviders;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    WorkplaceAssistantSupport(
            WorkplaceAssistantRepository repository,
            List<WorkplaceAssistantSuggestionProvider> suggestionProviders,
            ObjectMapper mapper) {
        this(repository, suggestionProviders, mapper, Clock.systemUTC());
    }

    WorkplaceAssistantSupport(
            WorkplaceAssistantRepository repository,
            List<WorkplaceAssistantSuggestionProvider> suggestionProviders,
            ObjectMapper mapper,
            Clock clock) {
        this.repository = repository;
        this.suggestionProviders = List.copyOf(suggestionProviders);
        this.mapper = mapper;
        this.clock = clock;
    }

    AssistantRequest view(RequestRow row) {
        GovernanceRow governance = governanceRow(row.tenantId());
        AuthorityValidationSnapshot validation = row.validationSnapshot() == null
                ? null : value(row.validationSnapshot(), AuthorityValidationSnapshot.class);
        List<String> limitations = mapper.convertValue(row.limitations(), mapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
        return new AssistantRequest(
                row.requestId(), row.state(), row.redactedRequestText(), row.redactionState(),
                new ConsentSnapshot(row.requestProcessingConsent(), row.feedbackUseConsent(),
                        governance.tenantOptIn(), governance.feedbackUseEnabled()),
                row.providerReference(), row.modelVersion(), row.promptVersion(), row.toolVersion(),
                repository.proposals(row.tenantId(), row.requestId()).stream()
                        .map(this::proposal).toList(), validation, row.bookingBatchId(),
                row.bookingBatchId() == null ? null : batchHref(row.bookingBatchId()),
                row.requeryRequired(), row.lastResultCode(), limitations, row.version(),
                row.retentionExpiresAt(), row.createdAt(), row.updatedAt());
    }

    ProposalItem proposal(ProposalRow row) {
        return new ProposalItem(
                row.proposalItemId(), row.requestedItem(), row.rationale(),
                row.constraintsUsed(), row.exclusions(), row.policyResult(), row.conflicts(),
                row.alternatives(), row.authoritativeIntentItemId(),
                row.authoritativeIntentItemVersion(), row.selectedResourceId(),
                row.selectedResourceVersion(), row.selectedResourceName(), row.version());
    }

    FeedbackReceipt feedback(FeedbackRow row) {
        return new FeedbackReceipt(
                row.feedbackId(), row.requestId(), row.rating(), row.redactedComment(),
                row.eligibleForModelImprovement(), row.auditEventId(), row.createdAt());
    }

    AuditEvent audit(AuditRow row) {
        return new AuditEvent(
                row.auditEventId(), row.requestId(), row.eventType(), row.actorUserId(),
                row.metadata(), row.correlationId(), row.createdAt());
    }

    AssistantGovernance governance(GovernanceRow row) {
        return new AssistantGovernance(
                row.tenantOptIn(), row.killSwitch(), row.providerReference(), row.modelVersion(),
                row.promptVersion(), row.toolVersion(), row.retentionDays(),
                row.feedbackUseEnabled(), row.redactionState(), row.version(),
                row.updatedAt(), row.updatedBy());
    }

    GovernanceRow governanceRow(long tenantId) {
        return repository.governance(tenantId).orElse(new GovernanceRow(
                tenantId, false, true, null, null, null, null,
                DEFAULT_RETENTION_DAYS, false, GovernanceRedactionState.BLOCKED,
                0, null, null));
    }

    GovernanceRow requireEnabledGovernance(long tenantId) {
        GovernanceRow governance = governanceRow(tenantId);
        if (!governance.tenantOptIn()) {
            throw forbidden("Workplace Assistant is not enabled for this tenant.");
        }
        if (governance.killSwitch()) {
            throw forbidden("Workplace Assistant is disabled by the tenant kill switch.");
        }
        if (governance.redactionState() != GovernanceRedactionState.READY) {
            throw forbidden("Workplace Assistant redaction is not ready.");
        }
        if (isBlank(governance.providerReference()) || isBlank(governance.modelVersion())
                || isBlank(governance.promptVersion()) || isBlank(governance.toolVersion())) {
            throw forbidden("Workplace Assistant model, prompt and tool governance is incomplete.");
        }
        provider(governance.providerReference());
        return governance;
    }

    WorkplaceAssistantSuggestionProvider provider(String reference) {
        return suggestionProviders.stream().filter(candidate -> candidate.supports(reference))
                .findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "The governed Workplace Assistant provider is unavailable."));
    }

    ValidatedSuggestion validateSuggestion(
            List<RequestedBookingItem> requested,
            SuggestionResult result,
            WorkplaceAssistantRedactor redactor) {
        if (result == null || result.items() == null || result.limitations() == null) {
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The Assistant provider returned an incomplete suggestion.");
        }
        Set<String> requestedKeys = requested.stream()
                .map(RequestedBookingItem::clientItemKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, SuggestedItem> suggested = new LinkedHashMap<>();
        for (SuggestedItem item : result.items()) {
            if (item == null || isBlank(item.clientItemKey())
                    || item.constraintsUsed() == null || item.exclusions() == null
                    || !requestedKeys.contains(item.clientItemKey())) {
                throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "The Assistant provider returned an invalid suggestion set.");
            }
            SuggestedItem sanitized = new SuggestedItem(
                    item.clientItemKey(), providerText(item.rationale(), redactor),
                    providerTexts(item.constraintsUsed(), redactor),
                    providerTexts(item.exclusions(), redactor));
            if (suggested.putIfAbsent(item.clientItemKey(), sanitized) != null) {
                throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "The Assistant provider returned an invalid suggestion set.");
            }
        }
        if (!suggested.keySet().equals(requestedKeys)) {
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The Assistant provider did not preserve every requested booking item.");
        }
        List<String> limitations = providerTexts(result.limitations(), redactor);
        int totalText = suggested.values().stream().mapToInt(item ->
                item.rationale().length()
                        + item.constraintsUsed().stream().mapToInt(String::length).sum()
                        + item.exclusions().stream().mapToInt(String::length).sum()).sum()
                + limitations.stream().mapToInt(String::length).sum();
        if (totalText > MAX_PROVIDER_TOTAL_TEXT) {
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The Assistant provider output exceeded the governed storage budget.");
        }
        List<SuggestedItem> ordered = requested.stream()
                .map(item -> suggested.get(item.clientItemKey())).toList();
        return new ValidatedSuggestion(Map.copyOf(suggested), ordered, limitations);
    }

    private List<String> providerTexts(
            List<String> values, WorkplaceAssistantRedactor redactor) {
        if (values.size() > MAX_PROVIDER_LIST_ITEMS) {
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The Assistant provider returned too many output details.");
        }
        return values.stream().map(value -> providerText(value, redactor)).toList();
    }

    private String providerText(String value, WorkplaceAssistantRedactor redactor) {
        if (isBlank(value) || value.length() > MAX_PROVIDER_ITEM_TEXT
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The Assistant provider returned unsafe output text.");
        }
        String sanitized = redactor.redact(value.trim()).value();
        if (isBlank(sanitized) || sanitized.length() > MAX_PROVIDER_ITEM_TEXT
                || sanitized.chars().anyMatch(Character::isISOControl)) {
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The Assistant provider output could not be stored safely.");
        }
        return sanitized;
    }

    void validateRequestedItems(List<RequestedBookingItem> items) {
        Set<String> keys = new HashSet<>();
        for (RequestedBookingItem item : items) {
            if (!keys.add(item.clientItemKey())) {
                throw invalid("Assistant booking item keys must be unique.");
            }
            if (!item.endsAt().isAfter(item.startsAt())) {
                throw invalid("Each requested booking must end after it starts.");
            }
        }
    }

    void validateGovernance(GovernanceUpdateRequest request) {
        if (request.tenantOptIn() && !request.killSwitch()) {
            if (request.redactionState() != GovernanceRedactionState.READY
                    || isBlank(request.modelProviderReference())
                    || isBlank(request.modelVersion())
                    || isBlank(request.promptVersion())
                    || isBlank(request.toolVersion())) {
                throw invalid("Enabled Assistant governance requires a ready redactor and "
                        + "model, prompt and tool versions.");
            }
            provider(request.modelProviderReference().trim());
        }
    }

    List<ProposalRow> select(
            List<ProposalRow> proposals, SelectionMode mode, List<UUID> selectedIds) {
        if (mode == SelectionMode.ALL) {
            if (!selectedIds.isEmpty()) {
                throw invalid("ALL confirmation must not include selected proposal IDs.");
            }
            if (proposals.isEmpty()) throw invalid("There are no proposals to select.");
            return proposals;
        }
        if (selectedIds.isEmpty()) {
            throw invalid("SELECTED confirmation requires at least one proposal ID.");
        }
        Set<UUID> unique = new LinkedHashSet<>(selectedIds);
        if (unique.size() != selectedIds.size()) {
            throw invalid("A proposal item can be selected only once.");
        }
        Map<UUID, ProposalRow> byId = proposals.stream()
                .collect(Collectors.toMap(ProposalRow::proposalItemId, Function.identity()));
        return unique.stream().map(id -> {
            ProposalRow proposal = byId.get(id);
            if (proposal == null) throw notFound("A selected proposal item was not found.");
            return proposal;
        }).toList();
    }

    RequestRow requireRequest(long tenantId, long actorId, UUID requestId) {
        return repository.request(tenantId, actorId, requestId)
                .orElseThrow(() -> notFound("The Workplace Assistant request was not found."));
    }

    RequestRow requireRequestForUpdate(long tenantId, long actorId, UUID requestId) {
        return repository.requestForUpdate(tenantId, actorId, requestId)
                .orElseThrow(() -> notFound("The Workplace Assistant request was not found."));
    }

    void requireRetainedContent(RequestRow request, OffsetDateTime now) {
        if (request.retentionDeletedAt() != null
                || !request.retentionExpiresAt().isAfter(now)) {
            throw conflict("The Assistant request content retention period has expired.");
        }
    }

    CommandRow acceptedCommand(
            long tenantId, long actorId, UUID requestId, String commandType,
            String idempotencyKey, String fingerprint, String statusHref,
            String reason, String correlationId, OffsetDateTime now) {
        return new CommandRow(
                UUID.randomUUID(), tenantId, actorId, requestId, commandType,
                idempotencyKey, fingerprint, CommandState.ACCEPTED, statusHref,
                reason, correlationId, null, now, null);
    }

    CommandRow completedCommand(
            long tenantId, long actorId, UUID requestId, String commandType,
            String idempotencyKey, String fingerprint, String statusHref,
            String reason, String correlationId, OffsetDateTime now) {
        return new CommandRow(
                UUID.randomUUID(), tenantId, actorId, requestId, commandType,
                idempotencyKey, fingerprint, CommandState.SUCCEEDED, statusHref,
                reason, correlationId, "SUCCEEDED", now, now);
    }

    CommandReceipt receipt(CommandRow row, boolean replayed) {
        return receipt(row, replayed, row.statusHref(), row.acceptedAt(), row.correlationId());
    }

    CommandReceipt receipt(
            CommandRow row, boolean replayed, String fallbackHref,
            OffsetDateTime fallbackAcceptedAt, String fallbackCorrelation) {
        if (row == null) {
            return new CommandReceipt(UUID.randomUUID(), CommandState.SUCCEEDED,
                    fallbackHref, replayed, fallbackCorrelation, "SUCCEEDED",
                    fallbackAcceptedAt, fallbackAcceptedAt);
        }
        return new CommandReceipt(row.commandId(), row.state(), row.statusHref(),
                replayed, row.correlationId(), row.resultCode(),
                row.acceptedAt(), row.completedAt());
    }

    void requireFingerprint(String existing, String received) {
        if (!existing.equals(received)) {
            throw conflict("The idempotency key was already used for different input.");
        }
    }

    String fingerprint(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(mapper.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "The Assistant request fingerprint could not be generated.");
        }
    }

    String derivedKey(String scope, String key) {
        return "assistant-" + scope + "-" + fingerprint(key).substring(0, 48);
    }

    <T> T value(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Stored Workplace Assistant validation is unreadable.");
        }
    }

    OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    static void requireActor(long tenantId, long actorId) {
        requireTenant(tenantId);
        if (actorId <= 0) throw invalid("A positive actor ID is required.");
    }

    static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw invalid("A positive tenant ID is required.");
    }

    static String requireKey(String value) {
        if (!visibleAscii(value)) {
            throw invalid("A printable ASCII idempotency key up to 160 characters is required.");
        }
        return value;
    }

    static String correlation(String value) {
        if (value == null) return UUID.randomUUID().toString();
        if (!visibleAscii(value)) {
            throw invalid("A printable ASCII correlation ID up to 160 characters is required.");
        }
        return value;
    }

    private static boolean visibleAscii(String value) {
        return value != null && !value.isEmpty() && value.length() <= 160
                && StandardCharsets.US_ASCII.newEncoder().canEncode(value)
                && value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
    }

    static String localeValue(String value) {
        return value == null || value.isBlank() ? Locale.KOREAN.toLanguageTag() : value.trim();
    }

    static String requestHref(UUID requestId) {
        return "/v1/workplace/assistant/requests/" + requestId;
    }

    static String executionHref(UUID requestId) {
        return requestHref(requestId) + "/execution";
    }

    static String batchHref(UUID batchId) {
        return "/v1/workplace/booking-batches/" + batchId;
    }

    static String governanceHref() {
        return "/v1/admin/workplace/assistant/governance";
    }

    static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    record ValidatedSuggestion(
            Map<String, SuggestedItem> itemsByKey,
            List<SuggestedItem> orderedItems,
            List<String> limitations) { }
}
