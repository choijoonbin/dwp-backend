package com.dwp.services.people.hris.performance.workforce;

import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotPage;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProjection;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQueryPort;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Loads the owner-controlled snapshot and materializes only a PER-owned projection. */
@Service
public final class PerformanceWorkforceProjectionService {

    private static final int PAGE_LIMIT = WorkforceSnapshotQuery.MAX_LIMIT;
    private static final int MAX_PAGES = 10_000;
    private static final String PERFORMANCE_CALLER = "HRIS-PER";

    private final Supplier<WorkforceSnapshotQueryPort> portSupplier;
    private final PerformanceWorkforceProjectionStore store;

    public PerformanceWorkforceProjectionService(
            ObjectProvider<WorkforceSnapshotQueryPort> portProvider,
            PerformanceWorkforceProjectionStore store) {
        this(portProvider::getIfAvailable, store);
    }

    PerformanceWorkforceProjectionService(
            Supplier<WorkforceSnapshotQueryPort> portSupplier,
            PerformanceWorkforceProjectionStore store) {
        this.portSupplier = Objects.requireNonNull(portSupplier, "portSupplier must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public PerformanceWorkforceProjection.Head refresh(long tenantId, Instant asOf) {
        return store.replaceFromOwner(loadSnapshot(tenantId, asOf), null);
    }

    public PerformanceWorkforceProjection.Head replayOwnerSnapshot(
            long tenantId,
            Instant asOf,
            UUID eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        PerformanceWorkforceProjectionStore.ReplayFence fence =
                store.beginReplay(requireTenant(tenantId), eventId);
        return store.replaceFromOwner(loadSnapshot(tenantId, asOf), fence);
    }

    public Optional<PerformanceWorkforceProjection.Head> current(long tenantId) {
        requireTenant(tenantId);
        return store.head(tenantId);
    }

    public PerformanceWorkforceProjection.Preview preview(
            long tenantId,
            PerformanceWorkforceProjection.PopulationRule rule) {
        PerformanceWorkforceProjection.Head head = store.head(requireTenant(tenantId))
                .orElseThrow(() -> notReady("No PER workforce projection exists."));
        return PerformanceWorkforceProjection.preview(head, rule);
    }

    public PerformanceWorkforceProjection.Preview requirePreview(
            long tenantId,
            PerformanceWorkforceProjection.PopulationRule rule,
            long expectedLocalRevision,
            String expectedContentHash) {
        if (expectedLocalRevision <= 0) {
            throw new IllegalArgumentException("expectedLocalRevision must be positive");
        }
        if (expectedContentHash == null
                || !PerformanceWorkforceProjection.SHA_256
                .matcher(expectedContentHash).matches()) {
            throw new IllegalArgumentException(
                    "expectedContentHash must be a lowercase SHA-256 digest");
        }
        PerformanceWorkforceProjection.Preview preview = preview(tenantId, rule);
        if (preview.localRevision() != expectedLocalRevision) {
            throw new PerformanceWorkforceProjectionException(
                    PerformanceWorkforceProjectionException.Code.STALE_WORKFORCE_SNAPSHOT,
                    "The preview is not bound to the current PER projection revision.");
        }
        if (!preview.contentHash().equals(expectedContentHash)) {
            throw new PerformanceWorkforceProjectionException(
                    PerformanceWorkforceProjectionException.Code.PREVIEW_HASH_MISMATCH,
                    "The supplied preview hash does not match the server projection.");
        }
        return preview;
    }

    PerformanceWorkforceProjection.Snapshot loadSnapshot(long tenantId, Instant asOf) {
        requireTenant(tenantId);
        Instant canonicalAsOf = PerformanceWorkforceProjection.canonicalAsOf(asOf);
        WorkforceSnapshotQueryPort port;
        try {
            port = portSupplier.get();
        } catch (RuntimeException exception) {
            throw unavailable("The HRM workforce snapshot port is unavailable.", exception);
        }
        if (port == null) {
            throw unavailable("The HRM workforce snapshot port is unavailable.", null);
        }

        List<WorkforceSnapshotV1> snapshots = new ArrayList<>();
        List<String> pageDigests = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        Set<UUID> assignments = new HashSet<>();
        String cursor = null;
        String previousPosition = null;
        Long ownerRevision = null;

        try {
            for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
                WorkforceSnapshotPage page = port.query(new WorkforceSnapshotQuery(
                        WorkforceSnapshotQuery.PURPOSE_CODE,
                        canonicalAsOf,
                        WorkforceSnapshotProjection.PERFORMANCE_V1,
                        PAGE_LIMIT,
                        cursor));
                verifyPage(tenantId, canonicalAsOf, page);
                if (ownerRevision == null) {
                    ownerRevision = page.ownerRevision();
                } else if (ownerRevision.longValue() != page.ownerRevision()) {
                    throw new PerformanceWorkforceProjectionException(
                            PerformanceWorkforceProjectionException.Code.OWNER_REVISION_CHANGED,
                            "The HRM owner revision changed during snapshot pagination.");
                }
                for (WorkforceSnapshotV1 snapshot : page.snapshots()) {
                    String position = position(snapshot);
                    if (previousPosition != null && previousPosition.compareTo(position) >= 0) {
                        throw contractMismatch(
                                "The HRM snapshot is not canonical across page boundaries.");
                    }
                    if (!assignments.add(snapshot.assignmentPublicId())) {
                        throw contractMismatch(
                                "The HRM snapshot repeats an assignment across pages.");
                    }
                    previousPosition = position;
                    snapshots.add(snapshot);
                }
                pageDigests.add(page.payloadDigest());
                cursor = page.nextCursor();
                if (cursor == null) {
                    break;
                }
                if (!cursors.add(cursor)) {
                    throw new PerformanceWorkforceProjectionException(
                            PerformanceWorkforceProjectionException.Code.OWNER_CURSOR_REPEATED,
                            "The HRM owner repeated a continuation cursor.");
                }
                if (pageNumber == MAX_PAGES - 1) {
                    throw new PerformanceWorkforceProjectionException(
                            PerformanceWorkforceProjectionException.Code.OWNER_PAGE_LIMIT_EXCEEDED,
                            "The HRM workforce snapshot exceeded the bounded page limit.");
                }
            }
        } catch (PerformanceWorkforceProjectionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("The HRM workforce snapshot query failed closed.", exception);
        }
        if (ownerRevision == null) {
            throw unavailable("The HRM workforce snapshot returned no owner revision.", null);
        }

        List<PerformanceWorkforceProjection.Member> members =
                PerformanceWorkforceProjection.canonicalMembers(snapshots);
        String contentHash = PerformanceWorkforceProjection.snapshotContentHash(
                tenantId,
                canonicalAsOf,
                ownerRevision,
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                members);
        return new PerformanceWorkforceProjection.Snapshot(
                tenantId,
                canonicalAsOf,
                ownerRevision,
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                PerformanceWorkforceProjection.pageSetDigest(pageDigests),
                contentHash,
                members);
    }

    private static void verifyPage(
            long tenantId,
            Instant asOf,
            WorkforceSnapshotPage page) {
        if (page == null
                || page.tenantId() != tenantId
                || !PERFORMANCE_CALLER.equals(page.callerModule())
                || !WorkforceSnapshotQuery.PURPOSE_CODE.equals(page.purposeCode())
                || page.projection() != WorkforceSnapshotProjection.PERFORMANCE_V1
                || !asOf.equals(page.asOf())
                || page.snapshots().size() > PAGE_LIMIT) {
            throw contractMismatch(
                    "The HRM page does not match tenant, caller, purpose, projection, asOf, or limit.");
        }
    }

    private static long requireTenant(long tenantId) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        return tenantId;
    }

    private static String position(WorkforceSnapshotV1 snapshot) {
        return snapshot.assignmentPublicId() + ":" + snapshot.workerPublicId();
    }

    private static PerformanceWorkforceProjectionException contractMismatch(String message) {
        return new PerformanceWorkforceProjectionException(
                PerformanceWorkforceProjectionException.Code.OWNER_CONTRACT_MISMATCH,
                message);
    }

    private static PerformanceWorkforceProjectionException unavailable(
            String message,
            Throwable cause) {
        return cause == null
                ? new PerformanceWorkforceProjectionException(
                PerformanceWorkforceProjectionException.Code.AUTHORITY_UNAVAILABLE,
                message)
                : new PerformanceWorkforceProjectionException(
                PerformanceWorkforceProjectionException.Code.AUTHORITY_UNAVAILABLE,
                message,
                cause);
    }

    private static PerformanceWorkforceProjectionException notReady(String message) {
        return new PerformanceWorkforceProjectionException(
                PerformanceWorkforceProjectionException.Code.PROJECTION_NOT_READY,
                message);
    }
}
