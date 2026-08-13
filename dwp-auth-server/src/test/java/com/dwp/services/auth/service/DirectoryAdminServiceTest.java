package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.DirectoryAdminDtos;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.DirectoryGroup;
import com.dwp.services.auth.entity.DirectoryGroupMember;
import com.dwp.services.auth.entity.OrganizationUnit;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.OrganizationUnitRepository;
import com.dwp.services.auth.repository.UserRepository;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirectoryAdminServiceTest {

    private static final Long TENANT_ID = 3L;
    private static final Long ACTOR_ID = 7L;

    private final OrganizationUnitRepository organizationRepository =
            mock(OrganizationUnitRepository.class);
    private final DirectoryGroupRepository groupRepository = mock(DirectoryGroupRepository.class);
    private final DirectoryGroupMemberRepository groupMemberRepository =
            mock(DirectoryGroupMemberRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthSessionRepository authSessionRepository = mock(AuthSessionRepository.class);
    private final IdentityAuditService auditService = mock(IdentityAuditService.class);
    private final GroupRoleConflictGuard groupRoleConflictGuard =
            mock(GroupRoleConflictGuard.class);
    private final DirectoryAdminService service = new DirectoryAdminService(
            organizationRepository,
            groupRepository,
            groupMemberRepository,
            userRepository,
            authSessionRepository,
            auditService,
            groupRoleConflictGuard);

    @Test
    void rejectsAnOrganizationParentThatWouldCreateACycle() {
        OrganizationUnit parent = organization(10L, null, "LOCAL", "ACTIVE", 1L, 0L);
        OrganizationUnit child = organization(11L, 10L, "LOCAL", "ACTIVE", 1L, 0L);
        when(organizationRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(parent, child));

        assertConflict(() -> service.updateOrganization(
                TENANT_ID,
                ACTOR_ID,
                "corr-cycle",
                10L,
                new DirectoryAdminDtos.UpdateOrganizationUnitRequest(
                        "Parent", null, 11L, 0L)));

        verify(organizationRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDeactivatingAnOrganizationWithActiveChildren() {
        OrganizationUnit organization =
                organization(10L, null, "LOCAL", "ACTIVE", 1L, 0L);
        when(organizationRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(organization));
        when(userRepository.countByTenantIdAndPrimaryOrgUnitId(TENANT_ID, 10L))
                .thenReturn(0L);
        when(organizationRepository.countByTenantIdAndParentOrgUnitIdAndStatus(
                        TENANT_ID, 10L, "ACTIVE"))
                .thenReturn(1L);

        assertConflict(() -> service.changeOrganizationStatus(
                TENANT_ID,
                ACTOR_ID,
                null,
                10L,
                "INACTIVE",
                new DirectoryAdminDtos.LifecycleRequest(0L)));

        verify(organizationRepository, never()).saveAndFlush(any());
    }

    @Test
    void movesOrganizationMembersBumpsIdentityContextRevokesSessionsAndAudits() {
        OrganizationUnit target = organization(10L, null, "LOCAL", "ACTIVE", 2L, 0L);
        OrganizationUnit previous = organization(11L, null, "LOCAL", "ACTIVE", 4L, 0L);
        User user = user(21L, 11L, "ACTIVE", 5L);
        AuthSession session = AuthSession.builder()
                .sessionId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .userId(21L)
                .build();
        when(organizationRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(target, previous));
        when(userRepository.findByTenantIdAndPrimaryOrgUnitIdOrderByDisplayNameAscUserIdAsc(
                        TENANT_ID, 10L))
                .thenReturn(List.of());
        when(userRepository.findByTenantIdAndUserIdInForUpdate(TENANT_ID, Set.of(21L)))
                .thenReturn(List.of(user));
        when(authSessionRepository.findByTenantIdAndUserIdInAndRevokedAtIsNull(
                        TENANT_ID, Set.of(21L)))
                .thenReturn(List.of(session));

        DirectoryAdminDtos.OrganizationUnitDetail result = service.replaceOrganizationMembers(
                TENANT_ID,
                ACTOR_ID,
                "corr-org-members",
                10L,
                new DirectoryAdminDtos.ReplaceMembersRequest(Set.of(21L), 0L));

        assertThat(result.organization().memberCount()).isEqualTo(1L);
        assertThat(result.members()).extracting(DirectoryAdminDtos.DirectoryMemberSummary::userId)
                .containsExactly(21L);
        assertThat(user.getPrimaryOrgUnitId()).isEqualTo(10L);
        assertThat(user.getAccessRevision()).isEqualTo(6L);
        assertThat(target.getRevision()).isEqualTo(3L);
        assertThat(previous.getRevision()).isEqualTo(5L);
        assertThat(session.getRevokedAt()).isNotNull();
        verify(auditService, times(2)).success(
                eq(TENANT_ID), eq(ACTOR_ID), any(), any(), any(), eq("corr-org-members"), any(), any());
    }

    @Test
    void rejectsLocalChangesToExternallyManagedOrganizations() {
        OrganizationUnit organization =
                organization(10L, null, "SCIM", "ACTIVE", 1L, 0L);
        when(organizationRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(organization));

        assertConflict(() -> service.updateOrganization(
                TENANT_ID,
                ACTOR_ID,
                null,
                10L,
                new DirectoryAdminDtos.UpdateOrganizationUnitRequest(
                        "Managed", null, null, 0L)));

        verify(organizationRepository, never()).saveAndFlush(any());
    }

    @Test
    void replacesDirectGroupMembersAndRevokesAffectedSessions() {
        DirectoryGroup group = group(30L, "LOCAL", "ACTIVE", 1L, 0L);
        User removedUser = user(21L, null, "ACTIVE", 2L);
        User addedUser = user(22L, null, "ACTIVE", 3L);
        DirectoryGroupMember current = DirectoryGroupMember.builder()
                .groupMemberId(40L)
                .tenantId(TENANT_ID)
                .groupId(30L)
                .userId(21L)
                .sourceType("LOCAL")
                .build();
        when(groupRepository.findByGroupIdAndTenantIdForUpdate(30L, TENANT_ID))
                .thenReturn(Optional.of(group));
        when(groupMemberRepository.findByTenantIdAndGroupId(TENANT_ID, 30L))
                .thenReturn(List.of(current));
        when(userRepository.findByTenantIdAndUserIdInForUpdate(
                        TENANT_ID, Set.of(21L, 22L)))
                .thenReturn(List.of(removedUser, addedUser));
        when(authSessionRepository.findByTenantIdAndUserIdInAndRevokedAtIsNull(
                        TENANT_ID, Set.of(21L, 22L)))
                .thenReturn(List.of());
        when(groupRepository.saveAndFlush(group)).thenReturn(group);

        DirectoryAdminDtos.DirectoryGroupDetail result = service.replaceGroupMembers(
                TENANT_ID,
                ACTOR_ID,
                "corr-group-members",
                30L,
                new DirectoryAdminDtos.ReplaceMembersRequest(Set.of(22L), 0L));

        assertThat(result.members()).extracting(DirectoryAdminDtos.DirectoryMemberSummary::userId)
                .containsExactly(22L);
        assertThat(removedUser.getAccessRevision()).isEqualTo(3L);
        assertThat(addedUser.getAccessRevision()).isEqualTo(4L);
        assertThat(group.getRevision()).isEqualTo(2L);
        verify(groupMemberRepository).deleteAll(List.of(current));
        verify(groupMemberRepository).saveAll(any());
        verify(auditService).success(
                eq(TENANT_ID),
                eq(ACTOR_ID),
                eq("directory.group.members.replaced"),
                eq("DIRECTORY_GROUP"),
                eq("30"),
                eq("corr-group-members"),
                any(),
                any());
    }

    @Test
    void rejectsDeactivatingAGroupWithMembers() {
        DirectoryGroup group = group(30L, "LOCAL", "ACTIVE", 1L, 0L);
        when(groupRepository.findByGroupIdAndTenantIdForUpdate(30L, TENANT_ID))
                .thenReturn(Optional.of(group));
        when(groupMemberRepository.countByTenantIdAndGroupId(TENANT_ID, 30L))
                .thenReturn(1L);

        assertConflict(() -> service.changeGroupStatus(
                TENANT_ID,
                ACTOR_ID,
                null,
                30L,
                "INACTIVE",
                new DirectoryAdminDtos.LifecycleRequest(0L)));

        verify(groupRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsGroupMemberAdditionWhenInheritedRolesWouldViolateSod() {
        DirectoryGroup group = group(30L, "LOCAL", "ACTIVE", 1L, 0L);
        User requestedUser = user(22L, null, "ACTIVE", 3L);
        when(groupRepository.findByGroupIdAndTenantIdForUpdate(30L, TENANT_ID))
                .thenReturn(Optional.of(group));
        when(groupMemberRepository.findByTenantIdAndGroupId(TENANT_ID, 30L))
                .thenReturn(List.of());
        when(userRepository.findByTenantIdAndUserIdInForUpdate(TENANT_ID, Set.of(22L)))
                .thenReturn(List.of(requestedUser));
        when(groupRoleConflictGuard.evaluateMembershipAddition(
                        TENANT_ID, 30L, Set.of(22L)))
                .thenReturn(Optional.of(new GroupRoleConflictGuard.Violation(
                        22L,
                        "ROLE_CONFLICT_AUDIT_INDEPENDENCE",
                        List.of("AUDITOR", "WORKSPACE_MEMBER"),
                        List.of("HR_ADMIN"))));

        assertThatThrownBy(() -> service.replaceGroupMembers(
                        TENANT_ID,
                        ACTOR_ID,
                        "corr-group-sod",
                        30L,
                        new DirectoryAdminDtos.ReplaceMembersRequest(Set.of(22L), 0L)))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(groupMemberRepository, never()).saveAll(any());
        verify(groupRepository, never()).saveAndFlush(any());
        verify(auditService).denied(
                eq(TENANT_ID), eq(ACTOR_ID), eq("directory.group.members.rejected"),
                eq("DIRECTORY_GROUP"), eq("30"), eq("corr-group-sod"),
                eq("ROLE_CONFLICT_AUDIT_INDEPENDENCE"), any());
    }

    private OrganizationUnit organization(
            Long id,
            Long parentId,
            String source,
            String status,
            Long revision,
            Long version) {
        return OrganizationUnit.builder()
                .orgUnitId(id)
                .tenantId(TENANT_ID)
                .orgKey("ORG_" + id)
                .name("Organization " + id)
                .parentOrgUnitId(parentId)
                .sourceType(source)
                .status(status)
                .revision(revision)
                .version(version)
                .build();
    }

    private DirectoryGroup group(
            Long id,
            String source,
            String status,
            Long revision,
            Long version) {
        return DirectoryGroup.builder()
                .groupId(id)
                .tenantId(TENANT_ID)
                .groupKey("GROUP_" + id)
                .displayName("Group " + id)
                .sourceType(source)
                .status(status)
                .revision(revision)
                .version(version)
                .build();
    }

    private User user(
            Long id,
            Long primaryOrgUnitId,
            String status,
            Long accessRevision) {
        return User.builder()
                .userId(id)
                .tenantId(TENANT_ID)
                .displayName("User " + id)
                .email("user" + id + "@example.com")
                .status(status)
                .accessRevision(accessRevision)
                .primaryOrgUnitId(primaryOrgUnitId)
                .version(0L)
                .build();
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }
}
