package com.dwp.services.time.commandreceipt;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Persists registry failures to the service audit outbox and always emits a structured log. */
@Component
final class OutboxCommandReceiptAuditSink implements CommandReceiptAuditSink {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxCommandReceiptAuditSink.class);

    private final ObjectProvider<AuditOutboxRecorder> recorder;

    OutboxCommandReceiptAuditSink(ObjectProvider<AuditOutboxRecorder> recorder) {
        this.recorder = recorder;
    }

    @Override
    public void record(CommandReceiptAuditEvent event) {
        log.warn(
                "Command-receipt provider resolution failed; tenant={} receipt={}"
                        + " correlation={} failure={} providers={}",
                event.tenantId(),
                event.receiptId(),
                event.correlationId(),
                event.failure(),
                event.providerIds());
        AuditOutboxRecorder available = recorder.getIfAvailable();
        if (available == null) {
            return;
        }
        available.record(AuditEvent.builder()
                .tenantId(event.tenantId())
                .category("SYSTEM_EVENT")
                .action("time.command-receipt.provider-resolution")
                .outcome("FAILED")
                .severity(event.failure() == CommandReceiptAuditEvent.Failure.MULTIPLE_OWNERS
                        ? "CRITICAL" : "HIGH")
                .riskScore(event.failure() == CommandReceiptAuditEvent.Failure.MULTIPLE_OWNERS
                        ? 95 : 85)
                .actorType("SYSTEM")
                .sourceService("dwp-time-server")
                .sourceModule("commandreceipt")
                .targetType("TIME_COMMAND_RECEIPT")
                .targetId(event.receiptId().toString())
                .reason(event.failure().name())
                .correlationId(event.correlationId().toString())
                .metadata(Map.<String, Object>of("providerIds", event.providerIds()))
                .retentionClass("EXTENDED")
                .build());
    }
}
