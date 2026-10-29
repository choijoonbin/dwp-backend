package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailProviderPayloadPolicyTest {

    @Test
    void rejectsBodyAndRecipientCapsBeforeMaterialization() {
        MailProviderPayloadPolicy policy = new MailProviderPayloadPolicy(
                10, 1, 5, 10, 100, 10, 100);
        MailConnectorPort.ProviderMessage message = new MailConnectorPort.ProviderMessage(
                "message", "thread", "folder", Instant.now(), "sender@example.test",
                List.of("one@example.test", "two@example.test"), "subject",
                "body-too-large", Map.of());

        assertThatThrownBy(() -> policy.validate(new MailConnectorPort.SyncBatch(
                List.of(message), "cursor", false, false)))
                .isInstanceOf(MailSyncMaterializationFailure.class)
                .hasMessage("PROVIDER_RECIPIENT_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsAggregateAttachmentBytesWithSafeCode() {
        MailProviderPayloadPolicy policy = new MailProviderPayloadPolicy(
                10, 10, 100, 10, 100, 10, 5);
        MailConnectorPort.ProviderAttachment attachment =
                new MailConnectorPort.ProviderAttachment(
                        "attachment", "provider-ref", "report.pdf", "application/pdf",
                        6, null, null, Map.of());
        MailConnectorPort.ProviderMessage message = new MailConnectorPort.ProviderMessage(
                "message", "thread", "folder", Instant.now(), "sender@example.test",
                List.of("one@example.test"), "subject", "body",
                MailConnectorPort.BodyFormat.TEXT, List.of(attachment), Map.of());

        assertThatThrownBy(() -> policy.validate(new MailConnectorPort.SyncBatch(
                List.of(message), "cursor", false, false)))
                .isInstanceOf(MailSyncMaterializationFailure.class)
                .hasMessage("PROVIDER_ATTACHMENT_BYTES_EXCEEDED");
    }

    @Test
    void rejectsAggregateBatchBodyEvenWhenEachMessageIsIndividuallySmall() {
        MailProviderPayloadPolicy policy = new MailProviderPayloadPolicy(
                10, 10, 5, 10, 100, 10, 100);
        MailConnectorPort.ProviderMessage first = new MailConnectorPort.ProviderMessage(
                "message-1", "thread-1", "folder", Instant.now(),
                "sender@example.test", List.of("one@example.test"),
                "subject", "abc", Map.of());
        MailConnectorPort.ProviderMessage second = new MailConnectorPort.ProviderMessage(
                "message-2", "thread-2", "folder", Instant.now(),
                "sender@example.test", List.of("two@example.test"),
                "subject", "def", Map.of());

        assertThatThrownBy(() -> policy.validate(new MailConnectorPort.SyncBatch(
                List.of(first, second), "cursor", false, false)))
                .isInstanceOf(MailSyncMaterializationFailure.class)
                .hasMessage("PROVIDER_BODY_LIMIT_EXCEEDED");
    }
}
