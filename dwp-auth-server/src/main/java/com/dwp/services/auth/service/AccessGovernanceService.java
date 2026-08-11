package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AccessGovernanceDtos;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.DirectoryGroup;
import com.dwp.services.auth.entity.DirectoryGroupMember;
import com.dwp.services.auth.entity.GroupRoleAssignment;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.GroupRoleAssignmentRepository;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AccessGovernanceService {

    private final RoleRepository roleRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final DirectoryGroupRepository groupRepository;
    private final DirectoryGroupMemberRepository groupMemberRepository;
    private final GroupRoleAssignmentRepository groupRoleRepository;
    private final UserRepository userRepository;
    private final AuthSessionRepository sessionRepository;
    private final IdentityAuditService auditService;
    private final RoleDelegationPolicyService delegationPolicyService;

    public AccessGovernanceService(
            RoleRepository roleRepository,
            ResourceRepository resourceRepository,
            PermissionRepository permissionRepository,
            RolePermissionRepository rolePermissionRepository,
            RoleMemberRepository roleMemberRepository,
            DirectoryGroupRepository groupRepository,
            DirectoryGroupMemberRepository groupMemberRepository,
            GroupRoleAssignmentRepository groupRoleRepository,
            UserRepository userRepository,
            AuthSessionRepository sessionRepository,
            IdentityAuditService auditService,
            RoleDelegationPolicyService delegationPolicyService) {
        this.roleRepository = roleRepository;
        this.resourceRepository = resourceRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.roleMemberRepository = roleMemberRepository;
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupRoleRepository = groupRoleRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.auditService = auditService;
        this.delegationPolicyService = delegationPolicyService;
    }

    @Transactional(readOnly = true)
    public List<AccessGovernanceDtos.RoleSummary> roles(Long tenantId) {
        List<Role> roles = roleRepository.findByTenantIdOrderByCodeAsc(tenantId);
        Map<Long, List<AccessGovernanceDtos.PermissionGrant>> grants = grantsByRole(
                tenantId, roles.stream().map(Role::getRoleId).toList());
        return roles.stream()
                .map(role -> roleSummary(role, grants.getOrDefault(role.getRoleId(), List.of())))
                .toList();
    }

    @Transactional
    public AccessGovernanceDtos.RoleSummary createRole(
            Long tenantId,
            Long actorId,
            String correlationId,
            AccessGovernanceDtos.CreateRoleRequest request) {
        delegationPolicyService.resolve(tenantId, actorId);
        if (request.privileged()) {
            auditDenied(
                    tenantId, actorId, correlationId, "ROLE", request.code(),
                    "CUSTOM_PRIVILEGED_ROLE_REQUIRES_APPROVAL");
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Privileged custom roles require a separate approval workflow.");
        }
        Role role = Role.builder()
                .tenantId(tenantId)
                .code(request.code().trim().toUpperCase(Locale.ROOT))
                .name(request.name().trim())
                .description(trimToNull(request.description()))
                .roleType("CUSTOM")
                .privileged(request.privileged())
                .assignableToGroups(request.assignableToGroups())
                .status("ACTIVE")
                .build();
        role.setCreatedBy(actorId);
        role.setUpdatedBy(actorId);
        role = saveRole(role);
        auditService.success(
                tenantId, actorId, "access.role.created", "ROLE",
                role.getRoleId().toString(), correlationId, null, roleSnapshot(role));
        return roleSummary(role, List.of());
    }

    @Transactional
    public AccessGovernanceDtos.RoleSummary updateRole(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long roleId,
            AccessGovernanceDtos.UpdateRoleRequest request) {
        Role role = requireRole(tenantId, roleId);
        requireVersion(role.getVersion(), request.version());
        delegationPolicyService.resolve(tenantId, actorId);
        if ("SYSTEM".equals(role.getRoleType())) {
            auditDenied(
                    tenantId, actorId, correlationId, "ROLE", roleId.toString(),
                    "SYSTEM_ROLE_IMMUTABLE");
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "System roles are centrally managed and cannot be changed here.");
        }
        if (request.privileged()) {
            auditDenied(
                    tenantId, actorId, correlationId, "ROLE", roleId.toString(),
                    "CUSTOM_PRIVILEGED_ROLE_REQUIRES_APPROVAL");
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Privileged custom roles require a separate approval workflow.");
        }
        Map<String, Object> before = roleSnapshot(role);
        role.setName(request.name().trim());
        role.setDescription(trimToNull(request.description()));
        role.setStatus(request.status());
        role.setPrivileged(false);
        role.setAssignableToGroups(request.assignableToGroups());
        role.setUpdatedBy(actorId);
        role = saveRole(role);
        invalidateUsersForRole(tenantId, roleId, actorId);
        auditService.success(
                tenantId, actorId, "access.role.updated", "ROLE",
                roleId.toString(), correlationId, before, roleSnapshot(role));
        return roleSummary(role, grantsByRole(tenantId, List.of(roleId)).getOrDefault(roleId, List.of()));
    }

    @Transactional
    public AccessGovernanceDtos.RoleSummary replacePermissions(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long roleId,
            AccessGovernanceDtos.ReplacePermissionsRequest request) {
        Role role = requireRole(tenantId, roleId);
        requireVersion(role.getVersion(), request.version());
        delegationPolicyService.resolve(tenantId, actorId);
        if ("SYSTEM".equals(role.getRoleType())) {
            auditDenied(
                    tenantId, actorId, correlationId, "ROLE", roleId.toString(),
                    "SYSTEM_ROLE_PERMISSIONS_IMMUTABLE");
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "System role permissions are centrally managed and cannot be changed here.");
        }
        List<RolePermission> beforePermissions = rolePermissionRepository.findByTenantIdAndRoleId(
                tenantId, roleId);
        Map<Long, Resource> resources = request.permissions().stream()
                .map(AccessGovernanceDtos.PermissionSelection::resourceId)
                .distinct()
                .map(resourceId -> resourceRepository.findAvailableById(resourceId, tenantId)
                        .orElseThrow(() -> new BaseException(
                                ErrorCode.INVALID_INPUT_VALUE,
                                "A selected resource is not available to this tenant.")))
                .collect(Collectors.toMap(Resource::getResourceId, Function.identity()));
        Set<String> codes = request.permissions().stream()
                .map(selection -> selection.permissionCode().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Permission> permissions = permissionRepository.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(Permission::getCode, Function.identity()));
        if (permissions.size() != codes.size()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "An unknown permission was selected.");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (AccessGovernanceDtos.PermissionSelection selection : request.permissions()) {
            String tuple = selection.resourceId() + ":" + selection.permissionCode();
            if (!unique.add(tuple)) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Duplicate role permission selection.");
            }
        }
        rolePermissionRepository.deleteByTenantIdAndRoleId(tenantId, roleId);
        rolePermissionRepository.flush();
        rolePermissionRepository.saveAll(request.permissions().stream()
                .map(selection -> {
                    RolePermission grant = RolePermission.builder()
                            .tenantId(tenantId)
                            .roleId(roleId)
                            .resourceId(resources.get(selection.resourceId()).getResourceId())
                            .permissionId(permissions.get(
                                    selection.permissionCode().toUpperCase(Locale.ROOT)).getPermissionId())
                            .effect(selection.effect())
                            .build();
                    grant.setCreatedBy(actorId);
                    grant.setUpdatedBy(actorId);
                    return grant;
                })
                .toList());
        role.setUpdatedBy(actorId);
        role = saveRole(role);
        invalidateUsersForRole(tenantId, roleId, actorId);
        auditService.success(
                tenantId, actorId, "access.role-permissions.replaced", "ROLE",
                roleId.toString(), correlationId,
                Map.of("grantCount", beforePermissions.size()),
                Map.of("grantCount", request.permissions().size(), "roleVersion", valueOrZero(role.getVersion())));
        return roleSummary(role, grantsByRole(tenantId, List.of(roleId)).getOrDefault(roleId, List.of()));
    }

    @Transactional(readOnly = true)
    public List<AccessGovernanceDtos.ResourceSummary> resources(Long tenantId) {
        return resourceRepository.findAvailable(tenantId).stream()
                .map(resource -> new AccessGovernanceDtos.ResourceSummary(
                        resource.getResourceId(), resource.getType(), resource.getKey(),
                        resource.getName(), Boolean.TRUE.equals(resource.getEnabled())))
                .toList();
    }

    @Transactional
    public AccessGovernanceDtos.ResourceSummary createResource(
            Long tenantId,
            Long actorId,
            String correlationId,
            AccessGovernanceDtos.CreateResourceRequest request) {
        delegationPolicyService.resolve(tenantId, actorId);
        Resource resource = Resource.builder()
                .tenantId(tenantId)
                .type(request.type())
                .key(request.key().trim())
                .name(request.name().trim())
                .enabled(true)
                .build();
        resource.setCreatedBy(actorId);
        resource.setUpdatedBy(actorId);
        try {
            resource = resourceRepository.saveAndFlush(resource);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A resource with this type and key already exists.", exception);
        }
        auditService.success(
                tenantId, actorId, "access.resource.created", "RESOURCE",
                resource.getResourceId().toString(), correlationId, null,
                Map.of("type", resource.getType(), "key", resource.getKey(), "name", resource.getName()));
        return new AccessGovernanceDtos.ResourceSummary(
                resource.getResourceId(), resource.getType(), resource.getKey(),
                resource.getName(), true);
    }

    @Transactional(readOnly = true)
    public List<AccessGovernanceDtos.GroupRoleAssignmentSummary> groupAssignments(Long tenantId) {
        List<GroupRoleAssignment> assignments = groupRoleRepository
                .findByTenantIdOrderByGroupRoleAssignmentIdAsc(tenantId);
        Map<Long, DirectoryGroup> groups = groupRepository.findAllById(
                        assignments.stream().map(GroupRoleAssignment::getGroupId).distinct().toList())
                .stream().filter(group -> tenantId.equals(group.getTenantId()))
                .collect(Collectors.toMap(DirectoryGroup::getGroupId, Function.identity()));
        Map<Long, Role> roles = roleRepository.findByRoleIdIn(
                        assignments.stream().map(GroupRoleAssignment::getRoleId).distinct().toList())
                .stream().filter(role -> tenantId.equals(role.getTenantId()))
                .collect(Collectors.toMap(Role::getRoleId, Function.identity()));
        return assignments.stream()
                .filter(assignment -> groups.containsKey(assignment.getGroupId())
                        && roles.containsKey(assignment.getRoleId()))
                .map(assignment -> assignmentSummary(
                        assignment, groups.get(assignment.getGroupId()), roles.get(assignment.getRoleId())))
                .toList();
    }

    @Transactional
    public AccessGovernanceDtos.GroupRoleAssignmentSummary createGroupAssignment(
            Long tenantId,
            Long actorId,
            String correlationId,
            AccessGovernanceDtos.CreateGroupRoleAssignmentRequest request) {
        DirectoryGroup group = groupRepository.findByGroupIdAndTenantId(request.groupId(), tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Role role = requireRole(tenantId, request.roleId());
        requireAssignableRole(tenantId, actorId, correlationId, role);
        if (!"ACTIVE".equals(group.getStatus()) || !"ACTIVE".equals(role.getStatus())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The group and role must be active.");
        }
        if (!Boolean.TRUE.equals(role.getAssignableToGroups())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "This role cannot be assigned to groups.");
        }
        String scopeRef = trimToNull(request.scopeRef());
        if (("TENANT".equals(request.scopeType()) && scopeRef != null)
                || (!"TENANT".equals(request.scopeType()) && scopeRef == null)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The access scope is invalid.");
        }
        if (request.validFrom() != null && request.validTo() != null
                && !request.validTo().isAfter(request.validFrom())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The access validity window is invalid.");
        }
        GroupRoleAssignment assignment = GroupRoleAssignment.builder()
                .tenantId(tenantId)
                .groupId(group.getGroupId())
                .roleId(role.getRoleId())
                .assignmentType(request.assignmentType())
                .scopeType(request.scopeType())
                .scopeRef(scopeRef)
                .validFrom(request.validFrom())
                .validTo(request.validTo())
                .lifecycleState("ACTIVE")
                .justification(request.justification().trim())
                .build();
        assignment.setCreatedBy(actorId);
        assignment.setUpdatedBy(actorId);
        try {
            assignment = groupRoleRepository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This active group role assignment already exists.", exception);
        }
        invalidateGroupMembers(tenantId, group.getGroupId(), actorId);
        auditService.success(
                tenantId, actorId, "access.group-role-assignment.created",
                "GROUP_ROLE_ASSIGNMENT", assignment.getGroupRoleAssignmentId().toString(),
                correlationId, null, assignmentSnapshot(assignment));
        return assignmentSummary(assignment, group, role);
    }

    @Transactional
    public AccessGovernanceDtos.GroupRoleAssignmentSummary revokeGroupAssignment(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long assignmentId,
            Long expectedVersion) {
        GroupRoleAssignment assignment = groupRoleRepository
                .findByGroupRoleAssignmentIdAndTenantId(assignmentId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(assignment.getVersion(), expectedVersion);
        DirectoryGroup group = groupRepository.findByGroupIdAndTenantId(
                        assignment.getGroupId(), tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Role role = requireRole(tenantId, assignment.getRoleId());
        requireAssignableRole(tenantId, actorId, correlationId, role);
        Map<String, Object> before = assignmentSnapshot(assignment);
        assignment.setLifecycleState("REVOKED");
        assignment.setUpdatedBy(actorId);
        assignment = groupRoleRepository.saveAndFlush(assignment);
        invalidateGroupMembers(tenantId, group.getGroupId(), actorId);
        auditService.success(
                tenantId, actorId, "access.group-role-assignment.revoked",
                "GROUP_ROLE_ASSIGNMENT", assignmentId.toString(), correlationId,
                before, assignmentSnapshot(assignment));
        return assignmentSummary(assignment, group, role);
    }

    @Transactional(readOnly = true)
    public AccessGovernanceDtos.EffectiveAccess effectiveAccess(Long tenantId, Long userId) {
        User user = userRepository.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        List<AccessGovernanceDtos.EffectiveRole> effectiveRoles = new ArrayList<>();
        List<RoleMember> direct = roleMemberRepository.findByTenantIdAndUserId(tenantId, userId);
        Map<Long, Role> roles = roleRepository.findByRoleIdIn(
                        direct.stream().map(RoleMember::getRoleId).toList())
                .stream().filter(role -> tenantId.equals(role.getTenantId())
                        && "ACTIVE".equals(role.getStatus()))
                .collect(Collectors.toMap(Role::getRoleId, Function.identity()));
        direct.forEach(member -> {
            Role role = roles.get(member.getRoleId());
            if (role != null) effectiveRoles.add(new AccessGovernanceDtos.EffectiveRole(
                    role.getRoleId(), role.getCode(), "DIRECT", null, null,
                    "TENANT", null, null));
        });

        List<DirectoryGroupMember> memberships = groupMemberRepository.findByTenantIdAndUserId(
                tenantId, userId);
        List<Long> groupIds = memberships.stream().map(DirectoryGroupMember::getGroupId).distinct().toList();
        Map<Long, DirectoryGroup> groups = groupRepository.findAllById(groupIds).stream()
                .filter(group -> tenantId.equals(group.getTenantId()) && "ACTIVE".equals(group.getStatus()))
                .collect(Collectors.toMap(DirectoryGroup::getGroupId, Function.identity()));
        List<GroupRoleAssignment> groupAssignments = groupIds.isEmpty()
                ? List.of()
                : groupRoleRepository.findByTenantIdAndGroupIdInAndLifecycleState(
                        tenantId, groupIds, "ACTIVE");
        Instant now = Instant.now();
        List<Long> groupRoleIds = groupAssignments.stream()
                .map(GroupRoleAssignment::getRoleId).distinct().toList();
        roleRepository.findByRoleIdIn(groupRoleIds).stream()
                .filter(role -> tenantId.equals(role.getTenantId()) && "ACTIVE".equals(role.getStatus()))
                .forEach(role -> roles.put(role.getRoleId(), role));
        groupAssignments.stream()
                .filter(assignment -> "ACTIVE".equals(assignment.getAssignmentType()))
                .filter(assignment -> assignment.getValidFrom() == null || !assignment.getValidFrom().isAfter(now))
                .filter(assignment -> assignment.getValidTo() == null || assignment.getValidTo().isAfter(now))
                .filter(assignment -> groups.containsKey(assignment.getGroupId()))
                .forEach(assignment -> {
                    Role role = roles.get(assignment.getRoleId());
                    DirectoryGroup group = groups.get(assignment.getGroupId());
                    if (role != null) effectiveRoles.add(new AccessGovernanceDtos.EffectiveRole(
                            role.getRoleId(), role.getCode(), "GROUP",
                            group.getGroupId(), group.getDisplayName(),
                            assignment.getScopeType(), assignment.getScopeRef(), assignment.getValidTo()));
                });

        Map<Long, List<String>> roleCodesById = effectiveRoles.stream()
                .collect(Collectors.groupingBy(
                        AccessGovernanceDtos.EffectiveRole::roleId,
                        LinkedHashMap::new,
                        Collectors.mapping(AccessGovernanceDtos.EffectiveRole::roleCode, Collectors.toList())));
        Map<Long, List<AccessGovernanceDtos.PermissionGrant>> grantsByRole = grantsByRole(
                tenantId, new ArrayList<>(roleCodesById.keySet()));
        Map<String, List<RoleGrant>> byPermission = new LinkedHashMap<>();
        grantsByRole.forEach((roleId, roleGrants) -> roleGrants.forEach(grant ->
                byPermission.computeIfAbsent(
                                grant.resourceType() + ":" + grant.resourceKey() + ":" + grant.permissionCode(),
                                ignored -> new ArrayList<>())
                        .add(new RoleGrant(roleId, grant))));
        List<AccessGovernanceDtos.EffectivePermission> permissions = byPermission.values().stream()
                .map(values -> {
                    AccessGovernanceDtos.PermissionGrant first = values.get(0).grant();
                    boolean denied = values.stream()
                            .anyMatch(value -> "DENY".equals(value.grant().effect()));
                    List<String> grantedBy = values.stream()
                            .map(RoleGrant::roleId)
                            .map(roleCodesById::get)
                            .filter(Objects::nonNull)
                            .flatMap(Collection::stream)
                            .distinct().sorted().toList();
                    return new AccessGovernanceDtos.EffectivePermission(
                            first.resourceType(), first.resourceKey(), first.permissionCode(),
                            denied ? "DENY" : "ALLOW", grantedBy);
                })
                .sorted(Comparator.comparing(AccessGovernanceDtos.EffectivePermission::resourceKey)
                        .thenComparing(AccessGovernanceDtos.EffectivePermission::permissionCode))
                .toList();
        return new AccessGovernanceDtos.EffectiveAccess(
                userId, user.getDisplayName(), valueOrZero(user.getAccessRevision()),
                effectiveRoles, permissions);
    }

    private record RoleGrant(Long roleId, AccessGovernanceDtos.PermissionGrant grant) {
    }

    private Map<Long, List<AccessGovernanceDtos.PermissionGrant>> grantsByRole(
            Long tenantId,
            List<Long> roleIds) {
        if (roleIds.isEmpty()) return Map.of();
        List<RolePermission> rolePermissions = rolePermissionRepository
                .findByTenantIdAndRoleIdInAndEffect(tenantId, roleIds, "ALLOW");
        List<RolePermission> denied = rolePermissionRepository
                .findByTenantIdAndRoleIdInAndEffect(tenantId, roleIds, "DENY");
        List<RolePermission> all = new ArrayList<>(rolePermissions);
        all.addAll(denied);
        Map<Long, Resource> resources = resourceRepository.findAllById(
                        all.stream().map(RolePermission::getResourceId).distinct().toList())
                .stream().filter(resource -> resource.getTenantId() == null
                        || tenantId.equals(resource.getTenantId()))
                .collect(Collectors.toMap(Resource::getResourceId, Function.identity()));
        Map<Long, Permission> permissions = permissionRepository.findAllById(
                        all.stream().map(RolePermission::getPermissionId).distinct().toList())
                .stream().collect(Collectors.toMap(Permission::getPermissionId, Function.identity()));
        return all.stream()
                .filter(grant -> resources.containsKey(grant.getResourceId())
                        && permissions.containsKey(grant.getPermissionId()))
                .collect(Collectors.groupingBy(
                        RolePermission::getRoleId,
                        LinkedHashMap::new,
                        Collectors.mapping(grant -> {
                            Resource resource = resources.get(grant.getResourceId());
                            Permission permission = permissions.get(grant.getPermissionId());
                            return new AccessGovernanceDtos.PermissionGrant(
                                    resource.getResourceId(), resource.getType(), resource.getKey(),
                                    resource.getName(), permission.getCode(), grant.getEffect());
                        }, Collectors.toList())));
    }

    private AccessGovernanceDtos.RoleSummary roleSummary(
            Role role,
            List<AccessGovernanceDtos.PermissionGrant> permissions) {
        return new AccessGovernanceDtos.RoleSummary(
                role.getRoleId(), role.getCode(), role.getName(), role.getDescription(),
                role.getRoleType(), Boolean.TRUE.equals(role.getPrivileged()),
                Boolean.TRUE.equals(role.getAssignableToGroups()), role.getStatus(),
                valueOrZero(role.getVersion()), permissions);
    }

    private AccessGovernanceDtos.GroupRoleAssignmentSummary assignmentSummary(
            GroupRoleAssignment assignment,
            DirectoryGroup group,
            Role role) {
        return new AccessGovernanceDtos.GroupRoleAssignmentSummary(
                assignment.getGroupRoleAssignmentId(), group.getGroupId(), group.getDisplayName(),
                role.getRoleId(), role.getCode(), assignment.getAssignmentType(),
                assignment.getScopeType(), assignment.getScopeRef(),
                assignment.getValidFrom(), assignment.getValidTo(), assignment.getLifecycleState(),
                assignment.getJustification(), valueOrZero(assignment.getVersion()));
    }

    private Role requireRole(Long tenantId, Long roleId) {
        return roleRepository.findByRoleIdAndTenantId(roleId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void requireAssignableRole(
            Long tenantId,
            Long actorId,
            String correlationId,
            Role role) {
        RoleDelegationPolicyService.DelegationContext context =
                delegationPolicyService.resolve(tenantId, actorId);
        if (!context.assignableRolesByCode().containsKey(role.getCode())) {
            auditDenied(
                    tenantId, actorId, correlationId, "ROLE",
                    role.getRoleId().toString(), "ROLE_OUTSIDE_DELEGATION_BOUNDARY");
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "This role is outside the current administrator's delegation boundary.");
        }
    }

    private void auditDenied(
            Long tenantId,
            Long actorId,
            String correlationId,
            String targetType,
            String targetId,
            String reason) {
        auditService.denied(
                tenantId,
                actorId,
                "access.governance.rejected",
                targetType,
                targetId,
                correlationId,
                reason,
                Map.of());
    }

    private Role saveRole(Role role) {
        try {
            return roleRepository.saveAndFlush(role);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A role with this code already exists.", exception);
        } catch (OptimisticLockingFailureException exception) {
            throw conflict();
        }
    }

    private void invalidateUsersForRole(Long tenantId, Long roleId, Long actorId) {
        Set<Long> userIds = roleMemberRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .stream().map(RoleMember::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Long> groupIds = groupRoleRepository
                .findByTenantIdAndRoleIdAndLifecycleState(tenantId, roleId, "ACTIVE")
                .stream().map(GroupRoleAssignment::getGroupId).distinct().toList();
        if (!groupIds.isEmpty()) {
            groupMemberRepository.findByTenantIdAndGroupIdIn(tenantId, groupIds)
                    .forEach(member -> userIds.add(member.getUserId()));
        }
        invalidateUsers(tenantId, userIds, actorId);
    }

    private void invalidateGroupMembers(Long tenantId, Long groupId, Long actorId) {
        invalidateUsers(
                tenantId,
                groupMemberRepository.findByTenantIdAndGroupId(tenantId, groupId)
                        .stream().map(DirectoryGroupMember::getUserId).toList(),
                actorId);
    }

    private void invalidateUsers(
            Long tenantId,
            Collection<Long> userIds,
            Long actorId) {
        if (userIds.isEmpty()) return;
        List<User> users = userRepository.findByTenantIdAndUserIdInForUpdate(tenantId, userIds);
        users.forEach(user -> {
            user.setAccessRevision(valueOrZero(user.getAccessRevision()) + 1L);
            user.setUpdatedBy(actorId);
        });
        userRepository.saveAll(users);
        Instant now = Instant.now();
        for (Long userId : userIds) {
            List<AuthSession> sessions = sessionRepository
                    .findByTenantIdAndUserIdAndRevokedAtIsNull(tenantId, userId);
            sessions.forEach(session -> {
                session.setRevokedAt(now);
                session.setUpdatedBy(actorId);
            });
            sessionRepository.saveAll(sessions);
        }
    }

    private void requireVersion(Long actual, Long expected) {
        if (!Objects.equals(valueOrZero(actual), expected)) throw conflict();
    }

    private BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "Access governance data changed after it was loaded. Refresh and try again.");
    }

    private Map<String, Object> roleSnapshot(Role role) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("roleId", role.getRoleId());
        snapshot.put("code", role.getCode());
        snapshot.put("name", role.getName());
        snapshot.put("roleType", role.getRoleType());
        snapshot.put("privileged", role.getPrivileged());
        snapshot.put("assignableToGroups", role.getAssignableToGroups());
        snapshot.put("status", role.getStatus());
        snapshot.put("version", valueOrZero(role.getVersion()));
        return snapshot;
    }

    private Map<String, Object> assignmentSnapshot(GroupRoleAssignment assignment) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("assignmentId", assignment.getGroupRoleAssignmentId());
        snapshot.put("groupId", assignment.getGroupId());
        snapshot.put("roleId", assignment.getRoleId());
        snapshot.put("assignmentType", assignment.getAssignmentType());
        snapshot.put("scopeType", assignment.getScopeType());
        snapshot.put("scopeRef", assignment.getScopeRef());
        snapshot.put("lifecycleState", assignment.getLifecycleState());
        snapshot.put("version", valueOrZero(assignment.getVersion()));
        return snapshot;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
