package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SavedViewService {

    private static final Pattern SURFACE = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,79}$");
    private static final Pattern IDEMPOTENCY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7,119}$");
    private static final Set<String> SCOPES = Set.of("PERSONAL", "TEAM", "TENANT");
    private static final Set<String> DISPOSITIONS = Set.of("TRANSFER", "RETAIN_ORPHANED");
    private static final Set<String> TRANSFER_REASONS = Set.of(
            "OFFBOARDING", "TEAM_REORGANIZATION", "OWNER_CORRECTION");
    private static final Set<String> SHARED_VIEW_ROLES = Set.of(
            "ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");
    private static final int MAX_CONFIGURATION_BYTES = 16_384;

    private final SavedViewRepository repository;
    private final PlatformAuditService audit;
    private final ObjectMapper objectMapper;

    public SavedViewService(
            SavedViewRepository repository,
            PlatformAuditService audit,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<SavedViewDtos.SavedView> list(
            Long tenantId,
            Long actorId,
            String roles,
            String groupRefsHeader,
            String surfaceKey) {
        String surface = surface(surfaceKey);
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        boolean sharedEditor = sharedEditor(roles);
        return repository.visible(tenantId, actorId, groupRefs, surface).stream()
                .map(row -> dto(row, actorId, sharedEditor))
                .toList();
    }

    @Transactional
    public SavedViewDtos.SavedView create(
            Long tenantId,
            Long actorId,
            String roles,
            String groupRefsHeader,
            String correlationId,
            String surfaceKey,
            SavedViewDtos.CreateRequest request) {
        String surface = surface(surfaceKey);
        String scope = scope(request.scope());
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        UUID ownerGroupRef = ownerGroupRef(scope, request.ownerGroupRef(), groupRefs);
        requireSharedEditor(scope, roles);
        String name = name(request.name());
        Map<String, Object> configuration = configuration(request.configuration());
        try {
            UUID id = repository.create(
                    tenantId, actorId, surface, name, scope, ownerGroupRef, configuration);
            repository.preference(
                    tenantId, actorId, surface, id, request.favorite(), request.defaultView());
            SavedViewRepository.Row created = accessible(tenantId, actorId, groupRefs, id);
            audit.success(tenantId, actorId, "workspace.saved-view.created", "SAVED_VIEW",
                    id.toString(), correlationId, null, created);
            return dto(created, actorId, sharedEditor(roles));
        } catch (DataIntegrityViolationException exception) {
            throw nameConflict(exception);
        }
    }

    @Transactional
    public SavedViewDtos.SavedView update(
            Long tenantId,
            Long actorId,
            String roles,
            String groupRefsHeader,
            String correlationId,
            UUID savedViewId,
            SavedViewDtos.UpdateRequest request) {
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        SavedViewRepository.Row before = accessible(tenantId, actorId, groupRefs, savedViewId);
        requireEditable(before, actorId, roles);
        String scope = scope(request.scope());
        requireSharedEditor(scope, roles);
        if (("PERSONAL".equals(scope) || "TEAM".equals(scope))
                && !Objects.equals(before.ownerUserId(), actorId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        UUID ownerGroupRef = ownerGroupRef(scope, request.ownerGroupRef(), groupRefs);
        try {
            if (!repository.update(
                    tenantId, actorId, savedViewId, name(request.name()), scope,
                    ownerGroupRef, configuration(request.configuration()), request.version())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            }
        } catch (DataIntegrityViolationException exception) {
            throw nameConflict(exception);
        }
        SavedViewRepository.Row after = accessible(tenantId, actorId, groupRefs, savedViewId);
        audit.success(tenantId, actorId, "workspace.saved-view.updated", "SAVED_VIEW",
                savedViewId.toString(), correlationId, before, after);
        return dto(after, actorId, sharedEditor(roles));
    }

    @Transactional
    public void delete(
            Long tenantId,
            Long actorId,
            String roles,
            String groupRefsHeader,
            String correlationId,
            UUID savedViewId) {
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        SavedViewRepository.Row before = accessible(tenantId, actorId, groupRefs, savedViewId);
        requireEditable(before, actorId, roles);
        if (!repository.archive(tenantId, actorId, savedViewId)) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        audit.success(tenantId, actorId, "workspace.saved-view.archived", "SAVED_VIEW",
                savedViewId.toString(), correlationId, summary(before),
                Map.of("lifecycleState", "ARCHIVED"));
    }

    @Transactional
    public SavedViewDtos.SavedView preference(
            Long tenantId,
            Long actorId,
            String roles,
            String groupRefsHeader,
            UUID savedViewId,
            SavedViewDtos.PreferenceRequest request) {
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        SavedViewRepository.Row view = accessible(tenantId, actorId, groupRefs, savedViewId);
        repository.preference(tenantId, actorId, view.surfaceKey(), savedViewId,
                request.favorite(), request.defaultView());
        return dto(accessible(tenantId, actorId, groupRefs, savedViewId),
                actorId, sharedEditor(roles));
    }

    @Transactional
    public void markUsed(
            Long tenantId,
            Long actorId,
            String groupRefsHeader,
            UUID savedViewId) {
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        SavedViewRepository.Row view = accessible(tenantId, actorId, groupRefs, savedViewId);
        repository.markUsed(tenantId, actorId, view.surfaceKey(), savedViewId);
    }

    @Transactional
    public SavedViewDtos.OwnershipPreview previewOwnership(
            Long tenantId,
            SavedViewDtos.OwnershipPlanRequest request) {
        OwnershipPlan plan = ownershipPlan(
                request.sourceOwnerUserId(), request.disposition(), request.targetOwnerUserId(),
                request.reasonCode(), request.reason(), request.sourceReference(),
                request.retentionUntil());
        List<SavedViewRepository.Row> views = repository.ownedActiveForUpdate(
                tenantId, plan.sourceOwnerUserId());
        return new SavedViewDtos.OwnershipPreview(
                plan.sourceOwnerUserId(), plan.disposition(), plan.targetOwnerUserId(),
                plan.retentionUntil(), views.size(), ownershipFingerprint(views),
                views.stream().map(this::candidate).toList(), OffsetDateTime.now());
    }

    @Transactional
    public SavedViewDtos.OwnershipTransfer transferOwnership(
            Long tenantId,
            Long actorId,
            String correlationId,
            SavedViewDtos.OwnershipTransferRequest request) {
        OwnershipPlan plan = ownershipPlan(
                request.sourceOwnerUserId(), request.disposition(), request.targetOwnerUserId(),
                request.reasonCode(), request.reason(), request.sourceReference(),
                request.retentionUntil());
        String idempotencyKey = idempotencyKey(request.idempotencyKey());
        SavedViewDtos.OwnershipTransferRequest normalized =
                new SavedViewDtos.OwnershipTransferRequest(
                        idempotencyKey, plan.sourceOwnerUserId(), plan.disposition(),
                        plan.targetOwnerUserId(), plan.reasonCode(), plan.reason(),
                        plan.sourceReference(), plan.retentionUntil(), request.expectedCount(),
                        normalizedFingerprint(request.ownershipFingerprint()));
        String requestFingerprint = requestFingerprint(normalized);
        repository.idempotencyLock(tenantId, idempotencyKey);
        SavedViewDtos.OwnershipTransfer existing = repository
                .transferByIdempotency(tenantId, idempotencyKey).orElse(null);
        if (existing != null) {
            if (!requestFingerprint.equals(existing.requestFingerprint())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The idempotency key was already used for a different ownership transfer.");
            }
            return existing;
        }
        List<SavedViewRepository.Row> views = repository.ownedActiveForUpdate(
                tenantId, plan.sourceOwnerUserId());
        String currentFingerprint = ownershipFingerprint(views);
        if (views.size() != normalized.expectedCount()
                || !currentFingerprint.equals(normalized.ownershipFingerprint())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Saved-view ownership changed after preview. Refresh the plan and retry.");
        }
        try {
            SavedViewDtos.OwnershipTransfer result = repository.transfer(
                    tenantId, actorId, UUID.randomUUID(), normalized, requestFingerprint, views);
            audit.success(
                    tenantId, actorId, "admin.saved-view-ownership.transferred",
                    "SAVED_VIEW_TRANSFER_BATCH", result.transferBatchId().toString(), correlationId,
                    null, transferSummary(result));
            return result;
        } catch (OptimisticLockingFailureException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Saved-view ownership changed during transfer. Refresh and retry.",
                    exception);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The target owner already has a conflicting active saved view.",
                    exception);
        }
    }

    @Transactional(readOnly = true)
    public List<SavedViewDtos.OrphanedView> orphaned(Long tenantId) {
        return repository.orphaned(tenantId);
    }

    @Transactional(readOnly = true)
    public List<SavedViewDtos.OwnershipTransferSummary> ownershipTransfers(
            Long tenantId, int limit) {
        return repository.transfers(tenantId, Math.max(1, Math.min(limit, 100)));
    }

    @Transactional
    public int archiveExpiredOrphans(OffsetDateTime now) {
        List<SavedViewRepository.RetentionRow> expired = repository.expiredOrphansForUpdate(now);
        int archived = 0;
        for (SavedViewRepository.RetentionRow candidate : expired) {
            SavedViewRepository.Row view = candidate.view();
            if (repository.archiveOrphan(
                    candidate.tenantId(), view.id(), view.version(), now)) {
                audit.serviceSuccess(
                        candidate.tenantId(),
                        "workspace.saved-view.retention-expired",
                        "SAVED_VIEW",
                        view.id().toString(),
                        null,
                        summary(view),
                        Map.of("lifecycleState", "ARCHIVED"));
                archived++;
            }
        }
        return archived;
    }

    private SavedViewRepository.Row accessible(
            Long tenantId, Long actorId, Set<UUID> groupRefs, UUID id) {
        SavedViewRepository.Row row = repository.find(tenantId, actorId, id)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        boolean accessible = "ACTIVE".equals(row.lifecycleState())
                && (Objects.equals(row.ownerUserId(), actorId)
                || "TENANT".equals(row.scope())
                || ("TEAM".equals(row.scope()) && groupRefs.contains(row.ownerGroupRef())));
        if (!accessible) throw new BaseException(ErrorCode.NOT_FOUND);
        return row;
    }

    private void requireEditable(SavedViewRepository.Row row, Long actorId, String roles) {
        if (("PERSONAL".equals(row.scope()) || "TEAM".equals(row.scope()))
                && Objects.equals(row.ownerUserId(), actorId)) return;
        if ("TENANT".equals(row.scope()) && sharedEditor(roles)) return;
        throw new BaseException(ErrorCode.FORBIDDEN);
    }

    private SavedViewDtos.SavedView dto(
            SavedViewRepository.Row row, Long actorId, boolean sharedEditor) {
        boolean editable = switch (row.scope()) {
            case "PERSONAL", "TEAM" -> Objects.equals(row.ownerUserId(), actorId);
            case "TENANT" -> sharedEditor;
            default -> false;
        };
        return new SavedViewDtos.SavedView(
                row.id(), row.surfaceKey(), row.name(), row.scope(), row.ownerUserId(),
                row.ownerGroupRef(), row.lifecycleState(), row.retentionUntil(), editable,
                row.favorite(), row.defaultView(), row.configuration(), row.version(),
                row.lastUsedAt(), row.createdAt(), row.updatedAt());
    }

    private SavedViewDtos.OwnershipCandidate candidate(SavedViewRepository.Row row) {
        return new SavedViewDtos.OwnershipCandidate(
                row.id(), row.surfaceKey(), row.name(), row.scope(), row.ownerGroupRef(),
                row.version(), row.updatedAt());
    }

    private Map<String, Object> summary(SavedViewRepository.Row row) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("savedViewId", row.id());
        summary.put("surfaceKey", row.surfaceKey());
        summary.put("scope", row.scope());
        summary.put("ownerUserId", row.ownerUserId());
        summary.put("ownerGroupRef", row.ownerGroupRef());
        summary.put("lifecycleState", row.lifecycleState());
        summary.put("version", row.version());
        return summary;
    }

    private Map<String, Object> transferSummary(SavedViewDtos.OwnershipTransfer transfer) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transferBatchId", transfer.transferBatchId());
        summary.put("sourceOwnerUserId", transfer.sourceOwnerUserId());
        summary.put("targetOwnerUserId", transfer.targetOwnerUserId());
        summary.put("disposition", transfer.disposition());
        summary.put("reasonCode", transfer.reasonCode());
        summary.put("sourceReference", transfer.sourceReference());
        summary.put("retentionUntil", transfer.retentionUntil());
        summary.put("transferredCount", transfer.transferredCount());
        summary.put("ownershipFingerprint", transfer.ownershipFingerprint());
        return summary;
    }

    private String surface(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SURFACE.matcher(normalized).matches()) {
            throw invalid("Invalid saved-view surface key.");
        }
        return normalized;
    }

    private String scope(String value) {
        String normalized = code(value);
        if (!SCOPES.contains(normalized)) throw invalid("Invalid saved-view scope.");
        return normalized;
    }

    private UUID ownerGroupRef(String scope, UUID requested, Set<UUID> groupRefs) {
        if (!"TEAM".equals(scope)) {
            if (requested != null) throw invalid("A group owner is only valid for a team view.");
            return null;
        }
        if (requested == null || !groupRefs.contains(requested)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "A team view must be owned by one of the actor's verified groups.");
        }
        return requested;
    }

    private Set<UUID> groupRefs(String header) {
        if (header == null || header.isBlank()) return Set.of();
        try {
            return Arrays.stream(header.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .limit(201)
                    .map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException exception) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN, "Verified group context is invalid.", exception);
        }
    }

    private String name(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw invalid("Invalid saved-view name.");
        }
        return normalized;
    }

    private Map<String, Object> configuration(Map<String, Object> value) {
        Map<String, Object> normalized = value == null ? Map.of() : new LinkedHashMap<>(value);
        try {
            if (objectMapper.writeValueAsBytes(normalized).length > MAX_CONFIGURATION_BYTES) {
                throw invalid("Saved-view configuration exceeds the 16 KiB limit.");
            }
            return normalized;
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Saved-view configuration is not valid JSON.",
                    exception);
        }
    }

    private OwnershipPlan ownershipPlan(
            Long sourceOwnerUserId,
            String disposition,
            Long targetOwnerUserId,
            String reasonCode,
            String reason,
            String sourceReference,
            OffsetDateTime retentionUntil) {
        if (sourceOwnerUserId == null || sourceOwnerUserId <= 0) {
            throw invalid("A valid source owner is required.");
        }
        String normalizedDisposition = code(disposition);
        if (!DISPOSITIONS.contains(normalizedDisposition)) {
            throw invalid("Invalid saved-view ownership disposition.");
        }
        String normalizedReasonCode = code(reasonCode);
        if (!TRANSFER_REASONS.contains(normalizedReasonCode)) {
            throw invalid("Invalid saved-view ownership reason.");
        }
        String normalizedReason = required(reason, 1000, "A transfer reason is required.");
        String normalizedSource = required(
                sourceReference, 240, "An authoritative source reference is required.");
        if ("TRANSFER".equals(normalizedDisposition)) {
            if (targetOwnerUserId == null || targetOwnerUserId <= 0
                    || targetOwnerUserId.equals(sourceOwnerUserId) || retentionUntil != null) {
                throw invalid("A transfer requires a different valid target owner.");
            }
        } else if (targetOwnerUserId != null || retentionUntil == null
                || !retentionUntil.isAfter(OffsetDateTime.now())) {
            throw invalid("Orphan retention requires a future retention deadline and no target owner.");
        }
        return new OwnershipPlan(
                sourceOwnerUserId, normalizedDisposition, targetOwnerUserId,
                normalizedReasonCode, normalizedReason, normalizedSource, retentionUntil);
    }

    private String ownershipFingerprint(List<SavedViewRepository.Row> views) {
        String material = views.stream()
                .sorted(java.util.Comparator.comparing(row -> row.id().toString()))
                .map(row -> row.id() + ":" + row.version())
                .collect(Collectors.joining("\n"));
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    private String requestFingerprint(SavedViewDtos.OwnershipTransferRequest request) {
        try {
            return sha256(objectMapper.writeValueAsBytes(request));
        } catch (JsonProcessingException exception) {
            throw invalid("The ownership transfer request is not serializable.");
        }
    }

    private String normalizedFingerprint(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[0-9a-f]{64}$")) {
            throw invalid("A valid ownership preview fingerprint is required.");
        }
        return normalized;
    }

    private String idempotencyKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!IDEMPOTENCY.matcher(normalized).matches()) {
            throw invalid("Invalid ownership transfer idempotency key.");
        }
        return normalized;
    }

    private String required(String value, int max, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) throw invalid(message);
        return normalized;
    }

    private String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void requireSharedEditor(String scope, String roles) {
        if ("TENANT".equals(scope) && !sharedEditor(roles)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    private boolean sharedEditor(String roles) {
        if (roles == null || roles.isBlank()) return false;
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(SHARED_VIEW_ROLES::contains);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException nameConflict(DataIntegrityViolationException exception) {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "A saved view with this name already exists in the selected scope.",
                exception);
    }

    private record OwnershipPlan(
            Long sourceOwnerUserId,
            String disposition,
            Long targetOwnerUserId,
            String reasonCode,
            String reason,
            String sourceReference,
            OffsetDateTime retentionUntil) {
    }
}
