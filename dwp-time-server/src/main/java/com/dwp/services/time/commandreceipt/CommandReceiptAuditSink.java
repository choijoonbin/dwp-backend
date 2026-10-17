package com.dwp.services.time.commandreceipt;

/** Records command-receipt provider ambiguity and unavailability. */
@FunctionalInterface
public interface CommandReceiptAuditSink {
    void record(CommandReceiptAuditEvent event);
}
