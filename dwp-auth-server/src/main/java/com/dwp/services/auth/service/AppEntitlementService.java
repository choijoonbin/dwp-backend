package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.identity.AppEntitlementDtos;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.PrincipalResourceGrantRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AppEntitlementService {

    private static final String SOURCE_TYPE = "APP_ACCESS_REQUEST";

    private final PrincipalResourceGrantRepository grants;
    private final UserRepository users;
    private final DirectoryGroupRepository groups;
    private final ResourceRepository resources;
    private final PermissionRepository permissions;
    private final IdentityAuditService audit;

    public AppEntitlementService(
            PrincipalResourceGrantRepository grants,
            UserRepository users,
            DirectoryGroupRepository groups,
            ResourceRepository resources,
            PermissionRepository permissions,
            IdentityAuditService audit) {
        this.grants = grants;
        this.users = users;
        this.groups = groups;
        this.resources = resources;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Transactional
    public AppEntitlementDtos.SyncResult synchronize(
            Long tenantId,
            String sourceRef,
            String correlationId,
            AppEntitlementDtos.SyncRequest request) {
        String normalizedSource = normalizeSourceRef(sourceRef);
        String principalType = request.principalType().toUpperCase(Locale.ROOT);
        String principalRef = request.principalRef().trim();
        String resourceKey = request.resourceKey().trim().toUpperCase(Locale.ROOT);
        String permissionCode = request.permissionCode().trim().toUpperCase(Locale.ROOT);
        String action = request.action().toUpperCase(Locale.ROOT);
        String justification = request.justification().trim();

        requireActor(tenantId, request.actorId());
        requirePrincipal(tenantId, principalType, principalRef);
        Resource resource = resources.findByTenantIdAndTypeAndKey(tenantId, "APP", resourceKey)
                .filter(value -> Boolean.TRUE.equals(value.getEnabled()))
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The application resource is not active in this tenant."));
        Permission permission = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The requested permission is not registered."));

        grants.lockSource(tenantId, SOURCE_TYPE, normalizedSource);
        PrincipalResourceGrantRepository.GrantRecord existing = grants
                .findBySource(tenantId, SOURCE_TYPE, normalizedSource)
                .orElse(null);
        if (existing != null) {
            requireSameSubject(existing, principalType, principalRef, resourceKey, permissionCode);
        }

        if ("GRANT".equals(action)) {
            return grant(
                    tenantId, normalizedSource, correlationId, request, principalType,
                    principalRef, resource, permission, justification, existing);
        }
        return revoke(
                tenantId, normalizedSource, correlationId, request.actorId(),
                justification, existing);
    }

    @Transactional
    public int expireDue(int limit) {
        var expired = grants.expireDue(Math.max(1, Math.min(500, limit)));
        for (PrincipalResourceGrantRepository.GrantRecord value : expired) {
            audit.success(
                    value.tenantId(), null, "identity.app-entitlement.expired",
                    "PRINCIPAL_RESOURCE_GRANT", value.grantId().toString(),
                    "app-entitlement-expiry:" + value.sourceRef(),
                    Map.of("lifecycleState", "ACTIVE"), snapshot(value));
        }
        return expired.size();
    }

    private AppEntitlementDtos.SyncResult grant(
            Long tenantId,
            String sourceRef,
            String correlationId,
            AppEntitlementDtos.SyncRequest request,
            String principalType,
            String principalRef,
            Resource resource,
            Permission permission,
            String justification,
            PrincipalResourceGrantRepository.GrantRecord existing) {
        if (request.validTo() != null && !request.validTo().isAfter(OffsetDateTime.now())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The entitlement end time must be in the future.");
        }
        if (existing != null) {
            if (!"ACTIVE".equals(existing.lifecycleState())) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "A terminal entitlement source cannot be granted again.");
            }
            return result(existing, false);
        }

        PrincipalResourceGrantRepository.GrantRecord created;
        try {
            created = grants.grant(
                    tenantId, principalType, principalRef, resource.getResourceId(),
                    permission.getPermissionId(), SOURCE_TYPE, sourceRef,
                    request.validTo(), justification, request.actorId());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An active entitlement already grants this application permission.",
                    exception);
        }
        audit.success(
                tenantId, request.actorId(), "identity.app-entitlement.granted",
                "PRINCIPAL_RESOURCE_GRANT", created.grantId().toString(), correlationId,
                null, snapshot(created));
        return result(created, true);
    }

    private AppEntitlementDtos.SyncResult revoke(
            Long tenantId,
            String sourceRef,
            String correlationId,
            Long actorId,
            String reason,
            PrincipalResourceGrantRepository.GrantRecord existing) {
        if (existing == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "The entitlement source does not exist.");
        }
        if (!"ACTIVE".equals(existing.lifecycleState())) {
            return result(existing, false);
        }
        if (!grants.revoke(
                tenantId, SOURCE_TYPE, sourceRef, actorId, reason, existing.version())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The entitlement changed after it was loaded.");
        }
        PrincipalResourceGrantRepository.GrantRecord revoked = grants
                .findBySource(tenantId, SOURCE_TYPE, sourceRef)
                .orElseThrow();
        audit.success(
                tenantId, actorId, "identity.app-entitlement.revoked",
                "PRINCIPAL_RESOURCE_GRANT", revoked.grantId().toString(), correlationId,
                snapshot(existing), snapshot(revoked));
        return result(revoked, true);
    }

    private void requireActor(Long tenantId, Long actorId) {
        users.findByUserIdAndTenantId(actorId, tenantId)
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The entitlement actor is not active in this tenant."));
    }

    private void requirePrincipal(Long tenantId, String principalType, String principalRef) {
        try {
            long principalId = Long.parseLong(principalRef);
            boolean active = "USER".equals(principalType)
                    ? users.findByUserIdAndTenantId(principalId, tenantId)
                            .filter(user -> "ACTIVE".equals(user.getStatus())).isPresent()
                    : groups.findByGroupIdAndTenantId(principalId, tenantId)
                            .filter(group -> "ACTIVE".equals(group.getStatus())).isPresent();
            if (!active) throw new NumberFormatException("inactive");
        } catch (NumberFormatException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The entitlement principal is not active in this tenant.");
        }
    }

    private void requireSameSubject(
            PrincipalResourceGrantRepository.GrantRecord existing,
            String principalType,
            String principalRef,
            String resourceKey,
            String permissionCode) {
        if (!Objects.equals(existing.principalType(), principalType)
                || !Objects.equals(existing.principalRef(), principalRef)
                || !Objects.equals(existing.resourceKey(), resourceKey)
                || !Objects.equals(existing.permissionCode(), permissionCode)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The entitlement source is already bound to a different subject or permission.");
        }
    }

    private String normalizeSourceRef(String sourceRef) {
        if (sourceRef == null) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        String normalized = sourceRef.trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The entitlement source reference is invalid.");
        }
        return normalized;
    }

    private AppEntitlementDtos.SyncResult result(
            PrincipalResourceGrantRepository.GrantRecord value,
            boolean changed) {
        return new AppEntitlementDtos.SyncResult(
                value.grantId().toString(), value.tenantId(), value.principalType(),
                value.principalRef(), value.resourceKey(), value.permissionCode(),
                value.sourceType(), value.sourceRef(), value.lifecycleState(),
                value.validFrom(), value.validTo(), value.version(), changed);
    }

    private Map<String, Object> snapshot(
            PrincipalResourceGrantRepository.GrantRecord value) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceType", value.sourceType());
        snapshot.put("sourceRef", value.sourceRef());
        snapshot.put("principalType", value.principalType());
        snapshot.put("principalRef", value.principalRef());
        snapshot.put("resourceKey", value.resourceKey());
        snapshot.put("permissionCode", value.permissionCode());
        snapshot.put("lifecycleState", value.lifecycleState());
        snapshot.put("validFrom", value.validFrom());
        snapshot.put("validTo", value.validTo());
        snapshot.put("version", value.version());
        return snapshot;
    }
}
