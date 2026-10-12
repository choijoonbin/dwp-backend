package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;

final class ReferenceConfigurationTestRepository implements ReferenceConfigurationRepository {

    final Map<String, VersionDetail> versions = new HashMap<>();
    final Map<String, TestEvidence> evidence = new HashMap<>();
    final Map<String, StoredReceipt> receipts = new HashMap<>();
    final List<String> outbox = new ArrayList<>();
    final List<String> audit = new ArrayList<>();
    RuntimeException listFailure;
    final List<UUID> findFailures = new ArrayList<>();

    @Override
    public void lockIdempotency(long tenantId, String idempotencyKey) {
        // The in-memory test double is single-threaded.
    }

    @Override
    public Optional<StoredReceipt> findReceipt(long tenantId, String idempotencyKey) {
        return Optional.ofNullable(receipts.get(tenantId + ":" + idempotencyKey));
    }

    @Override
    public void saveReceipt(
            long tenantId,
            String idempotencyKey,
            String requestDigest,
            CommandReceipt receipt) {
        receipts.put(tenantId + ":" + idempotencyKey, new StoredReceipt(requestDigest, receipt));
    }

    @Override
    public long createVersion(
            long tenantId,
            UUID configurationId,
            long actorId,
            CreateVersionCommand command,
            OffsetDateTime now) {
        long version = versions.values().stream()
                .filter(value -> value.configurationId().equals(configurationId))
                .mapToLong(VersionDetail::version)
                .max()
                .orElse(0) + 1;
        VersionDetail detail = new VersionDetail(
                configurationId,
                version,
                command.domain(),
                LifecycleState.DRAFT,
                command.effectiveFrom(),
                command.effectiveTo(),
                command.schemaKey(),
                command.schemaVersion(),
                command.payload().reference(),
                normalize(command.payload().digest()),
                null,
                0,
                actorId,
                null,
                null,
                null,
                now,
                now);
        versions.put(key(tenantId, configurationId, version), detail);
        evidence.put(key(tenantId, configurationId, version), TestEvidence.empty());
        return version;
    }

    @Override
    public Optional<VersionDetail> findVersion(long tenantId, UUID configurationId, long version) {
        if (findFailures.contains(configurationId)) {
            throw new IllegalStateException("simulated item failure");
        }
        return Optional.ofNullable(versions.get(key(tenantId, configurationId, version)));
    }

    @Override
    public List<CatalogItem> list(long tenantId, int limit) {
        if (listFailure != null) throw listFailure;
        Map<UUID, VersionDetail> latest = new HashMap<>();
        versions.forEach((key, value) -> {
            if (!key.startsWith(tenantId + ":")) return;
            latest.merge(value.configurationId(), value,
                    (left, right) -> left.version() > right.version() ? left : right);
        });
        return latest.values().stream()
                .sorted(Comparator.comparing(VersionDetail::schemaKey))
                .limit(limit)
                .map(value -> new CatalogItem(
                        value.configurationId(), value.schemaKey(), value.domain(), value.state(),
                        value.version(), value.updatedAt()))
                .toList();
    }

    @Override
    public boolean transition(
            long tenantId,
            UUID configurationId,
            long version,
            long expectedAggregateVersion,
            LifecycleState expectedState,
            LifecycleState targetState,
            TransitionEvidence evidence,
            OffsetDateTime now) {
        String key = key(tenantId, configurationId, version);
        VersionDetail current = versions.get(key);
        if (current == null
                || current.state() != expectedState
                || current.aggregateVersion() != expectedAggregateVersion) return false;
        TestEvidence priorEvidence = this.evidence.getOrDefault(key, TestEvidence.empty());
        if (targetState == LifecycleState.SIMULATED
                && !Objects.equals(priorEvidence.validationReceiptId(), evidence.validationReceiptId())) {
            return false;
        }
        if (targetState == LifecycleState.PENDING_APPROVAL
                && (!Objects.equals(priorEvidence.validationReceiptId(), evidence.validationReceiptId())
                || !Objects.equals(priorEvidence.simulationReceiptId(), evidence.simulationReceiptId()))) {
            return false;
        }
        if ((targetState == LifecycleState.SCHEDULED || targetState == LifecycleState.PUBLISHED)
                && (!Objects.equals(priorEvidence.approvalReceiptId(), evidence.approvalReceiptId())
                || !Objects.equals(priorEvidence.impactDigest(), normalize(evidence.impactDigest())))) {
            return false;
        }
        VersionDetail updated = new VersionDetail(
                current.configurationId(),
                current.version(),
                current.domain(),
                targetState,
                evidence.effectiveFrom() == null ? current.effectiveFrom() : evidence.effectiveFrom(),
                evidence.effectiveTo() == null ? current.effectiveTo() : evidence.effectiveTo(),
                current.schemaKey(),
                current.schemaVersion(),
                current.payloadReference(),
                current.payloadDigest(),
                targetState == LifecycleState.APPROVED
                        ? evidence.approvalReceiptId() : current.approvalReceiptId(),
                current.aggregateVersion() + 1,
                current.authoredBy(),
                targetState == LifecycleState.APPROVED ? evidence.actorId() : current.approvedBy(),
                evidence.scheduledAt() == null ? current.scheduledAt() : evidence.scheduledAt(),
                current.supersedesVersion(),
                current.createdAt(),
                now);
        versions.put(key, updated);
        this.evidence.put(key, new TestEvidence(
                targetState == LifecycleState.VALIDATED
                        ? evidence.validationReceiptId() : priorEvidence.validationReceiptId(),
                targetState == LifecycleState.SIMULATED
                        ? evidence.simulationReceiptId() : priorEvidence.simulationReceiptId(),
                targetState == LifecycleState.APPROVED
                        ? evidence.approvalReceiptId() : priorEvidence.approvalReceiptId(),
                targetState == LifecycleState.PENDING_APPROVAL
                        ? normalize(evidence.impactDigest()) : priorEvidence.impactDigest(),
                targetState == LifecycleState.APPROVED
                        ? normalize(evidence.approvalDecisionDigest()) : priorEvidence.approvalDecisionDigest(),
                targetState == LifecycleState.RETIRED
                        ? evidence.approvalReceiptId() : priorEvidence.retirementApprovalReceiptId()));
        return true;
    }

    @Override
    public long rollback(
            long tenantId,
            UUID configurationId,
            long currentVersion,
            long targetVersion,
            long expectedAggregateVersion,
            long actorId,
            RollbackCommand command,
            OffsetDateTime now) {
        VersionDetail current = findVersion(tenantId, configurationId, currentVersion).orElseThrow();
        VersionDetail source = findVersion(tenantId, configurationId, targetVersion).orElseThrow();
        if (current.state() != LifecycleState.PUBLISHED
                || current.aggregateVersion() != expectedAggregateVersion) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_VERSION_CONFLICT");
        }
        versions.put(key(tenantId, configurationId, currentVersion), new VersionDetail(
                current.configurationId(), current.version(), current.domain(), LifecycleState.RETIRED,
                current.effectiveFrom(), command.effectiveFrom(), current.schemaKey(),
                current.schemaVersion(), current.payloadReference(), current.payloadDigest(),
                current.approvalReceiptId(), current.aggregateVersion() + 1, current.authoredBy(),
                current.approvedBy(), current.scheduledAt(), current.supersedesVersion(),
                current.createdAt(), now));
        long next = versions.values().stream()
                .filter(value -> value.configurationId().equals(configurationId))
                .mapToLong(VersionDetail::version)
                .max().orElse(currentVersion) + 1;
        versions.put(key(tenantId, configurationId, next), new VersionDetail(
                configurationId, next, source.domain(), LifecycleState.PUBLISHED,
                command.effectiveFrom(), null, source.schemaKey(), source.schemaVersion(),
                source.payloadReference(), source.payloadDigest(), command.approvalReceiptId(),
                0, source.authoredBy(), actorId, null, currentVersion, now, now));
        TestEvidence sourceEvidence = evidence.getOrDefault(
                key(tenantId, configurationId, targetVersion), TestEvidence.empty());
        evidence.put(key(tenantId, configurationId, next), new TestEvidence(
                sourceEvidence.validationReceiptId(), sourceEvidence.simulationReceiptId(),
                command.approvalReceiptId(), normalize(command.impactDigest()), null, null));
        return next;
    }

    @Override
    public void appendOutbox(
            long tenantId,
            UUID configurationId,
            long version,
            String eventType,
            String payload,
            OffsetDateTime now) {
        outbox.add(eventType + ":" + configurationId + ":" + version + ":" + payload);
    }

    @Override
    public void appendAudit(
            long tenantId,
            UUID configurationId,
            long version,
            String action,
            long actorId,
            String correlationId,
            String evidenceDigest,
            OffsetDateTime now) {
        audit.add(action + ":" + actorId + ":" + configurationId + ":" + version);
    }

    void put(long tenantId, VersionDetail detail) {
        versions.put(key(tenantId, detail.configurationId(), detail.version()), detail);
        evidence.putIfAbsent(key(tenantId, detail.configurationId(), detail.version()), TestEvidence.empty());
    }

    private String key(long tenantId, UUID configurationId, long version) {
        return tenantId + ":" + configurationId + ":" + version;
    }

    private String normalize(String digest) {
        if (digest == null) return null;
        return digest.startsWith("sha256:") ? digest.substring(7).toLowerCase() : digest.toLowerCase();
    }

    private record TestEvidence(
            UUID validationReceiptId,
            UUID simulationReceiptId,
            UUID approvalReceiptId,
            String impactDigest,
            String approvalDecisionDigest,
            UUID retirementApprovalReceiptId) {
        static TestEvidence empty() {
            return new TestEvidence(null, null, null, null, null, null);
        }
    }
}
