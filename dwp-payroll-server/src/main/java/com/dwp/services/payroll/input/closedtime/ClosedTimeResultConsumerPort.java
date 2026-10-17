package com.dwp.services.payroll.input.closedtime;

/**
 * Non-durable Payroll seam for an already authenticated, bounded and verified
 * closed-time result.
 *
 * <p>This slice intentionally supplies no implementation. A transport listener,
 * durable receipt, quarantine, staging area or calculation input requires the
 * separately controlled PAY consumer successor.</p>
 */
public interface ClosedTimeResultConsumerPort {

    void accept(ClosedTimeResultContractVerifier.VerifiedHandoff handoff);

    /**
     * Records the consumer-side intent for an owner-authored reopen. A future
     * durable implementation must invalidate an unfrozen input or create
     * explicit retro lineage; it must never rewrite a finalized Payroll run.
     */
    void invalidate(ClosedTimeResultContractVerifier.VerifiedReopen reopen);
}
