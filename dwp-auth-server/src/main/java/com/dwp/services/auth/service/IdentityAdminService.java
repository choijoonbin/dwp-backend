package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.IdentityAdminDtos;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
public class IdentityAdminService {

    private static final String ACTIVE = "ACTIVE";
    private static final String ADMIN = "ADMIN";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final AuthSessionRepository authSessionRepository;
    private final IdentityAuditService auditService;

    public IdentityAdminService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            RoleMemberRepository roleMemberRepository,
            AuthSessionRepository authSessionRepository,
            IdentityAuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.roleMemberRepository = roleMemberRepository;
        this.authSessionRepository = authSessionRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public IdentityAdminDtos.PageResult<IdentityAdminDtos.UserAccessSummary> listUsers(
            Long tenantId,
            String query,
            int page,
            int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Specification<User> specification = (root, ignored, builder) ->
                builder.equal(root.get("tenantId"), tenantId);
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("displayName")), pattern),
                    builder.like(builder.lower(root.get("email")), pattern)));
        }
        Page<User> result = userRepository.findAll(
                specification,
                PageRequest.of(
                        safePage,
                        safeSize,
                        Sort.by("displayName").ascending().and(Sort.by("userId").ascending())));
        Map<Long, List<String>> rolesByUser = rolesByUser(tenantId, result.getContent());
        return new IdentityAdminDtos.PageResult<>(
                result.stream()
                        .map(user -> toUserSummary(
                                user,
                                rolesByUser.getOrDefault(user.getUserId(), List.of())))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public List<IdentityAdminDtos.RoleSummary> listRoles(Long tenantId) {
        return roleRepository.findByTenantIdAndStatusOrderByCodeAsc(tenantId, ACTIVE).stream()
                .map(role -> new IdentityAdminDtos.RoleSummary(
                        role.getCode(),
                        role.getName(),
                        role.getDescription(),
                        role.getStatus()))
                .toList();
    }

    @Transactional
    public IdentityAdminDtos.UserAccessSummary replaceRoles(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long targetUserId,
            IdentityAdminDtos.ReplaceUserRolesRequest request) {
        if (actorId.equals(targetUserId)) {
            throw conflict("Administrators cannot change their own roles.");
        }
        User user = userRepository.findByUserIdAndTenantId(targetUserId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(user, request.accessRevision(), request.version());

        Set<String> requestedCodes = request.roleCodes().stream()
                .map(this::normalizeRoleCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Role> requestedRoles = requestedCodes.isEmpty()
                ? List.of()
                : roleRepository.findByTenantIdAndCodeIn(tenantId, requestedCodes);
        validateRoles(requestedCodes, requestedRoles);

        List<RoleMember> currentMemberships =
                roleMemberRepository.findByTenantIdAndUserId(tenantId, targetUserId);
        Map<Long, Role> currentRolesById = rolesById(currentMemberships);
        Set<String> currentCodes = currentRolesById.values().stream()
                .map(Role::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (currentCodes.equals(requestedCodes)) {
            return toUserSummary(user, sortedCodes(currentCodes));
        }

        protectLastAdministrator(tenantId, currentCodes, requestedCodes);
        Map<String, Role> requestedByCode = requestedRoles.stream()
                .collect(Collectors.toMap(Role::getCode, Function.identity()));
        List<RoleMember> removals = currentMemberships.stream()
                .filter(member -> !requestedCodes.contains(
                        currentRolesById.get(member.getRoleId()).getCode()))
                .toList();
        Set<String> additions = requestedCodes.stream()
                .filter(code -> !currentCodes.contains(code))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        roleMemberRepository.deleteAll(removals);
        roleMemberRepository.saveAll(additions.stream()
                .map(code -> {
                    RoleMember member = RoleMember.builder()
                            .tenantId(tenantId)
                            .roleId(requestedByCode.get(code).getRoleId())
                            .userId(targetUserId)
                            .build();
                    member.setCreatedBy(actorId);
                    member.setUpdatedBy(actorId);
                    return member;
                })
                .toList());

        revokeSessions(tenantId, targetUserId, actorId);
        user.setAccessRevision(valueOrZero(user.getAccessRevision()) + 1L);
        user.setUpdatedBy(actorId);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (OptimisticLockingFailureException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Access roles changed after they were loaded. Refresh and try again.",
                    exception);
        }

        List<String> nextCodes = sortedCodes(requestedCodes);
        auditService.success(
                tenantId,
                actorId,
                "identity.user-roles.replaced",
                "USER_ACCESS",
                String.valueOf(targetUserId),
                correlationId,
                roleSnapshot(targetUserId, currentCodes, request.accessRevision()),
                roleSnapshot(targetUserId, requestedCodes, user.getAccessRevision()));
        return toUserSummary(user, nextCodes);
    }

    private Map<Long, List<String>> rolesByUser(Long tenantId, List<User> users) {
        if (users.isEmpty()) return Map.of();
        List<Long> userIds = users.stream().map(User::getUserId).toList();
        List<RoleMember> memberships =
                roleMemberRepository.findByTenantIdAndUserIdIn(tenantId, userIds);
        Map<Long, Role> rolesById = roleRepository.findByRoleIdIn(
                        memberships.stream().map(RoleMember::getRoleId).distinct().toList())
                .stream()
                .filter(role -> tenantId.equals(role.getTenantId()))
                .collect(Collectors.toMap(Role::getRoleId, Function.identity()));
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (RoleMember membership : memberships) {
            Role role = rolesById.get(membership.getRoleId());
            if (role == null) continue;
            result.computeIfAbsent(membership.getUserId(), ignored -> new java.util.ArrayList<>())
                    .add(role.getCode());
        }
        result.values().forEach(codes -> codes.sort(String::compareTo));
        return result;
    }

    private Map<Long, Role> rolesById(List<RoleMember> memberships) {
        if (memberships.isEmpty()) return Map.of();
        return roleRepository.findByRoleIdIn(
                        memberships.stream().map(RoleMember::getRoleId).toList())
                .stream()
                .collect(Collectors.toMap(Role::getRoleId, Function.identity()));
    }

    private void validateRoles(Set<String> requestedCodes, List<Role> roles) {
        Set<String> foundCodes = roles.stream()
                .filter(role -> ACTIVE.equals(role.getStatus()))
                .map(Role::getCode)
                .collect(Collectors.toSet());
        if (!foundCodes.equals(requestedCodes)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "One or more roles do not exist or are inactive in this tenant.");
        }
    }

    private void protectLastAdministrator(
            Long tenantId,
            Set<String> currentCodes,
            Set<String> requestedCodes) {
        boolean adminMembershipChanged =
                currentCodes.contains(ADMIN) != requestedCodes.contains(ADMIN);
        if (!adminMembershipChanged) return;

        Role adminRole = roleRepository.findByTenantIdAndCodeForUpdate(tenantId, ADMIN)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (currentCodes.contains(ADMIN)
                && roleMemberRepository.countByTenantIdAndRoleId(
                        tenantId, adminRole.getRoleId()) <= 1) {
            throw conflict("The tenant must retain at least one administrator.");
        }
    }

    private void revokeSessions(Long tenantId, Long userId, Long actorId) {
        Instant now = Instant.now();
        List<AuthSession> sessions =
                authSessionRepository.findByTenantIdAndUserIdAndRevokedAtIsNull(tenantId, userId);
        sessions.forEach(session -> {
            session.setRevokedAt(now);
            session.setUpdatedBy(actorId);
        });
        authSessionRepository.saveAll(sessions);
    }

    private void requireVersion(User user, Long accessRevision, Long version) {
        if (!Objects.equals(valueOrZero(user.getAccessRevision()), accessRevision)
                || !Objects.equals(valueOrZero(user.getVersion()), version)) {
            throw conflict("Access roles changed after they were loaded. Refresh and try again.");
        }
    }

    private IdentityAdminDtos.UserAccessSummary toUserSummary(User user, List<String> roles) {
        return new IdentityAdminDtos.UserAccessSummary(
                user.getUserId(),
                user.getDisplayName(),
                user.getEmail(),
                user.getStatus(),
                user.getMfaEnabled(),
                roles,
                valueOrZero(user.getAccessRevision()),
                valueOrZero(user.getVersion()),
                user.getUpdatedAt(),
                user.getUpdatedBy());
    }

    private Map<String, Object> roleSnapshot(
            Long userId,
            Collection<String> roles,
            Long accessRevision) {
        return Map.of(
                "userId", userId,
                "roles", sortedCodes(roles),
                "accessRevision", accessRevision);
    }

    private List<String> sortedCodes(Collection<String> codes) {
        return codes.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private String normalizeRoleCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private Long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
