package com.dwp.services.payroll.input.compensationplan;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;

import java.util.Objects;
import java.util.UUID;

/**
 * PAY-owned port for the exact SVC-PEP-PER-001 refetch. The transport adapter is
 * intentionally Control-owned; PAY never reads a People repository or database.
 */
public interface CompensationPlanSnapshotOwnerPort {

    Xcon020ApprovedCompensationPlanSnapshotV1 refetch(ExactSnapshotRequest request);

    record ExactSnapshotRequest(
            long tenantId,
            UUID snapshotId,
            long snapshotRevision,
            String payloadDigest,
            UUID correlationId,
            String purposeCode,
            String projectionId) {

        public ExactSnapshotRequest {
            if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
            Objects.requireNonNull(snapshotId, "snapshotId");
            if (snapshotRevision <= 0) {
                throw new IllegalArgumentException("snapshotRevision must be positive");
            }
            if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("payloadDigest must be canonical SHA-256");
            }
            Objects.requireNonNull(correlationId, "correlationId");
            if (!CompensationPlanInputContracts.OWNER_REFETCH_PURPOSE.equals(purposeCode)
                    || !CompensationPlanInputContracts.OWNER_REFETCH_PROJECTION.equals(
                    projectionId)) {
                throw new IllegalArgumentException("owner refetch purpose or projection differs");
            }
        }
    }

    /** A transient owner/transport failure. Broker delivery must be retried. */
    final class TemporarilyUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TemporarilyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public TemporarilyUnavailableException(String message) {
            super(message);
        }
    }

    /** A definitive owner rejection (missing, stale, superseded or unapproved). */
    final class SnapshotRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SnapshotRejectedException(String message) {
            super(message);
        }

        public SnapshotRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
