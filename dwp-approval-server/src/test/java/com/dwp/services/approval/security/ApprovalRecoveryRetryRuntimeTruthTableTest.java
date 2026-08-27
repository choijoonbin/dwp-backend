package com.dwp.services.approval.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.approval.api.ApprovalAdminController;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.domain.ApprovalResponseProjection;
import com.dwp.services.approval.domain.ApprovalService;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises the real filter-controller-service boundary for recovery retry rollout states. */
class ApprovalRecoveryRetryRuntimeTruthTableTest {

    private static final UUID OUTBOX_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000004");
    private static final String PATH = "/v1/admin/operations/events/" + OUTBOX_ID + "/retry";
    private static final String PUBLIC_PATH = "/api/approvals" + PATH;
    private static final String ROUTE_KEY =
            "route.approvals.admin.operations.retry.action";
    private static final String DECISION_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    private static final String ROLLOUT_REVISION =
            "rollout-" + "0123456789abcdef".repeat(4);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ApprovalQueryRepository queries = mock(ApprovalQueryRepository.class);
    private final ApprovalCommandRepository commands = mock(ApprovalCommandRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final ApprovalHighRiskCommandGuard guard = mock(ApprovalHighRiskCommandGuard.class);
    private final ApprovalService service = new ApprovalService(
            queries,
            commands,
            audit,
            mock(ApprovalIdentityDirectory.class),
            guard,
            mock(ApprovalOwnerPredicateEvaluator.class));
    private final ApprovalAdminController controller = new ApprovalAdminController(
            service, mock(ApprovalResponseProjection.class));
    private final ApprovalSecurityFilter filter = new ApprovalSecurityFilter(
            "trusted", "", true, objectMapper);
    private final AtomicBoolean controllerInvoked = new AtomicBoolean();

    @BeforeEach
    void stubOperationsProjection() {
        when(queries.adminPulse(42L)).thenReturn(new ApprovalDtos.AdminPulse(
                0, 0, 0, 0, 0, List.of()));
        when(queries.breachedTasks(42L, 20)).thenReturn(List.of());
        when(queries.integrationDeliveries(42L, 50)).thenReturn(List.of());
    }

    @AfterEach
    void clearContexts() {
        ApprovalDecisionRevisionContext.clear();
        ApprovalPilotAuthorizationContext.clear();
        ApprovalRequestContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"000", "100"})
    void baselineAndShadowExecuteTheLegacyBodylessCommandWithoutHeaders(String state)
            throws Exception {
        Invocation invocation = invoke(state, false, null, null, null);

        assertThat(invocation.response().getStatus()).isEqualTo(200);
        assertThat(invocation.apiResponse()).isNotNull();
        assertThat(invocation.apiResponse().getSuccess()).isTrue();
        assertThat(controllerInvoked.get()).isTrue();
        verify(commands).retryIntegrationDelivery(any(ApprovalRequestContext.Actor.class),
                eq(OUTBOX_ID));
        verify(commands, never()).retryIntegrationDelivery(
                any(ApprovalRequestContext.Actor.class), eq(OUTBOX_ID), anyLong());
        verifyNoInteractions(guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"110", "111"})
    void enforcementWithoutAnyCommandHeaderFailsBeforeTheController(String state)
            throws Exception {
        Invocation invocation = invoke(state, false, null, null, null);

        assertThat(invocation.response().getStatus())
                .isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT.getHttpStatus().value());
        assertThat(invocation.response().getContentAsString())
                .contains(ErrorCode.DECISION_REVISION_CONFLICT.getCode());
        assertThat(controllerInvoked.get()).isFalse();
        verifyNoInteractions(commands, guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"110", "111"})
    void enforcementCannotFallBackToLegacyWhenObjectVersionIsMissing(String state) {
        assertThatThrownBy(() -> invoke(state, true, null, null, null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));

        assertThat(controllerInvoked.get()).isTrue();
        verifyNoInteractions(commands, guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"110", "111"})
    void enforcementWithCompleteHeadersUsesTheGovernedVersionedCommand(String state)
            throws Exception {
        Invocation invocation = invoke(
                state, true, 2L, "signed-recovery-challenge", "retry-idempotency");

        assertThat(invocation.response().getStatus()).isEqualTo(200);
        assertThat(invocation.apiResponse()).isNotNull();
        assertThat(controllerInvoked.get()).isTrue();
        verify(commands, never()).retryIntegrationDelivery(
                any(ApprovalRequestContext.Actor.class), eq(OUTBOX_ID));
        verify(commands).retryIntegrationDelivery(
                any(ApprovalRequestContext.Actor.class), eq(OUTBOX_ID), eq(2L));
        verify(guard).begin(
                any(ApprovalRequestContext.Actor.class),
                eq("approvals.operations.execute"),
                eq("OUTBOX_EVENT"),
                eq(OUTBOX_ID),
                eq(2L),
                eq(PUBLIC_PATH),
                eq(Map.of()),
                eq(ApprovalStepUpHeaders.of(
                        "signed-recovery-challenge",
                        "retry-idempotency",
                        DECISION_REVISION,
                        2L)));
    }

    @Test
    void decisionEvidenceWithoutHighAuthorityStillPreventsLegacyFallback() {
        ApprovalRequestContext.set(
                17L,
                42L,
                null,
                Set.of("APPROVAL_OPERATOR"),
                Set.of("ADMIN.APPROVAL_OPERATIONS:EXECUTE"));
        ApprovalDecisionRevisionContext.set(
                DECISION_REVISION,
                OffsetDateTime.now().plusMinutes(5),
                "approval-management",
                "S_APPROVALS",
                ROUTE_KEY,
                "110");

        assertThatThrownBy(() -> controller.retryIntegrationDelivery(
                OUTBOX_ID, null, null, null, null, "correlation"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        verifyNoInteractions(commands, guard);
    }

    private Invocation invoke(
            String state,
            boolean includeExpectedDecisionRevision,
            Long expectedVersion,
            String challenge,
            String idempotencyKey) throws Exception {
        controllerInvoked.set(false);
        MockHttpServletRequest request = request(state);
        if (includeExpectedDecisionRevision) {
            request.addHeader(
                    ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                    DECISION_REVISION);
        }
        if (expectedVersion != null) {
            request.addHeader("X-DWP-Expected-Object-Version", expectedVersion.toString());
        }
        if (challenge != null) {
            request.addHeader("X-DWP-Step-Up-Challenge", challenge);
        }
        if (idempotencyKey != null) {
            request.addHeader("Idempotency-Key", idempotencyKey);
        }
        AtomicReference<ApiResponse<ApprovalDtos.OperationsResponse>> api =
                new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            controllerInvoked.set(true);
            api.set(controller.retryIntegrationDelivery(
                    OUTBOX_ID,
                    expectedVersion,
                    challenge,
                    idempotencyKey,
                    includeExpectedDecisionRevision ? DECISION_REVISION : null,
                    "runtime-truth-table"));
        });
        return new Invocation(response, api.get());
    }

    private MockHttpServletRequest request(String state) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(ApprovalSecurityFilter.USER_HEADER, "17");
        request.addHeader(ApprovalSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(ApprovalSecurityFilter.ROLES_HEADER, "APPROVAL_OPERATOR");
        request.addHeader(
                ApprovalSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.APPROVAL_OPERATIONS:MANAGE,ADMIN.APPROVAL_OPERATIONS:EXECUTE");
        request.addHeader(
                ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                state.charAt(1) == '1'
                        ? "APP_CONFIG_ADMIN@RS_APPROVALS," + ScopedAuthorityToken.wireToken(
                                "approvals.operations.execute",
                                "ADMIN.APPROVAL_OPERATIONS:EXECUTE",
                                "RS_APPROVALS")
                        : "APP_CONFIG_ADMIN@RS_APPROVALS");
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER, state);
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_REVISION_HEADER, ROLLOUT_REVISION);
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_COHORT_HEADER, "baseline");
        if (state.charAt(1) == '1') {
            request.addHeader(ApprovalSecurityFilter.ROUTE_CONTRACT_HEADER, ROUTE_KEY);
            request.addHeader(
                    ApprovalSecurityFilter.ACTIVE_ACCESS_MODE_HEADER,
                    ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL.name());
            request.addHeader(
                    ApprovalSecurityFilter.CURRENT_DECISION_REVISION_HEADER,
                    DECISION_REVISION);
            request.addHeader(
                    ApprovalSecurityFilter.CURRENT_DECISION_REVALIDATE_AT_HEADER,
                    "2030-01-01T00:00:00Z");
            request.addHeader(
                    ApprovalSecurityFilter.CURRENT_CONTEXT_HEADER,
                    "approval-management");
            request.addHeader(ApprovalSecurityFilter.CURRENT_SCOPE_HEADER,
                    ProductSurfaceScopeKey.resourceSet(
                            42L, 17L, "approvals", "approvals.admin", "RS_APPROVALS"));
        }
        return request;
    }

    private record Invocation(
            MockHttpServletResponse response,
            ApiResponse<ApprovalDtos.OperationsResponse> apiResponse) {
    }
}
