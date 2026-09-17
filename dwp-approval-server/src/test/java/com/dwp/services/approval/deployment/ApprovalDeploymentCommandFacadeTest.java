package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalDeploymentCommandFacadeTest {
    private static final String REVISION = "psr-" + "a".repeat(64);

    private final ApprovalDeploymentService service =
            mock(ApprovalDeploymentService.class);
    private final ApprovalDeploymentHttpAuthority authority =
            mock(ApprovalDeploymentHttpAuthority.class);
    private final ApprovalDeploymentCommandGuard guard =
            mock(ApprovalDeploymentCommandGuard.class);
    private final ApprovalDeploymentCommandFacade facade =
            new ApprovalDeploymentCommandFacade(service, authority, guard);

    @Test
    void currentAuthorityIsRequiredBeforeCreateCasIsEvaluated() {
        when(authority.command(anyString()))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        PackageCommand command = new PackageCommand(
                UUID.randomUUID(), "APR.PACKAGE.100", 1,
                "Approval package 100", List.of(), List.of());

        assertThatThrownBy(() -> facade.createPackage(
                command, 1, REVISION, headers()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(service, guard);
    }

    private ApprovalStepUpHeaders headers() {
        return ApprovalStepUpHeaders.of(
                "signed.challenge", "package-100", REVISION, 1L);
    }
}
