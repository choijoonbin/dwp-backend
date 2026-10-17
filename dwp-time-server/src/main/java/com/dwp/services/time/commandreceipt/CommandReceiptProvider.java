package com.dwp.services.time.commandreceipt;

/**
 * Tenant-scoped command-receipt owner.
 *
 * <p>Ownership discovery must not revalidate disclosure authority. The registry first asks every
 * provider whether it owns the tenant/receipt tuple and invokes {@link #receipt} only when exactly
 * one provider owns it. The selected provider then revalidates the sealed originating authority.
 */
public interface CommandReceiptProvider {

    /** Stable, non-sensitive identifier used only for registry diagnostics and audit. */
    String providerId();

    /** Returns whether this provider owns the tenant/receipt tuple. */
    boolean owns(CommandReceiptLookup lookup);

    /** Revalidates originating authority and returns the provider's native wire DTO. */
    Object receipt(CommandReceiptLookup lookup);
}
