package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
class DwpSandboxMailConnector implements MailConnectorPort {

    private static final Manifest MANIFEST = new Manifest(
            ProviderFamily.DWP_SANDBOX,
            "1.0.0",
            "DWP native sandbox",
            Set.of(Capability.READ, Capability.SEND, Capability.THREADS,
                    Capability.FOLDERS, Capability.PUSH));

    @Override
    public Manifest manifest() {
        return MANIFEST;
    }

    @Override
    public Readiness readiness(ConnectionContext context) {
        return new Readiness(ReadinessState.READY, Instant.now(), null, null);
    }

    @Override
    public SyncBatch synchronize(SyncRequest request) {
        return new SyncBatch(List.of(), "sandbox:cursor:stable", false, false);
    }

    @Override
    public DeliveryReceipt send(SendRequest request) {
        UUID stable = UUID.nameUUIDFromBytes(
                (request.providerAccountReference() + ':' + request.idempotencyKey())
                        .getBytes(StandardCharsets.UTF_8));
        String suffix = stable.toString();
        return new DeliveryReceipt(
                "sandbox:message:" + suffix,
                threadReference(request.replyToProviderMessageReference(), suffix),
                Instant.now());
    }

    private String threadReference(String replyToProviderMessageReference, String fallbackSuffix) {
        String messagePrefix = "sandbox:message:";
        if (replyToProviderMessageReference != null
                && replyToProviderMessageReference.startsWith(messagePrefix)) {
            return "sandbox:thread:"
                    + replyToProviderMessageReference.substring(messagePrefix.length());
        }
        return "sandbox:thread:" + fallbackSuffix;
    }

    @Override
    public SubscriptionReceipt renewSubscription(SubscriptionRequest request) {
        UUID stable = UUID.nameUUIDFromBytes(
                request.callback().toString().getBytes(StandardCharsets.UTF_8));
        return new SubscriptionReceipt(
                "sandbox:subscription:" + stable,
                request.requestedUntil());
    }
}
