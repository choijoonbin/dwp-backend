package com.dwp.services.approval.analytics;

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

class ApprovalAnalyticsHttpAuthorityTest {
    private static final long USER_ID = 17L;
    private static final long TENANT_ID = 42L;
    private static final UUID PERSON_ID = UUID.randomUUID();
    private static final String VIEW = "ADMIN.APPROVAL_OPERATIONS:VIEW";
    private static final String EXECUTE = "ADMIN.APPROVAL_OPERATIONS:EXECUTE";

    @AfterEach
    void clearContexts() {
        ApprovalRequestContext.clear();
        ReflectionTestUtils.invokeMethod(ApprovalManagementScopeContext.class, "clear");
    }

    @Test
    void revokedExecuteManageAndAuditorRoleCannotEnableRepresentativeDrillDown() {
        ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
        ApprovalRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, "Former auditor",
                Set.of("APPROVAL_ADMIN", "APPROVAL_AUDITOR"),
                Set.of(VIEW, EXECUTE, "ADMIN.APPROVAL_OPERATIONS:MANAGE"));
        when(identities.require(TENANT_ID, USER_ID)).thenReturn(
                new ApprovalIdentityDirectory.Subject(
                        TENANT_ID, USER_ID, UUID.randomUUID(), PERSON_ID,
                        "Current operator", "operator@example.test", "Administrator",
                        "ACTIVE", List.of("APPROVAL_ADMIN"),
                        List.of("APP.APPROVALS:VIEW", VIEW)));
        ReflectionTestUtils.invokeMethod(
                ApprovalManagementScopeContext.class, "set", "analytics-scope", "RS_APPROVALS");
        ApprovalAnalyticsHttpAuthority authority = new ApprovalAnalyticsHttpAuthority(
                new ApprovalWorkAuthority(identities));

        ApprovalAnalyticsModels.Scope scope = authority.scope();

        assertThat(scope.capabilities()).containsExactly(ApprovalAnalyticsModels.Capability.VIEW);
    }
}
