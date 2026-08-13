package com.dwp.services.auth.scim;

import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.DirectoryGroup;
import com.dwp.services.auth.entity.DirectoryGroupMember;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.GroupRoleConflictGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScimGroupServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final UUID CONNECTOR_ID = UUID.randomUUID();

    private final DirectoryGroupRepository groupRepository =
            mock(DirectoryGroupRepository.class);
    private final DirectoryGroupMemberRepository memberRepository =
            mock(DirectoryGroupMemberRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthSessionRepository sessionRepository = mock(AuthSessionRepository.class);
    private final ScimProvisioningAuditService auditService =
            mock(ScimProvisioningAuditService.class);
    private final GroupRoleConflictGuard conflictGuard = mock(GroupRoleConflictGuard.class);
    private final ScimGroupService service = new ScimGroupService(
            groupRepository,
            memberRepository,
            userRepository,
            sessionRepository,
            auditService,
            conflictGuard,
            mock(ScimCursorCodec.class),
            "http://localhost/scim/v2");

    @BeforeEach
    void setContext() {
        ScimConnectorContext.set(new ScimConnectorContext.ConnectorIdentity(
                CONNECTOR_ID, TENANT_ID, "entra"));
    }

    @AfterEach
    void clearContext() {
        ScimConnectorContext.clear();
    }

    @Test
    void rejectsProvisionedMemberWhenInheritedRolesWouldViolateSod() {
        UUID groupId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        DirectoryGroup group = group(groupId);
        User user = user(21L, userId, 2L);
        when(groupRepository.findByPublicIdAndTenantIdForUpdate(groupId, TENANT_ID))
                .thenReturn(Optional.of(group));
        when(groupRepository.saveAndFlush(group)).thenReturn(group);
        when(userRepository.findByTenantIdAndPublicIdIn(TENANT_ID, Set.of(userId)))
                .thenReturn(List.of(user));
        when(memberRepository.findByTenantIdAndGroupId(TENANT_ID, 30L))
                .thenReturn(List.of());
        when(conflictGuard.evaluateMembershipAddition(TENANT_ID, 30L, Set.of(21L)))
                .thenReturn(Optional.of(new GroupRoleConflictGuard.Violation(
                        21L,
                        "ROLE_CONFLICT_AUDIT_INDEPENDENCE",
                        List.of("AUDITOR", "WORKSPACE_MEMBER"),
                        List.of("HR_ADMIN"))));
        ScimModels.GroupRequest request = new ScimModels.GroupRequest(
                List.of(ScimModels.CORE_GROUP),
                "group-30",
                "People operators",
                List.of(new ScimModels.Member(userId.toString(), "User 21", "User")));

        assertThatThrownBy(() -> service.replace(groupId, request, "W/\"0\"", "corr-sod"))
                .isInstanceOfSatisfying(
                        ScimException.class,
                        error -> assertThat(error.status()).isEqualTo(409));

        verify(memberRepository, never()).deleteByTenantIdAndGroupIdAndSourceType(
                TENANT_ID, 30L, "SCIM");
        verify(auditService).denied(
                "REPLACE", "GROUP", groupId.toString(), "group-30", "corr-sod",
                "ROLE_CONFLICT_AUDIT_INDEPENDENCE");
    }

    @Test
    void deactivationInvalidatesAffectedIdentitySessions() {
        UUID groupId = UUID.randomUUID();
        DirectoryGroup group = group(groupId);
        User user = user(21L, UUID.randomUUID(), 2L);
        DirectoryGroupMember member = DirectoryGroupMember.builder()
                .tenantId(TENANT_ID)
                .groupId(30L)
                .userId(21L)
                .sourceType("SCIM")
                .build();
        AuthSession session = AuthSession.builder()
                .sessionId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .userId(21L)
                .build();
        when(groupRepository.findByPublicIdAndTenantIdForUpdate(groupId, TENANT_ID))
                .thenReturn(Optional.of(group));
        when(groupRepository.saveAndFlush(group)).thenReturn(group);
        when(memberRepository.findByTenantIdAndGroupId(TENANT_ID, 30L))
                .thenReturn(List.of(member));
        when(userRepository.findByTenantIdAndUserIdInForUpdate(TENANT_ID, Set.of(21L)))
                .thenReturn(List.of(user));
        when(sessionRepository.findByTenantIdAndUserIdInAndRevokedAtIsNull(
                        TENANT_ID, Set.of(21L)))
                .thenReturn(List.of(session));

        service.deactivate(groupId, "W/\"0\"", "corr-delete");

        assertThat(user.getAccessRevision()).isEqualTo(3L);
        assertThat(session.getRevokedAt()).isNotNull();
        verify(userRepository).saveAll(List.of(user));
        verify(sessionRepository).saveAll(List.of(session));
        verify(auditService).success(
                "DELETE", "GROUP", groupId.toString(), "group-30", "corr-delete");
    }

    private DirectoryGroup group(UUID publicId) {
        return DirectoryGroup.builder()
                .groupId(30L)
                .publicId(publicId)
                .tenantId(TENANT_ID)
                .groupKey("GROUP_30")
                .displayName("People operators")
                .externalId("group-30")
                .sourceType("SCIM")
                .status("ACTIVE")
                .revision(1L)
                .version(0L)
                .build();
    }

    private User user(Long id, UUID publicId, Long accessRevision) {
        return User.builder()
                .userId(id)
                .publicId(publicId)
                .tenantId(TENANT_ID)
                .displayName("User " + id)
                .status("ACTIVE")
                .accessRevision(accessRevision)
                .build();
    }
}
