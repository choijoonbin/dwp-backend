package com.dwp.services.auth.service;

import com.dwp.services.auth.entity.DirectoryGroupMember;
import com.dwp.services.auth.entity.GroupRoleAssignment;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.GroupRoleAssignmentRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Applies the same separation-of-duties policy to every group membership mutation path. */
@Service
public class GroupRoleConflictGuard {

    private final DirectoryGroupMemberRepository groupMemberRepository;
    private final GroupRoleAssignmentRepository groupRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final RoleDelegationPolicyService delegationPolicyService;

    public GroupRoleConflictGuard(
            DirectoryGroupMemberRepository groupMemberRepository,
            GroupRoleAssignmentRepository groupRoleRepository,
            RoleRepository roleRepository,
            UserRepository userRepository,
            RoleDelegationPolicyService delegationPolicyService) {
        this.groupMemberRepository = groupMemberRepository;
        this.groupRoleRepository = groupRoleRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.delegationPolicyService = delegationPolicyService;
    }

    @Transactional
    public Optional<Violation> evaluateRoleAssignment(
            Long tenantId,
            Long groupId,
            String roleCode) {
        List<Long> userIds = groupMemberRepository.findByTenantIdAndGroupId(tenantId, groupId)
                .stream()
                .map(DirectoryGroupMember::getUserId)
                .distinct()
                .sorted()
                .toList();
        return evaluate(tenantId, userIds, Set.of(roleCode));
    }

    @Transactional
    public Optional<Violation> evaluateMembershipAddition(
            Long tenantId,
            Long groupId,
            Collection<Long> userIds) {
        if (userIds.isEmpty()) return Optional.empty();
        List<GroupRoleAssignment> assignments = groupRoleRepository
                .findEffectiveByTenantIdAndGroupId(tenantId, groupId);
        if (assignments.isEmpty()) return Optional.empty();
        Map<Long, Role> roles = roleRepository.findByRoleIdIn(
                        assignments.stream().map(GroupRoleAssignment::getRoleId).distinct().toList())
                .stream()
                .filter(role -> tenantId.equals(role.getTenantId()))
                .filter(role -> "ACTIVE".equals(role.getStatus()))
                .collect(Collectors.toMap(Role::getRoleId, Function.identity()));
        Set<String> additionalRoles = assignments.stream()
                .map(assignment -> roles.get(assignment.getRoleId()))
                .filter(java.util.Objects::nonNull)
                .map(Role::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return evaluate(tenantId, userIds, additionalRoles);
    }

    private Optional<Violation> evaluate(
            Long tenantId,
            Collection<Long> candidateUserIds,
            Set<String> additionalRoleCodes) {
        if (candidateUserIds.isEmpty() || additionalRoleCodes.isEmpty()) {
            return Optional.empty();
        }
        List<Long> userIds = candidateUserIds.stream().distinct().sorted().toList();
        // Serialize group-derived access changes with direct role and lifecycle mutations.
        userRepository.findByTenantIdAndUserIdInForUpdate(tenantId, userIds);
        Map<Long, Set<String>> currentRoles = new LinkedHashMap<>(
                delegationPolicyService.effectiveRoleCodesByUser(tenantId, userIds));
        for (Long userId : userIds) {
            Set<String> existing = currentRoles.getOrDefault(userId, Set.of());
            RoleDelegationPolicyService.RoleSetDecision decision =
                    delegationPolicyService.evaluateAdditiveRoleSet(existing, additionalRoleCodes);
            if (!decision.allowed()) {
                return Optional.of(new Violation(
                        userId,
                        decision.reason(),
                        existing.stream().sorted().toList(),
                        additionalRoleCodes.stream().sorted().toList()));
            }
        }
        return Optional.empty();
    }

    public record Violation(
            Long userId,
            String reason,
            List<String> currentRoleCodes,
            List<String> additionalRoleCodes) {
    }
}
