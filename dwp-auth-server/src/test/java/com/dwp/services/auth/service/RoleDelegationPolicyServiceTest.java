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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleDelegationPolicyServiceTest {

    private static final Long TENANT_ID = 3L;
    private static final Long ACTOR_ID = 7L;

    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final RoleMemberRepository roleMemberRepository = mock(RoleMemberRepository.class);
    private final RoleAssignmentPolicyRepository policyRepository =
            mock(RoleAssignmentPolicyRepository.class);
    private final RoleConflictPolicyRepository conflictPolicyRepository =
            mock(RoleConflictPolicyRepository.class);
    private final BuiltinRoleDefinitionRepository definitionRepository =
            mock(BuiltinRoleDefinitionRepository.class);
    private final RoleDelegationPolicyService service = new RoleDelegationPolicyService(
            roleRepository,
            roleMemberRepository,
            policyRepository,
            conflictPolicyRepository,
            definitionRepository);

    @Test
    void resolvesOnlyExplicitActiveDirectDelegations() {
        Role tenantAdmin = role(1L, "TENANT_ADMIN", true);
        Role workspace = role(2L, "WORKSPACE_MEMBER", false);
        Role hrAdmin = role(3L, "HR_ADMIN", true);
        Role auditor = role(4L, "AUDITOR", true);
        Set<String> targetCodes = Set.of("WORKSPACE_MEMBER", "HR_ADMIN", "AUDITOR");

        when(roleMemberRepository.findRoleIds(TENANT_ID, ACTOR_ID)).thenReturn(List.of(1L));
        when(roleRepository.findByRoleIdIn(List.of(1L))).thenReturn(List.of(tenantAdmin));
        when(policyRepository.findByGrantorRoleCodeInAndAssignmentModeAndLifecycleState(
                        Set.of("TENANT_ADMIN"), "DIRECT", "ACTIVE"))
                .thenReturn(List.of(
                        assignmentPolicy("TENANT_ADMIN", "WORKSPACE_MEMBER"),
                        assignmentPolicy("TENANT_ADMIN", "HR_ADMIN"),
                        assignmentPolicy("TENANT_ADMIN", "AUDITOR")));
        when(definitionRepository.findAllById(targetCodes)).thenReturn(List.of(
                definition("WORKSPACE_MEMBER", "WORKSPACE", "BASELINE", 10),
                definition("HR_ADMIN", "PEOPLE", "DELEGATED", 20),
                definition("AUDITOR", "AUDIT", "DELEGATED", 30)));
        when(roleRepository.findByTenantIdAndCodeIn(TENANT_ID, targetCodes))
                .thenReturn(List.of(hrAdmin, auditor, workspace));
        when(conflictPolicyRepository
                        .findByLifecycleStateOrderByLeftRoleCodeAscRightRoleCodeAsc("ACTIVE"))
                .thenReturn(List.of(RoleConflictPolicy.builder()
                        .leftRoleCode("AUDITOR")
                        .rightRoleCode("HR_ADMIN")
                        .reasonCode("AUDIT_INDEPENDENCE")
                        .lifecycleState("ACTIVE")
                        .build()));

        RoleDelegationPolicyService.DelegationContext context =
                service.resolve(TENANT_ID, ACTOR_ID);

        assertThat(context.actorRoleCodes()).containsExactly("TENANT_ADMIN");
        assertThat(context.assignableRoles())
                .extracting(option -> option.role().getCode())
                .containsExactly("WORKSPACE_MEMBER", "HR_ADMIN", "AUDITOR");
        assertThat(context.assignableRoles().get(0).assignmentClass()).isEqualTo("BASELINE");
        assertThat(context.assignableRolesByCode().get("HR_ADMIN").conflictsWith())
                .containsExactly("AUDITOR");
    }

    @Test
    void deniesActorWithoutPersistedDelegationPolicy() {
        Role tenantAdmin = role(1L, "TENANT_ADMIN", true);
        when(roleMemberRepository.findRoleIds(TENANT_ID, ACTOR_ID)).thenReturn(List.of(1L));
        when(roleRepository.findByRoleIdIn(List.of(1L))).thenReturn(List.of(tenantAdmin));
        when(policyRepository.findByGrantorRoleCodeInAndAssignmentModeAndLifecycleState(
                        Set.of("TENANT_ADMIN"), "DIRECT", "ACTIVE"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.resolve(TENANT_ID, ACTOR_ID))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void protectsPeerAndHigherPrivilegeTargets() {
        RoleDelegationPolicyService.DelegationContext context =
                new RoleDelegationPolicyService.DelegationContext(
                        Set.of("TENANT_ADMIN"),
                        java.util.Map.of(
                                "WORKSPACE_MEMBER",
                                new RoleDelegationPolicyService.AssignableRole(
                                        role(2L, "WORKSPACE_MEMBER", false),
                                        "WORKSPACE", "BASELINE", "DIRECT", 10, Set.of())));

        assertThat(service.evaluateTarget(
                        context,
                        ACTOR_ID,
                        99L,
                        "ACTIVE",
                        Set.of("WORKSPACE_MEMBER", "TENANT_ADMIN")))
                .isEqualTo(new RoleDelegationPolicyService.RoleManagementDecision(
                        false, "PROTECTED_ROLE"));
        assertThat(service.evaluateTarget(context, ACTOR_ID, ACTOR_ID, "ACTIVE", Set.of()))
                .isEqualTo(new RoleDelegationPolicyService.RoleManagementDecision(false, "SELF"));
        assertThat(service.evaluateTarget(context, ACTOR_ID, 99L, "SUSPENDED", Set.of()))
                .isEqualTo(new RoleDelegationPolicyService.RoleManagementDecision(
                        false, "IDENTITY_INACTIVE"));
    }

    @Test
    void enforcesBaselineAndAuditIndependenceAcrossInheritedRoles() {
        when(conflictPolicyRepository
                        .findByLifecycleStateOrderByLeftRoleCodeAscRightRoleCodeAsc("ACTIVE"))
                .thenReturn(List.of(RoleConflictPolicy.builder()
                        .leftRoleCode("AUDITOR")
                        .rightRoleCode("HR_ADMIN")
                        .reasonCode("AUDIT_INDEPENDENCE")
                        .lifecycleState("ACTIVE")
                        .build()));

        assertThat(service.evaluateRoleSet(
                        Set.of("WORKSPACE_MEMBER"),
                        Set.of("WORKSPACE_MEMBER"),
                        Set.of()))
                .isEqualTo(new RoleDelegationPolicyService.RoleSetDecision(
                        false, "BASELINE_ROLE_REQUIRED"));
        assertThat(service.evaluateRoleSet(
                        Set.of("WORKSPACE_MEMBER", "AUDITOR"),
                        Set.of("WORKSPACE_MEMBER"),
                        Set.of("WORKSPACE_MEMBER", "HR_ADMIN")))
                .isEqualTo(new RoleDelegationPolicyService.RoleSetDecision(
                        false, "ROLE_CONFLICT_AUDIT_INDEPENDENCE"));
        assertThat(service.evaluateAdditiveRoleSet(
                        Set.of("WORKSPACE_MEMBER", "AUDITOR"),
                        Set.of("HR_ADMIN")))
                .isEqualTo(new RoleDelegationPolicyService.RoleSetDecision(
                        false, "ROLE_CONFLICT_AUDIT_INDEPENDENCE"));
    }

    private Role role(Long id, String code, boolean privileged) {
        return Role.builder()
                .roleId(id)
                .tenantId(TENANT_ID)
                .code(code)
                .name(code)
                .status("ACTIVE")
                .privileged(privileged)
                .build();
    }

    private RoleAssignmentPolicy assignmentPolicy(String grantor, String target) {
        return RoleAssignmentPolicy.builder()
                .grantorRoleCode(grantor)
                .targetRoleCode(target)
                .assignmentMode("DIRECT")
                .lifecycleState("ACTIVE")
                .build();
    }

    private BuiltinRoleDefinition definition(
            String code,
            String family,
            String assignmentClass,
            int sortOrder) {
        return BuiltinRoleDefinition.builder()
                .roleCode(code)
                .roleFamily(family)
                .assignmentClass(assignmentClass)
                .sortOrder(sortOrder)
                .lifecycleState("ACTIVE")
                .build();
    }
}
