package com.dwp.services.provider.provisioning;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.ProviderEstateRepository;
import com.dwp.services.provider.ProviderOperationsRepository;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.entitlement.EntitlementRepository;
import com.dwp.services.provider.entitlement.TenantEntitlementRepository;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStep;
import com.dwp.services.provider.operation.ProviderOperationStepAttempt;
import com.dwp.services.provider.operation.ProviderOperationStepAttemptRepository;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderProvisioningOrchestratorTest {

    private final ProviderOperationRepository operationRepository = mock(ProviderOperationRepository.class);
    private final ProviderOperationStepRepository stepRepository = mock(ProviderOperationStepRepository.class);
    private final ProviderOperationStepAttemptRepository attemptRepository =
            mock(ProviderOperationStepAttemptRepository.class);
    private final ProviderTenantRepository tenantRepository = mock(ProviderTenantRepository.class);
    private final ProviderOperationsRepository operationsRepository = mock(ProviderOperationsRepository.class);
    private final ProviderAuditService auditService = mock(ProviderAuditService.class);
    private final ProviderProvisioningOrchestrator orchestrator = new ProviderProvisioningOrchestrator(
            operationRepository,
            stepRepository,
            attemptRepository,
            tenantRepository,
            mock(EntitlementRepository.class),
            mock(TenantEntitlementRepository.class),
            mock(ProviderEstateRepository.class),
            operationsRepository,
            mock(DownstreamProvisioningClient.class),
            auditService,
            new ObjectMapper(),
            mock(TransactionTemplate.class));

    @BeforeEach
    void setContext() {
        ProviderRequestContext.setForTest(21L, 1L);
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
        when(operationRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(stepRepository.findByOperationIdOrderByStepOrderAsc(operationId)).thenReturn(List.of(step));
        when(stepRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(attemptRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProviderOperationStepAttempt attempt = invocation.getArgument(0);
            if (attempt.getOperationStepAttemptId() == null) {
                attempt.setOperationStepAttemptId(
                        UUID.fromString("60000000-0000-0000-0000-000000000003"));
            }
            return attempt;
        });
        when(operationsRepository.operationApproved(operationId)).thenReturn(true);
        when(operationsRepository.scheduleMaintenanceWindow(operationId, 21L))
                .thenReturn(Optional.of(maintenanceId));
        when(operationsRepository.maintenanceWindowId(operationId)).thenReturn(Optional.of(maintenanceId));

        ProviderOperation completed = orchestrator.execute(
                operationId, operation.getPlanHash(), 0L, false, "corr-maintenance");

        assertThat(completed.getLifecycleState()).isEqualTo("SUCCEEDED");
        assertThat(step.getLifecycleState()).isEqualTo("SUCCEEDED");
        assertThat(step.getExternalReference()).isEqualTo(maintenanceId.toString());
        verify(operationsRepository).scheduleMaintenanceWindow(operationId, 21L);
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
}
