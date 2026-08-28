package com.dwp.services.provider.provisioning;

import com.dwp.core.autoconfig.DwpHttpClientProperties;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.ProviderEstateRepository;
import com.dwp.services.provider.ProviderOperationsRepository;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.entitlement.EntitlementRepository;
import com.dwp.services.provider.entitlement.TenantEntitlementRepository;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStep;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderProvisioningOrchestratorTest {

    private final ProviderOperationRepository operationRepository = mock(ProviderOperationRepository.class);
    private final ProviderOperationStepRepository stepRepository = mock(ProviderOperationStepRepository.class);
    private final ProviderOperationLeaseRepository leaseRepository = mock(ProviderOperationLeaseRepository.class);
    private final ProviderOperationEvidenceRepository evidenceRepository =
            mock(ProviderOperationEvidenceRepository.class);
    private final ProviderTenantRepository tenantRepository = mock(ProviderTenantRepository.class);
    private final ProviderEstateRepository estateRepository = mock(ProviderEstateRepository.class);
    private final ProviderTenantPlacementRepository placementRepository =
            mock(ProviderTenantPlacementRepository.class);
    private final ProviderOnboardingFoundationVerifier foundationVerifier =
            mock(ProviderOnboardingFoundationVerifier.class);
    private final ProviderOperationsRepository operationsRepository = mock(ProviderOperationsRepository.class);
    private final DownstreamProvisioningClient downstream = mock(DownstreamProvisioningClient.class);
    private final TenantMutationOrchestrator tenantMutationOrchestrator =
            mock(TenantMutationOrchestrator.class);
    private final ProviderAuditService auditService = mock(ProviderAuditService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final UUID leaseToken = UUID.fromString("60000000-0000-0000-0000-000000000099");
    private final ProviderProvisioningOrchestrator orchestrator = newOrchestrator(
            new DwpHttpClientProperties(Duration.ofSeconds(2), Duration.ofSeconds(5)),
            Duration.ofMinutes(5));

    private ProviderProvisioningOrchestrator newOrchestrator(
            DwpHttpClientProperties httpClientProperties,
            Duration leaseDuration) {
        return new ProviderProvisioningOrchestrator(
            operationRepository,
            stepRepository,
            leaseRepository,
            evidenceRepository,
            new ProviderOperationProjectionCoordinator(
                    transactionTemplate, leaseRepository, evidenceRepository),
            new ProviderProvisioningFailureSanitizer(),
            tenantRepository,
            mock(EntitlementRepository.class),
            mock(TenantEntitlementRepository.class),
            estateRepository,
            placementRepository,
            foundationVerifier,
            operationsRepository,
            downstream,
            tenantMutationOrchestrator,
            new ProviderProvisioningAuditRecorder(
                    operationRepository, tenantRepository, operationsRepository, auditService),
            new ObjectMapper(),
            httpClientProperties,
            "provider-onboarding-test",
            leaseDuration);
    }

    @BeforeEach
    void setContext() {
        ProviderRequestContext.setForTest(21L, 1L);
        when(leaseRepository.claim(any(), anyLong(), anyBoolean(), anyString(), any()))
                .thenReturn(leaseToken);
        when(evidenceRepository.startAttempt(any(), any(), any(), anyLong(), anyString()))
                .thenReturn(1);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0)
                        .doInTransaction(null));
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                    .accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void approvedMaintenanceOperationSchedulesItsDraftAndCompletes() {
        UUID operationId = UUID.fromString("60000000-0000-0000-0000-000000000001");
        UUID maintenanceId = UUID.fromString("60000000-0000-0000-0000-000000000002");
        ProviderOperation operation = maintenanceOperation(operationId);
        ProviderOperationStep step = ProviderOperationStep.builder()
                .operationStepId(1L)
                .operationId(operationId)
                .stepOrder(1)
                .stepKey("SCHEDULE_MAINTENANCE")
                .lifecycleState("PENDING")
                .targetService("dwp-provider-server")
                .redactedResult("{}")
                .build();
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(stepRepository.findByOperationIdOrderByStepOrderAsc(operationId)).thenReturn(List.of(step));
        when(operationsRepository.operationApproved(operationId)).thenReturn(true);
        when(operationsRepository.scheduleMaintenanceWindow(operationId, 21L))
                .thenReturn(Optional.of(maintenanceId));
        when(operationsRepository.maintenanceWindowId(operationId)).thenReturn(Optional.of(maintenanceId));
        doAnswer(ignored -> {
            operation.setLifecycleState("SUCCEEDED");
            operation.setVersion(2L);
            return null;
        }).when(leaseRepository).complete(operationId, leaseToken);

        ProviderOperation completed = orchestrator.execute(
                operationId, operation.getPlanHash(), 0L, false, "corr-maintenance");

        assertThat(completed.getLifecycleState()).isEqualTo("SUCCEEDED");
        assertThat(step.getLifecycleState()).isEqualTo("SUCCEEDED");
        assertThat(step.getExternalReference()).isEqualTo(maintenanceId.toString());
        verify(operationsRepository).scheduleMaintenanceWindow(operationId, 21L);
        verify(evidenceRepository).succeedAttempt(
                eq(operationId), eq(leaseToken), eq(Duration.ofMinutes(5)), eq(1L), eq(1),
                eq(maintenanceId.toString()), anyString());
        verify(auditService).success(
                "provider.maintenance.scheduled",
                "MAINTENANCE_WINDOW",
                maintenanceId.toString(),
                null,
                null,
                "corr-maintenance",
                java.util.Map.of("operationId", operationId, "planHash", operation.getPlanHash()));
    }

    @Test
    void maintenanceExecutionCannotBypassItsApprovalGate() {
        UUID operationId = UUID.fromString("60000000-0000-0000-0000-000000000004");
        ProviderOperation operation = maintenanceOperation(operationId);
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(operationsRepository.operationApproved(operationId)).thenReturn(false);

        assertThatThrownBy(() -> orchestrator.execute(
                operationId, operation.getPlanHash(), 0L, false, "corr-unapproved"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("approvals");
        verify(operationsRepository, never()).scheduleMaintenanceWindow(any(), any());
    }

    @Test
    void expiredRetryWithAllStepsSucceededReplaysTerminalAuditBeforeLeaseRelease() {
        UUID operationId = UUID.fromString("60000000-0000-0000-0000-000000000003");
        UUID maintenanceId = UUID.fromString("60000000-0000-0000-0000-000000000015");
        ProviderOperation operation = maintenanceOperation(operationId);
        operation.setLifecycleState("EXECUTING");
        ProviderOperationStep step = ProviderOperationStep.builder()
                .operationStepId(15L)
                .operationId(operationId)
                .stepOrder(1)
                .stepKey("SCHEDULE_MAINTENANCE")
                .lifecycleState("SUCCEEDED")
                .targetService("dwp-provider-server")
                .redactedResult("{\"lifecycle\":\"SCHEDULED\"}")
                .build();
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(stepRepository.findByOperationIdOrderByStepOrderAsc(operationId)).thenReturn(List.of(step));
        when(operationsRepository.operationApproved(operationId)).thenReturn(true);
        when(operationsRepository.maintenanceWindowId(operationId)).thenReturn(Optional.of(maintenanceId));
        doAnswer(ignored -> {
            operation.setLifecycleState("SUCCEEDED");
            return null;
        }).when(leaseRepository).complete(operationId, leaseToken);

        ProviderOperation completed = orchestrator.execute(
                operationId, operation.getPlanHash(), 0L, true, "corr-terminal-retry");

        assertThat(completed.getLifecycleState()).isEqualTo("SUCCEEDED");
        verify(evidenceRepository).abandonRunning(operationId, leaseToken, Duration.ofMinutes(5));
        verify(operationsRepository, never()).scheduleMaintenanceWindow(any(), any());
        verify(auditService).success(
                "provider.maintenance.scheduled", "MAINTENANCE_WINDOW", maintenanceId.toString(),
                null, null, "corr-terminal-retry",
                java.util.Map.of("operationId", operationId, "planHash", operation.getPlanHash()));
        verify(leaseRepository).complete(operationId, leaseToken);
    }

    @Test
    void activeExecutionLeaseCannotBeReclaimedByExplicitRetry() {
        UUID operationId = UUID.fromString("60000000-0000-0000-0000-000000000005");
        ProviderOperation operation = maintenanceOperation(operationId);
        operation.setLifecycleState("EXECUTING");
        operation.setLeaseToken(UUID.randomUUID());
        operation.setLeaseExpiresAt(Instant.now().plusSeconds(60));
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(operationsRepository.operationApproved(operationId)).thenReturn(true);
        when(leaseRepository.claim(
                operationId, 0L, true, "provider-onboarding-test", Duration.ofMinutes(5)))
                .thenThrow(new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The provider operation is already executing or changed before its lease was claimed."));

        assertThatThrownBy(() -> orchestrator.execute(
                operationId, operation.getPlanHash(), 0L, true, "corr-active-lease"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("already executing");

        verify(evidenceRepository, never()).abandonRunning(any(), any(), any());
    }

    @Test
    void leaseMustCoverThreeSequentialDownstreamCallsInOneStep() {
        assertThatThrownBy(() -> newOrchestrator(
                new DwpHttpClientProperties(Duration.ofSeconds(30), Duration.ofSeconds(30)),
                Duration.ofMinutes(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("downstream timeout budget");
    }

    @Test
    void controlRecordRetryRehydratesTheOwnedFoundationWithoutCreatingADuplicateTenant() {
        UUID operationId = UUID.fromString("60000000-0000-0000-0000-000000000006");
        UUID tenantId = UUID.fromString("60000000-0000-0000-0000-000000000007");
        UUID organizationId = UUID.fromString("60000000-0000-0000-0000-000000000008");
        UUID cellId = UUID.fromString("60000000-0000-0000-0000-000000000009");
        ProviderOperation operation = ProviderOperation.builder()
                .operationId(operationId)
                .providerTenantId(tenantId)
                .operationType("TENANT_ONBOARD")
                .idempotencyKey("tenant:onboard:retry")
                .lifecycleState("PARTIAL")
                .riskTier("L2")
                .requestedBy(12L)
                .justification("Recover committed control record")
                .planHash("b".repeat(64))
                .plan(onboardingPlan())
                .version(4L)
                .build();
        ProviderOperationStep step = ProviderOperationStep.builder()
                .operationStepId(2L)
                .operationId(operationId)
                .stepOrder(1)
                .stepKey("CONTROL_RECORD")
                .lifecycleState("FAILED")
                .targetService("dwp-provider-server")
                .redactedResult("{}")
                .attemptCount(1)
                .build();
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .organizationId(organizationId)
                .tenantKey("acme")
                .displayName("Acme")
                .environmentKey("production")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .defaultLocale("en")
                .timeZone("Asia/Seoul")
                .schemaVersion(1)
                .configuration("{}")
                .lifecycleState("PROVISIONING")
                .onboardingState("CONTROL_PLANE_READY")
                .build();
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(stepRepository.findByOperationIdOrderByStepOrderAsc(operationId)).thenReturn(List.of(step));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(placementRepository.initializeOrValidate(
                eq(tenantId), eq("ap-northeast-2"), eq("POOL"), any(), eq(21L), eq(false)))
                .thenReturn(new ProviderTenantPlacementRepository.TenantPlacement(cellId, 4, true));
        when(evidenceRepository.startAttempt(any(), any(), any(), anyLong(), anyString()))
                .thenReturn(2);
        doAnswer(ignored -> {
            operation.setLifecycleState("SUCCEEDED");
            operation.setVersion(6L);
            return null;
        }).when(leaseRepository).complete(operationId, leaseToken);

        ProviderOperation completed = orchestrator.execute(
                operationId, operation.getPlanHash(), 4L, true, "corr-rehydrate");

        assertThat(completed.getLifecycleState()).isEqualTo("SUCCEEDED");
        verify(evidenceRepository).abandonRunning(operationId, leaseToken, Duration.ofMinutes(5));
        verify(foundationVerifier).requireExact(eq(tenant), any());
        verify(tenantRepository, never()).saveAndFlush(any());
        verify(estateRepository, never()).createOrganization(
                anyString(), anyString(), any(), any(), anyLong());
    }

    @Test
    void controlRecordEvidenceFailurePreservesTheCommittedFoundationForExactRetry() {
        UUID operationId = UUID.fromString("60000000-0000-0000-0000-000000000012");
        UUID tenantId = UUID.fromString("60000000-0000-0000-0000-000000000013");
        UUID cellId = UUID.fromString("60000000-0000-0000-0000-000000000014");
        ProviderOperation operation = ProviderOperation.builder()
                .operationId(operationId)
                .providerTenantId(tenantId)
                .operationType("TENANT_ONBOARD")
                .idempotencyKey("tenant:onboard:evidence-failure")
                .lifecycleState("PREVIEWED")
                .riskTier("L2")
                .requestedBy(12L)
                .justification("Evidence recovery regression")
                .planHash("d".repeat(64))
                .plan(onboardingPlan())
                .version(0L)
                .build();
        ProviderOperationStep step = ProviderOperationStep.builder()
                .operationStepId(5L)
                .operationId(operationId)
                .stepOrder(1)
                .stepKey("CONTROL_RECORD")
                .lifecycleState("PENDING")
                .targetService("dwp-provider-server")
                .redactedResult("{}")
                .build();
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .organizationId(UUID.randomUUID())
                .tenantKey("acme")
                .environmentKey("production")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("PROVISIONING")
                .onboardingState("CONTROL_PLANE_READY")
                .build();
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(stepRepository.findByOperationIdOrderByStepOrderAsc(operationId)).thenReturn(List.of(step));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(placementRepository.initializeOrValidate(
                eq(tenantId), eq("ap-northeast-2"), eq("POOL"), any(), eq(21L), eq(false)))
                .thenReturn(new ProviderTenantPlacementRepository.TenantPlacement(cellId, 4, true));
        doThrow(new DataAccessResourceFailureException("simulated evidence outage"))
                .when(evidenceRepository).succeedAttempt(
                        eq(operationId), eq(leaseToken), eq(Duration.ofMinutes(5)), eq(5L), eq(1),
                        anyString(), anyString());
        doAnswer(ignored -> {
            operation.setLifecycleState("PARTIAL");
            return null;
        }).when(leaseRepository).markPartial(
                eq(operationId), eq(leaseToken), eq("PROVISIONING_FAILED"), anyString());

        ProviderOperation partial = orchestrator.execute(
                operationId, operation.getPlanHash(), 0L, false, "corr-evidence-failure");

        assertThat(partial.getLifecycleState()).isEqualTo("PARTIAL");
        assertThat(tenant.getOnboardingState()).isEqualTo("CONTROL_PLANE_READY");
        verify(tenantRepository, never()).saveAndFlush(any());
        verify(evidenceRepository).failAttempt(
                eq(operationId), eq(leaseToken), eq(Duration.ofMinutes(5)), eq(5L), eq(1),
                eq("PROVISIONING_FAILED"),
                eq("Provider state persistence failed. Review the correlated service trace."));
    }

    @Test
    void invalidAuthBindingStopsTheNextDownstreamStepBeforeTenantIdentityPersistence() {
        UUID operationId = UUID.fromString("60000000-0000-0000-0000-000000000010");
        UUID tenantId = UUID.fromString("60000000-0000-0000-0000-000000000011");
        ProviderOperation operation = ProviderOperation.builder()
                .operationId(operationId)
                .providerTenantId(tenantId)
                .operationType("TENANT_ONBOARD")
                .idempotencyKey("tenant:onboard:binding")
                .lifecycleState("PREVIEWED")
                .riskTier("L2")
                .requestedBy(12L)
                .justification("Binding regression")
                .planHash("c".repeat(64))
                .plan(onboardingPlan())
                .version(0L)
                .build();
        ProviderOperationStep authStep = ProviderOperationStep.builder()
                .operationStepId(3L)
                .operationId(operationId)
                .stepOrder(1)
                .stepKey("AUTH_TENANT")
                .lifecycleState("PENDING")
                .targetService("dwp-auth-server")
                .redactedResult("{}")
                .build();
        ProviderOperationStep platformStep = ProviderOperationStep.builder()
                .operationStepId(4L)
                .operationId(operationId)
                .stepOrder(2)
                .stepKey("PLATFORM_TENANT")
                .lifecycleState("PENDING")
                .targetService("dwp-platform-server")
                .redactedResult("{}")
                .build();
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .organizationId(UUID.randomUUID())
                .lifecycleState("PROVISIONING")
                .onboardingState("CONTROL_PLANE_READY")
                .build();
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(stepRepository.findByOperationIdOrderByStepOrderAsc(operationId))
                .thenReturn(List.of(authStep, platformStep));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(downstream.provisionAuth(eq(tenantId), any())).thenThrow(new BaseException(
                ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Auth provisioning returned an invalid tenant binding."));
        doAnswer(ignored -> {
            operation.setLifecycleState("PARTIAL");
            operation.setFailureCode("EXTERNAL_SERVICE_ERROR");
            return null;
        }).when(leaseRepository).markPartial(
                eq(operationId), eq(leaseToken), eq("EXTERNAL_SERVICE_ERROR"), anyString());

        ProviderOperation failed = orchestrator.execute(
                operationId, operation.getPlanHash(), 0L, false, "corr-binding");

        assertThat(failed.getLifecycleState()).isEqualTo("PARTIAL");
        assertThat(tenant.getAuthTenantId()).isNull();
        verify(downstream, never()).provisionPlatform(any(), anyLong(), any());
        verify(placementRepository, never()).updateServiceInstance(
                eq(tenantId), eq("auth"), eq("READY"), any(), any(), anyString(), anyLong());
        verify(evidenceRepository).failAttempt(
                eq(operationId), eq(leaseToken), eq(Duration.ofMinutes(5)), eq(3L), eq(1),
                eq("EXTERNAL_SERVICE_ERROR"), anyString());
    }

    @Test
    void retryableActivationFailurePreservesTheTenantVersionForTheDurableMutationReplay() {
        UUID operationId = UUID.fromString("60000000-0000-0000-0000-000000000020");
        UUID tenantId = UUID.fromString("60000000-0000-0000-0000-000000000021");
        ProviderOperation operation = ProviderOperation.builder()
                .operationId(operationId)
                .providerTenantId(tenantId)
                .operationType("TENANT_ONBOARD")
                .idempotencyKey("tenant:onboard:activation-retry")
                .lifecycleState("PREVIEWED")
                .riskTier("L2")
                .requestedBy(12L)
                .justification("Durable activation retry regression")
                .planHash("e".repeat(64))
                .plan(onboardingPlan())
                .version(0L)
                .build();
        ProviderOperationStep step = ProviderOperationStep.builder()
                .operationStepId(6L)
                .operationId(operationId)
                .stepOrder(1)
                .stepKey("ACTIVATE_TENANT")
                .lifecycleState("PENDING")
                .targetService("dwp-provider-server")
                .redactedResult("{}")
                .build();
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .lifecycleState("PROVISIONING")
                .onboardingState("PENDING_EXTERNAL")
                .version(7L)
                .build();
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(stepRepository.findByOperationIdOrderByStepOrderAsc(operationId))
                .thenReturn(List.of(step));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantMutationOrchestrator.activateForOnboarding(
                tenant, operationId, leaseToken, "corr-activation-retry"))
                .thenThrow(new BaseException(
                        ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "The durable activation is waiting for retry."));
        doAnswer(ignored -> {
            operation.setLifecycleState("PARTIAL");
            return null;
        }).when(leaseRepository).markPartial(
                eq(operationId), eq(leaseToken), eq("EXTERNAL_SERVICE_ERROR"), anyString());

        ProviderOperation partial = orchestrator.execute(
                operationId, operation.getPlanHash(), 0L, false,
                "corr-activation-retry");

        assertThat(partial.getLifecycleState()).isEqualTo("PARTIAL");
        assertThat(tenant.getOnboardingState()).isEqualTo("PENDING_EXTERNAL");
        assertThat(tenant.getVersion()).isEqualTo(7L);
        verify(tenantRepository, never()).saveAndFlush(any());
        verify(evidenceRepository).failAttempt(
                eq(operationId), eq(leaseToken), eq(Duration.ofMinutes(5)), eq(6L), eq(1),
                eq("EXTERNAL_SERVICE_ERROR"), anyString());
    }

    @Test
    void serviceStepRetryCanProceedFromFailedOnboardingIntoDurableActivation() {
        UUID operationId = UUID.fromString("60000000-0000-0000-0000-000000000022");
        UUID tenantId = UUID.fromString("60000000-0000-0000-0000-000000000023");
        ProviderOperation operation = ProviderOperation.builder()
                .operationId(operationId)
                .providerTenantId(tenantId)
                .operationType("TENANT_ONBOARD")
                .idempotencyKey("tenant:onboard:service-retry")
                .lifecycleState("PARTIAL")
                .riskTier("L2")
                .requestedBy(12L)
                .justification("Service retry into durable activation")
                .planHash("f".repeat(64))
                .plan(onboardingPlan())
                .version(0L)
                .build();
        ProviderOperationStep platform = ProviderOperationStep.builder()
                .operationStepId(7L)
                .operationId(operationId)
                .stepOrder(1)
                .stepKey("PLATFORM_TENANT")
                .lifecycleState("FAILED")
                .targetService("dwp-platform-server")
                .attemptCount(1)
                .redactedResult("{}")
                .build();
        ProviderOperationStep activate = ProviderOperationStep.builder()
                .operationStepId(8L)
                .operationId(operationId)
                .stepOrder(2)
                .stepKey("ACTIVATE_TENANT")
                .lifecycleState("PENDING")
                .targetService("dwp-provider-server")
                .redactedResult("{}")
                .build();
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .authTenantId(101L)
                .lifecycleState("PROVISIONING")
                .onboardingState("FAILED")
                .version(4L)
                .build();
        var serviceResult = new DownstreamProvisioningClient.ServiceProvisioningResult(
                tenantId, 101L, "PROVISIONING", 1, "platform-tenant:101");
        var activationFence = new TenantMutationOrchestrator.ActivationFence(
                UUID.randomUUID(), tenantId, 5L, operationId, leaseToken);
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(stepRepository.findByOperationIdOrderByStepOrderAsc(operationId))
                .thenReturn(List.of(platform, activate));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(evidenceRepository.startAttempt(
                operationId, leaseToken, Duration.ofMinutes(5), 7L, operation.getPlanHash()))
                .thenReturn(2);
        when(evidenceRepository.startAttempt(
                operationId, leaseToken, Duration.ofMinutes(5), 8L, operation.getPlanHash()))
                .thenReturn(1);
        when(downstream.provisionPlatform(eq(tenantId), eq(101L), any()))
                .thenReturn(serviceResult);
        when(tenantMutationOrchestrator.activateForOnboarding(
                tenant, operationId, leaseToken, "corr-service-retry"))
                .thenReturn(activationFence);
        doAnswer(ignored -> {
            operation.setLifecycleState("SUCCEEDED");
            return null;
        }).when(leaseRepository).complete(operationId, leaseToken);

        ProviderOperation completed = orchestrator.execute(
                operationId, operation.getPlanHash(), 0L, true, "corr-service-retry");

        assertThat(completed.getLifecycleState()).isEqualTo("SUCCEEDED");
        assertThat(tenant.getOnboardingState()).isEqualTo("FAILED");
        verify(tenantMutationOrchestrator).activateForOnboarding(
                tenant, operationId, leaseToken, "corr-service-retry");
        verify(tenantMutationOrchestrator).completeOnboardingProjection(activationFence, 21L);
    }

    private ProviderOperation maintenanceOperation(UUID operationId) {
        return ProviderOperation.builder()
                .operationId(operationId)
                .operationType("MAINTENANCE_SCHEDULE")
                .idempotencyKey("maintenance:schedule:test")
                .lifecycleState("PREVIEWED")
                .riskTier("L3")
                .requestedBy(12L)
                .justification("Approved maintenance test")
                .planHash("a".repeat(64))
                .plan("{}")
                .version(0L)
                .build();
    }

    private String onboardingPlan() {
        return """
                {
                  "organizationKey":"acme-org",
                  "organizationName":"Acme Organization",
                  "tenantKey":"acme",
                  "displayName":"Acme",
                  "environmentKey":"production",
                  "serviceTier":"ENTERPRISE",
                  "dataRegion":"ap-northeast-2",
                  "isolationModel":"POOL",
                  "defaultLocale":"en",
                  "timeZone":"Asia/Seoul",
                  "entitlements":["core.workspace"],
                  "initialAdministrator":{
                    "displayName":"Acme Administrator",
                    "email":"admin@acme.example.com"
                  }
                }
                """;
    }
}
