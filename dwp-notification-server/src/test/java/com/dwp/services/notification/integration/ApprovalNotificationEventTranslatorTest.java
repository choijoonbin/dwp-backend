package com.dwp.services.notification.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalNotificationEventTranslatorTest {

    private static final UUID EVENT_ID = UUID.fromString(
            "50000000-0000-0000-0000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString(
            "60000000-0000-0000-0000-000000000001");

    private final ApprovalNotificationEventTranslator translator =
            new ApprovalNotificationEventTranslator(
                    new ObjectMapper().findAndRegisterModules(),
                    "approval.request.submitted,approval.request.approved,"
                            + "approval.request.rejected");

    @ParameterizedTest
    @CsvSource({
        "approval.request.submitted,APPROVAL.REQUEST_SUBMITTED,SUBMITTED",
        "approval.request.approved,APPROVAL.REQUEST_APPROVED,APPROVED",
        "approval.request.rejected,APPROVAL.REQUEST_REJECTED,REJECTED"
    })
    void mapsSupportedRequestLifecycleEvents(
            String eventType,
            String typeKey,
            String decision) {
        ApprovalNotificationEventTranslator.Translation translated = translator
                .translate(record(eventType, payload(eventType, decision, true)))
                .orElseThrow();

        assertThat(translated.actor().tenantId()).isEqualTo(1);
        assertThat(translated.actor().sourceService())
                .isEqualTo("dwp-approval-server");
        assertThat(translated.request().sourceEventId()).isEqualTo(EVENT_ID);
        assertThat(translated.request().typeKey()).isEqualTo(typeKey);
        assertThat(translated.request().recipientUserIds()).containsExactly(900018L);
        assertThat(translated.request().variables())
                .containsEntry("requestId", REQUEST_ID.toString())
                .containsEntry("decision", decision);
    }

    @Test
    void acknowledgesValidUnregisteredEventsWithoutMaterializingOrDeadLettering() {
        String type = "approval.request.withdrawn";

        Optional<ApprovalNotificationEventTranslator.Translation> translated =
                translator.translate(record(type, payload(type, "WITHDRAWN", true)));

        assertThat(translated).isEmpty();
    }

    @Test
    void rejectsMalformedAllowlistedContractForDeadLetterHandling() {
        String type = "approval.request.approved";

        assertThatThrownBy(() -> translator.translate(
                record(type, payload(type, "APPROVED", false))))
                .isInstanceOf(ApprovalNotificationEventException.class)
                .satisfies(error -> assertThat(
                        ((ApprovalNotificationEventException) error).classification())
                        .isEqualTo(ApprovalNotificationEventException.Classification
                                .PAYLOAD_CONTRACT_VIOLATION));
    }

    private ConsumerRecord<String, String> record(String eventType, String payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "dwp.approval.events.v1", 0, 10L, REQUEST_ID.toString(), payload);
        record.headers().add(new RecordHeader(
                ApprovalNotificationEventTranslator.EVENT_ID_HEADER,
                EVENT_ID.toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(
                ApprovalNotificationEventTranslator.EVENT_TYPE_HEADER,
                eventType.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(
                ApprovalNotificationEventTranslator.TENANT_ID_HEADER,
                "1".getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private String payload(String eventType, String decision, boolean includeTitle) {
        String title = includeTitle ? ",\"requestTitle\":\"클라우드 운영 예산\"" : "";
        return "{"
                + "\"specVersion\":\"1.0\","
                + "\"eventType\":\"" + eventType + "\","
                + "\"tenantId\":1,"
                + "\"requestId\":\"" + REQUEST_ID + "\","
                + "\"correlationId\":\"corr-1\","
                + "\"payload\":{\"requestId\":\"" + REQUEST_ID + "\","
                + "\"recipientUserId\":900018,"
                + "\"decision\":\"" + decision + "\""
                + title + "}}";
    }
}
