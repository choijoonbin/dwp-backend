package com.dwp.core.database.authority;

import java.util.Optional;

/**
 * UNWIRED external authenticated deployment authority SPI; no permissive/default/local provider.
 * Its real producer must linearize reserve/activate with epoch advance and Control DDL fencing,
 * revoke/drain running leases and require actual database sessions offline before schema changes.
 * A signed timestamp alone is NOT this fence. Continuous renewal/readiness/drain is not implemented.
 */
public interface RuntimeStartupFreshnessPort {
    Optional<String> reserve(Reservation request);
    Optional<String> activate(Activation request);
    /** Current-state refetch, not permission to renew a revoked/expired lease. No caller-side cache fallback. */
    Optional<String> current(Activation request);
    /** Cancellation is best-effort local cleanup, NOT a signed offline/drain acknowledgement. */
    void release(Reservation request, String leaseId);

    record Reservation(String service, String deploymentId, String applicationInstanceId,
            long epoch, String permitId, String startupChallengeSha256, String sealSha256) { }
    record Activation(Reservation reservation, String leaseId) { }
}
