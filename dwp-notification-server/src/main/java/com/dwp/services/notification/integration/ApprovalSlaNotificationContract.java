package com.dwp.services.notification.integration;

import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

final class ApprovalSlaNotificationContract {
    static final Set<String> EVENT_TYPES = Set.of("Approval.Quorum.SlaWarning", "Approval.Quorum.SlaBreached");
    private static final Set<String> ENVELOPE = Set.of("specVersion", "eventType", "tenantId", "requestId", "correlationId", "payload");
    private static final Set<String> PAYLOAD = Set.of("eventContract", "requestTitle", "occurredAt", "requestVersion",
            "managementResourceSetKey", "stepId", "stageKey", "stageVersion", "generation", "workflowVersionId",
            "workflowVersion", "workflowDefinitionSha256", "formVersionId", "formSchemaSha256", "payloadRevision",
            "payloadSha256", "policyVersion", "policySha256", "timerId", "leaseEpoch", "recipientUserIds",
            "recipientSnapshotSha256", "recipientSeats", "authorityRevision");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ApprovalSlaNotificationContract() { }

    static ApprovalSlaNotificationPlan translate(ConsumerRecord<String, String> record) {
        String eventHeader = header(record, "dwp-event-id");
        UUID eventId = uuid(eventHeader);
        String type = header(record, "dwp-event-type");
        String tenantHeader = header(record, "dwp-tenant-id");
        if (record.value() == null || record.value().getBytes(StandardCharsets.UTF_8).length > 300000)
            throw invalid("SLA envelope exceeds its UTF-8 bound.");
        JsonNode root;
        try { root = JSON.readTree(record.value()); }
        catch (JsonProcessingException exception) { throw invalid("Malformed SLA JSON."); }
        exactKeys(root, ENVELOPE);
        if (!"1.0".equals(text(root, "specVersion", 20)) || !EVENT_TYPES.contains(type)
                || !type.equals(text(root, "eventType", 200))) throw invalid("SLA event type binding mismatch.");
        long tenant = number(root, "tenantId", 1);
        if (!Long.toString(tenant).equals(tenantHeader)) throw invalid("SLA tenant binding mismatch.");
        UUID requestId = uuid(text(root, "requestId", 36));
        if (!requestId.toString().equals(record.key())) throw invalid("SLA Kafka request key mismatch.");
        if (!"".equals(text(root, "correlationId", 160, true))) throw invalid("SLA correlation contract mismatch.");
        JsonNode payload = root.get("payload");
        exactKeys(payload, PAYLOAD);
        if (!"DWP_APPROVAL_QUORUM_SLA_EVENT_V1".equals(text(payload, "eventContract", 80))) throw invalid("Unknown SLA contract.");
        Map<String, Object> pins = new LinkedHashMap<>();
        for (String key : List.of("requestTitle", "managementResourceSetKey", "stageKey", "authorityRevision")) {
            pins.put(key, text(payload, key, "requestTitle".equals(key) ? 300 : 200));
        }
        for (String key : List.of("stepId", "workflowVersionId", "formVersionId", "timerId")) {
            pins.put(key, uuid(text(payload, key, 36)).toString());
        }
        for (String key : List.of("workflowDefinitionSha256", "formSchemaSha256", "payloadSha256", "policySha256")) {
            pins.put(key, digest(payload, key));
        }
        pins.put("requestVersion", number(payload, "requestVersion", 0));
        pins.put("stageVersion", number(payload, "stageVersion", 0));
        for (String key : List.of("generation", "workflowVersion", "payloadRevision", "policyVersion", "leaseEpoch")) {
            pins.put(key, number(payload, key, 1));
        }
        Instant occurredAt;
        String occurred = text(payload, "occurredAt", 40);
        try { occurredAt = Instant.parse(occurred); }
        catch (RuntimeException exception) { throw invalid("Invalid SLA occurrence time."); }
        if (!occurredAt.toString().equals(occurred)) throw invalid("Noncanonical SLA occurrence time.");
        JsonNode userIds = payload.get("recipientUserIds"), seats = payload.get("recipientSeats");
        if (userIds == null || !userIds.isArray() || userIds.isEmpty() || userIds.size() > 1000
                || seats == null || !seats.isArray() || seats.size() != userIds.size()) throw invalid("Incomplete bounded SLA audience.");
        List<ApprovalSlaNotificationPlan.Recipient> recipients = new ArrayList<>();
        Set<UUID> taskIds = new java.util.HashSet<>(), personIds = new java.util.HashSet<>();
        long previous = 0;
        for (int index = 0; index < userIds.size(); index++) {
            JsonNode id = userIds.get(index), seat = seats.get(index);
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= previous) throw invalid("SLA audience must be strictly sorted and unique.");
            exactKeys(seat, Set.of("userId", "personPublicId", "taskId", "taskVersion"));
            long userId = number(seat, "userId", 1);
            if (userId != id.longValue()) throw invalid("SLA seat/user binding mismatch.");
            UUID person = uuid(text(seat, "personPublicId", 36)), task = uuid(text(seat, "taskId", 36));
            if (!taskIds.add(task) || !personIds.add(person)) throw invalid("SLA opaque seats must be unique.");
            recipients.add(new ApprovalSlaNotificationPlan.Recipient(userId, person, task, number(seat, "taskVersion", 0)));
            previous = userId;
        }
        String audienceHash = digest(payload, "recipientSnapshotSha256");
        String canonicalAudience = canonical(Map.of("recipientUserIds", canonicalValue(userIds), "recipientSeats", canonicalValue(seats)));
        if (!audienceHash.equals(sha256(canonicalAudience))) throw invalid("SLA audience hash mismatch.");
        String canonicalEnvelope = canonical(canonicalValue(root));
        return new ApprovalSlaNotificationPlan(eventId,
                new NotificationRequestContext.Actor(tenant, null, Set.of(), Set.of(), true, "dwp-approval-server"),
                type, canonicalEnvelope, sha256(canonicalEnvelope), sha256(record.value()),
                audienceHash, sha256(canonical(pins)), requestId, occurredAt, pins, recipients);
    }

    static String header(ConsumerRecord<String, String> record, String name) {
        var headers = record.headers().headers(name).iterator();
        if (!headers.hasNext()) throw invalid("Missing SLA header.");
        byte[] bytes = headers.next().value();
        if (headers.hasNext() || bytes == null) throw invalid("Duplicate/empty SLA header.");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)) || text.isBlank()
                || !text.equals(text.trim()) || text.length() > 200 || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid("Noncanonical SLA header.");
        return text;
    }

    private static void exactKeys(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) throw invalid("SLA object required.");
        Set<String> actual = new java.util.HashSet<>(); value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid("SLA closed object fields mismatch.");
    }
    private static String text(JsonNode parent, String field, int limit) { return text(parent, field, limit, false); }
    private static String text(JsonNode parent, String field, int limit, boolean emptyAllowed) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) throw invalid("SLA text required: " + field);
        String text = value.textValue();
        if ((!emptyAllowed && text.isBlank()) || !text.equals(text.trim()) || text.length() > limit
                || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid("Invalid SLA text: " + field);
        return text;
    }
    private static long number(JsonNode parent, String field, long minimum) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < minimum || value.longValue() > 9007199254740991L) throw invalid("Invalid SLA integer: " + field);
        return value.longValue();
    }
    private static UUID uuid(String text) {
        try { UUID uuid = UUID.fromString(text); if (uuid.toString().equals(text)) return uuid; }
        catch (IllegalArgumentException ignored) { }
        throw invalid("Noncanonical SLA UUID.");
    }
    private static String digest(JsonNode value, String key) {
        String digest = text(value, key, 64);
        if (!digest.matches("[a-f0-9]{64}")) throw invalid("Invalid SLA digest.");
        return digest;
    }
    private static Object canonicalValue(JsonNode value) {
        if (value.isObject()) {
            Map<String, Object> map = new TreeMap<>(); value.properties().forEach(e -> map.put(e.getKey(), canonicalValue(e.getValue()))); return map;
        }
        if (value.isArray()) { List<Object> list = new ArrayList<>(); value.forEach(item -> list.add(canonicalValue(item))); return list; }
        if (value.isTextual()) return value.textValue();
        if (value.isIntegralNumber() && value.canConvertToLong()) return value.longValue();
        throw invalid("Unsupported SLA canonical value.");
    }
    static String canonical(Object value) {
        try { return JSON.writeValueAsString(value instanceof Map<?, ?> map ? new TreeMap<>(map) : value); }
        catch (JsonProcessingException exception) { throw invalid("Cannot canonicalize SLA event."); }
    }
    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    static ApprovalNotificationEventException invalid(String message) {
        return new ApprovalNotificationEventException(ApprovalNotificationEventException.Classification.PAYLOAD_CONTRACT_VIOLATION, message);
    }
}
