package com.dwp.platform.contract;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformContractTest {

    private static final String PLAN_HASH = "a".repeat(64);
    private static final String PAYLOAD_SHA256 =
            "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5";

    private final ExecutionContext context = new ExecutionContext(
            "tenant-1",
            "user-1",
            Set.of("EMPLOYEE"),
            "correlation-1");

    @Test
    void snapshotsMutableCollectionsAtTheBoundary() {
        Set<String> roles = new HashSet<>(Set.of("EMPLOYEE"));
        ExecutionContext copiedContext = new ExecutionContext(
                "tenant-1",
                "user-1",
                roles,
                "correlation-1");
        roles.add("ADMIN");

        List<ConnectorPort.Item> items = new ArrayList<>();
        ConnectorPort.ReadPage page = new ConnectorPort.ReadPage(
                items,
                null,
                "opaque-sync-cursor",
                false);
        items.add(new ConnectorPort.Item(
                "source-1",
                "calendar-event",
                "Review",
                Instant.parse("2026-08-08T01:00:00Z"),
                URI.create("https://source.example/events/1"),
                "acl-1",
                DataClassification.INTERNAL));

        assertThat(copiedContext.roles()).containsExactly("EMPLOYEE");
        assertThat(page.items()).isEmpty();
    }

    @Test
    void rejectsUnboundedConnectorAndSearchReads() {
        assertThatThrownBy(() -> new ConnectorPort.ReadRequest(context, null, 201, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeSearchPort.SearchRequest(
                context,
                "policy",
                Set.of(),
                51))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresApprovalForElevatedPlansAndNeverMutatesDuringPreview() {
        assertThatThrownBy(() -> new AgentRuntimePort.PlanPreview(
                "run-1",
                PLAN_HASH,
                RiskTier.L2,
                false,
                false,
                "Preview",
                List.of(),
                List.of(),
                "audit-1",
                "correlation-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require approval");

        assertThatThrownBy(() -> new AgentRuntimePort.PlanPreview(
                "run-1",
                PLAN_HASH,
                RiskTier.L1,
                false,
                true,
                "Preview",
                List.of(),
                List.of(),
                "audit-1",
                "correlation-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never allow mutation");

        assertThatThrownBy(() -> new AgentRuntimePort.PlanPreview(
                "run-1",
                "mutable-plan-id",
                RiskTier.L1,
                false,
                false,
                "Preview",
                List.of(),
                List.of(),
                "audit-1",
                "correlation-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void mailConnectorAcceptsOnlyOpaqueSecretReferencesAndBoundedSynchronization() {
        assertThatThrownBy(() -> new MailConnectorPort.ConnectionContext(
                context,
                UUID.randomUUID(),
                URI.create("https://raw-secret.example/token"),
                "sk.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret-store scheme");

        MailConnectorPort.ConnectionContext connection =
                new MailConnectorPort.ConnectionContext(
                        context,
                        UUID.randomUUID(),
                        URI.create("vault://tenant/mail/microsoft"),
                        "sk.com");

        assertThatThrownBy(() -> new MailConnectorPort.SyncRequest(
                connection, "account-1", null, 501))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        assertThatThrownBy(() -> new MailConnectorPort.SendRequest(
                connection, "account-1", null, List.of("person@sk.com"),
                "Subject", "Message", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void mailSendRequestPreservesHtmlAndSnapshotsAttachmentContent() {
        MailConnectorPort.ConnectionContext connection =
                new MailConnectorPort.ConnectionContext(
                        context, UUID.randomUUID(), null, "sk.com");
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        MailConnectorPort.OutboundAttachment attachment =
                new MailConnectorPort.OutboundAttachment(
                        UUID.randomUUID(), "report.txt", "text/plain",
                        content.length, PAYLOAD_SHA256, content);
        List<MailConnectorPort.OutboundAttachment> attachments =
                new ArrayList<>(List.of(attachment));

        MailConnectorPort.SendRequest request = new MailConnectorPort.SendRequest(
                connection, "account-1", UUID.randomUUID(),
                List.of("to@sk.com"), List.of("cc@sk.com"), List.of("bcc@sk.com"),
                "Subject", "<p>Message</p>", MailConnectorPort.BodyFormat.HTML,
                attachments, null);
        content[0] = 'X';
        attachments.clear();
        byte[] exposed = request.attachments().getFirst().content();
        exposed[0] = 'X';

        assertThat(request.bodyFormat()).isEqualTo(MailConnectorPort.BodyFormat.HTML);
        assertThat(request.body()).isEqualTo("<p>Message</p>");
        assertThat(request.attachments()).containsExactly(attachment);
        assertThat(request.attachments().getFirst().content())
                .containsExactly("payload".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> request.attachments().add(attachment))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void legacyMailSendConstructorsDefaultToTextWithoutAttachments() {
        MailConnectorPort.ConnectionContext connection =
                new MailConnectorPort.ConnectionContext(
                        context, UUID.randomUUID(), null, "sk.com");
        MailConnectorPort.SendRequest sevenArgumentRequest =
                new MailConnectorPort.SendRequest(
                        connection, "account-1", UUID.randomUUID(),
                        List.of("to@sk.com"), "Subject", "Message", null);
        MailConnectorPort.SendRequest typedNineArgumentRequest =
                new MailConnectorPort.SendRequest(
                        connection, "account-1", UUID.randomUUID(),
                        List.of("to@sk.com"), List.of("cc@sk.com"), List.of("bcc@sk.com"),
                        "Subject", "Message", null);

        assertThat(sevenArgumentRequest.bodyFormat())
                .isEqualTo(MailConnectorPort.BodyFormat.TEXT);
        assertThat(sevenArgumentRequest.attachments()).isEmpty();
        assertThat(sevenArgumentRequest.senderMode())
                .isEqualTo(MailConnectorPort.SenderMode.ACCOUNT);
        assertThat(typedNineArgumentRequest.bodyFormat())
                .isEqualTo(MailConnectorPort.BodyFormat.TEXT);
        assertThat(typedNineArgumentRequest.attachments()).isEmpty();
        assertThat(MailConnectorPort.Capability.values()).contains(
                MailConnectorPort.Capability.SEND_ON_BEHALF,
                MailConnectorPort.Capability.BCC,
                MailConnectorPort.Capability.HTML_BODY,
                MailConnectorPort.Capability.ATTACHMENTS);
    }

    @Test
    void providerMessagePreservesHtmlAndAttachmentContentWhileLegacyDefaultsToText() {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        MailConnectorPort.ProviderAttachment attachment =
                new MailConnectorPort.ProviderAttachment(
                        "provider-attachment-1", "provider-content-1", "report.txt",
                        "text/plain", content.length, PAYLOAD_SHA256, content,
                        Map.of("disposition", "attachment"));
        MailConnectorPort.ProviderMessage typed = new MailConnectorPort.ProviderMessage(
                "provider-message-1", "provider-thread-1", "provider-folder-1",
                Instant.parse("2026-09-17T00:00:00Z"), "Sender <sender@example.test>",
                List.of("recipient@example.test"), "Subject", "<p>Body</p>",
                MailConnectorPort.BodyFormat.HTML, List.of(attachment), Map.of());
        MailConnectorPort.ProviderMessage legacy = new MailConnectorPort.ProviderMessage(
                "provider-message-2", "provider-thread-2", "provider-folder-1",
                Instant.parse("2026-09-17T00:00:00Z"), "sender@example.test",
                List.of(), "Subject", "Body", Map.of());
        content[0] = 'X';
        byte[] exposed = typed.attachments().getFirst().content();
        exposed[0] = 'X';

        assertThat(typed.bodyFormat()).isEqualTo(MailConnectorPort.BodyFormat.HTML);
        assertThat(typed.body()).isEqualTo("<p>Body</p>");
        assertThat(typed.attachments().getFirst().content())
                .containsExactly("payload".getBytes(StandardCharsets.UTF_8));
        assertThat(legacy.bodyFormat()).isEqualTo(MailConnectorPort.BodyFormat.TEXT);
        assertThat(legacy.attachments()).isEmpty();
    }

    @Test
    void outboundMailAttachmentRejectsInconsistentContentMetadata() {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new MailConnectorPort.OutboundAttachment(
                UUID.randomUUID(), "report.txt", "text/plain",
                content.length + 1L, PAYLOAD_SHA256, content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sizeBytes");
        assertThatThrownBy(() -> new MailConnectorPort.OutboundAttachment(
                UUID.randomUUID(), "report.txt", "text/plain",
                content.length, "a".repeat(64), content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match content");
    }
}
