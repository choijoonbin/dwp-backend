package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.IdentityAdminDtos;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.IdentityAccessEvidenceRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class IdentityAdminService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final AuthSessionRepository authSessionRepository;
    private final IdentityAccessEvidenceRepository accessEvidenceRepository;
    private final IdentityAuditService auditService;
    private final RoleDelegationPolicyService delegationPolicyService;

    public IdentityAdminService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            RoleMemberRepository roleMemberRepository,
            AuthSessionRepository authSessionRepository,
            IdentityAccessEvidenceRepository accessEvidenceRepository,
            IdentityAuditService auditService,
            RoleDelegationPolicyService delegationPolicyService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.roleMemberRepository = roleMemberRepository;
        this.authSessionRepository = authSessionRepository;
        this.accessEvidenceRepository = accessEvidenceRepository;
        this.auditService = auditService;
        this.delegationPolicyService = delegationPolicyService;
    }

    @Transactional(readOnly = true)
    public IdentityAdminDtos.PageResult<IdentityAdminDtos.UserAccessSummary> listUsers(
            Long tenantId,
            Long actorId,
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
        List<Long> userIds = result.getContent().stream().map(User::getUserId).toList();
        Map<Long, List<IdentityAccessEvidenceRepository.EffectiveAccessRow>> accessByUser =
                accessEvidenceRepository.effectiveAccess(tenantId, userIds);
        Map<Long, IdentityAccessEvidenceRepository.SessionEvidence> sessionsByUser =
                accessEvidenceRepository.sessionEvidence(tenantId, userIds);
        Optional<RoleDelegationPolicyService.DelegationContext> delegation =
                delegationPolicyService.findDirectDelegation(tenantId, actorId);
        Map<Long, Set<String>> effectiveRolesByUser =
                delegationPolicyService.effectiveRoleCodesByUser(
                        tenantId,
                        result.getContent().stream().map(User::getUserId).toList());
        return new IdentityAdminDtos.PageResult<>(
                result.stream()
                        .map(user -> {
                            RoleDelegationPolicyService.RoleManagementDecision decision = delegation
                                    .map(context -> delegationPolicyService.evaluateTarget(
                                            context,
                                            actorId,
                                            user.getUserId(),
                                            user.getStatus(),
                                            effectiveRolesByUser.getOrDefault(
                                                    user.getUserId(), Set.of())))
                                    .orElseGet(() -> new RoleDelegationPolicyService
                                            .RoleManagementDecision(
                                                    false,
                                                    "ROLE_ASSIGNMENT_REQUIRES_TENANT_ADMIN"));
                            return toUserSummary(
                                    user,
                                    rolesByUser.getOrDefault(user.getUserId(), List.of()),
                                    accessByUser.getOrDefault(user.getUserId(), List.of()),
                                    sessionsByUser.get(user.getUserId()),
                                    decision);
                        })
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public List<IdentityAdminDtos.RoleSummary> listRoles(Long tenantId, Long actorId) {
        return delegationPolicyService.findDirectDelegation(tenantId, actorId).stream()
                .flatMap(context -> context.assignableRoles().stream())
                .map(option -> new IdentityAdminDtos.RoleSummary(
                        option.role().getCode(),
                        option.role().getName(),
                        option.role().getDescription(),
                        option.roleFamily(),
                        option.assignmentClass(),
                        Boolean.TRUE.equals(option.role().getPrivileged()),
                        option.assignmentMode(),
                        option.conflictsWith().stream().sorted().toList(),
                        option.role().getStatus()))
                .toList();
    }

    @Transactional
    public IdentityAdminDtos.UserAccessSummary replaceRoles(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long targetUserId,
            IdentityAdminDtos.ReplaceUserRolesRequest request) {
        Set<String> requestedCodes = request.roleCodes().stream()
                .map(this::normalizeRoleCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        RoleDelegationPolicyService.DelegationContext context;
        try {
            context = delegationPolicyService.resolve(tenantId, actorId);
        } catch (BaseException exception) {
            auditDeniedRoleChange(
                    tenantId,
                    actorId,
                    correlationId,
                    targetUserId,
                    requestedCodes,
                    "ACTOR_HAS_NO_DELEGATION_POLICY");
            throw exception;
        }

        User user = userRepository.findByUserIdAndTenantId(targetUserId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(user, request.accessRevision(), request.version());

        List<RoleMember> currentMemberships =
                roleMemberRepository.findByTenantIdAndUserId(tenantId, targetUserId);
        Map<Long, Role> currentRolesById = rolesById(currentMemberships);
        Set<String> currentCodes = currentRolesById.values().stream()
                .map(Role::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> effectiveRoleCodes = delegationPolicyService
                .effectiveRoleCodesByUser(tenantId, List.of(targetUserId))
                .getOrDefault(targetUserId, Set.of());
        RoleDelegationPolicyService.RoleManagementDecision decision =
                delegationPolicyService.evaluateTarget(
                        context, actorId, targetUserId, user.getStatus(), effectiveRoleCodes);
        if (!decision.allowed()) {
            auditDeniedRoleChange(
                    tenantId,
                    actorId,
                    correlationId,
                    targetUserId,
                    requestedCodes,
                    decision.reason());
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "This identity is outside the current administrator's delegation boundary.");
        }
        if (!context.assignableRolesByCode().keySet().containsAll(requestedCodes)) {
            auditDeniedRoleChange(
                    tenantId,
                    actorId,
                    correlationId,
                    targetUserId,
                    requestedCodes,
                    "ROLE_OUTSIDE_DELEGATION_BOUNDARY");
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "One or more requested roles are outside the delegation boundary.");
        }
        RoleDelegationPolicyService.RoleSetDecision roleSetDecision =
                delegationPolicyService.evaluateRoleSet(
                        effectiveRoleCodes, currentCodes, requestedCodes);
        if (!roleSetDecision.allowed()) {
            auditDeniedRoleChange(
                    tenantId,
                    actorId,
                    correlationId,
                    targetUserId,
                    requestedCodes,
                    roleSetDecision.reason());
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    roleSetDecision.reason().equals("BASELINE_ROLE_REQUIRED")
                            ? "Every managed workforce identity must retain baseline workspace access."
                            : "The requested role combination violates separation-of-duties policy.");
        }
        if (currentCodes.equals(requestedCodes)) {
            return toUserSummary(tenantId, user, sortedCodes(currentCodes), decision);
        }

        Map<String, Role> requestedByCode = requestedCodes.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        code -> context.assignableRolesByCode().get(code).role()));
        List<RoleMember> removals = currentMemberships.stream()
                .filter(member -> !requestedCodes.contains(
                        currentRolesById.get(member.getRoleId()).getCode()))
                .toList();
        Set<String> additions = requestedCodes.stream()
                .filter(code -> !currentCodes.contains(code))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!removals.isEmpty()) roleMemberRepository.deleteAll(removals);
        if (!additions.isEmpty()) {
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
        }

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
                roleSnapshot(
                        targetUserId,
                        requestedCodes,
                        user.getAccessRevision(),
                        request.justification().trim()));
        return toUserSummary(tenantId, user, nextCodes, decision);
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

    private IdentityAdminDtos.UserAccessSummary toUserSummary(
            User user,
            List<String> roles,
            List<IdentityAccessEvidenceRepository.EffectiveAccessRow> access,
            IdentityAccessEvidenceRepository.SessionEvidence session,
            RoleDelegationPolicyService.RoleManagementDecision decision) {
        List<IdentityAdminDtos.EffectiveAccessSummary> effectiveAccess = access.stream()
                .map(row -> new IdentityAdminDtos.EffectiveAccessSummary(
                        row.roleId(), row.roleCode(), row.roleName(), row.privileged(),
                        row.sourceType(), row.sourceId(), row.sourceKey(), row.sourceName(),
                        row.assignmentType(), row.scopeType(), row.scopeRef(), row.validFrom(),
                        row.validTo(), row.assignedAt()))
                .toList();
        return new IdentityAdminDtos.UserAccessSummary(
                user.getUserId(),
                user.getDisplayName(),
                user.getEmail(),
                user.getStatus(),
                user.getMfaEnabled(),
                roles,
                effectiveAccess.stream()
                        .filter(item -> "DIRECT".equals(item.sourceType())
                                || "GROUP".equals(item.sourceType()))
                        .map(IdentityAdminDtos.EffectiveAccessSummary::roleCode)
                        .distinct()
                        .sorted()
                        .toList(),
                effectiveAccess,
                session == null ? null : session.lastSignInAt(),
                session == null ? 0L : session.activeSessionCount(),
                new IdentityAdminDtos.RoleManagementSummary(
                        decision.allowed(), decision.reason()),
                valueOrZero(user.getAccessRevision()),
                valueOrZero(user.getVersion()),
                user.getUpdatedAt(),
                user.getUpdatedBy());
    }

    private IdentityAdminDtos.UserAccessSummary toUserSummary(
            Long tenantId,
            User user,
            List<String> roles,
            RoleDelegationPolicyService.RoleManagementDecision decision) {
        List<IdentityAccessEvidenceRepository.EffectiveAccessRow> access =
                accessEvidenceRepository.effectiveAccess(tenantId, List.of(user.getUserId()))
                        .getOrDefault(user.getUserId(), List.of());
        IdentityAccessEvidenceRepository.SessionEvidence session =
                accessEvidenceRepository.sessionEvidence(tenantId, List.of(user.getUserId()))
                        .get(user.getUserId());
        return toUserSummary(user, roles, access, session, decision);
    }

    private Map<String, Object> roleSnapshot(
            Long userId,
            Collection<String> roles,
            Long accessRevision) {
        return roleSnapshot(userId, roles, accessRevision, null);
    }

    private Map<String, Object> roleSnapshot(
            Long userId,
            Collection<String> roles,
            Long accessRevision,
            String justification) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("userId", userId);
        snapshot.put("roles", sortedCodes(roles));
        snapshot.put("accessRevision", accessRevision);
        if (justification != null) snapshot.put("justification", justification);
        return snapshot;
    }

    private void auditDeniedRoleChange(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long targetUserId,
            Collection<String> requestedCodes,
            String reason) {
        auditService.denied(
                tenantId,
                actorId,
                "identity.user-roles.rejected",
                "USER_ACCESS",
                String.valueOf(targetUserId),
                correlationId,
                reason,
                Map.of("requestedRoles", sortedCodes(requestedCodes)));
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
