package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.DirectoryGroup;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.identity.SpaceEntitlementDtos;
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
import java.util.UUID;

@Service
public class SpaceEntitlementService {

    private static final String SOURCE_TYPE = "SPACE_MEMBERSHIP";

    private final PrincipalResourceGrantRepository grants;
    private final UserRepository users;
    private final DirectoryGroupRepository groups;
    private final ResourceRepository resources;
    private final PermissionRepository permissions;
    private final IdentityAuditService audit;

    public SpaceEntitlementService(
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
    public SpaceEntitlementDtos.SyncResult synchronize(
            Long tenantId,
            String sourceRef,
            String correlationId,
            SpaceEntitlementDtos.SyncRequest request) {
        String normalizedSource = normalizeSourceRef(sourceRef);
        String principalType = request.principalType().toUpperCase(Locale.ROOT);
        String principalRef = resolvePrincipal(
                tenantId, principalType, request.principalRef().trim());
        String resourceKey = request.resourceKey().trim().toUpperCase(Locale.ROOT);
        String permissionCode = request.permissionCode().trim().toUpperCase(Locale.ROOT);
        String action = request.action().toUpperCase(Locale.ROOT);
        String justification = request.justification().trim();

        requireActor(tenantId, request.actorId());
        grants.lockSource(tenantId, SOURCE_TYPE, normalizedSource);
        PrincipalResourceGrantRepository.GrantRecord existing = grants
                .findBySource(tenantId, SOURCE_TYPE, normalizedSource)
                .orElse(null);

        if (existing != null) {
            requireSameSubject(existing, principalType, principalRef, resourceKey, permissionCode);
        }
        if ("REVOKE".equals(action)) {
            return revoke(tenantId, normalizedSource, correlationId,
                    request.actorId(), justification, existing, principalType,
                    principalRef, resourceKey, permissionCode);
        }
        if (request.validTo() != null && !request.validTo().isAfter(OffsetDateTime.now())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The Space entitlement end time must be in the future.");
        }

        Resource resource = activeSpaceResource(
                tenantId, resourceKey, request.resourceName().trim(), request.actorId());
        Permission permission = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The requested Space permission is not registered."));
        return grant(tenantId, normalizedSource, correlationId, request,
                principalType, principalRef, resource, permission, justification, existing);
    }

    @Transactional(readOnly = true)
    public SpaceEntitlementDtos.PrincipalValidationResult validatePrincipal(
            Long tenantId,
            SpaceEntitlementDtos.PrincipalValidationRequest request) {
        requireActor(tenantId, request.actorId());
        String principalType = request.principalType().toUpperCase(Locale.ROOT);
        String suppliedRef = request.principalRef().trim();
        String canonicalRef = resolvePrincipal(tenantId, principalType, suppliedRef);
        return new SpaceEntitlementDtos.PrincipalValidationResult(
                tenantId, principalType, suppliedRef, canonicalRef, true);
    }

    private SpaceEntitlementDtos.SyncResult grant(
            Long tenantId,
            String sourceRef,
            String correlationId,
            SpaceEntitlementDtos.SyncRequest request,
            String principalType,
            String principalRef,
            Resource resource,
            Permission permission,
            String justification,
            PrincipalResourceGrantRepository.GrantRecord existing) {
        if (existing != null && "ACTIVE".equals(existing.lifecycleState())) {
            return result(existing, false);
        }
        if (existing != null) {
            if (!grants.reactivate(
                    tenantId, SOURCE_TYPE, sourceRef, request.validTo(), justification,
                    request.actorId(), existing.version())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "The Space entitlement changed after it was loaded.");
            }
            PrincipalResourceGrantRepository.GrantRecord reactivated = grants
                    .findBySource(tenantId, SOURCE_TYPE, sourceRef)
                    .orElseThrow();
            audit.success(
                    tenantId, request.actorId(), "identity.space-entitlement.reactivated",
                    "PRINCIPAL_RESOURCE_GRANT", reactivated.grantId().toString(), correlationId,
                    snapshot(existing), snapshot(reactivated));
            return result(reactivated, true);
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
                    "An active Space entitlement already grants this permission.", exception);
        }
        audit.success(
                tenantId, request.actorId(), "identity.space-entitlement.granted",
                "PRINCIPAL_RESOURCE_GRANT", created.grantId().toString(), correlationId,
                null, snapshot(created));
        return result(created, true);
    }

    private SpaceEntitlementDtos.SyncResult revoke(
            Long tenantId,
            String sourceRef,
            String correlationId,
            Long actorId,
            String reason,
            PrincipalResourceGrantRepository.GrantRecord existing,
            String principalType,
            String principalRef,
            String resourceKey,
            String permissionCode) {
        if (existing == null) {
            return new SpaceEntitlementDtos.SyncResult(
                    null, tenantId, principalType, principalRef, resourceKey,
                    permissionCode, SOURCE_TYPE, sourceRef, "ABSENT",
                    null, null, 0, false);
        }
        if (!"ACTIVE".equals(existing.lifecycleState())) {
            return result(existing, false);
        }
        if (!grants.revoke(
                tenantId, SOURCE_TYPE, sourceRef, actorId, reason, existing.version())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The Space entitlement changed after it was loaded.");
        }
        PrincipalResourceGrantRepository.GrantRecord revoked = grants
                .findBySource(tenantId, SOURCE_TYPE, sourceRef)
                .orElseThrow();
        audit.success(
                tenantId, actorId, "identity.space-entitlement.revoked",
                "PRINCIPAL_RESOURCE_GRANT", revoked.grantId().toString(), correlationId,
                snapshot(existing), snapshot(revoked));
        return result(revoked, true);
    }

    private Resource activeSpaceResource(
            Long tenantId, String resourceKey, String resourceName, Long actorId) {
        Resource resource = resources.findByTenantIdAndTypeAndKey(tenantId, "SPACE", resourceKey)
                .orElseGet(() -> {
                    Resource created = Resource.builder()
                            .tenantId(tenantId)
                            .type("SPACE")
                            .key(resourceKey)
                            .name(resourceName)
                            .enabled(true)
                            .build();
                    created.setCreatedBy(actorId);
                    created.setUpdatedBy(actorId);
                    return resources.save(created);
                });
        if (!Boolean.TRUE.equals(resource.getEnabled())) {
            resource.setEnabled(true);
        }
        if (!Objects.equals(resource.getName(), resourceName)) {
            resource.setName(resourceName);
        }
        resource.setUpdatedBy(actorId);
        return resources.save(resource);
    }

    private String resolvePrincipal(Long tenantId, String principalType, String suppliedRef) {
        if ("USER".equals(principalType)) {
            User user = resolveUser(tenantId, suppliedRef);
            if (!"ACTIVE".equals(user.getStatus())) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                        "The Space user principal is not active in this tenant.");
            }
            return user.getUserId().toString();
        }
        DirectoryGroup group = resolveGroup(tenantId, suppliedRef);
        if (!"ACTIVE".equals(group.getStatus())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The Space group principal is not active in this tenant.");
        }
        return group.getGroupId().toString();
    }

    private User resolveUser(Long tenantId, String suppliedRef) {
        try {
            return users.findByUserIdAndTenantId(Long.parseLong(suppliedRef), tenantId)
                    .orElseThrow();
        } catch (RuntimeException ignored) {
            try {
                UUID publicRef = UUID.fromString(suppliedRef);
                return users.findByTenantIdAndPersonPublicId(tenantId, publicRef)
                        .or(() -> users.findByPublicIdAndTenantId(publicRef, tenantId))
                        .orElseThrow();
            } catch (RuntimeException exception) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                        "The Space user principal could not be resolved.");
            }
        }
    }

    private DirectoryGroup resolveGroup(Long tenantId, String suppliedRef) {
        try {
            return groups.findByGroupIdAndTenantId(Long.parseLong(suppliedRef), tenantId)
                    .orElseThrow();
        } catch (RuntimeException ignored) {
            try {
                return groups.findByPublicIdAndTenantId(UUID.fromString(suppliedRef), tenantId)
                        .orElseThrow();
            } catch (RuntimeException ignoredAgain) {
                return groups.findByTenantIdAndGroupKey(tenantId, suppliedRef)
                        .orElseThrow(() -> new BaseException(
                                ErrorCode.INVALID_INPUT_VALUE,
                                "The Space group principal could not be resolved."));
            }
        }
    }

    private void requireActor(Long tenantId, Long actorId) {
        users.findByUserIdAndTenantId(actorId, tenantId)
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The Space entitlement actor is not active in this tenant."));
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
                    "The Space entitlement source is bound to a different subject or permission.");
        }
    }

    private String normalizeSourceRef(String sourceRef) {
        if (sourceRef == null) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        String normalized = sourceRef.trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The Space entitlement source reference is invalid.");
        }
        return normalized;
    }

    private SpaceEntitlementDtos.SyncResult result(
            PrincipalResourceGrantRepository.GrantRecord value,
            boolean changed) {
        return new SpaceEntitlementDtos.SyncResult(
                value.grantId().toString(), value.tenantId(), value.principalType(),
                value.principalRef(), value.resourceKey(), value.permissionCode(),
                value.sourceType(), value.sourceRef(), value.lifecycleState(),
                value.validFrom(), value.validTo(), value.version(), changed);
    }

    private Map<String, Object> snapshot(
            PrincipalResourceGrantRepository.GrantRecord value) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("grantId", value.grantId().toString());
        snapshot.put("principalType", value.principalType());
        snapshot.put("principalRef", value.principalRef());
        snapshot.put("resourceKey", value.resourceKey());
        snapshot.put("permissionCode", value.permissionCode());
        snapshot.put("sourceType", value.sourceType());
        snapshot.put("sourceRef", value.sourceRef());
        snapshot.put("lifecycleState", value.lifecycleState());
        snapshot.put("validFrom", value.validFrom());
        snapshot.put("validTo", value.validTo());
        snapshot.put("version", value.version());
        return snapshot;
    }
}
