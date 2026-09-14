package com.dwp.services.notification.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalSlaNotificationContractTest {
    static final long TENANT = 42;
    static final UUID REQUEST = opaque("request");
    static final String HASH = "a".repeat(64);
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @ParameterizedTest
    @ValueSource(strings = {"Approval.Quorum.SlaWarning", "Approval.Quorum.SlaBreached"})
    void bindsEachSupportedEventAndItsDirectChildRequest(String type) throws Exception {
        ObjectNode event = event(2);
        event.put("eventType", type);
        UUID parent = UUID.randomUUID();
        var plan = ApprovalSlaNotificationContract.translate(record(parent, encode(event), type));

        assertThat(plan.eventId()).isEqualTo(parent);
        assertThat(plan.actor().tenantId()).isEqualTo(TENANT);
        assertThat(plan.actor().internal()).isTrue();
        assertThat(plan.actor().sourceService()).isEqualTo("dwp-approval-server");
        assertThat(plan.requestId()).isEqualTo(REQUEST);
        var seat = plan.recipients().getFirst();
        var request = plan.request(seat);
        assertThat(request.sourceEventId()).isEqualTo(plan.childEventId(seat));
        assertThat(request.recipientUserIds()).containsExactly(seat.userId());
        assertThat(request.typeKey()).isEqualTo(type.endsWith("SlaWarning")
                ? "APPROVAL.SLA_WARNING" : "APPROVAL.SLA_BREACHED");
        assertThat(request.variables()).containsEntry("sourceEventId", parent.toString())
                .containsEntry("taskId", seat.taskId().toString()).containsEntry("taskVersion", seat.taskVersion());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 99, 100, 101, 999, 1000})
    void chunksEveryFrozenSeatExactlyOnceWithoutDroppingTheTail(int count) throws Exception {
        var plan = plan(count);
        List<ApprovalSlaNotificationPlan.Recipient> delivered = new ArrayList<>();
        assertThat(plan.chunkCount()).isEqualTo((count + 99) / 100);
        for (int index = 0; index < plan.chunkCount(); index++) {
            assertThat(plan.chunk(index)).hasSize(Math.min(100, count - index * 100));
            delivered.addAll(plan.chunk(index));
        }
        assertThat(delivered).containsExactlyElementsOf(plan.recipients());
        assertThat(delivered).extracting(ApprovalSlaNotificationPlan.Recipient::userId)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, count).mapToObj(Long::valueOf).toList());
        assertThat(delivered.stream().map(plan::childEventId).distinct().count()).isEqualTo(count);
        assertThatThrownBy(() -> plan.chunk(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan.chunk(plan.chunkCount())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects1001SeatsRatherThanSilentlyTruncating() throws Exception {
        rejects(event(1001));
    }

    @Test
    void keepsExactOriginalUtf8HashSeparateFromCanonicalAndSourceHashes() throws Exception {
        UUID parent = UUID.randomUUID();
        ObjectNode event = event(3);
        payload(event).put("requestTitle", "Approval \uacb0\uc7ac \ud83d\udcc4");
        String compact = encode(event);
        String spaced = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(event) + "\n";
        var original = ApprovalSlaNotificationContract.translate(record(parent, compact));
        var whitespace = ApprovalSlaNotificationContract.translate(record(parent, spaced));

        assertThat(original.originalEnvelopeSha256()).isEqualTo(digest(compact));
        assertThat(whitespace.originalEnvelopeSha256()).isEqualTo(digest(spaced)).isNotEqualTo(digest(compact));
        assertThat(original.envelopeSha256()).isEqualTo(digest(original.canonicalEnvelope()));
        assertThat(whitespace.canonicalEnvelope()).isEqualTo(original.canonicalEnvelope());
        assertThat(whitespace.envelopeSha256()).isEqualTo(original.envelopeSha256());
        assertThat(whitespace.sourcePinsSha256()).isEqualTo(original.sourcePinsSha256());
        assertThat(whitespace.recipientSnapshotSha256()).isEqualTo(original.recipientSnapshotSha256());
        assertThat(original.sourcePinsSha256()).isEqualTo(digest(encode(new TreeMap<>(original.pins()))));
    }

    @Test
    void childUuidReplayIsDeterministicAndParentAndSeatBound() throws Exception {
        UUID parent = UUID.randomUUID();
        String wire = encode(event(3));
        var first = ApprovalSlaNotificationContract.translate(record(parent, wire));
        var replay = ApprovalSlaNotificationContract.translate(record(parent, wire));
        var nextParent = ApprovalSlaNotificationContract.translate(record(UUID.randomUUID(), wire));
        for (var seat : first.recipients()) {
            UUID expected = UUID.nameUUIDFromBytes(("dwp:approval:sla:v1:" + parent + ":"
                    + seat.userId() + ":" + seat.taskId()).getBytes(StandardCharsets.UTF_8));
            assertThat(replay.childEventId(seat)).isEqualTo(expected).isEqualTo(first.childEventId(seat));
            assertThat(nextParent.childEventId(seat)).isNotEqualTo(expected);
        }
        var unknown = new ApprovalSlaNotificationPlan.Recipient(4, opaque("person-4"), opaque("task-4"), 0);
        assertThatThrownBy(() -> first.childEventId(unknown)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> first.recipients().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.pins().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicatesTrailingTokensAndNonObjectOrOversizedUtf8() throws Exception {
        String valid = encode(event(1));
        for (String wire : List.of(valid.replace("\"tenantId\":42", "\"tenantId\":42,\"tenantId\":42"),
                valid.replace("\"requestVersion\":0", "\"requestVersion\":0,\"requestVersion\":0"),
                valid + " {}", valid + " null", "[]", "null", "{", "")) {
            assertThatThrownBy(() -> ApprovalSlaNotificationContract.translate(record(UUID.randomUUID(), wire)))
                    .isInstanceOf(ApprovalNotificationEventException.class);
        }
        String multibyte = valid + "\uac00".repeat(100001);
        assertThatThrownBy(() -> ApprovalSlaNotificationContract.translate(record(UUID.randomUUID(), multibyte)))
                .isInstanceOf(ApprovalNotificationEventException.class).hasMessageContaining("UTF-8 bound");
        assertThatThrownBy(() -> ApprovalSlaNotificationContract.translate(record(UUID.randomUUID(), null)))
                .isInstanceOf(ApprovalNotificationEventException.class);
    }

    @Test
    void closesAllEnvelopeAndKafkaHeaderAliases() throws Exception {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                e -> e.put("extra", "unknown"), e -> e.remove("correlationId"),
                e -> e.put("specVersion", "2.0"), e -> e.put("correlationId", "injected"),
                e -> e.put("eventType", "Approval.Quorum.Unknown"), e -> e.put("tenantId", 43),
                e -> e.put("requestId", opaque("another-request").toString()))) {
            ObjectNode event = event(1); mutation.accept(event); rejects(event);
        }
        for (String header : List.of("dwp-event-id", "dwp-event-type", "dwp-tenant-id")) {
            var missing = record(UUID.randomUUID(), encode(event(1)));
            missing.headers().remove(header);
            assertThatThrownBy(() -> ApprovalSlaNotificationContract.translate(missing))
                    .isInstanceOf(ApprovalNotificationEventException.class);
            var duplicate = record(UUID.randomUUID(), encode(event(1)));
            duplicate.headers().add(header, duplicate.headers().lastHeader(header).value());
            assertThatThrownBy(() -> ApprovalSlaNotificationContract.translate(duplicate))
                    .isInstanceOf(ApprovalNotificationEventException.class);
            for (byte[] invalid : List.of(new byte[] {(byte) 0xc3, 0x28}, new byte[0],
                    " 42".getBytes(StandardCharsets.UTF_8), "42\n".getBytes(StandardCharsets.UTF_8))) {
                var malformed = record(UUID.randomUUID(), encode(event(1)));
                malformed.headers().remove(header).add(header, invalid);
                assertThatThrownBy(() -> ApprovalSlaNotificationContract.translate(malformed))
                        .isInstanceOf(ApprovalNotificationEventException.class);
            }
        }
    }

    @Test
    void rejectsTruncatedReorderedDuplicateOrReboundAudience() throws Exception {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                p -> p.withArray("recipientSeats").remove(1),
                p -> p.withArray("recipientUserIds").set(1, JSON.getNodeFactory().numberNode(1)),
                p -> p.withArray("recipientUserIds").set(0, JSON.getNodeFactory().numberNode(2)),
                p -> ((ObjectNode) p.withArray("recipientSeats").get(0)).put("userId", 2),
                p -> ((ObjectNode) p.withArray("recipientSeats").get(1)).put("personPublicId", opaque("person-1").toString()),
                p -> ((ObjectNode) p.withArray("recipientSeats").get(1)).put("taskId", opaque("task-1").toString()),
                p -> ((ObjectNode) p.withArray("recipientSeats").get(0)).put("taskVersion", -1),
                p -> ((ObjectNode) p.withArray("recipientSeats").get(0)).put("extra", true),
                p -> p.put("recipientSnapshotSha256", "b".repeat(64)))) {
            ObjectNode event = event(2); mutation.accept(payload(event)); rejects(event);
        }
    }

    @Test
    void rejectsInvalidPinsNumbersDatesAndUnknownFields() throws Exception {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                p -> p.put("generation", 0), p -> p.put("leaseEpoch", 1.5),
                p -> p.put("requestVersion", "0"), p -> p.put("policyVersion", 9007199254740992L),
                p -> p.put("occurredAt", "2026-09-14T00:00:00.000Z"),
                p -> p.put("stepId", "00000000-0000-0000-0000-00000000000A"),
                p -> p.put("formSchemaSha256", "A".repeat(64)), p -> p.put("requestTitle", "title\n"),
                p -> p.put("authorityRevision", " revision"), p -> p.remove("timerId"),
                p -> p.put("eventContract", "unverified"), p -> p.put("extra", "unknown"))) {
            ObjectNode event = event(1); mutation.accept(payload(event)); rejects(event);
        }
    }

    @Test
    void acceptsTheFirstInProgressStageVersionZeroWithoutLooseningOtherPositiveVersionPins() throws Exception {
        ObjectNode firstWarning = event(1);
        payload(firstWarning).put("stageVersion", 0L);
        var plan = ApprovalSlaNotificationContract.translate(record(UUID.randomUUID(), encode(firstWarning)));
        assertThat(plan.pins()).containsEntry("stageVersion", 0L);
        ObjectNode negativeStage = firstWarning.deepCopy();
        payload(negativeStage).put("stageVersion", -1L);
        rejects(negativeStage);
        for (String positivePin : List.of("generation", "workflowVersion", "payloadRevision", "policyVersion", "leaseEpoch")) {
            ObjectNode invalid = firstWarning.deepCopy();
            payload(invalid).put(positivePin, 0L);
            rejects(invalid);
        }
    }

    static ApprovalSlaNotificationPlan plan(int count) throws Exception {
        return ApprovalSlaNotificationContract.translate(record(UUID.randomUUID(), encode(event(count))));
    }

    static ObjectNode event(int count) throws Exception {
        var recipients = IntStream.rangeClosed(1, count).mapToObj(i -> Map.<String, Object>of(
                "userId", (long) i, "personPublicId", opaque("person-" + i).toString(),
                "taskId", opaque("task-" + i).toString(), "taskVersion", 0L)).toList();
        var users = IntStream.rangeClosed(1, count).mapToObj(Long::valueOf).toList();
        Map<String, Object> payload = new TreeMap<>();
        payload.put("eventContract", "DWP_APPROVAL_QUORUM_SLA_EVENT_V1");
        payload.put("requestTitle", "Approval request");
        payload.put("occurredAt", "2026-09-14T00:00:00Z");
        payload.put("requestVersion", 0L);
        payload.put("managementResourceSetKey", "approval-management-resource-set");
        payload.put("stageKey", "manager-review");
        payload.put("authorityRevision", "psr-" + HASH);
        for (String key : List.of("stepId", "workflowVersionId", "formVersionId", "timerId"))
            payload.put(key, opaque(key).toString());
        for (String key : List.of("workflowDefinitionSha256", "formSchemaSha256", "payloadSha256", "policySha256"))
            payload.put(key, HASH);
        for (String key : List.of("stageVersion", "generation", "workflowVersion", "payloadRevision", "policyVersion", "leaseEpoch"))
            payload.put(key, 1L);
        payload.put("recipientUserIds", users);
        payload.put("recipientSeats", recipients);
        payload.put("recipientSnapshotSha256", digest(encode(Map.of("recipientUserIds", users, "recipientSeats", recipients))));
        return JSON.valueToTree(Map.of("specVersion", "1.0", "eventType", "Approval.Quorum.SlaWarning",
                "tenantId", TENANT, "requestId", REQUEST.toString(), "correlationId", "", "payload", payload));
    }

    static ObjectNode payload(ObjectNode event) { return (ObjectNode) event.get("payload"); }
    static UUID opaque(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); }
    static String encode(Object value) throws Exception { return JSON.writeValueAsString(value); }
    static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    static ConsumerRecord<String, String> record(UUID eventId, String wire) {
        return record(eventId, wire, "Approval.Quorum.SlaWarning");
    }
    static ConsumerRecord<String, String> record(UUID eventId, String wire, String type) {
        var record = new ConsumerRecord<String, String>("dwp.approval.events.v1", 0, 1, REQUEST.toString(), wire);
        record.headers().add("dwp-event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("dwp-event-type", type.getBytes(StandardCharsets.UTF_8));
        record.headers().add("dwp-tenant-id", Long.toString(TENANT).getBytes(StandardCharsets.UTF_8));
        return record;
    }
    private static void rejects(ObjectNode event) throws Exception {
        String wire = encode(event);
        assertThatThrownBy(() -> ApprovalSlaNotificationContract.translate(record(UUID.randomUUID(), wire)))
                .isInstanceOf(ApprovalNotificationEventException.class)
                .satisfies(error -> assertThat(((ApprovalNotificationEventException) error).classification())
                        .isEqualTo(ApprovalNotificationEventException.Classification.PAYLOAD_CONTRACT_VIOLATION));
    }
}
