package com.dwp.services.platform.registry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.reference.ReferenceLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.dwp.services.platform.registry.RegistryDtos.snapshot;

@Service
public class RegistryService {

    private final RegistryEntryRepository repository;
    private final PlatformAuditService auditService;

    public RegistryService(
            RegistryEntryRepository repository,
            PlatformAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public RegistryDtos.PageResult<RegistryDtos.RegistryEntryResponse> list(
            Long tenantId,
            RegistryType registryType,
            ReferenceLifecycle lifecycleState,
            String query,
            int page,
            int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String queryPattern = query == null || query.isBlank()
                ? null
                : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        Page<RegistryEntry> result = repository.findHeads(
                tenantId,
                registryType,
                lifecycleState,
                queryPattern,
                PageRequest.of(
                        safePage,
                        safeSize,
                        Sort.by("registryType").ascending()
                                .and(Sort.by("entryKey").ascending())));
        return new RegistryDtos.PageResult<>(
                result.stream().map(this::toResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public RegistryDtos.RegistryEntryDetail get(
            Long tenantId,
            RegistryType registryType,
            String rawEntryKey) {
        String entryKey = normalizeKey(rawEntryKey);
        List<RegistryEntry> history = repository
                .findByTenantIdAndRegistryTypeAndEntryKeyOrderByRevisionDesc(
                        tenantId,
                        registryType,
                        entryKey);
        if (history.isEmpty()) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return new RegistryDtos.RegistryEntryDetail(
                toResponse(history.get(0)),
                history.stream().map(this::toResponse).toList());
    }

    @Transactional
    public RegistryDtos.RegistryEntryResponse create(
            Long tenantId,
            Long actorId,
            String correlationId,
            RegistryDtos.CreateRegistryEntryRequest request) {
        String entryKey = normalizeKey(request.entryKey());
        if (repository.existsByTenantIdAndRegistryTypeAndEntryKey(
                tenantId,
                request.registryType(),
                entryKey)) {
            throw conflict("Registry entry already exists: " + request.registryType() + "/" + entryKey);
        }
        RegistryEntry entry = buildEntry(
                tenantId,
                request.registryType(),
                entryKey,
                1,
                request.name(),
                request.description(),
                request.ownerRef(),
                request.riskTier(),
                request.artifactVersion());
        entry = save(entry, "Registry entry already exists: " + request.registryType() + "/" + entryKey);
        auditService.success(
                tenantId,
                actorId,
                "registry-entry.created",
                "REGISTRY_ENTRY",
                targetId(entry),
                correlationId,
                null,
                snapshot(entry));
        return toResponse(entry);
    }

    @Transactional
    public RegistryDtos.RegistryEntryResponse createRevision(
            Long tenantId,
            Long actorId,
            String correlationId,
            RegistryType registryType,
            String rawEntryKey,
            RegistryDtos.CreateRegistryRevisionRequest request) {
        String entryKey = normalizeKey(rawEntryKey);
        if (repository.findByTenantIdAndRegistryTypeAndEntryKeyAndLifecycleState(
                tenantId,
                registryType,
                entryKey,
                ReferenceLifecycle.DRAFT).isPresent()) {
            throw conflict("A draft revision already exists. Activate or retire it first.");
        }
        RegistryEntry head = requireHead(tenantId, registryType, entryKey);
        RegistryEntry revision = buildEntry(
                tenantId,
                registryType,
                entryKey,
                head.getRevision() + 1,
                request.name(),
                request.description(),
                request.ownerRef(),
                request.riskTier(),
                request.artifactVersion());
        revision = save(revision, "A registry revision was created concurrently.");
        auditService.success(
                tenantId,
                actorId,
                "registry-entry.revision-created",
                "REGISTRY_ENTRY",
                targetId(revision),
                correlationId,
                snapshot(head),
                snapshot(revision));
        return toResponse(revision);
    }

    @Transactional
    public RegistryDtos.RegistryEntryResponse updateRevision(
            Long tenantId,
            Long actorId,
            String correlationId,
            RegistryType registryType,
            String rawEntryKey,
            Integer revision,
            RegistryDtos.UpdateRegistryRevisionRequest request) {
        RegistryEntry entry = requireRevision(tenantId, registryType, rawEntryKey, revision);
        requireDraft(entry);
        requireVersion(entry.getVersion(), request.version());
        Map<String, Object> before = snapshot(entry);
        applyDefinition(
                entry,
                request.name(),
                request.description(),
                request.ownerRef(),
                request.riskTier(),
                request.artifactVersion());
        entry = repository.saveAndFlush(entry);
        auditService.success(
                tenantId,
                actorId,
                "registry-entry.updated",
                "REGISTRY_ENTRY",
                targetId(entry),
                correlationId,
                before,
                snapshot(entry));
        return toResponse(entry);
    }

    @Transactional
    public RegistryDtos.RegistryEntryResponse activateRevision(
            Long tenantId,
            Long actorId,
            String correlationId,
            RegistryType registryType,
            String rawEntryKey,
            Integer revision,
            Long expectedVersion) {
        RegistryEntry entry = requireRevision(tenantId, registryType, rawEntryKey, revision);
        requireDraft(entry);
        requireVersion(entry.getVersion(), expectedVersion);
        Long draftEntryId = entry.getRegistryEntryId();

        repository.findByTenantIdAndRegistryTypeAndEntryKeyAndLifecycleState(
                        tenantId,
                        registryType,
                        entry.getEntryKey(),
                        ReferenceLifecycle.ACTIVE)
                .filter(active -> !active.getRegistryEntryId().equals(draftEntryId))
                .ifPresent(active -> supersede(tenantId, actorId, correlationId, active));

        Map<String, Object> before = snapshot(entry);
        entry.setLifecycleState(ReferenceLifecycle.ACTIVE);
        entry = repository.saveAndFlush(entry);
        auditService.success(
                tenantId,
                actorId,
                "registry-entry.activated",
                "REGISTRY_ENTRY",
                targetId(entry),
                correlationId,
                before,
                snapshot(entry));
        return toResponse(entry);
    }

    @Transactional
    public RegistryDtos.RegistryEntryResponse retireRevision(
            Long tenantId,
            Long actorId,
            String correlationId,
            RegistryType registryType,
            String rawEntryKey,
            Integer revision,
            Long expectedVersion) {
        RegistryEntry entry = requireRevision(tenantId, registryType, rawEntryKey, revision);
        requireVersion(entry.getVersion(), expectedVersion);
        if (entry.getLifecycleState() == ReferenceLifecycle.RETIRED) {
            return toResponse(entry);
        }
        Map<String, Object> before = snapshot(entry);
        entry.setLifecycleState(ReferenceLifecycle.RETIRED);
        entry = repository.saveAndFlush(entry);
        auditService.success(
                tenantId,
                actorId,
                "registry-entry.retired",
                "REGISTRY_ENTRY",
                targetId(entry),
                correlationId,
                before,
                snapshot(entry));
        return toResponse(entry);
    }

    @Transactional(readOnly = true)
    public List<RegistryDtos.RuntimeRegistryEntry> listRuntime(
            Long tenantId,
            RegistryType registryType) {
        List<RegistryEntry> entries = registryType == null
                ? repository.findByTenantIdAndLifecycleStateOrderByRegistryTypeAscNameAsc(
                        tenantId,
                        ReferenceLifecycle.ACTIVE)
                : repository.findByTenantIdAndRegistryTypeAndLifecycleStateOrderByNameAsc(
                        tenantId,
                        registryType,
                        ReferenceLifecycle.ACTIVE);
        return entries.stream().map(this::toRuntimeResponse).toList();
    }

    @Transactional(readOnly = true)
    public RegistryDtos.RuntimeRegistryEntry getRuntime(
            Long tenantId,
            RegistryType registryType,
            String rawEntryKey) {
        String entryKey = normalizeKey(rawEntryKey);
        RegistryEntry entry = repository
                .findByTenantIdAndRegistryTypeAndEntryKeyAndLifecycleState(
                        tenantId,
                        registryType,
                        entryKey,
                        ReferenceLifecycle.ACTIVE)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return toRuntimeResponse(entry);
    }

    private RegistryEntry buildEntry(
            Long tenantId,
            RegistryType registryType,
            String entryKey,
            Integer revision,
            String name,
            String description,
            String ownerRef,
            RiskTier riskTier,
            String artifactVersion) {
        RegistryEntry entry = RegistryEntry.builder()
                .tenantId(tenantId)
                .registryType(registryType)
                .entryKey(entryKey)
                .revision(revision)
                .lifecycleState(ReferenceLifecycle.DRAFT)
                .build();
        applyDefinition(entry, name, description, ownerRef, riskTier, artifactVersion);
        return entry;
    }

    private void applyDefinition(
            RegistryEntry entry,
            String name,
            String description,
            String ownerRef,
            RiskTier riskTier,
            String artifactVersion) {
        entry.setName(name.trim());
        entry.setDescription(trimToNull(description));
        entry.setOwnerRef(ownerRef.trim());
        entry.setRiskTier(riskTier);
        entry.setArtifactVersion(artifactVersion.trim());
    }

    private RegistryEntry save(RegistryEntry entry, String conflictMessage) {
        try {
            return repository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, conflictMessage, exception);
        }
    }

    private RegistryEntry requireHead(
            Long tenantId,
            RegistryType registryType,
            String entryKey) {
        return repository.findFirstByTenantIdAndRegistryTypeAndEntryKeyOrderByRevisionDesc(
                        tenantId,
                        registryType,
                        entryKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private RegistryEntry requireRevision(
            Long tenantId,
            RegistryType registryType,
            String rawEntryKey,
            Integer revision) {
        String entryKey = normalizeKey(rawEntryKey);
        return repository.findByTenantIdAndRegistryTypeAndEntryKeyAndRevision(
                        tenantId,
                        registryType,
                        entryKey,
                        revision)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void requireDraft(RegistryEntry entry) {
        if (entry.getLifecycleState() != ReferenceLifecycle.DRAFT) {
            throw conflict("Only draft registry revisions can be edited or activated.");
        }
    }

    private void requireVersion(Long actual, Long expected) {
        if (!Objects.equals(actual, expected)) {
            throw conflict("The resource changed after it was loaded. Refresh and try again.");
        }
    }

    private void supersede(
            Long tenantId,
            Long actorId,
            String correlationId,
            RegistryEntry active) {
        Map<String, Object> before = snapshot(active);
        active.setLifecycleState(ReferenceLifecycle.RETIRED);
        active = repository.saveAndFlush(active);
        auditService.success(
                tenantId,
                actorId,
                "registry-entry.superseded",
                "REGISTRY_ENTRY",
                targetId(active),
                correlationId,
                before,
                snapshot(active));
    }

    private RegistryDtos.RegistryEntryResponse toResponse(RegistryEntry entry) {
        return new RegistryDtos.RegistryEntryResponse(
                entry.getRegistryType(),
                entry.getEntryKey(),
                entry.getRevision(),
                entry.getName(),
                entry.getDescription(),
                entry.getOwnerRef(),
                entry.getRiskTier(),
                entry.getArtifactVersion(),
                entry.getLifecycleState(),
                entry.getVersion(),
                entry.getUpdatedAt(),
                entry.getUpdatedBy());
    }

    private RegistryDtos.RuntimeRegistryEntry toRuntimeResponse(RegistryEntry entry) {
        return new RegistryDtos.RuntimeRegistryEntry(
                entry.getRegistryType(),
                entry.getEntryKey(),
                entry.getRevision(),
                entry.getName(),
                entry.getDescription(),
                entry.getOwnerRef(),
                entry.getRiskTier(),
                entry.getArtifactVersion());
    }

    private String normalizeKey(String value) {
        if (value == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_.-]{0,99}")) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Invalid registry key.");
        }
        return normalized;
    }

    private String targetId(RegistryEntry entry) {
        return entry.getRegistryType() + "/" + entry.getEntryKey() + "@" + entry.getRevision();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
