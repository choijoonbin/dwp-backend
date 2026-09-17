package com.dwp.services.approval.auditrecords;

import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalAuditHttpAuthorityTest {
    private static final long USER_ID = 17L;
    private static final long TENANT_ID = 42L;
    private static final UUID PERSON_ID = UUID.randomUUID();

    @AfterEach
    void clearContexts() {
        ApprovalRequestContext.clear();
        ReflectionTestUtils.invokeMethod(ApprovalManagementScopeContext.class, "clear");
    }

    @Test
    void revokedExecuteManageAndAuditorRoleCannotBecomeAuditCapabilities() {
        ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
        ApprovalRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, "Former auditor",
                Set.of("APPROVAL_ADMIN", "APPROVAL_AUDITOR"),
                Set.of(ApprovalAuditHttpAuthority.VIEW_PERMISSION,
                        ApprovalAuditHttpAuthority.EXECUTE_PERMISSION,
                        "ADMIN.APPROVAL_OPERATIONS:MANAGE"));
        when(identities.require(TENANT_ID, USER_ID)).thenReturn(
                new ApprovalIdentityDirectory.Subject(
                        TENANT_ID, USER_ID, UUID.randomUUID(), PERSON_ID,
                        "Current operator", "operator@example.test", "Administrator",
                        "ACTIVE", List.of("APPROVAL_ADMIN"),
                        List.of("APP.APPROVALS:VIEW",
                                ApprovalAuditHttpAuthority.VIEW_PERMISSION)));
        ReflectionTestUtils.invokeMethod(
                ApprovalManagementScopeContext.class, "set", "audit-scope", "RS_APPROVALS");
        ApprovalAuditHttpAuthority authority = new ApprovalAuditHttpAuthority(
                new ApprovalWorkAuthority(identities));

        ApprovalAuditModels.Scope scope = authority.readScope();

        assertThat(scope.capabilities()).containsExactly(ApprovalAuditModels.Capability.VIEW);
    }
}
