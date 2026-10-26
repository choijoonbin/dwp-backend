package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Port for an externally provisioned atomic owner credential lease.
 *
 * <p>The V271 public JWK is never treated as private material.  The runtime can only
 * consume a complete lease supplied by a separately configured secret provider.</p>
 */
public final class HrisServiceGrantAuthorityOwnerCredentialProvider {
    private final LeaseSource source;

    public HrisServiceGrantAuthorityOwnerCredentialProvider(LeaseSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public OwnerCredentialLease requireCurrent(
            long tenantId, HrisServiceGrantAuthorityV1.TargetTuple target,
            UUID grantSetId, long tenantGrantRevision, long authorizationRevision,
            Instant now) {
        if (tenantId < 1 || tenantGrantRevision < 1 || authorizationRevision < 1)
            throw new IllegalArgumentException("authority revisions must be positive");
        Objects.requireNonNull(target); Objects.requireNonNull(grantSetId);
        Objects.requireNonNull(now);
        OwnerCredentialLease lease = source.load(tenantId, target);
        if (lease == null || lease.tenantId() != tenantId
                || !lease.target().equals(target) || !lease.grantSetId().equals(grantSetId)
                || lease.tenantGrantRevision() != tenantGrantRevision
                || lease.authorizationRevision() != authorizationRevision
                || !lease.ownerWorkloadId().equals(target.ownerWorkloadId())
                || lease.keyId().isBlank() || lease.privateKeyHandle().isBlank()
                || !now.isBefore(lease.expiresAt())) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        return lease;
    }

    @FunctionalInterface
    public interface LeaseSource {
        OwnerCredentialLease load(long tenantId, HrisServiceGrantAuthorityV1.TargetTuple target);
    }

    public record OwnerCredentialLease(
            long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target,
            UUID grantSetId,
            long tenantGrantRevision,
            long authorizationRevision,
            String ownerWorkloadId,
            String keyId,
            String privateKeyHandle,
            Instant expiresAt) {
        public OwnerCredentialLease {
            if (tenantId < 1 || tenantGrantRevision < 1 || authorizationRevision < 1)
                throw new IllegalArgumentException("lease revisions must be positive");
            Objects.requireNonNull(target); Objects.requireNonNull(grantSetId);
            Objects.requireNonNull(ownerWorkloadId); Objects.requireNonNull(keyId);
            Objects.requireNonNull(privateKeyHandle); Objects.requireNonNull(expiresAt);
        }
    }
}
