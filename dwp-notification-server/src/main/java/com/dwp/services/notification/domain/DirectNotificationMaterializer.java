package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationMaterializationRepository.PersistenceResult;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.RenderedContent;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.TemplateContract;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationResult;
import com.dwp.services.notification.operations.NotificationRetentionService;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DirectNotificationMaterializer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_.-]{0,79})\\s*}}");
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,79}");

    private final NotificationDatabaseScope databaseScope;
    private final NotificationMaterializationRepository repository;
    private final NotificationProducerOwnershipPolicy ownershipPolicy;
    private final NotificationRetentionService retentionService;
    private final NotificationChangePublisher changePublisher;
    private final ObjectMapper objectMapper;

    public DirectNotificationMaterializer(
            NotificationDatabaseScope databaseScope,
            NotificationMaterializationRepository repository,
            NotificationProducerOwnershipPolicy ownershipPolicy,
            NotificationRetentionService retentionService,
            NotificationChangePublisher changePublisher,
            ObjectMapper objectMapper) {
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.ownershipPolicy = ownershipPolicy;
        this.retentionService = retentionService;
        this.changePublisher = changePublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MaterializationResult materialize(
            NotificationRequestContext.Actor actor,
            DirectMaterializationRequest request,
            String correlationId) {
        databaseScope.applyWorker(actor.tenantId());
        Map<String, Object> variables = sanitize(request.variables());
        DirectMaterializationRequest sanitizedRequest = new DirectMaterializationRequest(
                request.sourceEventId(),
                request.sourceEventType().trim(),
                request.sourceSchemaVersion(),
                request.typeKey().trim(),
                request.recipientUserIds().stream().distinct().toList(),
                trimmed(request.threadKey()),
                normalizedLocale(request.locale()),
                trimmed(request.reasonCode()),
                trimmed(request.actorReference()),
                trimmed(request.subjectReference()),
                trimmed(request.targetReference()),
                request.occurredAt(),
                request.dueAt(),
                request.actionRequired(),
                variables);
        TemplateContract contract = repository.contract(
                actor.tenantId(),
                sanitizedRequest.typeKey(),
                sanitizedRequest.sourceEventType(),
                sanitizedRequest.sourceSchemaVersion(),
                sanitizedRequest.locale());
        ownershipPolicy.requireOwnership(actor, contract);
        String payloadHash = payloadHash(sanitizedRequest);
        java.time.Instant admittedAt = java.time.Instant.now();
        RenderedContent content = render(contract, variables);
        PersistenceResult result = repository.materialize(
                actor.tenantId(),
                sanitizedRequest,
                contract,
                content,
                payloadHash,
                correlationId);
        if (!result.result().duplicate()) {
            retentionService.applyDefaultExpiry(
                    actor.tenantId(), result.result().notificationId(), admittedAt);
        }
        changePublisher.publishAfterCommit(result.signals());
        return result.result();
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

    private String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
