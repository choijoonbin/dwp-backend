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
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    private final ProviderControlPlaneService service = new ProviderControlPlaneService(
            tenantRepository,
            entitlementRepository,
            tenantEntitlementRepository,
            operationRepository,
            stepRepository,
            mock(ProviderAuditService.class),
            new ObjectMapper());

    @BeforeEach
    void setContext() {
        ProviderRequestContext.set(12L, 1L);
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
        assertThat(first.plan()).contains("downstream-provisioning-adapter", "KMS", "S3");
        assertThat(first.steps()).extracting(ProviderDtos.OperationStep::stepKey)
                .containsExactly(
                        "CONTROL_RECORD", "AUTH_TENANT", "PLATFORM_TENANT",
                        "PEOPLE_TENANT", "ASSET_STORAGE");
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

    private ProviderDtos.OnboardingPlanRequest request(String displayName) {
        return new ProviderDtos.OnboardingPlanRequest(
                "acme", displayName, "ENTERPRISE", "ap-northeast-2", "POOL",
                List.of("core.workspace"), "Approved enterprise onboarding request");
    }
}
