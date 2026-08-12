package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SavedViewService {

    private static final Pattern SURFACE = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,79}$");
    private static final Set<String> SCOPES = Set.of("PERSONAL", "TENANT");
    private static final Set<String> SHARED_VIEW_ROLES = Set.of("ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");
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
            Long tenantId, Long actorId, String roles, String surfaceKey) {
        String surface = surface(surfaceKey);
        boolean sharedEditor = sharedEditor(roles);
        return repository.visible(tenantId, actorId, surface).stream()
                .map(row -> dto(row, actorId, sharedEditor))
                .toList();
    }

    @Transactional
    public SavedViewDtos.SavedView create(
            Long tenantId,
            Long actorId,
            String roles,
            String correlationId,
            String surfaceKey,
            SavedViewDtos.CreateRequest request) {
        String surface = surface(surfaceKey);
        String scope = scope(request.scope());
        requireSharedEditor(scope, roles);
        String name = name(request.name());
        Map<String, Object> configuration = configuration(request.configuration());
        try {
            UUID id = repository.create(tenantId, actorId, surface, name, scope, configuration);
            repository.preference(
                    tenantId, actorId, surface, id, request.favorite(), request.defaultView());
            SavedViewRepository.Row created = accessible(tenantId, actorId, id);
            audit.success(tenantId, actorId, "workspace.saved-view.created", "SAVED_VIEW",
                    id.toString(), correlationId, null, created);
            return dto(created, actorId, sharedEditor(roles));
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A saved view with this name already exists in the selected scope.",
                    exception);
        }
    }

    @Transactional
    public SavedViewDtos.SavedView update(
            Long tenantId,
            Long actorId,
            String roles,
            String correlationId,
            UUID savedViewId,
            SavedViewDtos.UpdateRequest request) {
        SavedViewRepository.Row before = accessible(tenantId, actorId, savedViewId);
        requireEditable(before, actorId, roles);
        String scope = scope(request.scope());
        requireSharedEditor(scope, roles);
        if ("PERSONAL".equals(scope) && !before.ownerUserId().equals(actorId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        try {
            if (!repository.update(tenantId, actorId, savedViewId, name(request.name()), scope,
                    configuration(request.configuration()), request.version())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A saved view with this name already exists in the selected scope.",
                    exception);
        }
        SavedViewRepository.Row after = accessible(tenantId, actorId, savedViewId);
        audit.success(tenantId, actorId, "workspace.saved-view.updated", "SAVED_VIEW",
                savedViewId.toString(), correlationId, before, after);
        return dto(after, actorId, sharedEditor(roles));
    }

    @Transactional
    public void delete(
            Long tenantId,
            Long actorId,
            String roles,
            String correlationId,
            UUID savedViewId) {
        SavedViewRepository.Row before = accessible(tenantId, actorId, savedViewId);
        requireEditable(before, actorId, roles);
        if (!repository.delete(tenantId, savedViewId)) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        audit.success(tenantId, actorId, "workspace.saved-view.deleted", "SAVED_VIEW",
                savedViewId.toString(), correlationId, before, null);
    }

    @Transactional
    public SavedViewDtos.SavedView preference(
            Long tenantId,
            Long actorId,
            String roles,
            UUID savedViewId,
            SavedViewDtos.PreferenceRequest request) {
        SavedViewRepository.Row view = accessible(tenantId, actorId, savedViewId);
        repository.preference(tenantId, actorId, view.surfaceKey(), savedViewId,
                request.favorite(), request.defaultView());
        return dto(accessible(tenantId, actorId, savedViewId), actorId, sharedEditor(roles));
    }

    @Transactional
    public void markUsed(Long tenantId, Long actorId, UUID savedViewId) {
        SavedViewRepository.Row view = accessible(tenantId, actorId, savedViewId);
        repository.markUsed(tenantId, actorId, view.surfaceKey(), savedViewId);
    }

    private SavedViewRepository.Row accessible(Long tenantId, Long actorId, UUID id) {
        SavedViewRepository.Row row = repository.find(tenantId, actorId, id)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!row.ownerUserId().equals(actorId) && !"TENANT".equals(row.scope())) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private void requireEditable(SavedViewRepository.Row row, Long actorId, String roles) {
        if ("PERSONAL".equals(row.scope()) && row.ownerUserId().equals(actorId)) return;
        if ("TENANT".equals(row.scope()) && sharedEditor(roles)) return;
        throw new BaseException(ErrorCode.FORBIDDEN);
    }

    private SavedViewDtos.SavedView dto(
            SavedViewRepository.Row row, Long actorId, boolean sharedEditor) {
        boolean editable = "PERSONAL".equals(row.scope())
                ? row.ownerUserId().equals(actorId)
                : sharedEditor;
        return new SavedViewDtos.SavedView(
                row.id(), row.surfaceKey(), row.name(), row.scope(), row.ownerUserId(), editable,
                row.favorite(), row.defaultView(), row.configuration(), row.version(),
                row.lastUsedAt(), row.createdAt(), row.updatedAt());
    }

    private String surface(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SURFACE.matcher(normalized).matches()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid saved-view surface key.");
        }
        return normalized;
    }

    private String scope(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid saved-view scope.");
        }
        return normalized;
    }

    private String name(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid saved-view name.");
        }
        return normalized;
    }

    private Map<String, Object> configuration(Map<String, Object> value) {
        Map<String, Object> normalized = value == null ? Map.of() : new LinkedHashMap<>(value);
        try {
            if (objectMapper.writeValueAsBytes(normalized).length > MAX_CONFIGURATION_BYTES) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Saved-view configuration exceeds the 16 KiB limit.");
            }
            return normalized;
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Saved-view configuration is not valid JSON.",
                    exception);
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
}
