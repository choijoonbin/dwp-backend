package com.dwp.services.provider;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.entitlement.Entitlement;
import com.dwp.services.provider.entitlement.EntitlementRepository;
import com.dwp.services.provider.entitlement.TenantEntitlementRepository;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStep;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.provisioning.DownstreamProvisioningClient;
import com.dwp.services.provider.provisioning.ProviderProvisioningOrchestrator;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderControlPlaneServiceTest {

    private final ProviderTenantRepository tenantRepository = mock(ProviderTenantRepository.class);
    private final EntitlementRepository entitlementRepository = mock(EntitlementRepository.class);
    private final TenantEntitlementRepository tenantEntitlementRepository = mock(TenantEntitlementRepository.class);
    private final ProviderOperationRepository operationRepository = mock(ProviderOperationRepository.class);
    private final ProviderOperationStepRepository stepRepository = mock(ProviderOperationStepRepository.class);
    private final ProviderEstateRepository estateRepository = mock(ProviderEstateRepository.class);
    private final ProviderOperationsRepository operationsRepository = mock(ProviderOperationsRepository.class);
    private final ProviderControlPlaneService service = new ProviderControlPlaneService(
            tenantRepository,
            entitlementRepository,
            tenantEntitlementRepository,
            operationRepository,
            stepRepository,
            estateRepository,
            operationsRepository,
            mock(ProviderProvisioningOrchestrator.class),
            mock(DownstreamProvisioningClient.class),
            mock(ProviderAuditService.class),
            new ObjectMapper());

    @BeforeEach
    void setContext() {
        ProviderRequestContext.setForTest(12L, 1L);
    }

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void previewIsIdempotentAndRejectsKeyReuseForDifferentPlan() {
        Entitlement workspace = Entitlement.builder()
                .entitlementId(1L)
                .entitlementKey("core.workspace")
                .name("Core workspace")
                .entitlementType("APP")
                .lifecycleState("ACTIVE")
                .build();
        AtomicReference<ProviderOperation> stored = new AtomicReference<>();
        AtomicReference<List<ProviderOperationStep>> storedSteps = new AtomicReference<>(List.of());
        when(entitlementRepository.findByLifecycleStateOrderByEntitlementKeyAsc("ACTIVE"))
                .thenReturn(List.of(workspace));
        when(operationRepository.findByIdempotencyKey("tenant:onboard:acme"))
                .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(tenantRepository.findByTenantKey("acme")).thenReturn(Optional.empty());
        when(estateRepository.regions()).thenReturn(List.of(
                new ProviderDtos.RegionSummary(
                        "ap-northeast-2", "Seoul", "KR", "STANDARD", "ACTIVE")));
        when(estateRepository.organizationIdByKey("acme-org")).thenReturn(Optional.empty());
        when(operationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProviderOperation operation = invocation.getArgument(0);
            operation.setOperationId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
            operation.setVersion(0L);
            stored.set(operation);
            return operation;
        });
        when(stepRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ProviderOperationStep> steps = new ArrayList<>();
            invocation.<Iterable<ProviderOperationStep>>getArgument(0).forEach(steps::add);
            storedSteps.set(steps);
            return steps;
        });
        when(stepRepository.findByOperationIdOrderByStepOrderAsc(any()))
                .thenAnswer(ignored -> storedSteps.get());

        ProviderDtos.OnboardingPlanRequest request = request("Acme Corporation");
        ProviderDtos.OperationSummary first = service.previewOnboarding(
                "tenant:onboard:acme", "corr-1", request);
        ProviderDtos.OperationSummary replay = service.previewOnboarding(
                "tenant:onboard:acme", "corr-2", request);

        assertThat(replay.operationId()).isEqualTo(first.operationId());
        assertThat(first.planHash()).hasSize(64);
        assertThat(first.plan()).contains("IDEMPOTENT_SAGA", "initialAdministrator");
        assertThat(first.steps()).extracting(ProviderDtos.OperationStep::stepKey)
                .containsExactly(
                        "CONTROL_RECORD", "AUTH_TENANT", "PLATFORM_TENANT",
                        "PEOPLE_TENANT", "ASSET_STORAGE", "ACTIVATE_TENANT");
        verify(operationRepository, times(1)).saveAndFlush(any());

        assertThatThrownBy(() -> service.previewOnboarding(
                "tenant:onboard:acme", "corr-3", request("Changed display name")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different provider plan");
    }

    @Test
    void entitlementReplacementRejectsAStaleTenantVersion() {
        UUID tenantId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .tenantKey("acme")
                .displayName("Acme")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .version(4L)
                .build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        ProviderDtos.ReplaceEntitlementsRequest request =
                new ProviderDtos.ReplaceEntitlementsRequest(
                        List.of("core.workspace"), "Approved change", 3L);

        assertThatThrownBy(() -> service.replaceEntitlements(tenantId, "corr-4", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void standardSupportRequiresACustomerApprovalReference() {
        UUID tenantId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .organizationId(UUID.fromString("30000000-0000-0000-0000-000000000002"))
                .tenantKey("acme")
                .displayName("Acme")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .version(0L)
                .build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(operationsRepository.supportPolicy(any()))
                .thenReturn(new ProviderOperationsRepository.SupportPolicy("L2", true, 1));

        ProviderDtos.CreateSupportSessionRequest request =
                new ProviderDtos.CreateSupportSessionRequest(
                        tenantId,
                        List.of("TENANT_CONFIGURATION_READ"),
                        30,
                        "Investigate an approved customer issue",
                        null,
                        false);

        assertThatThrownBy(() -> service.createSupportSession("corr-5", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("customer approval reference");
    }

    @Test
    void supportSessionRejectsUnknownOrRetiredScopes() {
        UUID tenantId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .organizationId(UUID.fromString("30000000-0000-0000-0000-000000000004"))
                .tenantKey("acme-support")
                .displayName("Acme Support")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .version(0L)
                .build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(operationsRepository.supportPolicy(any()))
                .thenReturn(new ProviderOperationsRepository.SupportPolicy("L1", false, 0));

        ProviderDtos.CreateSupportSessionRequest request =
                new ProviderDtos.CreateSupportSessionRequest(
                        tenantId,
                        List.of("RETIRED_SCOPE"),
                        15,
                        "Investigate an approved customer issue",
                        "CASE-1001",
                        false);

        assertThatThrownBy(() -> service.createSupportSession("corr-6", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("unknown or inactive");
    }

    @Test
    void incidentRegionScopeRequiresARegisteredRegion() {
        ProviderDtos.CreateIncidentRequest request = new ProviderDtos.CreateIncidentRequest(
                "Regional dependency failure",
                "SEV2",
                "REGION",
                null,
                "unknown-region",
                null,
                null,
                "Customers cannot complete sign-in.",
                "We are investigating a regional service issue.",
                "Incident command has been activated.");
        when(estateRepository.regions()).thenReturn(List.of(
                new ProviderDtos.RegionSummary(
                        "ap-northeast-2", "Seoul", "KR", "STANDARD", "ACTIVE")));

        assertThatThrownBy(() -> service.createIncident("corr-7", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("valid region");
    }

    @Test
    void incidentScopeRejectsConflictingTargets() {
        ProviderDtos.CreateIncidentRequest request = new ProviderDtos.CreateIncidentRequest(
                "Cross-scope incident",
                "SEV2",
                "SERVICE",
                "auth",
                "ap-northeast-2",
                null,
                null,
                "Customers cannot complete sign-in.",
                "We are investigating a service issue.",
                "Incident command has been activated.");

        assertThatThrownBy(() -> service.createIncident("corr-8", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("exactly one matching target");
    }

    @Test
    void requesterCannotApproveTheirOwnHighRiskChange() {
        UUID approvalId = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID operationId = UUID.fromString("40000000-0000-0000-0000-000000000002");
        when(operationsRepository.approval(approvalId)).thenReturn(Optional.of(
                new ProviderOperationsRepository.ApprovalRecord(
                        approvalId,
                        operationId,
                        "PENDING",
                        "PROVIDER_ADMIN",
                        true,
                        12L,
                        null,
                        0L)));

        ProviderDtos.DecideOperationApprovalRequest request =
                new ProviderDtos.DecideOperationApprovalRequest(
                        "APPROVED", "Reviewed change impact and rollback plan", 0L);

        assertThatThrownBy(() -> service.decideOperationApproval(approvalId, "corr-9", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Separation of duties");
    }

    @Test
    void maintenanceRejectsAnInsufficientCustomerNoticeWindow() {
        Instant now = Instant.now();
        ProviderDtos.CreateMaintenanceWindowRequest request =
                new ProviderDtos.CreateMaintenanceWindowRequest(
                        "MW-CONTROLLED-001",
                        "Regional database maintenance",
                        "Apply a tested database maintenance release.",
                        "GLOBAL",
                        null,
                        null,
                        null,
                        null,
                        "BRIEF_INTERRUPTION",
                        60,
                        now.plusSeconds(12 * 3600),
                        now.plusSeconds(13 * 3600),
                        now.minusSeconds(3600),
                        24);

        assertThatThrownBy(() -> service.createMaintenanceWindow("corr-10", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("minimum notice period");
    }

    @Test
    void noImpactMaintenanceCannotDeclareInterruptionSeconds() {
        Instant now = Instant.now();
        ProviderDtos.CreateMaintenanceWindowRequest request =
                new ProviderDtos.CreateMaintenanceWindowRequest(
                        "MW-NO-IMPACT-001",
                        "Control metadata refresh",
                        "Refresh provider metadata without customer impact.",
                        "GLOBAL",
                        null,
                        null,
                        null,
                        null,
                        "NO_IMPACT",
                        30,
                        now.plusSeconds(7 * 24 * 3600),
                        now.plusSeconds(7 * 24 * 3600 + 1800),
                        now.minusSeconds(60),
                        120);

        assertThatThrownBy(() -> service.createMaintenanceWindow("corr-11", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("cannot declare customer interruption");
    }

    @Test
    void maintenanceCreatesAControlledHighRiskOperationBeforeScheduling() {
        Instant now = Instant.now();
        UUID operationId = UUID.fromString("50000000-0000-0000-0000-000000000001");
        UUID maintenanceId = UUID.fromString("50000000-0000-0000-0000-000000000002");
        AtomicReference<ProviderOperation> storedOperation = new AtomicReference<>();
        AtomicReference<List<ProviderOperationStep>> storedSteps = new AtomicReference<>(List.of());
        when(operationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProviderOperation operation = invocation.getArgument(0);
            operation.setOperationId(operationId);
            operation.setVersion(0L);
            storedOperation.set(operation);
            return operation;
        });
        when(stepRepository.saveAll(any())).thenAnswer(invocation -> {
            List<ProviderOperationStep> steps = new ArrayList<>();
            invocation.<Iterable<ProviderOperationStep>>getArgument(0).forEach(steps::add);
            storedSteps.set(steps);
            return steps;
        });
        when(operationsRepository.createMaintenanceWindow(any(), any(), any()))
                .thenReturn(maintenanceId);
        when(operationsRepository.maintenanceWindows()).thenReturn(List.of(
                new ProviderDtos.MaintenanceWindowSummary(
                        maintenanceId, operationId, "MW-CONTROLLED-002",
                        "Global dependency maintenance",
                        "Apply a tested dependency release through change control.",
                        "GLOBAL", "Global", "BRIEF_INTERRUPTION", 60, "DRAFT",
                        now.plusSeconds(7 * 24 * 3600),
                        now.plusSeconds(7 * 24 * 3600 + 1800),
                        now.minusSeconds(60), 120, true, 0L)));

        ProviderDtos.CreateMaintenanceWindowRequest request =
                new ProviderDtos.CreateMaintenanceWindowRequest(
                        "MW-CONTROLLED-002",
                        "Global dependency maintenance",
                        "Apply a tested dependency release through change control.",
                        "GLOBAL", null, null, null, null,
                        "BRIEF_INTERRUPTION", 60,
                        now.plusSeconds(7 * 24 * 3600),
                        now.plusSeconds(7 * 24 * 3600 + 1800),
                        now.minusSeconds(60), 120);

        ProviderDtos.MaintenanceWindowSummary created =
                service.createMaintenanceWindow("corr-12", request);

        assertThat(created.lifecycleState()).isEqualTo("DRAFT");
        assertThat(created.operationId()).isEqualTo(operationId);
        assertThat(storedOperation.get().getOperationType()).isEqualTo("MAINTENANCE_SCHEDULE");
        assertThat(storedOperation.get().getRiskTier()).isEqualTo("L3");
        assertThat(storedOperation.get().getLifecycleState()).isEqualTo("PREVIEWED");
        assertThat(storedOperation.get().getPlanHash()).hasSize(64);
        assertThat(storedOperation.get().getPlan())
                .contains("CONTROLLED_SINGLE_STEP", "SCHEDULE_MAINTENANCE");
        assertThat(storedSteps.get()).extracting(ProviderOperationStep::getStepKey)
                .containsExactly("SCHEDULE_MAINTENANCE");
        verify(operationsRepository).ensureOperationApproval(storedOperation.get());
    }

    private ProviderDtos.OnboardingPlanRequest request(String displayName) {
        return new ProviderDtos.OnboardingPlanRequest(
                "acme-org", "Acme Organization", "Acme Corporation", "CRM-1001",
                "acme", displayName, "production", "ENTERPRISE", "ap-northeast-2", "POOL",
                "ko-KR", "Asia/Seoul", "acme.example.com",
                "Acme Administrator", "admin@acme.example.com",
                List.of("core.workspace"), "Approved enterprise onboarding request");
    }
}
