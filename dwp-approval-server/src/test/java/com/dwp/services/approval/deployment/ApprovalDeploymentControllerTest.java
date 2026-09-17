package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalDeploymentControllerTest {
    private final ApprovalDeploymentService service = mock(ApprovalDeploymentService.class);
    private final ApprovalDeploymentCommandFacade commands =
            mock(ApprovalDeploymentCommandFacade.class);
    private final ApprovalWorkAuthority workAuthority = mock(ApprovalWorkAuthority.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalDeploymentController(
                        service, commands,
                        new ApprovalDeploymentHttpAuthority(workAuthority)))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        when(workAuthority.requireCurrent(ApprovalDeploymentHttpAuthority.VIEW_PERMISSION))
                .thenReturn(actor());
    }

    @AfterEach
    void clear() {
        ReflectionTestUtils.invokeMethod(ApprovalManagementScopeContext.class, "clear");
    }

    @Test
    void dashboardUsesServerSelectedScopeAndReturns200() throws Exception {
        scope("RS_TEAM_A");
        when(service.dashboard(any())).thenAnswer(invocation -> {
            Scope selected = invocation.getArgument(0);
            if (!"RS_TEAM_A".equals(selected.resourceSetKey())) {
                throw new BaseException(ErrorCode.FORBIDDEN);
            }
            return new DeploymentDashboard(
                    Instant.parse("2026-09-16T00:00:00Z"), List.of(), List.of());
        });

        mvc.perform(get("/v1/admin/operations/deployments/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.environmentHeads.length()").value(0));
    }

    @Test
    void differentSelectedScopeCannotReadAnotherScopesPackage() throws Exception {
        scope("RS_TEAM_B");
        when(service.packageById(any(), any())).thenAnswer(invocation -> {
            Scope selected = invocation.getArgument(0);
            if (!"RS_TEAM_A".equals(selected.resourceSetKey())) {
                throw new BaseException(ErrorCode.FORBIDDEN);
            }
            return mock(PackageRecord.class);
        });

        mvc.perform(get("/v1/admin/operations/deployments/packages/{packageId}",
                        UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void staleRollbackVersionReturns409() throws Exception {
        UUID promotionId = UUID.randomUUID();
        when(commands.requestRollback(
                any(), anyLong(), anyString(), anyString(), any()))
                .thenThrow(new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT));

        mvc.perform(post("/v1/admin/operations/deployments/promotions/{promotionId}/rollback",
                        promotionId)
                        .header("X-DWP-Expected-Object-Version", 4)
                        .header("Idempotency-Key", "rollback-100")
                        .header("X-DWP-Expected-Decision-Revision", "psr-" + "a".repeat(64))
                        .header("X-DWP-Step-Up-Challenge", "signed.challenge.value")
                        .contentType("application/json")
                        .content("{\"reason\":\"Canary health regressed after activation.\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void packageCreateRequiresTheGovernedCreateHeadersAndReturns200() throws Exception {
        String decisionRevision = "psr-" + "a".repeat(64);
        when(commands.createPackage(any(), anyLong(), anyString(), any()))
                .thenReturn(null);

        mvc.perform(post("/v1/admin/operations/deployments/packages")
                        .header("X-DWP-Expected-Object-Version", 0)
                        .header("Idempotency-Key", "package-100")
                        .header("X-DWP-Expected-Decision-Revision", decisionRevision)
                        .header("X-DWP-Step-Up-Challenge", "signed.challenge.value")
                        .contentType("application/json")
                        .content(packageBody(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void packageCreateWithNonZeroCasReturns409BeforeMutation() throws Exception {
        when(commands.createPackage(any(), anyLong(), anyString(), any()))
                .thenThrow(new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT));
        mvc.perform(post("/v1/admin/operations/deployments/packages")
                        .header("X-DWP-Expected-Object-Version", 1)
                        .header("Idempotency-Key", "package-101")
                        .header("X-DWP-Expected-Decision-Revision", "psr-" + "a".repeat(64))
                        .header("X-DWP-Step-Up-Challenge", "signed.challenge.value")
                        .contentType("application/json")
                        .content(packageBody(false)))
                .andExpect(status().isConflict());
    }

    @Test
    void packageCreateRejectsUnknownBodyFields() throws Exception {
        mvc.perform(post("/v1/admin/operations/deployments/packages")
                        .header("X-DWP-Expected-Object-Version", 0)
                        .header("Idempotency-Key", "package-102")
                        .header("X-DWP-Expected-Decision-Revision", "psr-" + "a".repeat(64))
                        .header("X-DWP-Step-Up-Challenge", "signed.challenge.value")
                        .contentType("application/json")
                        .content(packageBody(true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedDeleteIsClosedByTheHttpMethodMatrix() throws Exception {
        mvc.perform(delete("/v1/admin/operations/deployments/promotions/{promotionId}",
                        UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void revokedReadAuthorityReturns403() throws Exception {
        scope("RS_TEAM_A");
        when(workAuthority.requireCurrent(ApprovalDeploymentHttpAuthority.VIEW_PERMISSION))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));

        mvc.perform(get("/v1/admin/operations/deployments/packages"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingManagementScopeReturns503() throws Exception {
        mvc.perform(get("/v1/admin/operations/deployments/packages"))
                .andExpect(status().isServiceUnavailable());
    }

    private ApprovalRequestContext.Actor actor() {
        return new ApprovalRequestContext.Actor(
                17L, 42L, UUID.randomUUID(), "Deployment operator",
                Set.of("APPROVAL_OPERATOR"),
                Set.of(ApprovalDeploymentHttpAuthority.VIEW_PERMISSION));
    }

    private String packageBody(boolean unknownField) {
        String unknown = unknownField ? ",\"unsupportedAction\":true" : "";
        return """
                {
                  "packageId":"%s",
                  "packageKey":"APR.PACKAGE.100",
                  "packageVersion":1,
                  "displayName":"Approval package 100",
                  "assets":[{
                    "assetKey":"workflow.primary",
                    "assetType":"WORKFLOW",
                    "assetId":"%s",
                    "assetVersion":"1",
                    "contentSha256":"%s",
                    "rollbackDisposition":"REVERSIBLE",
                    "externalSideEffects":false
                  }],
                  "dependencies":[]%s
                }
                """.formatted(
                UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64), unknown);
    }

    private void scope(String resourceSet) {
        ReflectionTestUtils.invokeMethod(
                ApprovalManagementScopeContext.class, "set",
                "opaque-" + resourceSet.toLowerCase(), resourceSet);
    }
}
