package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(
        name = "dwp.gateway.product-surface-rollout.invalidation-enabled",
        havingValue = "true")
public class FeatureRolloutInvalidationConsumer {

    public static final String TOPIC = "dwp.feature-rollout.decision.changed.v1";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(FeatureRolloutInvalidationConsumer.class);
    private static final Set<String> EVENT_FIELDS = Set.of(
            "eventId",
            "tenantScope",
            "tenantId",
            "flagKey",
            "opaqueRevision",
            "state",
            "occurredAt");
    private static final Pattern FLAG_KEY = Pattern.compile(
            "^(access|ux)\\.product-surfaces\\.[a-z0-9.-]+\\.v1$");
    private static final Set<String> STATES = Set.of(
            "ENABLED", "DISABLED", "ADVANCED", "PAUSED", "RESUMED", "ROLLED_BACK");

    private final FeatureRolloutDecisionCache cache;
    private final ObjectMapper objectMapper;

    public FeatureRolloutInvalidationConsumer(
            FeatureRolloutDecisionCache cache,
            ObjectMapper objectMapper) {
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${dwp.gateway.product-surface-rollout.invalidation-topic:"
                    + TOPIC + "}",
            groupId = "${dwp.gateway.product-surface-rollout.invalidation-group-id:"
                    + "dwp-gateway-product-surface-rollout}")
    public void onMessage(String payload) {
        try {
            consume(parseStrict(payload));
        } catch (RuntimeException exception) {
            LOGGER.warn("Ignored malformed product-surface rollout invalidation: {}",
                    exception.getClass().getSimpleName());
        }
    }

    /** Returns false when the delivery is a duplicate or older revision. */
    public boolean consume(DecisionChangedEvent event) {
        validate(event);
        long revision = FeatureRolloutDecisionCache.revisionNumber(event.opaqueRevision());
        if ("ALL".equals(event.tenantScope())) {
            return cache.invalidateAll(event.flagKey(), revision, event.occurredAt());
        }
        return cache.invalidateTenant(
                event.tenantId(), event.flagKey(), revision, event.occurredAt());
    }

    private DecisionChangedEvent parseStrict(String payload) {
        try {
            JsonNode root = objectMapper.reader()
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .readTree(payload);
            if (root == null || !root.isObject()) throw invalid();
            Set<String> fields = root.propertyStream()
                    .map(entry -> entry.getKey())
                    .collect(Collectors.toUnmodifiableSet());
            if (!EVENT_FIELDS.equals(fields)) throw invalid();
            JsonNode tenant = root.get("tenantId");
            Long tenantId = tenant.isNull() ? null : positiveLong(tenant);
            return new DecisionChangedEvent(
                    uuid(text(root, "eventId")),
                    text(root, "tenantScope"),
                    tenantId,
                    text(root, "flagKey"),
                    text(root, "opaqueRevision"),
                    text(root, "state"),
                    Instant.parse(text(root, "occurredAt")));
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private void validate(DecisionChangedEvent event) {
        if (event == null || event.eventId() == null
                || !FLAG_KEY.matcher(event.flagKey() == null ? "" : event.flagKey()).matches()
                || !STATES.contains(event.state())
                || event.occurredAt() == null) {
            throw invalid();
        }
        FeatureRolloutDecisionCache.revisionNumber(event.opaqueRevision());
        if ("ALL".equals(event.tenantScope())) {
            if (event.tenantId() != null) throw invalid();
            return;
        }
        if (!"EXACT".equals(event.tenantScope())
                || event.tenantId() == null || event.tenantId() <= 0) {
            throw invalid();
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private Long positiveLong(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw invalid();
        }
        return value.longValue();
    }

    private UUID uuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw invalid();
        return parsed;
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid feature rollout invalidation event");
    }

    public record DecisionChangedEvent(
            UUID eventId,
            String tenantScope,
            Long tenantId,
            String flagKey,
            String opaqueRevision,
            String state,
            Instant occurredAt) {
    }
}
