package com.dwp.services.auth.service;

import com.dwp.services.auth.entity.DirectoryGroupMember;
import com.dwp.services.auth.entity.GroupRoleAssignment;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.GroupRoleAssignmentRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupRoleConflictGuardTest {

    private static final Long TENANT_ID = 7L;

    private final DirectoryGroupMemberRepository memberRepository =
            mock(DirectoryGroupMemberRepository.class);
    private final GroupRoleAssignmentRepository assignmentRepository =
            mock(GroupRoleAssignmentRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleDelegationPolicyService policyService =
            mock(RoleDelegationPolicyService.class);
    private final GroupRoleConflictGuard guard = new GroupRoleConflictGuard(
            memberRepository,
            assignmentRepository,
            roleRepository,
            userRepository,
            policyService);

    @Test
    void rejectsAGroupRoleWhenAnyCurrentMemberWouldViolateSod() {
        DirectoryGroupMember first = member(21L);
        DirectoryGroupMember second = member(22L);
        when(memberRepository.findByTenantIdAndGroupId(TENANT_ID, 30L))
                .thenReturn(List.of(second, first));
        when(userRepository.findByTenantIdAndUserIdInForUpdate(
                TENANT_ID, List.of(21L, 22L))).thenReturn(List.of());
        when(policyService.effectiveRoleCodesByUser(TENANT_ID, List.of(21L, 22L)))
                .thenReturn(Map.of(
                        21L, Set.of("WORKSPACE_MEMBER", "AUDITOR"),
                        22L, Set.of("WORKSPACE_MEMBER")));
        when(policyService.evaluateAdditiveRoleSet(
                        Set.of("WORKSPACE_MEMBER", "AUDITOR"), Set.of("HR_ADMIN")))
                .thenReturn(new RoleDelegationPolicyService.RoleSetDecision(
                        false, "ROLE_CONFLICT_AUDIT_INDEPENDENCE"));

        Optional<GroupRoleConflictGuard.Violation> result =
                guard.evaluateRoleAssignment(TENANT_ID, 30L, "HR_ADMIN");

        assertThat(result).contains(new GroupRoleConflictGuard.Violation(
                21L,
                "ROLE_CONFLICT_AUDIT_INDEPENDENCE",
                List.of("AUDITOR", "WORKSPACE_MEMBER"),
                List.of("HR_ADMIN")));
    }

    @Test
    void checksOnlyEffectiveRolesAttachedToTheTargetGroupForNewMembers() {
        GroupRoleAssignment assignment = GroupRoleAssignment.builder()
                .tenantId(TENANT_ID)
                .groupId(30L)
                .roleId(40L)
                .build();
        Role role = Role.builder()
                .roleId(40L)
                .tenantId(TENANT_ID)
                .code("PEOPLE_ADMIN")
                .status("ACTIVE")
                .build();
        when(assignmentRepository.findEffectiveByTenantIdAndGroupId(TENANT_ID, 30L))
                .thenReturn(List.of(assignment));
        when(roleRepository.findByRoleIdIn(List.of(40L))).thenReturn(List.of(role));
        when(userRepository.findByTenantIdAndUserIdInForUpdate(TENANT_ID, List.of(21L)))
                .thenReturn(List.of());
        when(policyService.effectiveRoleCodesByUser(TENANT_ID, List.of(21L)))
                .thenReturn(Map.of(21L, Set.of("WORKSPACE_MEMBER")));
        when(policyService.evaluateAdditiveRoleSet(
                        Set.of("WORKSPACE_MEMBER"), Set.of("PEOPLE_ADMIN")))
                .thenReturn(new RoleDelegationPolicyService.RoleSetDecision(true, "ALLOWED"));

        assertThat(guard.evaluateMembershipAddition(TENANT_ID, 30L, List.of(21L)))
                .isEmpty();
        verify(policyService).evaluateAdditiveRoleSet(
                Set.of("WORKSPACE_MEMBER"), Set.of("PEOPLE_ADMIN"));
    }

    private DirectoryGroupMember member(Long userId) {
        return DirectoryGroupMember.builder()
                .tenantId(TENANT_ID)
                .groupId(30L)
                .userId(userId)
                .build();
    }
}
