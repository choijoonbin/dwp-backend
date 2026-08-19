package com.dwp.services.platform.mail;

import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DwpSandboxMailConnectorTest {

    @Test
    void deliveryReceiptIsStableForTheSameIdempotencyKey() {
        DwpSandboxMailConnector connector = new DwpSandboxMailConnector();
        UUID idempotencyKey = UUID.randomUUID();
        MailConnectorPort.ConnectionContext context = new MailConnectorPort.ConnectionContext(
                new ExecutionContext("1", "7", Set.of(), "corr-sandbox"),
                UUID.randomUUID(), null, "sk.com");
        MailConnectorPort.SendRequest request = new MailConnectorPort.SendRequest(
                context, "sandbox:user:7", idempotencyKey,
                List.of("recipient@sk.com"), "검증 메일", "본문", null);

        MailConnectorPort.DeliveryReceipt first = connector.send(request);
        MailConnectorPort.DeliveryReceipt retried = connector.send(request);

        assertThat(retried.providerMessageReference())
                .isEqualTo(first.providerMessageReference());
        assertThat(retried.providerThreadReference())
                .isEqualTo(first.providerThreadReference());
        assertThat(first.acceptedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void replyKeepsTheOriginalSandboxConversationReference() {
        DwpSandboxMailConnector connector = new DwpSandboxMailConnector();
        MailConnectorPort.ConnectionContext context = new MailConnectorPort.ConnectionContext(
                new ExecutionContext("1", "7", Set.of(), "corr-sandbox-reply"),
                UUID.randomUUID(), null, "sk.com");
        MailConnectorPort.SendRequest originalRequest = new MailConnectorPort.SendRequest(
                context, "sandbox:user:7", UUID.randomUUID(),
                List.of("recipient@sk.com"), "검증 메일", "본문", null);
        MailConnectorPort.DeliveryReceipt original = connector.send(originalRequest);
        MailConnectorPort.SendRequest replyRequest = new MailConnectorPort.SendRequest(
                context, "sandbox:user:7", UUID.randomUUID(),
                List.of("recipient@sk.com"), "검증 메일", "회신",
                original.providerMessageReference());

        MailConnectorPort.DeliveryReceipt reply = connector.send(replyRequest);

        assertThat(reply.providerThreadReference()).isEqualTo(original.providerThreadReference());
        assertThat(reply.providerMessageReference()).isNotEqualTo(original.providerMessageReference());
    }
}
