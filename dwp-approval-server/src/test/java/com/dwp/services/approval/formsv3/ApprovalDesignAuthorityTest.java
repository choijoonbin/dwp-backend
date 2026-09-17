package com.dwp.services.approval.formsv3;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalFormManagementScopeTestSupport;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.templates.ApprovalTemplateAuthority;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalDesignAuthorityTest {
    private static final Set<String> PERMISSIONS = Set.of("ADMIN.APPROVAL_DESIGN:VIEW");
    private final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    private final ApprovalFormV3Authority forms = new ApprovalFormV3Authority(identities);
    private final ApprovalTemplateAuthority templates = new ApprovalTemplateAuthority(identities);

    @BeforeEach
    void context() {
        ApprovalRequestContext.set(99L, 42L, UUID.randomUUID(), "Approval Admin",
                Set.of("TENANT_ADMIN"), PERMISSIONS);
        ApprovalFormManagementScopeTestSupport.set("opaque-scope", "RS_APPROVALS");
    }

    @AfterEach
    void clear() {
        ApprovalRequestContext.clear();
        ApprovalFormManagementScopeTestSupport.clear();
    }

    @Test
    void currentIdentityRemovalIsForbiddenForBothDesignAuthorities() {
        when(identities.require(42L, 99L)).thenThrow(new BaseException(ErrorCode.NOT_FOUND));

        denied(forms, ErrorCode.FORBIDDEN);
        denied(templates, ErrorCode.FORBIDDEN);
    }

    @Test
    void identityResolutionFailureIsServiceUnavailableForBothDesignAuthorities() {
        when(identities.require(42L, 99L)).thenThrow(new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR));

        denied(forms, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        denied(templates, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void postReadRevocationFailsTheUnchangedFence() {
        var active = subject("ACTIVE", List.of("TENANT_ADMIN"), List.copyOf(PERMISSIONS));
        var revoked = subject("INACTIVE", List.of("TENANT_ADMIN"), List.copyOf(PERMISSIONS));
        when(identities.require(42L, 99L)).thenReturn(active, revoked);
        ApprovalFormV3Authority.Access access = forms.require("VIEW");

        assertThatThrownBy(() -> forms.unchanged(access, "VIEW"))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    private void denied(ApprovalFormV3Authority authority, ErrorCode code) {
        assertThatThrownBy(() -> authority.require("VIEW"))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode()).isEqualTo(code));
    }

    private void denied(ApprovalTemplateAuthority authority, ErrorCode code) {
        assertThatThrownBy(() -> authority.require("VIEW"))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode()).isEqualTo(code));
    }

    private ApprovalIdentityDirectory.Subject subject(String status, List<String> roles, List<String> permissions) {
        return new ApprovalIdentityDirectory.Subject(42L, 99L, UUID.randomUUID(), UUID.randomUUID(),
                "Approval Admin", "admin@example.com", "Administrator", status, roles, permissions);
    }
}
