package com.dwp.services.provider;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.commercial.ProviderCommercialRenewalRepository;
import com.dwp.services.provider.entitlement.Entitlement;
import com.dwp.services.provider.entitlement.EntitlementRepository;
import com.dwp.services.provider.entitlement.TenantEntitlementRepository;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStep;
import com.dwp.services.provider.operation.ProviderOperationStepAttemptRepository;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.provisioning.ProviderProvisioningOrchestrator;
import com.dwp.services.provider.provisioning.TenantMutationOrchestrator;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.support.ProviderSupportRequestRepository;
import com.dwp.services.provider.support.ProviderSupportRequestSecurityPolicy;
import com.dwp.services.provider.support.ProviderSupportActivationGate;
import com.dwp.services.provider.support.ProviderSupportSessionRepository;
import com.dwp.services.provider.support.ProviderSupportSessionLifecycleService;
import com.dwp.services.provider.support.CustomerApprovalEvidencePolicy;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ProviderControlPlaneServiceTest {

    private final ProviderTenantRepository tenantRepository = mock(ProviderTenantRepository.class);
    private final EntitlementRepository entitlementRepository = mock(EntitlementRepository.class);
    private final TenantEntitlementRepository tenantEntitlementRepository = mock(TenantEntitlementRepository.class);
    private final ProviderOperationRepository operationRepository = mock(ProviderOperationRepository.class);
    private final ProviderOperationStepRepository stepRepository = mock(ProviderOperationStepRepository.class);
    private final ProviderEstateRepository estateRepository = mock(ProviderEstateRepository.class);
    private final ProviderOperationsRepository operationsRepository = mock(ProviderOperationsRepository.class);
    private final ProviderCommercialRenewalRepository commercialRenewalRepository =
            mock(ProviderCommercialRenewalRepository.class);
    private final ProviderSupportRequestRepository supportRequestRepository =
            mock(ProviderSupportRequestRepository.class);
    private final ProviderSupportRequestSecurityPolicy supportRequestSecurityPolicy =
            mock(ProviderSupportRequestSecurityPolicy.class);
    private final ProviderSupportSessionRepository supportSessionRepository =
            mock(ProviderSupportSessionRepository.class);
    private final ProviderSupportSessionLifecycleService supportSessionLifecycleService =
            mock(ProviderSupportSessionLifecycleService.class);
    private final ProviderSupportActivationGate supportActivationGate =
            mock(ProviderSupportActivationGate.class);
    private final CustomerApprovalEvidencePolicy customerApprovalEvidencePolicy =
            mock(CustomerApprovalEvidencePolicy.class);
    private final TenantMutationOrchestrator tenantMutationOrchestrator =
            mock(TenantMutationOrchestrator.class);
    private final ProviderAuditService auditService = mock(ProviderAuditService.class);
    private final ProviderControlPlaneService service = new ProviderControlPlaneService(
            tenantRepository,
            entitlementRepository,
            tenantEntitlementRepository,
            operationRepository,
            stepRepository,
            mock(ProviderOperationStepAttemptRepository.class),
            estateRepository,
            operationsRepository,
            commercialRenewalRepository,
            supportRequestRepository,
            supportRequestSecurityPolicy,
            supportSessionRepository,
            supportSessionLifecycleService,
            supportActivationGate,
            customerApprovalEvidencePolicy,
            mock(ProviderProvisioningOrchestrator.class),
            tenantMutationOrchestrator,
            auditService,
            JsonMapper.builder().findAndAddModules().build());

    @BeforeEach
    void setContext() {
        ProviderRequestContext.setForTest(12L, 1L);
        when(supportRequestSecurityPolicy.requireActivationTarget(
                any(), any(), any(), any())).thenAnswer(invocation -> {
                    Optional<ProviderSupportRequestRepository.SupportAccessRequestRecord> candidate =
                            invocation.getArgument(0);
                    return candidate == null ? null : candidate.orElse(null);
                });
        when(supportRequestSecurityPolicy.requireCancellationTarget(
                any(), any(), any(), any())).thenAnswer(invocation -> {
                    Optional<ProviderSupportRequestRepository.SupportAccessRequestRecord> candidate =
                            invocation.getArgument(0);
                    return candidate == null ? null : candidate.orElse(null);
                });
        when(supportRequestSecurityPolicy.requireRevocationTarget(
                any(), any(), any(), any())).thenAnswer(invocation -> {
                    Optional<ProviderSupportSessionRepository.SupportSessionRecord> candidate =
                            invocation.getArgument(0);
                    return candidate == null ? null : candidate.orElse(null);
                });
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
        when(entitlementRepository.findByLifecycleStateOrderByEntitlementKeyAsc("ACTIVE"))
                .thenReturn(List.of(Entitlement.builder()
                        .entitlementId(1L)
                        .entitlementKey("core.workspace")
                        .name("Core workspace")
                        .entitlementType("APP")
                        .lifecycleState("ACTIVE")
                        .build()));
        doThrow(new BaseException(
                com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT,
                "The tenant changed before the durable mutation could be created."))
                .when(tenantMutationOrchestrator)
                .replaceEntitlements(any(), org.mockito.ArgumentMatchers.eq(3L),
                        any(), any(), any());

        assertThatThrownBy(() -> service.replaceEntitlements(tenantId, "corr-4", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void onboardingTenantBlocksPlanDriftButDelegatesContainmentLifecycle() {
        UUID tenantId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .lifecycleState("PROVISIONING")
                .onboardingState("PENDING_EXTERNAL")
                .version(2L)
                .build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> service.lifecycle(
                tenantId,
                "corr-onboarding-activate",
                new ProviderDtos.LifecycleRequest("ACTIVE", "Resume onboarding", 2L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("onboarding must be ready");
        assertThatThrownBy(() -> service.replaceEntitlements(
                tenantId,
                "corr-onboarding-entitlements",
                new ProviderDtos.ReplaceEntitlementsRequest(
                        List.of("core.workspace"), "Adjust onboarding", 2L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("onboarding must be ready");

        doThrow(new IllegalStateException("suspension delegated"))
                .when(tenantMutationOrchestrator)
                .lifecycle(eq(tenant), eq(2L), eq("SUSPENDED"), any(), any());
        doThrow(new IllegalStateException("retirement delegated"))
                .when(tenantMutationOrchestrator)
                .lifecycle(eq(tenant), eq(2L), eq("RETIRED"), any(), any());

        assertThatThrownBy(() -> service.lifecycle(
                tenantId,
                "corr-onboarding-suspend",
                new ProviderDtos.LifecycleRequest("SUSPENDED", "Pause onboarding", 2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("suspension delegated");
        assertThatThrownBy(() -> service.lifecycle(
                tenantId,
                "corr-onboarding-retire",
                new ProviderDtos.LifecycleRequest("RETIRED", "Retire failed onboarding", 2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("retirement delegated");
        verify(tenantMutationOrchestrator, never())
                .lifecycle(eq(tenant), eq(2L), eq("ACTIVE"), any(), any());
        verify(tenantMutationOrchestrator, never())
                .replaceEntitlements(any(), anyLong(), any(), any(), any());
    }

    @Test
    void readyTenantDelegatesActivationAndEntitlementMutations() {
        UUID tenantId = UUID.fromString("20000000-0000-0000-0000-000000000003");
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .version(3L)
                .build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        Entitlement workspace = Entitlement.builder()
                .entitlementId(1L)
                .entitlementKey("core.workspace")
                .name("Core workspace")
                .entitlementType("APP")
                .lifecycleState("ACTIVE")
                .build();
        when(entitlementRepository.findByLifecycleStateOrderByEntitlementKeyAsc("ACTIVE"))
                .thenReturn(List.of(workspace));
        doThrow(new IllegalStateException("lifecycle delegated"))
                .when(tenantMutationOrchestrator)
                .lifecycle(eq(tenant), eq(3L), eq("ACTIVE"), any(), any());
        doThrow(new IllegalStateException("entitlements delegated"))
                .when(tenantMutationOrchestrator)
                .replaceEntitlements(eq(tenant), eq(3L), eq(List.of("core.workspace")), any(), any());

        assertThatThrownBy(() -> service.lifecycle(
                tenantId,
                "corr-ready-activate",
                new ProviderDtos.LifecycleRequest("ACTIVE", "Approved activation", 3L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("lifecycle delegated");
        assertThatThrownBy(() -> service.replaceEntitlements(
                tenantId,
                "corr-ready-entitlements",
                new ProviderDtos.ReplaceEntitlementsRequest(
                        List.of("core.workspace"), "Approved entitlement change", 3L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("entitlements delegated");
    }

    @Test
    void administratorInvitationFailsClosedBeforeAProviderCanReceiveACapability() {
        UUID tenantId = UUID.fromString("20000000-0000-0000-0000-000000000010");
        UUID organizationId = UUID.fromString("20000000-0000-0000-0000-000000000011");
        UUID administratorId = UUID.fromString("20000000-0000-0000-0000-000000000012");
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .organizationId(organizationId)
                .tenantKey("activation-boundary")
                .displayName("Activation Boundary")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .authTenantId(42L)
                .version(0L)
                .build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(estateRepository.administrator(tenantId, administratorId)).thenReturn(Optional.of(
                new ProviderEstateRepository.AdministratorRecord(
                        administratorId, 77L, "tenant-admin@example.test",
                        "Tenant administrator", "INVITED")));

        assertThatThrownBy(() -> service.issueAdministratorInvitation(
                tenantId,
                administratorId,
                "corr-activation-boundary",
                new ProviderDtos.IssueAdministratorInvitationRequest(
                        60, "Initial customer administrator activation")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("customer-owned out-of-band delivery");

        ArgumentCaptor<Object> snapshot = ArgumentCaptor.forClass(Object.class);
        verify(auditService).denied(
                org.mockito.ArgumentMatchers.eq("provider.tenant-administrator.invitation-blocked"),
                org.mockito.ArgumentMatchers.eq("TENANT_ADMINISTRATOR"),
                org.mockito.ArgumentMatchers.eq(administratorId.toString()),
                org.mockito.ArgumentMatchers.eq(tenantId),
                org.mockito.ArgumentMatchers.eq(organizationId),
                org.mockito.ArgumentMatchers.eq("corr-activation-boundary"),
                snapshot.capture());
        Map<?, ?> denialEvidence = (Map<?, ?>) snapshot.getValue();
        assertThat(denialEvidence.get("decision")).isEqualTo("DENY");
        assertThat(denialEvidence.get("reasonCode"))
                .isEqualTo("CUSTOMER_OWNED_DELIVERY_UNAVAILABLE");
        assertThat(denialEvidence.containsKey("email")).isFalse();
        assertThat(denialEvidence.containsKey("justification")).isFalse();
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
                .thenReturn(new ProviderOperationsRepository.SupportPolicy("L1", true, 1));

        ProviderDtos.CreateSupportSessionRequest request =
                new ProviderDtos.CreateSupportSessionRequest(
                        tenantId,
                        List.of("TENANT_EXPERIENCE_PREVIEW"),
                        30,
                        "Investigate an approved customer issue",
                        null,
                        false,
                        "support-request-1001");

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
                        false,
                        "support-request-1002");

        assertThatThrownBy(() -> service.createSupportSession("corr-6", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("active L1 customer-approved");
    }

    @Test
    void breakGlassRemainsDisabledUntilExternalControlsAreBound() {
        UUID tenantB = UUID.fromString("30000000-0000-0000-0000-000000000005");
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantB)
                .organizationId(UUID.fromString("30000000-0000-0000-0000-000000000006"))
                .tenantKey("tenant-b")
                .displayName("Tenant B")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .version(0L)
                .build();
        when(tenantRepository.findById(tenantB)).thenReturn(Optional.of(tenant));
        when(operationsRepository.supportPolicy(any()))
                .thenReturn(new ProviderOperationsRepository.SupportPolicy("L1", true, 1));
        ProviderDtos.CreateSupportSessionRequest request =
                new ProviderDtos.CreateSupportSessionRequest(
                        tenantB,
                        List.of("TENANT_EXPERIENCE_PREVIEW"),
                        15,
                        "Diagnose Tenant B after reviewing Tenant A",
                        "CASE-B-1001",
                        true,
                        "support-tenant-b-1001");

        assertThatThrownBy(() -> service.createSupportSession("corr-tenant-b", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("disabled until incident binding");

        verify(supportSessionRepository, never()).requireNoActiveSupportSession(any());
        verify(supportSessionRepository, never()).activateApprovedRequest(
                any(), anyLong(), any(), any(), any());
    }

    @Test
    void supportSessionRevocationUsesOptimisticConcurrency() {
        UUID sessionId = UUID.fromString("30000000-0000-0000-0000-000000000007");
        UUID tenantId = UUID.fromString("30000000-0000-0000-0000-000000000008");
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .organizationId(UUID.fromString("30000000-0000-0000-0000-000000000009"))
                .tenantKey("tenant-revoke")
                .displayName("Tenant Revoke")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .version(0L)
                .build();
        Instant expiresAt = Instant.now().plusSeconds(900);
        when(supportSessionRepository.session(sessionId)).thenReturn(Optional.of(
                new ProviderSupportSessionRepository.SupportSessionRecord(
                        sessionId, tenantId, 12L, "ACTIVE", "hash", "STANDARD",
                        expiresAt, Instant.now(), expiresAt, 4L,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"))));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(supportSessionRepository.revoke(sessionId, 12L, 4L)).thenReturn(false);

        assertThatThrownBy(() -> service.revokeSupportSession(
                sessionId,
                "corr-revoke-race",
                new ProviderDtos.RevokeSupportSessionRequest(
                        "Terminate the approved diagnostic session", 4L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed before it could be revoked");

    }

    @Test
    void supportOperatorCannotRevokeAnotherOperatorsSession() {
        UUID sessionId = UUID.fromString("30000000-0000-0000-0000-000000000017");
        UUID tenantId = UUID.fromString("30000000-0000-0000-0000-000000000018");
        UUID organizationId = UUID.fromString("30000000-0000-0000-0000-000000000019");
        ProviderTenant tenant = ProviderTenant.builder()
                .providerTenantId(tenantId)
                .organizationId(organizationId)
                .tenantKey("tenant-owner-boundary")
                .displayName("Tenant Owner Boundary")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .version(0L)
                .build();
        Instant expiresAt = Instant.now().plusSeconds(900);
        when(supportSessionRepository.session(sessionId)).thenReturn(Optional.of(
                new ProviderSupportSessionRepository.SupportSessionRecord(
                        sessionId, tenantId, 99L, "ACTIVE", "hash", "STANDARD",
                        expiresAt, Instant.now(), expiresAt, 4L,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"))));
        when(supportRequestSecurityPolicy.requireRevocationTarget(
                any(), any(), any(), any()))
                .thenThrow(new BaseException(com.dwp.core.common.ErrorCode.FORBIDDEN));
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                12L, 120L, 1L, "Support operator",
                Set.of("PROVIDER_SUPPORT"), Set.of("SUPPORT_SESSION_WRITE"),
                UUID.fromString("00000000-0000-0000-0000-000000000001")));

        assertThatThrownBy(() -> service.revokeSupportSession(
                sessionId,
                "corr-owner-boundary",
                new ProviderDtos.RevokeSupportSessionRequest("Unauthorized revoke", 4L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));

        verify(supportRequestSecurityPolicy).requireRevocationTarget(
                any(), any(), any(), any());
        verify(supportSessionRepository, never()).revoke(any(), any(), anyLong());
    }

    @Test
    void activationRevalidatesThatEveryApprovedScopeIsStillActive() {
        UUID requestId = UUID.fromString("30000000-0000-0000-0000-000000000010");
        UUID tenantId = UUID.fromString("30000000-0000-0000-0000-000000000011");
        when(supportRequestRepository.byId(requestId)).thenReturn(Optional.of(
                new ProviderSupportRequestRepository.SupportAccessRequestRecord(
                        requestId,
                        tenantId,
                        12L,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "APPROVED",
                        "STANDARD",
                        "Investigate a customer-approved workforce issue",
                        List.of("WORKFORCE_READ"),
                        15,
                        "CASE-RETIRED-1001",
                        true,
                        "L2",
                        "retired-scope-1001",
                        "a".repeat(64),
                        Instant.now().plusSeconds(900),
                        14L,
                        null,
                        "NOT_REQUIRED",
                        2L)));
        when(operationsRepository.supportPolicy(any()))
                .thenReturn(new ProviderOperationsRepository.SupportPolicy("L1", false, 0));

        assertThatThrownBy(() -> service.activateSupportAccessRequest(
                requestId,
                "corr-retired-scope",
                new ProviderDtos.ActivateSupportAccessRequest(2L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("active L1 customer-approved");

        verify(supportSessionRepository, never()).activateApprovedRequest(
                any(), anyLong(), any(), any(), any());
    }

    @Test
    void activationRequiresAnActiveReadyTenantLinkedToAuth() {
        UUID requestId = UUID.fromString("30000000-0000-0000-0000-000000000012");
        UUID tenantId = UUID.fromString("30000000-0000-0000-0000-000000000013");
        when(supportRequestRepository.byId(requestId)).thenReturn(Optional.of(
                approvedPreviewRequest(requestId, tenantId)));
        when(operationsRepository.supportPolicy(any())).thenReturn(
                new ProviderOperationsRepository.SupportPolicy("L1", true, 1));
        when(customerApprovalEvidencePolicy.requireVerified("CASE-APPROVED-1001"))
                .thenReturn(CustomerApprovalEvidencePolicy.LOCAL_REFERENCE_ONLY);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(
                supportTenant(tenantId, null)));

        assertThatThrownBy(() -> service.activateSupportAccessRequest(
                requestId, "corr-no-auth-link",
                new ProviderDtos.ActivateSupportAccessRequest(2L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("ACTIVE, READY, and linked to auth");

        verify(supportSessionRepository, never()).activateApprovedRequest(
                any(), anyLong(), any(), any(), any());
    }

    @Test
    void activationRequiresTheRepositoryToCreateTheExactGrantAtomically() {
        UUID requestId = UUID.fromString("30000000-0000-0000-0000-000000000014");
        UUID tenantId = UUID.fromString("30000000-0000-0000-0000-000000000015");
        when(supportRequestRepository.byId(requestId)).thenReturn(Optional.of(
                approvedPreviewRequest(requestId, tenantId)));
        when(operationsRepository.supportPolicy(any())).thenReturn(
                new ProviderOperationsRepository.SupportPolicy("L1", true, 1));
        when(customerApprovalEvidencePolicy.requireVerified("CASE-APPROVED-1001"))
                .thenReturn(CustomerApprovalEvidencePolicy.LOCAL_REFERENCE_ONLY);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(
                supportTenant(tenantId, 42L)));
        when(supportSessionRepository.activateApprovedRequest(
                org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq(
                        UUID.fromString("00000000-0000-0000-0000-000000000001")),
                any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activateSupportAccessRequest(
                requestId, "corr-activation-race",
                new ProviderDtos.ActivateSupportAccessRequest(2L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed or expired");

        verify(supportSessionRepository).activateApprovedRequest(
                org.mockito.ArgumentMatchers.eq(requestId),
                org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq(
                        UUID.fromString("00000000-0000-0000-0000-000000000001")),
                any());
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
        setApprovalActor(12L, "PROVIDER_CHANGE_APPROVER");
        UUID approvalId = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID operationId = UUID.fromString("40000000-0000-0000-0000-000000000002");
        when(operationsRepository.approval(approvalId)).thenReturn(Optional.of(
                new ProviderOperationsRepository.ApprovalRecord(
                        approvalId,
                        operationId,
                        "PENDING",
                        "PROVIDER_CHANGE_APPROVER",
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
    void dedicatedChangeApproverCanApproveAnotherOperatorsHighRiskChange() {
        setApprovalActor(13L, "PROVIDER_CHANGE_APPROVER");
        UUID approvalId = UUID.fromString("40000000-0000-0000-0000-000000000011");
        UUID operationId = UUID.fromString("40000000-0000-0000-0000-000000000012");
        when(operationsRepository.approval(approvalId)).thenReturn(Optional.of(
                new ProviderOperationsRepository.ApprovalRecord(
                        approvalId, operationId, "PENDING", "PROVIDER_CHANGE_APPROVER",
                        true, 12L, null, 0L)));
        when(operationsRepository.decideApproval(
                approvalId, "APPROVED", "Reviewed tenant impact", 13L, 0L)).thenReturn(true);
        ProviderOperation operation = ProviderOperation.builder()
                .operationId(operationId)
                .operationType("TENANT_ONBOARD")
                .lifecycleState("PREVIEWED")
                .riskTier("L3")
                .build();
        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        ProviderDtos.OperationApprovalSummary summary = new ProviderDtos.OperationApprovalSummary(
                approvalId, operationId, null, null, "TENANT_ONBOARD", "L3",
                "RISK_REVIEW", 1, "APPROVED", "PROVIDER_CHANGE_APPROVER", true,
                12L, "Tenant provisioner", 13L, "Change approver",
                "Regulated onboarding", "Reviewed tenant impact",
                Instant.now(), Instant.now(), Instant.now().plusSeconds(3600), 1L);
        when(operationsRepository.operationApprovals(null)).thenReturn(List.of(summary));

        ProviderDtos.OperationApprovalSummary decided = service.decideOperationApproval(
                approvalId, "corr-dedicated-approver",
                new ProviderDtos.DecideOperationApprovalRequest(
                        "APPROVED", "Reviewed tenant impact", 0L));

        assertThat(decided).isEqualTo(summary);
        verify(operationsRepository).decideApproval(
                approvalId, "APPROVED", "Reviewed tenant impact", 13L, 0L);
    }

    @Test
    void providerAdminWithoutDedicatedRoleCannotApproveHighRiskChange() {
        setApprovalActor(13L, "PROVIDER_ADMIN");
        UUID approvalId = UUID.fromString("40000000-0000-0000-0000-000000000021");
        UUID operationId = UUID.fromString("40000000-0000-0000-0000-000000000022");
        when(operationsRepository.approval(approvalId)).thenReturn(Optional.of(
                new ProviderOperationsRepository.ApprovalRecord(
                        approvalId, operationId, "PENDING", "PROVIDER_CHANGE_APPROVER",
                        true, 12L, null, 0L)));

        assertThatThrownBy(() -> service.decideOperationApproval(
                approvalId, "corr-admin-not-approver",
                new ProviderDtos.DecideOperationApprovalRequest(
                        "APPROVED", "Attempted broad admin approval", 0L)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("required approval role");
        verify(operationsRepository, never()).decideApproval(any(), any(), any(), any(), anyLong());
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
    void commercialRenewalRejectsAStaleSubscriptionVersion() {
        UUID subscriptionId = UUID.fromString("60000000-0000-0000-0000-000000000001");
        when(commercialRenewalRepository.byKey(12L, "renewal-skax-2027"))
                .thenReturn(Optional.empty());
        when(commercialRenewalRepository.subscription(subscriptionId)).thenReturn(Optional.of(
                new ProviderCommercialRenewalRepository.SubscriptionRecord(
                        subscriptionId,
                        UUID.fromString("60000000-0000-0000-0000-000000000002"),
                        UUID.fromString("60000000-0000-0000-0000-000000000003"),
                        "enterprise", "Enterprise", Instant.now().minusSeconds(3600),
                        Instant.now().plusSeconds(86400), "SKAX-2026", 4L, 3L)));

        ProviderDtos.CreateSubscriptionRenewalRequest request =
                new ProviderDtos.CreateSubscriptionRenewalRequest(
                        subscriptionId, "regulated", Instant.now().plusSeconds(172800),
                        "SKAX-2027", "Renew regulated services", "renewal-skax-2027", 3L);

        assertThatThrownBy(() -> service.createSubscriptionRenewal("corr-commercial-1", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void commercialRenewalIdempotencyRejectsDifferentInput() {
        UUID revisionId = UUID.fromString("60000000-0000-0000-0000-000000000004");
        UUID subscriptionId = UUID.fromString("60000000-0000-0000-0000-000000000005");
        when(commercialRenewalRepository.byKey(12L, "renewal-skax-2028")).thenReturn(Optional.of(
                new ProviderCommercialRenewalRepository.RenewalRecord(
                        revisionId, subscriptionId,
                        UUID.fromString("60000000-0000-0000-0000-000000000006"),
                        "PUBLISHED", 2L,
                        UUID.fromString("60000000-0000-0000-0000-000000000007"),
                        Instant.now().plusSeconds(172800), "SKAX-2028", "Original reason",
                        List.of(), List.of(), "0".repeat(64), "f".repeat(64),
                        "renewal-skax-2028", 12L, Instant.now().plusSeconds(3600), 3L)));

        ProviderDtos.CreateSubscriptionRenewalRequest request =
                new ProviderDtos.CreateSubscriptionRenewalRequest(
                        subscriptionId, "enterprise", Instant.now().plusSeconds(172800),
                        "SKAX-2028", "Changed reason", "renewal-skax-2028", 2L);

        assertThatThrownBy(() -> service.createSubscriptionRenewal("corr-commercial-2", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different proposal");
    }

    @Test
    void commercialRenewalCannotBeSelfApproved() {
        UUID revisionId = UUID.fromString("60000000-0000-0000-0000-000000000008");
        when(commercialRenewalRepository.byId(revisionId)).thenReturn(Optional.of(
                new ProviderCommercialRenewalRepository.RenewalRecord(
                        revisionId,
                        UUID.fromString("60000000-0000-0000-0000-000000000009"),
                        UUID.fromString("60000000-0000-0000-0000-000000000010"),
                        "PENDING_APPROVAL", 0L,
                        UUID.fromString("60000000-0000-0000-0000-000000000011"),
                        Instant.now().plusSeconds(86400), "SKAX-2027", "Renew contract",
                        List.of("premium.audit"), List.of(), "a".repeat(64), "b".repeat(64),
                        "renewal-skax-2027", 12L, Instant.now().plusSeconds(3600), 0L)));

        ProviderDtos.DecideSubscriptionRenewalRequest request =
                new ProviderDtos.DecideSubscriptionRenewalRequest(
                        "APPROVED", "Reviewed customer and entitlement impact", 0L);

        assertThatThrownBy(() -> service.decideSubscriptionRenewal(
                revisionId, "corr-commercial-3", request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("self-approved");
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

    private ProviderSupportRequestRepository.SupportAccessRequestRecord approvedPreviewRequest(
            UUID requestId,
            UUID tenantId) {
        return new ProviderSupportRequestRepository.SupportAccessRequestRecord(
                requestId, tenantId, 12L,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "APPROVED", "STANDARD",
                "Investigate a customer-approved tenant preview",
                List.of("TENANT_EXPERIENCE_PREVIEW"), 15,
                "CASE-APPROVED-1001", true, "L1", "approved-preview-1001",
                "a".repeat(64), Instant.now().plusSeconds(900), 14L,
                null, "NOT_REQUIRED", 2L);
    }

    private ProviderTenant supportTenant(UUID tenantId, Long authTenantId) {
        return ProviderTenant.builder()
                .providerTenantId(tenantId)
                .organizationId(UUID.fromString("30000000-0000-0000-0000-000000000099"))
                .tenantKey("support-target")
                .displayName("Support Target")
                .environmentKey("production")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .authTenantId(authTenantId)
                .version(0L)
                .build();
    }

    private ProviderDtos.OnboardingPlanRequest request(String displayName) {
        return new ProviderDtos.OnboardingPlanRequest(
                "acme-org", "Acme Organization", "Acme Corporation", "CRM-1001",
                "acme", displayName, "production", "ENTERPRISE", "ap-northeast-2", "POOL",
                "ko-KR", "Asia/Seoul", "acme.example.com",
                "Acme Administrator", "admin@acme.example.com",
                List.of("core.workspace"), "Approved enterprise onboarding request");
    }

    private void setApprovalActor(Long operatorId, String role) {
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                operatorId, operatorId, 1L, "Approval test operator",
                Set.of(role), Set.of("CHANGE_APPROVE")));
    }
}
