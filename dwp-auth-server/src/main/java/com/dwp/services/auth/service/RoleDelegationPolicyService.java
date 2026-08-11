package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.BuiltinRoleDefinition;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleAssignmentPolicy;
import com.dwp.services.auth.entity.RoleConflictPolicy;
import com.dwp.services.auth.repository.BuiltinRoleDefinitionRepository;
import com.dwp.services.auth.repository.RoleAssignmentPolicyRepository;
import com.dwp.services.auth.repository.RoleConflictPolicyRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RoleDelegationPolicyService {

    private static final String ACTIVE = "ACTIVE";
    private static final String DIRECT = "DIRECT";
    private static final String BASELINE_ROLE_CODE = "WORKSPACE_MEMBER";

    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final RoleAssignmentPolicyRepository policyRepository;
    private final RoleConflictPolicyRepository conflictPolicyRepository;
    private final BuiltinRoleDefinitionRepository definitionRepository;

    public RoleDelegationPolicyService(
            RoleRepository roleRepository,
            RoleMemberRepository roleMemberRepository,
            RoleAssignmentPolicyRepository policyRepository,
            RoleConflictPolicyRepository conflictPolicyRepository,
            BuiltinRoleDefinitionRepository definitionRepository) {
        this.roleRepository = roleRepository;
        this.roleMemberRepository = roleMemberRepository;
        this.policyRepository = policyRepository;
        this.conflictPolicyRepository = conflictPolicyRepository;
        this.definitionRepository = definitionRepository;
    }

    @Transactional(readOnly = true)
    public DelegationContext resolve(Long tenantId, Long actorId) {
        Set<String> actorRoleCodes = activeRoleCodes(
                tenantId,
                roleMemberRepository.findRoleIds(tenantId, actorId));
        if (actorRoleCodes.isEmpty()) throw forbidden();

        Set<String> targetCodes = policyRepository
                .findByGrantorRoleCodeInAndAssignmentModeAndLifecycleState(
                        actorRoleCodes, DIRECT, ACTIVE)
                .stream()
                .map(RoleAssignmentPolicy::getTargetRoleCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (targetCodes.isEmpty()) throw forbidden();

        Map<String, BuiltinRoleDefinition> definitions = definitionRepository
                .findAllById(targetCodes)
                .stream()
                .filter(definition -> ACTIVE.equals(definition.getLifecycleState()))
                .collect(Collectors.toMap(
                        BuiltinRoleDefinition::getRoleCode,
                        Function.identity()));
        Map<String, Set<String>> conflictsByRole = conflictsByRole(targetCodes);

        List<AssignableRole> assignableRoles = roleRepository
                .findByTenantIdAndCodeIn(tenantId, targetCodes)
                .stream()
                .filter(role -> ACTIVE.equals(role.getStatus()))
                .filter(role -> definitions.containsKey(role.getCode()))
                .map(role -> {
                    BuiltinRoleDefinition definition = definitions.get(role.getCode());
                    return new AssignableRole(
                            role,
                            definition.getRoleFamily(),
                            definition.getAssignmentClass(),
                            DIRECT,
                            definition.getSortOrder(),
                            conflictsByRole.getOrDefault(role.getCode(), Set.of()));
                })
                .sorted(Comparator.comparingInt(AssignableRole::sortOrder)
                        .thenComparing(option -> option.role().getCode()))
                .toList();
        if (assignableRoles.isEmpty()) throw forbidden();

        Map<String, AssignableRole> byCode = assignableRoles.stream()
                .collect(Collectors.toMap(
                        option -> option.role().getCode(),
                        Function.identity(),
                        (left, ignored) -> left,
                        LinkedHashMap::new));
        return new DelegationContext(
                Collections.unmodifiableSet(new LinkedHashSet<>(actorRoleCodes)),
                Collections.unmodifiableMap(byCode));
    }

    @Transactional(readOnly = true)
    public Map<Long, Set<String>> effectiveRoleCodesByUser(
            Long tenantId,
            Collection<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        List<RoleMemberRepository.EffectiveRoleMembership> memberships =
                roleMemberRepository.findEffectiveRoleMemberships(tenantId, userIds);
        Map<Long, Role> roles = roleRepository.findByRoleIdIn(
                        memberships.stream()
                                .map(RoleMemberRepository.EffectiveRoleMembership::getRoleId)
                                .distinct()
                                .toList())
                .stream()
                .filter(role -> tenantId.equals(role.getTenantId()))
                .filter(role -> ACTIVE.equals(role.getStatus()))
                .collect(Collectors.toMap(Role::getRoleId, Function.identity()));

        Map<Long, Set<String>> result = new LinkedHashMap<>();
        userIds.forEach(userId -> result.put(userId, new LinkedHashSet<>()));
        memberships.forEach(membership -> {
            Role role = roles.get(membership.getRoleId());
            if (role != null) {
                result.computeIfAbsent(
                                membership.getUserId(),
                                ignored -> new LinkedHashSet<>())
                        .add(role.getCode());
            }
        });
        result.replaceAll((ignored, codes) -> Collections.unmodifiableSet(codes));
        return Collections.unmodifiableMap(result);
    }

    public RoleManagementDecision evaluateTarget(
            DelegationContext context,
            Long actorId,
            Long targetUserId,
            String targetStatus,
            Collection<String> effectiveRoleCodes) {
        if (actorId.equals(targetUserId)) {
            return new RoleManagementDecision(false, "SELF");
        }
        if (!Set.of("ACTIVE", "INVITED").contains(targetStatus)) {
            return new RoleManagementDecision(false, "IDENTITY_INACTIVE");
        }
        boolean protectedRole = effectiveRoleCodes.stream()
                .anyMatch(code -> !context.assignableRolesByCode().containsKey(code));
        return protectedRole
                ? new RoleManagementDecision(false, "PROTECTED_ROLE")
                : new RoleManagementDecision(true, "ALLOWED");
    }

    @Transactional(readOnly = true)
    public RoleSetDecision evaluateRoleSet(
            Collection<String> currentEffectiveRoleCodes,
            Collection<String> currentDirectRoleCodes,
            Collection<String> requestedDirectRoleCodes) {
        Set<String> prospectiveRoleCodes = new LinkedHashSet<>(currentEffectiveRoleCodes);
        prospectiveRoleCodes.removeAll(currentDirectRoleCodes);
        prospectiveRoleCodes.addAll(requestedDirectRoleCodes);
        if (!prospectiveRoleCodes.contains(BASELINE_ROLE_CODE)) {
            return new RoleSetDecision(false, "BASELINE_ROLE_REQUIRED");
        }
        return conflictPolicyRepository
                .findByLifecycleStateOrderByLeftRoleCodeAscRightRoleCodeAsc(ACTIVE)
                .stream()
                .filter(policy -> prospectiveRoleCodes.contains(policy.getLeftRoleCode())
                        && prospectiveRoleCodes.contains(policy.getRightRoleCode()))
                .findFirst()
                .map(this::conflictDecision)
                .orElseGet(() -> new RoleSetDecision(true, "ALLOWED"));
    }

    private RoleSetDecision conflictDecision(RoleConflictPolicy policy) {
        return new RoleSetDecision(false, "ROLE_CONFLICT_" + policy.getReasonCode());
    }

    private Map<String, Set<String>> conflictsByRole(Set<String> targetCodes) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        conflictPolicyRepository
                .findByLifecycleStateOrderByLeftRoleCodeAscRightRoleCodeAsc(ACTIVE)
                .forEach(policy -> {
                    if (targetCodes.contains(policy.getLeftRoleCode())
                            && targetCodes.contains(policy.getRightRoleCode())) {
                        result.computeIfAbsent(
                                        policy.getLeftRoleCode(),
                                        ignored -> new LinkedHashSet<>())
                                .add(policy.getRightRoleCode());
                        result.computeIfAbsent(
                                        policy.getRightRoleCode(),
                                        ignored -> new LinkedHashSet<>())
                                .add(policy.getLeftRoleCode());
                    }
                });
        result.replaceAll((ignored, codes) -> Collections.unmodifiableSet(codes));
        return Collections.unmodifiableMap(result);
    }

    private Set<String> activeRoleCodes(Long tenantId, List<Long> roleIds) {
        if (roleIds.isEmpty()) return Set.of();
        return roleRepository.findByRoleIdIn(roleIds).stream()
                .filter(role -> tenantId.equals(role.getTenantId()))
                .filter(role -> ACTIVE.equals(role.getStatus()))
                .map(Role::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private BaseException forbidden() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The current identity has no active role delegation policy.");
    }

    public record AssignableRole(
            Role role,
            String roleFamily,
            String assignmentClass,
            String assignmentMode,
            int sortOrder,
            Set<String> conflictsWith) {
    }

    public record DelegationContext(
            Set<String> actorRoleCodes,
            Map<String, AssignableRole> assignableRolesByCode) {

        public List<AssignableRole> assignableRoles() {
            return new ArrayList<>(assignableRolesByCode.values());
        }
    }

    public record RoleManagementDecision(boolean allowed, String reason) {
    }

    public record RoleSetDecision(boolean allowed, String reason) {
    }
}
