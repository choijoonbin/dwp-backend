package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
class SavedViewOrphanLifecycleService {
    private static final Set<String> REASONS = Set.of(
            "OFFBOARDING", "TEAM_REORGANIZATION", "OWNER_CORRECTION");
    private final SavedViewRepository repository;
    private final SavedViewSubjectDirectory subjects;
    private final PlatformAuditService audit;
    private final ObjectMapper mapper;
    private final SavedViewLifecycleHistoryRepository history;
    private final SavedViewOwnershipConflictPolicy ownershipConflicts;
    private final SavedViewTargetEligibilityPolicy targetEligibility;

    SavedViewOrphanLifecycleService(SavedViewRepository repository,
            SavedViewSubjectDirectory subjects, PlatformAuditService audit, ObjectMapper mapper,
            SavedViewLifecycleHistoryRepository history,
            SavedViewOwnershipConflictPolicy ownershipConflicts,
            SavedViewTargetEligibilityPolicy targetEligibility) {
        this.repository = repository;
        this.subjects = subjects;
        this.audit = audit;
        this.mapper = mapper;
        this.history = history;
        this.ownershipConflicts = ownershipConflicts;
        this.targetEligibility = targetEligibility;
    }

    List<SavedViewDtos.OrphanLifecycleResult> actions(Long tenantId, int limit) {
        return history.latest(tenantId, Math.max(1, Math.min(limit, 100)));
    }

    Optional<SavedViewDtos.OrphanLifecycleResult> resolve(
            Long tenantId, UUID savedViewId, SavedViewDtos.OrphanReassignRequest request) {
        return matching(tenantId, reassignPlan(savedViewId, request));
    }

    SavedViewDtos.OrphanLifecycleResult reassign(Long tenantId, Long actorId,
            String correlationId, UUID savedViewId,
            SavedViewDtos.OrphanReassignRequest request) {
        Plan plan = reassignPlan(savedViewId, request);
        lock(tenantId, plan);
        Optional<SavedViewDtos.OrphanLifecycleResult> replay = matching(tenantId, plan);
        if (replay.isPresent()) return replay.get();
        if (actorId.equals(plan.targetOwnerUserId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Administrators cannot assign saved-view custody to themselves.");
        }
        SavedViewSubjectDirectory.Subject target = subjects.require(
                tenantId, plan.targetOwnerUserId());
        SavedViewRepository.Row before = required(tenantId, savedViewId, plan.version());
        eligible(before, target);
        ownershipConflicts.requireOrphanReassignClear(
                tenantId, savedViewId, plan.targetOwnerUserId());
        return apply(tenantId, actorId, correlationId, before, plan, target);
    }

    SavedViewDtos.OrphanLifecycleResult extend(Long tenantId, Long actorId,
            String correlationId, UUID savedViewId,
            SavedViewDtos.OrphanRetentionRequest request) {
        Plan plan = plan(savedViewId, request.idempotencyKey(), "EXTEND_RETENTION", null,
                request.retentionUntil(), request.version(), request.reasonCode(), request.reason(),
                request.sourceReference());
        lock(tenantId, plan);
        Optional<SavedViewDtos.OrphanLifecycleResult> replay = matching(tenantId, plan);
        if (replay.isPresent()) return replay.get();
        SavedViewRepository.Row before = required(tenantId, savedViewId, plan.version());
        OffsetDateTime now = OffsetDateTime.now();
        if (plan.nextRetentionUntil() == null || !plan.nextRetentionUntil().isAfter(now)
                || !plan.nextRetentionUntil().isAfter(before.retentionUntil())
                || plan.nextRetentionUntil().isAfter(now.plusDays(365))) {
            throw invalid("The new retention deadline must extend the current deadline and fall within 365 days.");
        }
        return apply(tenantId, actorId, correlationId, before, plan, null);
    }

    SavedViewDtos.OrphanLifecycleResult archive(Long tenantId, Long actorId,
            String correlationId, UUID savedViewId, SavedViewDtos.OrphanArchiveRequest request) {
        Plan plan = plan(savedViewId, request.idempotencyKey(), "ARCHIVE_NOW", null, null,
                request.version(), request.reasonCode(), request.reason(), request.sourceReference());
        lock(tenantId, plan);
        Optional<SavedViewDtos.OrphanLifecycleResult> replay = matching(tenantId, plan);
        if (replay.isPresent()) return replay.get();
        return apply(tenantId, actorId, correlationId,
                required(tenantId, savedViewId, plan.version()), plan, null);
    }

    private Plan reassignPlan(UUID id, SavedViewDtos.OrphanReassignRequest request) {
        if (request.targetOwnerUserId() == null || request.targetOwnerUserId() <= 0) {
            throw invalid("A valid target owner is required.");
        }
        return plan(id, request.idempotencyKey(), "REASSIGN", request.targetOwnerUserId(), null,
                request.version(), request.reasonCode(), request.reason(), request.sourceReference());
    }

    private Plan plan(UUID id, String key, String action, Long target, OffsetDateTime retention,
            long version, String reasonCode, String reason, String source) {
        if (id == null || version < 0) throw invalid("A valid retained saved view and version are required.");
        String normalizedKey = key == null ? "" : key.trim();
        String normalizedCode = reasonCode == null
                ? "" : reasonCode.trim().toUpperCase(Locale.ROOT);
        String normalizedReason = reason == null ? "" : reason.trim();
        String normalizedSource = source == null ? "" : source.trim();
        if (!normalizedKey.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{7,119}$")
                || !REASONS.contains(normalizedCode) || normalizedReason.length() < 10
                || normalizedReason.length() > 1000 || normalizedSource.length() < 3
                || normalizedSource.length() > 240) throw invalid("Invalid orphan lifecycle command.");
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("savedViewId", id); material.put("idempotencyKey", normalizedKey);
        material.put("action", action); material.put("targetOwnerUserId", target);
        material.put("nextRetentionUntil", retention == null ? null : retention.toString());
        material.put("version", version); material.put("reasonCode", normalizedCode);
        material.put("reason", normalizedReason); material.put("sourceReference", normalizedSource);
        return new Plan(id, normalizedKey, action, target, retention, version, normalizedCode,
                normalizedReason, normalizedSource, fingerprint(material));
    }

    private void lock(Long tenantId, Plan plan) {
        repository.idempotencyLock(tenantId, "saved-view-lifecycle:" + plan.idempotencyKey());
    }

    private Optional<SavedViewDtos.OrphanLifecycleResult> matching(Long tenantId, Plan plan) {
        Optional<SavedViewDtos.OrphanLifecycleResult> existing =
                repository.lifecycleByIdempotency(tenantId, plan.idempotencyKey());
        if (existing.isPresent() && (!plan.savedViewId().equals(existing.get().savedViewId())
                || !plan.action().equals(existing.get().action())
                || !plan.requestFingerprint().equals(existing.get().requestFingerprint()))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used for a different lifecycle command.");
        }
        return existing;
    }

    private SavedViewRepository.Row required(Long tenantId, UUID id, long version) {
        SavedViewRepository.Row row = repository.orphanForUpdate(tenantId, id)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.SAVED_VIEW_CUSTODY_STALE,
                        "The saved view is no longer retained and cannot be changed."));
        if (row.version() != version) {
            throw new BaseException(
                    ErrorCode.SAVED_VIEW_CUSTODY_STALE,
                    "The retained saved view changed. Refresh it and retry.");
        }
        return row;
    }

    private void eligible(SavedViewRepository.Row row, SavedViewSubjectDirectory.Subject target) {
        targetEligibility.require(List.of(row), target);
    }

    private SavedViewDtos.OrphanLifecycleResult apply(Long tenantId, Long actorId,
            String correlationId, SavedViewRepository.Row before, Plan plan,
            SavedViewSubjectDirectory.Subject target) {
        String display = displaySnapshot(target);
        SavedViewRepository.LifecycleCommand command = new SavedViewRepository.LifecycleCommand(
                plan.idempotencyKey(), plan.action(), plan.targetOwnerUserId(), display,
                plan.nextRetentionUntil(), plan.reasonCode(), plan.reason(), plan.sourceReference(),
                plan.version(), plan.requestFingerprint());
        try {
            SavedViewDtos.OrphanLifecycleResult result = repository.applyOrphanLifecycle(
                    tenantId, actorId, UUID.randomUUID(), before, command);
            String auditAction = switch (plan.action()) {
                case "REASSIGN" -> "admin.saved-view-retention.reassigned";
                case "EXTEND_RETENTION" -> "admin.saved-view-retention.extended";
                case "ARCHIVE_NOW" -> "admin.saved-view-retention.archived";
                default -> throw new IllegalArgumentException(plan.action());
            };
            audit.success(tenantId, actorId, auditAction,
                    "SAVED_VIEW", before.id().toString(), correlationId,
                    beforeSnapshot(before), resultSnapshot(result));
            return result;
        } catch (OptimisticLockingFailureException exception) {
            throw new BaseException(
                    ErrorCode.SAVED_VIEW_CUSTODY_STALE,
                    "Saved-view lifecycle changed.", exception);
        } catch (DataIntegrityViolationException exception) {
            throw ownershipConflicts.conflict(before.scope(), exception);
        }
    }

    private String displaySnapshot(SavedViewSubjectDirectory.Subject subject) {
        if (subject == null) return null;
        String displayName = subject.displayName() == null
                ? "" : subject.displayName().strip();
        String value = displayName.isEmpty() ? "User #" + subject.userId() : displayName;
        return value.substring(0, Math.min(value.length(), 240));
    }

    private Map<String, Object> beforeSnapshot(SavedViewRepository.Row row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("savedViewId", row.id());
        snapshot.put("surfaceKey", row.surfaceKey());
        snapshot.put("scope", row.scope());
        snapshot.put("ownerGroupRef", row.ownerGroupRef());
        snapshot.put("lifecycleState", row.lifecycleState());
        snapshot.put("retentionUntil", row.retentionUntil());
        snapshot.put("version", row.version());
        return snapshot;
    }

    private Map<String, Object> resultSnapshot(
            SavedViewDtos.OrphanLifecycleResult result) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("commandId", result.commandId());
        snapshot.put("savedViewName", result.savedViewName());
        snapshot.put("surfaceKey", result.surfaceKey());
        snapshot.put("scope", result.scope());
        snapshot.put("action", result.action());
        snapshot.put("targetOwnerUserId", result.targetOwnerUserId());
        snapshot.put("targetOwnerDisplayName", result.targetOwnerDisplayName());
        snapshot.put("newLifecycleState", result.newLifecycleState());
        snapshot.put("nextRetentionUntil", result.nextRetentionUntil());
        snapshot.put("reasonCode", result.reasonCode());
        snapshot.put("reason", result.reason());
        snapshot.put("sourceReference", result.sourceReference());
        snapshot.put("resultingVersion", result.resultingVersion());
        return snapshot;
    }

    private String fingerprint(Object value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(mapper.writeValueAsBytes(value))); }
        catch (JsonProcessingException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record Plan(UUID savedViewId, String idempotencyKey, String action,
            Long targetOwnerUserId, OffsetDateTime nextRetentionUntil, long version,
            String reasonCode, String reason, String sourceReference, String requestFingerprint) { }
}
