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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SavedViewService {

    private static final Set<String> DISPOSITIONS = Set.of("TRANSFER", "RETAIN_ORPHANED");
    private static final Set<String> TRANSFER_REASONS = Set.of(
            "OFFBOARDING", "TEAM_REORGANIZATION", "OWNER_CORRECTION");
    private static final Set<String> SHARED_VIEW_ROLES = Set.of(
            "ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");
    static final String NO_AFFECTED_VIEWS = "NO_AFFECTED_VIEWS";
    static final String SOURCE_OWNER_NOT_SUCCESSOR = "SOURCE_OWNER_NOT_SUCCESSOR";
    static final String SELF_ASSIGNMENT_NOT_ALLOWED = "SELF_ASSIGNMENT_NOT_ALLOWED";
    static final String EVALUATION_UNAVAILABLE = "EVALUATION_UNAVAILABLE";
    private final SavedViewRepository repository;
    private final PlatformAuditService audit;
    private final ObjectMapper objectMapper;
    private final SavedViewSubjectDirectory subjects;
    private final SavedViewOrphanLifecycleService orphanLifecycle;
    private final SavedViewOwnershipConflictPolicy ownershipConflicts;
    private final SavedViewSurfaceAccessPolicy surfaceAccess;
    private final SavedViewTargetEligibilityPolicy targetEligibility;
    private final SavedViewInputNormalizer input;
    public SavedViewService(SavedViewRepository repository, PlatformAuditService audit,
            ObjectMapper objectMapper, SavedViewSubjectDirectory subjects,
            SavedViewOrphanLifecycleService orphanLifecycle,
            SavedViewOwnershipConflictPolicy ownershipConflicts,
            SavedViewSurfaceAccessPolicy surfaceAccess,
            SavedViewTargetEligibilityPolicy targetEligibility) {
        this.repository = repository;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.subjects = subjects;
        this.orphanLifecycle = orphanLifecycle;
        this.ownershipConflicts = ownershipConflicts;
        this.surfaceAccess = surfaceAccess;
        this.targetEligibility = targetEligibility;
        this.input = new SavedViewInputNormalizer(objectMapper);
    }
    @Transactional(readOnly = true)
    public List<SavedViewDtos.SavedView> list(
            Long tenantId,
            Long actorId,
            String permissions,
            String roles,
            String groupRefsHeader,
            String surfaceKey) {
        String surface = input.surface(surfaceKey);
        surfaceAccess.requireRead(surface, permissions);
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
            String permissions,
            String roles,
            String groupRefsHeader,
            String correlationId,
            String surfaceKey,
            SavedViewDtos.CreateRequest request) {
        String surface = input.surface(surfaceKey);
        surfaceAccess.requireWrite(surface, permissions);
        String scope = input.scope(request.scope());
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        UUID ownerGroupRef = ownerGroupRef(scope, request.ownerGroupRef(), groupRefs);
        requireSharedEditor(scope, roles);
        String name = input.name(request.name());
        Map<String, Object> configuration = input.configuration(request.configuration());
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
            throw input.nameConflict(exception);
        }
    }

    @Transactional
    public SavedViewDtos.SavedView update(
            Long tenantId,
            Long actorId,
            String permissions,
            String roles,
            String groupRefsHeader,
            String correlationId,
            UUID savedViewId,
            SavedViewDtos.UpdateRequest request) {
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        SavedViewRepository.Row before = accessible(tenantId, actorId, groupRefs, savedViewId);
        surfaceAccess.requireWrite(before.surfaceKey(), permissions);
        requireEditable(before, actorId, roles);
        String scope = input.scope(request.scope());
        requireSharedEditor(scope, roles);
        if (("PERSONAL".equals(scope) || "TEAM".equals(scope))
                && !Objects.equals(before.ownerUserId(), actorId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        UUID ownerGroupRef = ownerGroupRef(scope, request.ownerGroupRef(), groupRefs);
        try {
            if (!repository.update(
                    tenantId, actorId, savedViewId, input.name(request.name()), scope,
                    ownerGroupRef, input.configuration(request.configuration()), request.version())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            }
        } catch (DataIntegrityViolationException exception) {
            throw input.nameConflict(exception);
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
            String permissions,
            String roles,
            String groupRefsHeader,
            String correlationId,
            UUID savedViewId) {
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        SavedViewRepository.Row before = accessible(tenantId, actorId, groupRefs, savedViewId);
        surfaceAccess.requireWrite(before.surfaceKey(), permissions);
        requireEditable(before, actorId, roles);
        if (!repository.archive(tenantId, actorId, savedViewId)) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        audit.success(tenantId, actorId, "workspace.saved-view.archived", "SAVED_VIEW",
                savedViewId.toString(), correlationId, SavedViewAuditSnapshots.view(before),
                Map.of("lifecycleState", "ARCHIVED"));
    }

    @Transactional
    public SavedViewDtos.SavedView preference(
            Long tenantId,
            Long actorId,
            String permissions,
            String roles,
            String groupRefsHeader,
            UUID savedViewId,
            SavedViewDtos.PreferenceRequest request) {
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        SavedViewRepository.Row view = accessible(tenantId, actorId, groupRefs, savedViewId);
        surfaceAccess.requireWrite(view.surfaceKey(), permissions);
        repository.preference(tenantId, actorId, view.surfaceKey(), savedViewId,
                request.favorite(), request.defaultView());
        return dto(accessible(tenantId, actorId, groupRefs, savedViewId),
                actorId, sharedEditor(roles));
    }

    @Transactional
    public void markUsed(
            Long tenantId,
            Long actorId,
            String permissions,
            String groupRefsHeader,
            UUID savedViewId) {
        Set<UUID> groupRefs = groupRefs(groupRefsHeader);
        SavedViewRepository.Row view = accessible(tenantId, actorId, groupRefs, savedViewId);
        surfaceAccess.requireUse(view.surfaceKey(), permissions);
        repository.markUsed(tenantId, actorId, view.surfaceKey(), savedViewId);
    }
    @Transactional
    public SavedViewDtos.OwnershipPreview previewOwnership(
            Long tenantId,
            Long actorId,
            SavedViewDtos.OwnershipPlanRequest request) {
        OwnershipPlan plan = ownershipPlan(
                request.sourceOwnerUserId(), request.disposition(), request.targetOwnerUserId(),
                request.reasonCode(), request.reason(), request.sourceReference(),
                request.retentionUntil(), true);
        requireIndependentTarget(actorId, plan.targetOwnerUserId());
        subjects.require(tenantId, plan.sourceOwnerUserId());
        SavedViewSubjectDirectory.Subject target = "TRANSFER".equals(plan.disposition())
                ? subjects.require(tenantId, plan.targetOwnerUserId()) : null;
        List<SavedViewRepository.Row> views = repository.ownedActiveForUpdate(
                tenantId, plan.sourceOwnerUserId());
        requireEligibleTarget(views, plan, target);
        List<SavedViewDtos.OwnershipNameConflict> conflicts = ownershipConflicts
                .transferConflicts(tenantId, plan.sourceOwnerUserId(), plan.targetOwnerUserId());
        return new SavedViewDtos.OwnershipPreview(
                plan.sourceOwnerUserId(), plan.disposition(), plan.targetOwnerUserId(),
                plan.retentionUntil(), views.size(), ownershipFingerprint(views, plan),
                views.stream().map(this::candidate).toList(), conflicts, OffsetDateTime.now());
    }
    @Transactional(readOnly = true)
    public Optional<SavedViewDtos.OwnershipTransfer> resolveCompletedTransfer(
            Long tenantId,
            SavedViewDtos.OwnershipTransferRequest request) {
        NormalizedTransfer normalized = normalizedTransfer(request, false);
        return matchingTransfer(
                tenantId, normalized.request().idempotencyKey(), normalized.requestFingerprint());
    }

    @Transactional
    public SavedViewDtos.OwnershipTransfer transferOwnership(
            Long tenantId,
            Long actorId,
            String correlationId,
            SavedViewDtos.OwnershipTransferRequest request) {
        NormalizedTransfer normalizedTransfer = normalizedTransfer(request, false);
        SavedViewDtos.OwnershipTransferRequest normalized = normalizedTransfer.request();
        OwnershipPlan plan = normalizedTransfer.plan();
        repository.idempotencyLock(tenantId, normalized.idempotencyKey());
        Optional<SavedViewDtos.OwnershipTransfer> replay = matchingTransfer(
                tenantId, normalized.idempotencyKey(), normalizedTransfer.requestFingerprint());
        if (replay.isPresent()) return replay.get();
        requireIndependentTarget(actorId, plan.targetOwnerUserId());
        validateNewOwnershipPlan(plan);
        SavedViewSubjectDirectory.Subject source = subjects.require(
                tenantId, normalized.sourceOwnerUserId());
        SavedViewSubjectDirectory.Subject target = normalized.targetOwnerUserId() == null
                ? null : subjects.require(tenantId, normalized.targetOwnerUserId());
        List<SavedViewRepository.Row> views = repository.ownedActiveForUpdate(
                tenantId, plan.sourceOwnerUserId());
        requireEligibleTarget(views, plan, target);
        ownershipConflicts.requireTransferClear(
                tenantId, plan.sourceOwnerUserId(), plan.targetOwnerUserId());
        String currentFingerprint = ownershipFingerprint(views, plan);
        if (views.size() != normalized.expectedCount()
                || !currentFingerprint.equals(normalized.ownershipFingerprint())) {
            throw new BaseException(
                    ErrorCode.SAVED_VIEW_CUSTODY_STALE,
                    "Saved-view ownership changed after preview. Refresh the plan and retry.");
        }
        try {
            SavedViewDtos.OwnershipTransfer result = repository.transfer(
                    tenantId, actorId, UUID.randomUUID(), displaySnapshot(source),
                    displaySnapshot(target), normalized,
                    normalizedTransfer.requestFingerprint(), views);
            audit.success(
                    tenantId, actorId, "admin.saved-view-ownership.transferred",
                    "SAVED_VIEW_TRANSFER_BATCH", result.transferBatchId().toString(), correlationId,
                    null, SavedViewAuditSnapshots.transfer(result));
            return result;
        } catch (OptimisticLockingFailureException exception) {
            throw new BaseException(
                    ErrorCode.SAVED_VIEW_CUSTODY_STALE,
                    "Saved-view ownership changed during transfer. Refresh and retry.",
                    exception);
        } catch (DataIntegrityViolationException exception) {
            throw ownershipConflicts.conflict("PERSONAL", exception);
        }
    }

    @Transactional(readOnly = true)
    public Optional<SavedViewDtos.OrphanLifecycleResult> resolveCompletedOrphanReassign(
            Long tenantId,
            UUID savedViewId,
            SavedViewDtos.OrphanReassignRequest request) {
        return orphanLifecycle.resolve(tenantId, savedViewId, request);
    }

    @Transactional
    public SavedViewDtos.OrphanLifecycleResult reassignOrphan(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID savedViewId,
            SavedViewDtos.OrphanReassignRequest request) {
        return orphanLifecycle.reassign(tenantId, actorId, correlationId, savedViewId, request);
    }

    @Transactional
    public SavedViewDtos.OrphanLifecycleResult extendOrphanRetention(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID savedViewId,
            SavedViewDtos.OrphanRetentionRequest request) {
        return orphanLifecycle.extend(tenantId, actorId, correlationId, savedViewId, request);
    }

    @Transactional
    public SavedViewDtos.OrphanLifecycleResult archiveOrphanNow(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID savedViewId,
            SavedViewDtos.OrphanArchiveRequest request) {
        return orphanLifecycle.archive(tenantId, actorId, correlationId, savedViewId, request);
    }

    @Transactional(readOnly = true)
    public List<SavedViewDtos.OrphanedView> orphaned(Long tenantId) {
        return repository.orphaned(tenantId);
    }

    @Transactional(readOnly = true)
    public List<SavedViewDtos.CustodyCandidate> custodyCandidates(
            Long tenantId,
            Long actorId,
            List<SavedViewSubjectDirectory.DirectorySubject> directory,
            Long sourceOwnerUserId,
            UUID orphanedSavedViewId) {
        if (sourceOwnerUserId != null && orphanedSavedViewId != null) {
            throw input.invalid("Choose either a source owner or one retained saved view for target evaluation.");
        }
        if (sourceOwnerUserId != null && sourceOwnerUserId <= 0) {
            throw input.invalid("A valid source owner is required for target evaluation.");
        }
        List<SavedViewRepository.Row> affected = sourceOwnerUserId != null
                ? repository.ownedActive(tenantId, sourceOwnerUserId)
                : orphanedSavedViewId != null
                        ? repository.orphan(tenantId, orphanedSavedViewId)
                                .map(List::of).orElseGet(List::of)
                        : null;
        Map<Long, String> nameConflictReasons = orphanedSavedViewId == null
                ? Map.of()
                : ownershipConflicts.orphanCandidateReasons(
                        tenantId,
                        orphanedSavedViewId,
                        directory.stream().map(
                                SavedViewSubjectDirectory.DirectorySubject::userId).toList());
        return directory.stream()
                .map(candidate -> custodyCandidate(
                        actorId, sourceOwnerUserId, affected, candidate,
                        nameConflictReasons.get(candidate.userId())))
                .toList();
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
                        SavedViewAuditSnapshots.view(view),
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

    private SavedViewDtos.CustodyCandidate custodyCandidate(
            Long actorId,
            Long sourceOwnerUserId,
            List<SavedViewRepository.Row> affected,
            SavedViewSubjectDirectory.DirectorySubject candidate,
            String nameConflictReason) {
        if (affected == null) {
            return custodyCandidate(candidate, "NOT_EVALUATED", List.of());
        }
        Set<String> reasons = new LinkedHashSet<>();
        if (affected.isEmpty()) reasons.add(NO_AFFECTED_VIEWS);
        if (Objects.equals(candidate.userId(), sourceOwnerUserId)) {
            reasons.add(SOURCE_OWNER_NOT_SUCCESSOR);
        }
        if (Objects.equals(candidate.userId(), actorId)) {
            reasons.add(SELF_ASSIGNMENT_NOT_ALLOWED);
        }
        if (!affected.isEmpty()) {
            if (!candidate.hasCompleteEligibilityEvidence()) {
                reasons.add(EVALUATION_UNAVAILABLE);
            } else {
                reasons.addAll(targetEligibility.reasons(
                        affected, candidate.exactSnapshot()));
            }
            if (nameConflictReason != null) reasons.add(nameConflictReason);
        }
        return custodyCandidate(
                candidate,
                reasons.isEmpty() ? "ELIGIBLE" : "INELIGIBLE",
                List.copyOf(reasons));
    }

    private SavedViewDtos.CustodyCandidate custodyCandidate(
            SavedViewSubjectDirectory.DirectorySubject candidate,
            String eligibilityStatus,
            List<String> reasons) {
        return new SavedViewDtos.CustodyCandidate(
                candidate.tenantId(), candidate.userId(), candidate.publicId(),
                candidate.personPublicId(), candidate.displayName(), candidate.email(),
                candidate.jobTitle(), candidate.status(), candidate.identityPlane(),
                eligibilityStatus, reasons);
    }

    private UUID ownerGroupRef(String scope, UUID requested, Set<UUID> groupRefs) {
        if (!"TEAM".equals(scope)) {
            if (requested != null) throw input.invalid("A group owner is only valid for a team view.");
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

    private OwnershipPlan ownershipPlan(
            Long sourceOwnerUserId,
            String disposition,
            Long targetOwnerUserId,
            String reasonCode,
            String reason,
            String sourceReference,
            OffsetDateTime retentionUntil,
            boolean requireFutureRetention) {
        if (sourceOwnerUserId == null || sourceOwnerUserId <= 0) {
            throw input.invalid("A valid source owner is required.");
        }
        String normalizedDisposition = input.code(disposition);
        if (!DISPOSITIONS.contains(normalizedDisposition)) {
            throw input.invalid("Invalid saved-view ownership disposition.");
        }
        String normalizedReasonCode = input.code(reasonCode);
        if (!TRANSFER_REASONS.contains(normalizedReasonCode)) {
            throw input.invalid("Invalid saved-view ownership reason.");
        }
        String normalizedReason = input.required(
                reason, 10, 1000, "A transfer reason of at least 10 characters is required.");
        String normalizedSource = input.required(
                sourceReference, 3, 240,
                "An authoritative source reference of at least 3 characters is required.");
        if ("TRANSFER".equals(normalizedDisposition)) {
            if (targetOwnerUserId == null || targetOwnerUserId <= 0
                    || targetOwnerUserId.equals(sourceOwnerUserId) || retentionUntil != null) {
                throw input.invalid("A transfer requires a different valid target owner.");
            }
        } else if (targetOwnerUserId != null || retentionUntil == null) {
            throw input.invalid(
                    "Orphan retention requires a retention deadline and no target owner.");
        }
        OwnershipPlan plan = new OwnershipPlan(
                sourceOwnerUserId, normalizedDisposition, targetOwnerUserId,
                normalizedReasonCode, normalizedReason, normalizedSource, retentionUntil);
        if (requireFutureRetention) validateNewOwnershipPlan(plan);
        return plan;
    }

    private void validateNewOwnershipPlan(OwnershipPlan plan) {
        if (!"RETAIN_ORPHANED".equals(plan.disposition())) return;
        OffsetDateTime now = OffsetDateTime.now();
        if (!plan.retentionUntil().isAfter(now)
                || plan.retentionUntil().isAfter(now.plusDays(365))) {
            throw input.invalid("Orphan retention must end within the next 365 days.");
        }
    }

    private NormalizedTransfer normalizedTransfer(
            SavedViewDtos.OwnershipTransferRequest request,
            boolean requireFutureRetention) {
        OwnershipPlan plan = ownershipPlan(
                request.sourceOwnerUserId(), request.disposition(), request.targetOwnerUserId(),
                request.reasonCode(), request.reason(), request.sourceReference(),
                request.retentionUntil(), requireFutureRetention);
        if (request.expectedCount() <= 0) {
            throw input.invalid("At least one saved view is required for an ownership transfer.");
        }
        SavedViewDtos.OwnershipTransferRequest normalized =
                new SavedViewDtos.OwnershipTransferRequest(
                        input.idempotencyKey(request.idempotencyKey()), plan.sourceOwnerUserId(),
                        plan.disposition(), plan.targetOwnerUserId(), plan.reasonCode(),
                        plan.reason(), plan.sourceReference(), plan.retentionUntil(),
                        request.expectedCount(),
                        input.fingerprint(request.ownershipFingerprint()));
        return new NormalizedTransfer(
                plan, normalized, requestFingerprint(normalized));
    }

    private Optional<SavedViewDtos.OwnershipTransfer> matchingTransfer(
            Long tenantId, String idempotencyKey, String requestFingerprint) {
        Optional<SavedViewDtos.OwnershipTransfer> existing = repository
                .transferByIdempotency(tenantId, idempotencyKey);
        if (existing.isPresent()
                && !requestFingerprint.equals(existing.get().requestFingerprint())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used for a different ownership transfer.");
        }
        return existing;
    }

    private void requireIndependentTarget(Long actorId, Long targetOwnerUserId) {
        if (targetOwnerUserId != null && Objects.equals(actorId, targetOwnerUserId)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Administrators cannot assign saved-view custody to themselves.");
        }
    }

    private void requireEligibleTarget(
            List<SavedViewRepository.Row> views,
            OwnershipPlan plan,
            SavedViewSubjectDirectory.Subject target) {
        if (!"TRANSFER".equals(plan.disposition())) return;
        requireEligibleTarget(views, target);
    }

    private void requireEligibleTarget(
            List<SavedViewRepository.Row> views,
            SavedViewSubjectDirectory.Subject target) {
        targetEligibility.require(views, target);
    }

    private String displaySnapshot(SavedViewSubjectDirectory.Subject subject) {
        if (subject == null) return null;
        String displayName = subject.displayName() == null
                ? "" : subject.displayName().strip();
        String fallback = "User #" + subject.userId();
        String value = displayName.isEmpty() ? fallback : displayName;
        return value.substring(0, Math.min(value.length(), 240));
    }

    private String ownershipFingerprint(
            List<SavedViewRepository.Row> views,
            OwnershipPlan plan) {
        List<String> viewSnapshot = views.stream()
                .sorted(java.util.Comparator.comparing(row -> row.id().toString()))
                .map(row -> row.id() + ":" + row.version())
                .toList();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("sourceOwnerUserId", plan.sourceOwnerUserId());
        material.put("disposition", plan.disposition());
        material.put("targetOwnerUserId", plan.targetOwnerUserId());
        material.put("reasonCode", plan.reasonCode());
        material.put("reason", plan.reason());
        material.put("sourceReference", plan.sourceReference());
        material.put("retentionUntil",
                plan.retentionUntil() == null ? null : plan.retentionUntil().toString());
        material.put("views", viewSnapshot);
        try {
            return sha256(objectMapper.writeValueAsBytes(material));
        } catch (JsonProcessingException exception) {
            throw input.invalid("The ownership preview plan is not serializable.");
        }
    }

    private String requestFingerprint(Object request) {
        try {
            return sha256(objectMapper.writeValueAsBytes(request));
        } catch (JsonProcessingException exception) {
            throw input.invalid("The saved-view custody request is not serializable.");
        }
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

    private record OwnershipPlan(Long sourceOwnerUserId, String disposition,
            Long targetOwnerUserId, String reasonCode, String reason,
            String sourceReference, OffsetDateTime retentionUntil) { }

    private record NormalizedTransfer(OwnershipPlan plan,
            SavedViewDtos.OwnershipTransferRequest request, String requestFingerprint) { }

}
