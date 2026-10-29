package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferDtos.*;

interface ControlledTransferRepository {

    Optional<Receipt> findReplay(ReplayProbe probe);

    Receipt create(PreparedTransfer transfer);

    TransferPage catalog(
            AccessSeal access,
            OffsetDateTime asOf,
            String filter,
            Cursor cursor,
            int limit);

    record ReplayProbe(
            AccessSeal access,
            OwnerModule ownerModule,
            String requestDigest) {
    }

    record PreparedTransfer(
            UUID transferPublicId,
            AccessSeal access,
            OwnerModule ownerModule,
            TransferKind transferKind,
            UUID recipientPolicyRef,
            Long egressPolicyRevision,
            String objectRef,
            String objectVersion,
            String objectDigest,
            long byteSize,
            String mediaType,
            String metadataAuthority,
            String metadataSnapshotDigest,
            String metadataAuthorityDigest,
            OffsetDateTime metadataAuthorityFreshUntil,
            String storageETag,
            UUID uploadSessionId,
            String scannerKey,
            String scannerVersion,
            OffsetDateTime scannedAt,
            String scanAuthorityDigest,
            OffsetDateTime scanAuthorityFreshUntil,
            String egressAuthorityDigest,
            OffsetDateTime egressAuthorityFreshUntil,
            Classification classification,
            OffsetDateTime expiresAt,
            String requestDigest,
            OffsetDateTime requestedAt) {
    }

    record Cursor(OffsetDateTime updatedAt, UUID publicId) {
    }

    record TransferPage(List<CatalogItem> items, Cursor nextCursor) {

        public TransferPage {
            items = List.copyOf(items);
        }
    }
}
