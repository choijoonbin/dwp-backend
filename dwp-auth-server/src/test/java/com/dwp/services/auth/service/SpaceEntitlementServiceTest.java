package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.identity.SpaceEntitlementDtos;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.PrincipalResourceGrantRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpaceEntitlementServiceTest {

    private final PrincipalResourceGrantRepository grants =
            mock(PrincipalResourceGrantRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DirectoryGroupRepository groups = mock(DirectoryGroupRepository.class);
    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);

    private SpaceEntitlementService service;

    @BeforeEach
    void setUp() {
        service = new SpaceEntitlementService(
                grants, users, groups, resources, permissions, audit);
        when(users.findByUserIdAndTenantId(7L, 1L)).thenReturn(Optional.of(
                User.builder().tenantId(1L).userId(7L).status("ACTIVE").build()));
        when(users.findByUserIdAndTenantId(12L, 1L)).thenReturn(Optional.of(
                User.builder().tenantId(1L).userId(12L).status("ACTIVE").build()));
        when(resources.findByTenantIdAndTypeAndKey(1L, "SPACE", "SPACE.PROJECT-ALPHA"))
                .thenReturn(Optional.of(resource()));
        when(resources.save(any(Resource.class))).thenReturn(resource());
        when(permissions.findByCode("VIEW")).thenReturn(Optional.of(
                Permission.builder().permissionId(3L).code("VIEW").name("View").build()));
    }

    @Test
    void grantsAMembershipEntitlementExactlyOnce() {
        PrincipalResourceGrantRepository.GrantRecord created = record(
                UUID.randomUUID(), "ACTIVE", 0L);
        when(grants.findBySource(1L, "SPACE_MEMBERSHIP", "space:member:view"))
                .thenReturn(Optional.empty());
        when(grants.grant(
                1L, "USER", "7", 21L, 3L, "SPACE_MEMBERSHIP", "space:member:view",
                null, "Space membership desired state", 12L)).thenReturn(created);

        SpaceEntitlementDtos.SyncResult result = service.synchronize(
                1L, "space:member:view", "corr-1", request("GRANT"));

        assertThat(result.changed()).isTrue();
        assertThat(result.lifecycleState()).isEqualTo("ACTIVE");
        verify(audit).success(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reactivatesTheSameEvidenceBoundGrant() {
        PrincipalResourceGrantRepository.GrantRecord revoked = record(
                UUID.randomUUID(), "REVOKED", 4L);
        PrincipalResourceGrantRepository.GrantRecord active = record(
                revoked.grantId(), "ACTIVE", 5L);
        when(grants.findBySource(1L, "SPACE_MEMBERSHIP", "space:member:view"))
                .thenReturn(Optional.of(revoked))
                .thenReturn(Optional.of(active));
        when(grants.reactivate(
                1L, "SPACE_MEMBERSHIP", "space:member:view", null,
                "Space membership desired state", 12L, 4L)).thenReturn(true);

        SpaceEntitlementDtos.SyncResult result = service.synchronize(
                1L, "space:member:view", "corr-2", request("GRANT"));

        assertThat(result.changed()).isTrue();
        assertThat(result.version()).isEqualTo(5L);
        verify(grants, never()).grant(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsEvidenceReuseForAnotherSpaceOrPrincipal() {
        PrincipalResourceGrantRepository.GrantRecord conflicting =
                new PrincipalResourceGrantRepository.GrantRecord(
                        UUID.randomUUID(), 1L, "USER", "99", "SPACE.OTHER", "VIEW",
                        "SPACE_MEMBERSHIP", "space:member:view", "ACTIVE",
                        OffsetDateTime.now(), null, "Original membership state", 0L);
        when(grants.findBySource(1L, "SPACE_MEMBERSHIP", "space:member:view"))
                .thenReturn(Optional.of(conflicting));

        assertThatThrownBy(() -> service.synchronize(
                1L, "space:member:view", "corr-3", request("GRANT")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void validatesAnActiveSpacePrincipalWithoutCreatingAGrant() {
        SpaceEntitlementDtos.PrincipalValidationResult result = service.validatePrincipal(
                1L, new SpaceEntitlementDtos.PrincipalValidationRequest("USER", "7", 12L));

        assertThat(result.active()).isTrue();
        assertThat(result.canonicalRef()).isEqualTo("7");
        verify(grants, never()).grant(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAnInactiveRecoveryPrincipal() {
        when(users.findByUserIdAndTenantId(9L, 1L)).thenReturn(Optional.of(
                User.builder().tenantId(1L).userId(9L).status("INACTIVE").build()));

        assertThatThrownBy(() -> service.validatePrincipal(
                1L, new SpaceEntitlementDtos.PrincipalValidationRequest("USER", "9", 12L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(grants, never()).grant(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsARecoveryPrincipalOutsideTheTenant() {
        UUID personPublicId = UUID.randomUUID();
        when(users.findByTenantIdAndPersonPublicId(1L, personPublicId))
                .thenReturn(Optional.empty());
        when(users.findByPublicIdAndTenantId(personPublicId, 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validatePrincipal(
                1L, new SpaceEntitlementDtos.PrincipalValidationRequest(
                        "USER", personPublicId.toString(), 12L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(grants, never()).grant(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private SpaceEntitlementDtos.SyncRequest request(String action) {
        return new SpaceEntitlementDtos.SyncRequest(
                "USER", "7", "SPACE.PROJECT-ALPHA", "Project Alpha", "VIEW", action,
                null, "Space membership desired state", 12L);
    }

    private Resource resource() {
        return Resource.builder()
                .resourceId(21L).tenantId(1L).type("SPACE")
                .key("SPACE.PROJECT-ALPHA").name("Project Alpha")
                .enabled(true).build();
    }

    private PrincipalResourceGrantRepository.GrantRecord record(
            UUID grantId, String lifecycleState, long version) {
        return new PrincipalResourceGrantRepository.GrantRecord(
                grantId, 1L, "USER", "7", "SPACE.PROJECT-ALPHA", "VIEW",
                "SPACE_MEMBERSHIP", "space:member:view", lifecycleState,
                OffsetDateTime.now(), null, "Space membership desired state", version);
    }
}
