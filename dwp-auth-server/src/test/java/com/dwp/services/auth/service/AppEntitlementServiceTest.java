package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.identity.AppEntitlementDtos;
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

class AppEntitlementServiceTest {

    private final PrincipalResourceGrantRepository grants =
            mock(PrincipalResourceGrantRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DirectoryGroupRepository groups = mock(DirectoryGroupRepository.class);
    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);

    private AppEntitlementService service;

    @BeforeEach
    void setUp() {
        service = new AppEntitlementService(
                grants, users, groups, resources, permissions, audit);
        when(users.findByUserIdAndTenantId(12L, 1L)).thenReturn(Optional.of(
                User.builder().tenantId(1L).userId(12L).status("ACTIVE").build()));
        when(users.findByUserIdAndTenantId(7L, 1L)).thenReturn(Optional.of(
                User.builder().tenantId(1L).userId(7L).status("ACTIVE").build()));
        when(resources.findByTenantIdAndTypeAndKey(1L, "APP", "APP.MAIL_CALENDAR"))
                .thenReturn(Optional.of(Resource.builder()
                        .resourceId(21L).tenantId(1L).type("APP")
                        .key("APP.MAIL_CALENDAR").name("Mail and calendar")
                        .enabled(true).build()));
        when(permissions.findByCode("VIEW")).thenReturn(Optional.of(
                Permission.builder().permissionId(3L).code("VIEW").name("View").build()));
    }

    @Test
    void grantsAnApprovedRequestExactlyOnce() {
        UUID grantId = UUID.randomUUID();
        var created = record(grantId, "ACTIVE", 0L);
        when(grants.findBySource(1L, "APP_ACCESS_REQUEST", "request-1"))
                .thenReturn(Optional.empty());
        when(grants.grant(
                1L, "USER", "7", 21L, 3L, "APP_ACCESS_REQUEST", "request-1",
                null, "Approved access execution", 12L)).thenReturn(created);

        var result = service.synchronize(
                1L, "request-1", "corr-1",
                request("GRANT", "Approved access execution"));

        assertThat(result.changed()).isTrue();
        assertThat(result.lifecycleState()).isEqualTo("ACTIVE");
        verify(audit).success(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void treatsARepeatedDeliveryAsAnIdempotentSuccess() {
        var existing = record(UUID.randomUUID(), "ACTIVE", 0L);
        when(grants.findBySource(1L, "APP_ACCESS_REQUEST", "request-1"))
                .thenReturn(Optional.of(existing));

        var result = service.synchronize(
                1L, "request-1", "corr-2",
                request("GRANT", "Approved access execution"));

        assertThat(result.changed()).isFalse();
        verify(grants, never()).grant(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsReuseOfAnEvidenceKeyForAnotherSubject() {
        var existing = new PrincipalResourceGrantRepository.GrantRecord(
                UUID.randomUUID(), 1L, "USER", "99", "APP.MAIL_CALENDAR", "VIEW",
                "APP_ACCESS_REQUEST", "request-1", "ACTIVE", OffsetDateTime.now(),
                null, "Original approved execution", 0L);
        when(grants.findBySource(1L, "APP_ACCESS_REQUEST", "request-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.synchronize(
                1L, "request-1", "corr-3",
                request("GRANT", "Approved access execution")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    private AppEntitlementDtos.SyncRequest request(String action, String justification) {
        return new AppEntitlementDtos.SyncRequest(
                "USER", "7", "APP.MAIL_CALENDAR", "VIEW", action,
                null, 12L, justification);
    }

    private PrincipalResourceGrantRepository.GrantRecord record(
            UUID grantId, String state, long version) {
        return new PrincipalResourceGrantRepository.GrantRecord(
                grantId, 1L, "USER", "7", "APP.MAIL_CALENDAR", "VIEW",
                "APP_ACCESS_REQUEST", "request-1", state, OffsetDateTime.now(),
                null, "Approved access execution", version);
    }
}
