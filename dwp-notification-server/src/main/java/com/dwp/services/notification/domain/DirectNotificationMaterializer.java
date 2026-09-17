package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationMaterializationRepository.PersistenceResult;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.RenderedContent;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContext;
import com.dwp.services.notification.domain.NotificationModels.MaterializationResult;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.dwp.services.notification.integration.ApprovalSlaNotificationPlan;
import com.dwp.services.notification.integration.ApprovalSlaRecipientAuthority;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DirectNotificationMaterializer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_.-]{0,79})\\s*}}");
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,79}");

    private final NotificationMaterializationTransactions transactions;
    private final NotificationProducerOwnershipPolicy ownershipPolicy;
    private final NotificationRecipientEntitlementAdmission entitlementAdmission;
    private final ObjectMapper objectMapper;

    public DirectNotificationMaterializer(
            NotificationMaterializationTransactions transactions,
            NotificationProducerOwnershipPolicy ownershipPolicy,
            NotificationRecipientEntitlementAdmission entitlementAdmission,
            ObjectMapper objectMapper) {
        this.transactions = transactions;
        this.ownershipPolicy = ownershipPolicy;
        this.entitlementAdmission = entitlementAdmission;
        this.objectMapper = objectMapper;
    }

    public MaterializationResult materialize(
            NotificationRequestContext.Actor actor,
            DirectMaterializationRequest request,
            String correlationId) {
        PreparedMaterialization prepared = prepare(request);
        DirectMaterializationRequest sanitizedRequest = prepared.materializationRequest();
        TemplateContract contract = transactions.contract(
                actor.tenantId(),
                sanitizedRequest.typeKey(),
                sanitizedRequest.sourceEventType(),
                sanitizedRequest.sourceSchemaVersion(),
                sanitizedRequest.locale());
        ownershipPolicy.requireOwnership(actor, contract);
        Set<Long> entitledRecipients = entitlementAdmission.admittedRecipients(
                actor.tenantId(),
                sanitizedRequest.recipientUserIds(),
                contract.ownerAppKey());
        String payloadHash = payloadHash(prepared.payloadRequest());
        Instant admittedAt = Instant.now();
        RenderedContent content = render(contract, sanitizedRequest.variables());
        PersistenceResult result = transactions.materialize(
                actor.tenantId(),
                sanitizedRequest,
                contract,
                content,
                payloadHash,
                correlationId,
                entitledRecipients,
                admittedAt);
        return result.result();
    }

    /** All children join the caller's journal transaction; only a verified current SLA profile admits them. */
    public List<MaterializationResult> materializeApprovalSlaWithinWorkerTransaction(
            NotificationRequestContext.Actor actor, ApprovalSlaNotificationPlan plan,
            List<DirectMaterializationRequest> requests, ApprovalSlaRecipientAuthority.Verified authority) {
        if (actor == null || plan == null || !actor.equals(plan.actor()) || authority == null
                || !actor.internal() || !"dwp-approval-server".equals(actor.sourceService()))
            throw new IllegalArgumentException("Current SLA producer authority is required.");
        authority.requireBatch(plan, requests);
        transactions.requireExistingWorkerTransaction(actor.tenantId());
        List<PreparedMaterialization> preparedRequests = requests.stream()
                .map(this::prepare)
                .toList();
        DirectMaterializationRequest first = preparedRequests.getFirst().materializationRequest();
        TemplateContract contract = transactions.contractWithinWorkerTransaction(actor.tenantId(),
                first.typeKey(), first.sourceEventType(), first.sourceSchemaVersion(), first.locale());
        ownershipPolicy.requireOwnership(actor, contract);
        if (!"approvals".equals(contract.ownerAppKey()) || !first.typeKey().equals(contract.typeKey()))
            throw new IllegalArgumentException("SLA template ownership changed.");
        List<MaterializationResult> results = new ArrayList<>();
        for (PreparedMaterialization prepared : preparedRequests) {
            DirectMaterializationRequest request = prepared.materializationRequest();
            var result = transactions.materializeWithinWorkerTransaction(actor.tenantId(), request, contract,
                    render(contract, request.variables()), payloadHash(prepared.payloadRequest()), "",
                    Set.of(request.recipientUserIds().getFirst()), Instant.now());
            results.add(result.result());
        }
        TemplateContract latest = transactions.contractWithinWorkerTransaction(actor.tenantId(),
                first.typeKey(), first.sourceEventType(), first.sourceSchemaVersion(), first.locale());
        if (!contract.equals(latest)) throw new IllegalArgumentException("SLA rendering contract changed during materialization.");
        authority.requireCurrent(plan);
        transactions.requireExistingWorkerTransaction(actor.tenantId());
        return List.copyOf(results);
    }

    private RenderedContent render(TemplateContract contract, Map<String, Object> variables) {
        String title = renderText(contract.titleTemplate(), variables, 300);
        String preview = renderText(contract.previewTemplate(), variables, 600);
        String body = renderText(contract.bodyTemplate(), variables, 4000);
        Map<String, Object> action = renderMap(contract.actionTemplate(), variables);
        return new RenderedContent(title, preview, body, action);
    }

    private String renderText(String template, Map<String, Object> variables, int maximumLength) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            Object value = variables.get(matcher.group(1));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(
                    value == null ? "" : value.toString()));
        }
        matcher.appendTail(rendered);
        String plainText = rendered.toString()
                .replaceAll("<[^>]*>", "")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .trim();
        return plainText.length() <= maximumLength
                ? plainText
                : plainText.substring(0, maximumLength);
    }

    private Map<String, Object> renderMap(
            Map<String, Object> template,
            Map<String, Object> variables) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        template.forEach((key, value) -> {
            if (value instanceof String stringValue) {
                rendered.put(key, renderText(stringValue, variables, 500));
            } else if (value instanceof Number || value instanceof Boolean || value == null) {
                rendered.put(key, value);
            }
        });
        return Collections.unmodifiableMap(rendered);
    }

    private Map<String, Object> sanitize(Map<String, Object> input) {
        if (input.size() > 50) {
            throw new IllegalArgumentException("Notification variables exceed the limit.");
        }
        Map<String, Object> sanitized = new TreeMap<>();
        input.forEach((key, value) -> {
            if (key == null || !SAFE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Notification variable key is invalid.");
            }
            if (value == null || value instanceof Boolean || value instanceof Number) {
                sanitized.put(key, value);
                return;
            }
            if (value instanceof String stringValue && stringValue.length() <= 500) {
                sanitized.put(key, stringValue
                        .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                        .trim());
                return;
            }
            throw new IllegalArgumentException(
                    "Notification variables must be scalar safe values.");
        });
        return Collections.unmodifiableMap(sanitized);
    }

    private PreparedMaterialization prepare(DirectMaterializationRequest request) {
        Map<String, Object> variables = sanitize(request.variables());
        List<MaterializationContext> explicit =
                NotificationStructuredContexts.explicit(request.contexts());
        DirectMaterializationRequest payloadRequest = request(
                request, explicit, variables);
        List<MaterializationContext> materialized =
                NotificationStructuredContexts.withLegacy(payloadRequest, explicit);
        return new PreparedMaterialization(
                payloadRequest,
                request(payloadRequest, materialized, variables));
    }

    private DirectMaterializationRequest request(
            DirectMaterializationRequest source,
            List<MaterializationContext> contexts,
            Map<String, Object> variables) {
        return new DirectMaterializationRequest(
                source.sourceEventId(),
                source.sourceEventType().trim(),
                source.sourceSchemaVersion(),
                source.typeKey().trim(),
                source.recipientUserIds().stream().distinct().toList(),
                trimmed(source.threadKey()),
                normalizedLocale(source.locale()),
                canonicalReasonCode(source.reasonCode()),
                trimmed(source.actorReference()),
                trimmed(source.subjectReference()),
                trimmed(source.targetReference()),
                source.occurredAt(),
                source.dueAt(),
                source.actionRequired(),
                contexts,
                variables);
    }

    private String payloadHash(DirectMaterializationRequest request) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("sourceEventId", request.sourceEventId().toString());
        canonical.put("sourceEventType", request.sourceEventType());
        canonical.put("sourceSchemaVersion", request.sourceSchemaVersion());
        canonical.put("typeKey", request.typeKey());
        List<Long> recipients = new ArrayList<>(request.recipientUserIds());
        recipients.sort(Long::compareTo);
        canonical.put("recipients", recipients);
        canonical.put("threadKey", request.threadKey());
        canonical.put("locale", request.locale());
        canonical.put("reasonCode", request.reasonCode());
        canonical.put("actorReference", request.actorReference());
        canonical.put("subjectReference", request.subjectReference());
        canonical.put("targetReference", request.targetReference());
        canonical.put("occurredAt", request.occurredAt());
        canonical.put("dueAt", request.dueAt());
        canonical.put("actionRequired", request.actionRequired());
        if (!request.contexts().isEmpty()) {
            canonical.put("contexts", request.contexts());
        }
        canonical.put("variables", request.variables());
        try {
            byte[] payload = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash notification event.", exception);
        }
    }

    private String normalizedLocale(String value) {
        return value == null || value.isBlank() ? "ko-KR" : value.trim();
    }

    static String canonicalReasonCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MENTION", "MENTIONED" -> "MENTION";
            case "ROLE" -> "ROLE";
            case "ORGANIZATION", "ORG" -> "ORGANIZATION";
            case "SUBSCRIPTION", "SUBSCRIBED" -> "SUBSCRIPTION";
            case "MANDATORY_POLICY", "MANDATORY" -> "MANDATORY_POLICY";
            case "DIRECT", "DIRECT_RECIPIENT" -> "DIRECT";
            default -> "DIRECT";
        };
    }

    private String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record PreparedMaterialization(
            DirectMaterializationRequest payloadRequest,
            DirectMaterializationRequest materializationRequest) {
    }
}
