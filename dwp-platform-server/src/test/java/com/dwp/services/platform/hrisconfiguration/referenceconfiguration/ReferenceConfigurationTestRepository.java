package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;

final class ReferenceConfigurationTestRepository implements ReferenceConfigurationRepository {

    final Map<String, VersionDetail> versions = new HashMap<>();
    final Map<String, StoredReceipt> receipts = new HashMap<>();
    final Map<String, StoredReceipt> receiptsById = new HashMap<>();
    final List<CanonicalReceipt> canonicalReceipts = new ArrayList<>();
    final List<Snapshot> history = new ArrayList<>();
    final List<String> outbox = new ArrayList<>();
    final List<String> audit = new ArrayList<>();
    RuntimeException listFailure;
    final List<UUID> findFailures = new ArrayList<>();

    @Override
    public void lockIdempotency(
            long tenantId, long subjectId, String originatingAction, String idempotencyKey) {
        // The production adapter uses an xact advisory lock; this double is single threaded.
    }

    @Override
    public Optional<StoredReceipt> findReceipt(
            long tenantId, long subjectId, String originatingAction, String idempotencyKey) {
        return Optional.ofNullable(receipts.get(receiptKey(
                tenantId, subjectId, originatingAction, idempotencyKey)));
    }

    @Override
    public Optional<StoredReceipt> findReceiptById(long tenantId, UUID receiptId) {
        return Optional.ofNullable(receiptsById.get(tenantId + ":" + receiptId));
    }

    @Override
    public void saveReceipt(
            long tenantId,
            long subjectId,
            String originatingAction,
            String idempotencyKey,
            String requestDigest,
            CommandReceipt receipt) {
        StoredReceipt stored = new StoredReceipt(requestDigest, receipt);
        receipts.put(receiptKey(tenantId, subjectId, originatingAction, idempotencyKey), stored);
        receiptsById.put(tenantId + ":" + receipt.receiptId(), stored);
    }

    @Override
    public void saveCanonicalReceipt(long tenantId, CanonicalReceipt receipt) {
        canonicalReceipts.add(receipt);
    }

    @Override
    public long createVersion(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long actorId,
            CreateVersionCommand command,
            OffsetDateTime now) {
        long version = tenantVersions(tenantId).stream()
                .filter(value -> value.configurationId().equals(configurationId))
                .mapToLong(VersionDetail::version).max().orElse(0) + 1;
        put(tenantId, new VersionDetail(
                configurationId, version, command.domain(), scope.type(), scope.id(),
                LifecycleState.DRAFT, command.effectiveFrom(), command.effectiveTo(),
                command.schemaKey(), command.schemaVersion(), command.payload().reference(),
                normalize(command.payload().digest()), null, null, null, null,
                0, actorId, null, null, null, null, now, now));
        return version;
    }

    @Override
    public Optional<VersionDetail> findVersion(
            long tenantId, PopulationScope scope, UUID configurationId, long version) {
        if (findFailures.contains(configurationId)) {
            throw new IllegalStateException("simulated item failure");
        }
        VersionDetail detail = versions.get(key(tenantId, configurationId, version));
        if (detail == null || detail.scopeType() != scope.type() || !detail.scopeId().equals(scope.id())) {
            return Optional.empty();
        }
        return Optional.of(detail);
    }

    @Override
    public CatalogPage queryCatalog(
            long tenantId,
            PopulationScope scope,
            OffsetDateTime asOf,
            CatalogFilter filter,
            UUID seekAfter,
            int limit) {
        List<WorkbenchRow> candidates = latestRows(tenantId, scope, asOf).stream()
                .filter(row -> matches(row, filter))
                .sorted(Comparator.comparing(row -> row.item().publicId()))
                .toList();
        int start = seekIndex(candidates, seekAfter);
        if (seekAfter != null && start < 0) return new CatalogPage(List.of(), false, false);
        int from = seekAfter == null ? 0 : start + 1;
        int to = Math.min(candidates.size(), from + limit);
        List<CatalogItem> items = from >= candidates.size() ? List.of()
                : candidates.subList(from, to).stream().map(WorkbenchRow::item).toList();
        return new CatalogPage(items, to < candidates.size(), true);
    }

    @Override
    public WorkbenchPage queryWorkbench(
            long tenantId,
            PopulationScope scope,
            WorkbenchQuery query,
            OffsetDateTime asOf,
            UUID seekAfter,
            int limit) {
        if (listFailure != null) throw listFailure;
        List<WorkbenchRow> candidates = latestRows(tenantId, scope, asOf).stream()
                .filter(row -> matches(row, query.filter()))
                .sorted(comparator(query.sort()))
                .map(row -> findFailures.contains(row.item().publicId())
                        ? new WorkbenchRow(row.item(), null) : row)
                .toList();
        int start = seekIndex(candidates, seekAfter);
        if (seekAfter != null && start < 0) return new WorkbenchPage(List.of(), false, false);
        int from = seekAfter == null ? 0 : start + 1;
        int to = Math.min(candidates.size(), from + limit);
        List<WorkbenchRow> rows = from >= candidates.size() ? List.of()
                : List.copyOf(candidates.subList(from, to));
        return new WorkbenchPage(rows, to < candidates.size(), true);
    }

    @Override
    public boolean transition(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long version,
            long expectedAggregateVersion,
            LifecycleState expectedState,
            LifecycleState targetState,
            TransitionEvidence evidence,
            OffsetDateTime now) {
        String key = key(tenantId, configurationId, version);
        VersionDetail current = versions.get(key);
        if (current == null || current.scopeType() != scope.type()
                || !current.scopeId().equals(scope.id()) || current.state() != expectedState
                || current.aggregateVersion() != expectedAggregateVersion) return false;
        if (targetState == LifecycleState.SIMULATED
                && (current.validation() == null || !current.validation().receiptId()
                .equals(evidence.simulation().validationReceiptId()))) return false;
        if (targetState == LifecycleState.PENDING_APPROVAL
                && (current.validation() == null || current.simulation() == null
                || !current.validation().receiptId().equals(evidence.submission().validationReceiptId())
                || !current.simulation().receiptId().equals(evidence.submission().simulationReceiptId()))) {
            return false;
        }
        if (targetState == LifecycleState.APPROVED
                && (current.submission() == null || !current.submission().receiptId()
                .equals(evidence.approval().submissionReceiptId()))) return false;
        VersionDetail updated = new VersionDetail(
                current.configurationId(), current.version(), current.domain(),
                current.scopeType(), current.scopeId(), targetState,
                evidence.effectiveFrom() == null ? current.effectiveFrom() : evidence.effectiveFrom(),
                evidence.effectiveTo() == null ? current.effectiveTo() : evidence.effectiveTo(),
                current.schemaKey(), current.schemaVersion(), current.payloadReference(),
                current.payloadDigest(),
                evidence.validation() == null ? current.validation() : evidence.validation(),
                evidence.simulation() == null ? current.simulation() : evidence.simulation(),
                evidence.submission() == null ? current.submission() : evidence.submission(),
                evidence.approval() == null ? current.approval() : evidence.approval(),
                current.aggregateVersion() + 1, current.authoredBy(),
                evidence.approval() == null ? current.approvedBy() : evidence.actorId(),
                evidence.scheduledAt() == null ? current.scheduledAt() : evidence.scheduledAt(),
                current.supersedesVersion(), evidence.replacementVersion() == null
                ? current.replacementVersion() : evidence.replacementVersion(),
                current.createdAt(), now);
        versions.put(key, updated);
        history.add(new Snapshot(tenantId, updated, now));
        return true;
    }

    @Override
    public long rollback(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long currentVersion,
            long targetVersion,
            long expectedAggregateVersion,
            long actorId,
            RollbackCommand command,
            String ownerImpactDigest,
            OffsetDateTime now) {
        VersionDetail current = findVersion(tenantId, scope, configurationId, currentVersion).orElseThrow();
        VersionDetail source = findVersion(tenantId, scope, configurationId, targetVersion).orElseThrow();
        if (current.state() != LifecycleState.PUBLISHED
                || current.aggregateVersion() != expectedAggregateVersion) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_VERSION_CONFLICT");
        }
        VersionDetail retired = new VersionDetail(
                current.configurationId(), current.version(), current.domain(), current.scopeType(),
                current.scopeId(), LifecycleState.RETIRED, current.effectiveFrom(), now,
                current.schemaKey(), current.schemaVersion(), current.payloadReference(),
                current.payloadDigest(), current.validation(), current.simulation(), current.submission(),
                current.approval(), current.aggregateVersion() + 1, current.authoredBy(),
                current.approvedBy(), current.scheduledAt(), current.supersedesVersion(),
                current.replacementVersion(), current.createdAt(), now);
        versions.put(key(tenantId, configurationId, currentVersion), retired);
        history.add(new Snapshot(tenantId, retired, now));
        long next = tenantVersions(tenantId).stream()
                .filter(value -> value.configurationId().equals(configurationId))
                .mapToLong(VersionDetail::version).max().orElse(currentVersion) + 1;
        SimulationFact copiedSimulation = source.simulation() == null ? null : new SimulationFact(
                source.simulation().receiptId(), source.simulation().validationReceiptId(),
                source.simulation().evidenceDigest(), ownerImpactDigest,
                source.simulation().producedAt(), source.simulation().freshUntil());
        ApprovalFact rollbackApproval = new ApprovalFact(
                command.approvalReceiptId(),
                source.approval() == null ? null : source.approval().submissionReceiptId(),
                source.approval() == null ? null : source.approval().decisionDigest(),
                source.approval() == null ? null : source.approval().evidenceDigest(), now);
        put(tenantId, new VersionDetail(
                configurationId, next, source.domain(), source.scopeType(), source.scopeId(),
                LifecycleState.PUBLISHED, now, null, source.schemaKey(),
                source.schemaVersion(), source.payloadReference(), source.payloadDigest(),
                source.validation(), copiedSimulation, source.submission(), rollbackApproval,
                1, source.authoredBy(), actorId, null, currentVersion, null, now, now));
        return next;
    }

    @Override
    public boolean retireAndActivateSuccessor(
            long tenantId,
            PopulationScope scope,
            UUID configurationId,
            long currentVersion,
            long currentAggregateVersion,
            long successorVersion,
            long successorAggregateVersion,
            OffsetDateTime boundary,
            String reasonCode,
            OffsetDateTime now) {
        VersionDetail current = findVersion(
                tenantId, scope, configurationId, currentVersion).orElse(null);
        VersionDetail successor = findVersion(
                tenantId, scope, configurationId, successorVersion).orElse(null);
        if (current == null || successor == null
                || current.state() != LifecycleState.PUBLISHED
                || current.aggregateVersion() != currentAggregateVersion
                || current.effectiveFrom().isAfter(now)
                || current.effectiveFrom().isAfter(boundary)
                || current.effectiveFrom().isEqual(boundary)
                || current.effectiveTo() != null && !current.effectiveTo().isAfter(now)) {
            return false;
        }
        if (successor.state() != LifecycleState.SCHEDULED
                || successor.aggregateVersion() != successorAggregateVersion
                || !successor.effectiveFrom().isEqual(boundary)
                || successor.scheduledAt() == null || successor.scheduledAt().isAfter(now)
                || successor.effectiveTo() != null && !successor.effectiveTo().isAfter(now)
                || successor.approval() == null) {
            throw new IllegalStateException(
                    "REFERENCE_CONFIGURATION_SUCCESSOR_ACTIVATION_CONFLICT");
        }
        VersionDetail retired = new VersionDetail(
                current.configurationId(), current.version(), current.domain(), current.scopeType(),
                current.scopeId(), LifecycleState.RETIRED, current.effectiveFrom(), boundary,
                current.schemaKey(), current.schemaVersion(), current.payloadReference(),
                current.payloadDigest(), current.validation(), current.simulation(), current.submission(),
                current.approval(), current.aggregateVersion() + 1, current.authoredBy(),
                current.approvedBy(), current.scheduledAt(), current.supersedesVersion(),
                successorVersion, current.createdAt(), now);
        VersionDetail activated = new VersionDetail(
                successor.configurationId(), successor.version(), successor.domain(),
                successor.scopeType(), successor.scopeId(), LifecycleState.PUBLISHED,
                successor.effectiveFrom(), successor.effectiveTo(), successor.schemaKey(),
                successor.schemaVersion(), successor.payloadReference(), successor.payloadDigest(),
                successor.validation(), successor.simulation(), successor.submission(),
                successor.approval(), successor.aggregateVersion() + 1, successor.authoredBy(),
                successor.approvedBy(), successor.scheduledAt(), successor.supersedesVersion(),
                successor.replacementVersion(), successor.createdAt(), now);
        versions.put(key(tenantId, configurationId, currentVersion), retired);
        versions.put(key(tenantId, configurationId, successorVersion), activated);
        history.add(new Snapshot(tenantId, retired, now));
        history.add(new Snapshot(tenantId, activated, now));
        return true;
    }

    @Override
    public void appendOutbox(
            long tenantId, UUID configurationId, long version,
            String eventType, String payload, OffsetDateTime now) {
        outbox.add(eventType + ":" + configurationId + ":" + version + ":" + payload);
    }

    @Override
    public void appendAudit(
            long tenantId, UUID configurationId, long version, String action,
            long actorId, String correlationId, String evidenceDigest, OffsetDateTime now) {
        audit.add(action + ":" + actorId + ":" + configurationId + ":" + version);
    }

    void put(long tenantId, VersionDetail detail) {
        versions.put(key(tenantId, detail.configurationId(), detail.version()), detail);
        history.add(new Snapshot(tenantId, detail, detail.updatedAt()));
    }

    private List<VersionDetail> tenantVersions(long tenantId) {
        return versions.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(tenantId + ":"))
                .map(Map.Entry::getValue).toList();
    }

    private List<WorkbenchRow> latestRows(
            long tenantId, PopulationScope scope, OffsetDateTime asOf) {
        Map<UUID, VersionDetail> latest = new HashMap<>();
        history.stream()
                .filter(value -> value.tenantId() == tenantId)
                .filter(value -> value.detail().scopeType() == scope.type()
                        && value.detail().scopeId().equals(scope.id()))
                .filter(value -> !value.capturedAt().isAfter(asOf))
                .map(Snapshot::detail)
                .forEach(value -> latest.merge(value.configurationId(), value,
                        (left, right) -> left.version() > right.version()
                                || left.version() == right.version()
                                && left.aggregateVersion() > right.aggregateVersion()
                                ? left : right));
        return latest.values().stream().map(detail -> new WorkbenchRow(
                new CatalogItem(detail.configurationId(), detail.schemaKey(), detail.domain(),
                        detail.state(), detail.version(), detail.updatedAt()), detail)).toList();
    }

    private boolean matches(WorkbenchRow row, CatalogFilter filter) {
        return filter == null || (filter.key() == null || filter.key().equals(row.item().key()))
                && (filter.domain() == null || filter.domain() == row.item().domain())
                && (filter.state() == null || filter.state() == row.item().state())
                && (filter.minimumVersion() == null || row.item().version() >= filter.minimumVersion());
    }

    private boolean matches(WorkbenchRow row, WorkbenchFilter filter) {
        if (filter == null) return true;
        VersionDetail detail = row.detail();
        CatalogItem item = row.item();
        return (empty(filter.policyKeys()) || filter.policyKeys().contains(detail.schemaKey()))
                && (filter.minimumPolicyRevision() == null
                || detail.version() >= filter.minimumPolicyRevision())
                && (empty(filter.referencePublicIds())
                || filter.referencePublicIds().contains(item.publicId().toString()))
                && (empty(filter.referenceCodes()) || filter.referenceCodes().contains(item.domain().name()))
                && (empty(filter.effectiveStatuses())
                || filter.effectiveStatuses().contains(item.state().name()))
                && (filter.minimumStatusRevision() == null
                || detail.version() >= filter.minimumStatusRevision())
                && (filter.effectiveOn() == null
                || !filter.effectiveOn().isBefore(detail.effectiveFrom().toLocalDate())
                && (detail.effectiveTo() == null
                || filter.effectiveOn().isBefore(detail.effectiveTo().toLocalDate())));
    }

    private Comparator<WorkbenchRow> comparator(List<String> sorts) {
        Comparator<WorkbenchRow> result = null;
        for (String sort : sorts) {
            Comparator<WorkbenchRow> next = switch (sort) {
                case "policyVersion:ASC" -> Comparator.comparingLong(row -> row.detail().version());
                case "policyVersion:DESC" -> Comparator.comparingLong(
                        (WorkbenchRow row) -> row.detail().version()).reversed();
                case "referenceData:ASC" -> Comparator.comparing(row -> row.item().key());
                case "referenceData:DESC" -> Comparator.comparing(
                        (WorkbenchRow row) -> row.item().key()).reversed();
                case "effectiveStatus:ASC" -> Comparator.comparing(row -> row.item().state().name());
                case "effectiveStatus:DESC" -> Comparator.comparing(
                        (WorkbenchRow row) -> row.item().state().name()).reversed();
                case "itemPublicId:ASC" -> Comparator.comparing(row -> row.item().publicId());
                case "itemPublicId:DESC" -> Comparator.comparing(
                        (WorkbenchRow row) -> row.item().publicId()).reversed();
                default -> throw new IllegalArgumentException("REFERENCE_CONFIGURATION_SORT_INVALID");
            };
            result = result == null ? next : result.thenComparing(next);
        }
        Comparator<WorkbenchRow> stable = Comparator.comparing(row -> row.item().publicId());
        return result == null ? stable : result.thenComparing(stable);
    }

    private int seekIndex(List<WorkbenchRow> rows, UUID seekAfter) {
        if (seekAfter == null) return -1;
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).item().publicId().equals(seekAfter)) return index;
        }
        return -1;
    }

    private boolean empty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private String receiptKey(
            long tenantId, long subjectId, String originatingAction, String idempotencyKey) {
        return tenantId + ":" + subjectId + ":" + originatingAction + ":" + idempotencyKey;
    }

    private String key(long tenantId, UUID configurationId, long version) {
        return tenantId + ":" + configurationId + ":" + version;
    }

    private String normalize(String digest) {
        if (digest == null) return null;
        return digest.startsWith("sha256:")
                ? digest.substring(7).toLowerCase() : digest.toLowerCase();
    }

    record Snapshot(long tenantId, VersionDetail detail, OffsetDateTime capturedAt) {
    }
}
