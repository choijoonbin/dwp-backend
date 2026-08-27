package com.dwp.services.auth.identity;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.DirectoryGroup;
import com.dwp.services.auth.entity.DirectoryGroupMember;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentitySubjectLookupServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private RoleMemberRepository roleMembers;

    @Mock
    private RoleRepository roles;

    @Mock
    private DirectoryGroupMemberRepository groupMembers;

    @Mock
    private DirectoryGroupRepository groups;

    @Mock
    private EffectivePermissionKeyResolver permissionKeys;

    @Test
    void returnsOnlyTheRequestedTenantUser() {
        User user = User.builder()
                .userId(19L)
                .tenantId(4L)
                .publicId(UUID.randomUUID())
                .displayName("Custody Target")
                .email("target@example.com")
                .status("ACTIVE")
                .identityPlane("TENANT")
                .build();
        when(users.findTenantIdentityByUserIdAndTenantId(19L, 4L))
                .thenReturn(Optional.of(user));
        when(permissionKeys.resolve(4L, 19L))
                .thenReturn(List.of("APP.WORK:VIEW"));

        IdentitySubjectLookupService.Subject result =
                service().subject(4L, 19L);

        assertThat(result.tenantId()).isEqualTo(4L);
        assertThat(result.userId()).isEqualTo(19L);
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.identityPlane()).isEqualTo("TENANT");
        assertThat(result.permissionKeys()).containsExactly("APP.WORK:VIEW");
    }

    @Test
    void masksMissingOrCrossTenantUsersAsNotFound() {
        when(users.findTenantIdentityByUserIdAndTenantId(19L, 4L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().subject(4L, 19L))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsProviderIdentityEvenIfTheRepositoryContractIsViolated() {
        User provider = User.builder()
                .userId(900001L)
                .tenantId(4L)
                .publicId(UUID.randomUUID())
                .displayName("Provider operator")
                .email("provider@dwp.local")
                .status("ACTIVE")
                .identityPlane("PROVIDER")
                .build();
        when(users.findTenantIdentityByUserIdAndTenantId(900001L, 4L))
                .thenReturn(Optional.of(provider));

        assertThatThrownBy(() -> service().subject(4L, 900001L))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(roleMembers, roles, groupMembers, groups, permissionKeys);
    }

    @Test
    void returnsOnlyActiveDirectoryGroupPublicReferences() {
        UUID activeGroupRef = UUID.randomUUID();
        User user = User.builder()
                .userId(19L)
                .tenantId(4L)
                .publicId(UUID.randomUUID())
                .displayName("Custody Target")
                .status("ACTIVE")
                .identityPlane("TENANT")
                .build();
        when(users.findTenantIdentityByUserIdAndTenantId(19L, 4L))
                .thenReturn(Optional.of(user));
        when(groupMembers.findByTenantIdAndUserId(4L, 19L)).thenReturn(List.of(
                DirectoryGroupMember.builder().tenantId(4L).groupId(81L).userId(19L).build(),
                DirectoryGroupMember.builder().tenantId(4L).groupId(82L).userId(19L).build()));
        when(groups.findByTenantIdAndGroupIdInAndStatus(
                org.mockito.ArgumentMatchers.eq(4L),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq("ACTIVE")))
                .thenReturn(List.of(DirectoryGroup.builder()
                        .groupId(81L)
                        .tenantId(4L)
                        .publicId(activeGroupRef)
                        .groupKey("finance-approvers")
                        .displayName("Finance approvers")
                        .status("ACTIVE")
                        .build()));

        IdentitySubjectLookupService.Subject result = service().subject(4L, 19L);

        assertThat(result.groupRefs()).containsExactly(activeGroupRef);
    }

    @Test
    void searchesTheRequestedTenantWithAStatusBoundaryAndBoundedLimit() {
        User user = User.builder()
                .userId(21L)
                .tenantId(4L)
                .publicId(UUID.randomUUID())
                .personPublicId(UUID.randomUUID())
                .displayName("Approval Delegate")
                .email("delegate@sk.com")
                .jobTitle("Finance manager")
                .status("ACTIVE")
                .identityPlane("TENANT")
                .build();
        when(users.searchTenantDirectoryUsers(
                org.mockito.ArgumentMatchers.eq(4L),
                org.mockito.ArgumentMatchers.eq("delegate"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(user));
        when(permissionKeys.resolve(4L, 21L))
                .thenReturn(List.of("APP.WORK:VIEW"));

        List<IdentitySubjectLookupService.DirectorySubject> result =
                service().search(4L, " delegate ", true, 100);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(21L);
        assertThat(result.getFirst().personPublicId()).isEqualTo(user.getPersonPublicId());
        assertThat(result.getFirst().jobTitle()).isEqualTo("Finance manager");
        assertThat(result.getFirst().permissionKeys())
                .containsExactly("APP.WORK:VIEW");
        verify(users).searchTenantDirectoryUsers(
                org.mockito.ArgumentMatchers.eq(4L),
                org.mockito.ArgumentMatchers.eq("delegate"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.<Pageable>argThat(pageable ->
                        pageable.getPageSize() == 30));
    }

    @Test
    void sourceSearchExcludesProviderAndInvitedIdentitiesDefensively() {
        User inactiveTenant = directoryUser(22L, "INACTIVE", "TENANT");
        User invitedTenant = directoryUser(23L, "INVITED", "TENANT");
        User activeProvider = directoryUser(900001L, "ACTIVE", "PROVIDER");
        when(users.searchTenantDirectoryUsers(
                org.mockito.ArgumentMatchers.eq(4L),
                org.mockito.ArgumentMatchers.eq(""),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(inactiveTenant, invitedTenant, activeProvider));

        List<IdentitySubjectLookupService.DirectorySubject> result =
                service().search(4L, "", false, 30);

        assertThat(result).extracting(IdentitySubjectLookupService.DirectorySubject::userId)
                .containsExactly(22L);
        assertThat(result.getFirst().identityPlane()).isEqualTo("TENANT");
    }

    @Test
    void targetSearchExcludesInactiveIdentitiesDefensively() {
        User activeTenant = directoryUser(21L, "ACTIVE", "TENANT");
        User inactiveTenant = directoryUser(22L, "INACTIVE", "TENANT");
        when(users.searchTenantDirectoryUsers(
                org.mockito.ArgumentMatchers.eq(4L),
                org.mockito.ArgumentMatchers.eq(""),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(activeTenant, inactiveTenant));

        List<IdentitySubjectLookupService.DirectorySubject> result =
                service().search(4L, "", true, 30);

        assertThat(result).extracting(IdentitySubjectLookupService.DirectorySubject::userId)
                .containsExactly(21L);
    }

    private User directoryUser(Long userId, String status, String identityPlane) {
        return User.builder()
                .userId(userId)
                .tenantId(4L)
                .publicId(UUID.randomUUID())
                .displayName("Directory user " + userId)
                .status(status)
                .identityPlane(identityPlane)
                .build();
    }

    private IdentitySubjectLookupService service() {
        return new IdentitySubjectLookupService(
                users, roleMembers, roles, groupMembers, groups, permissionKeys);
    }
}
