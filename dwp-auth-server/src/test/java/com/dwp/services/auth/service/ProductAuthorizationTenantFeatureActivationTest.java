package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.tenantcapabilityoverride.TenantCapabilityOverrideReader;
import com.dwp.services.auth.tenantfeatureactivation.TenantFeatureActivationReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationTenantFeatureActivationTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-17T06:00:00Z"), ZoneOffset.UTC);
    private static final String ROUTE =
            "route.hcm.operations.organization-positions.page";

    @Mock
    private ProductAuthorizationContractRepository repository;
    @Mock
    private ProductAuthorizationIdentityEvidenceService evidenceService;
    @Mock
    private TenantFeatureActivationReader featureActivations;

    private ProductAuthorizationAuthorityAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        ProductAuthorizationContractDtos.BundleContract contract;
        try (var input = getClass().getResourceAsStream(
                "/product-authorization/product-surfaces-v1.bundle-v50.generated.json")) {
            contract = new ObjectMapper().findAndRegisterModules().readValue(
                    input, ProductAuthorizationContractDtos.BundleContract.class);
        }
        UUID bundleId = UUID.fromString("50000000-0000-0000-0000-000000000259");
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        var stored = new ProductAuthorizationContractRepository.StoredBundle(
                bundleId, contract.bundleKey(), contract.version(), "ACTIVE",
                contract.schemaVersion(), contract.checksumAlgorithm(), contract.checksum(),
                contract.owner(), "security-reviewer", now, now, now);
        when(repository.findActive("product-surfaces")).thenReturn(Optional.of(stored));
        when(repository.loadContract(stored)).thenReturn(contract);
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", bundleId, contract.version(), "release", now)));
        adapter = new ProductAuthorizationAuthorityAdapter(
                repository, evidenceService, TenantCapabilityOverrideReader.none(),
                featureActivations, CLOCK, "urn:dwp:acr:mfa");
    }

    @Test
    void existingAuthorizationDenialRunsBeforeFeatureStateAndDoesNotDiscloseIt()
            throws Exception {
        Method evaluate = ProductAuthorizationAuthorityAdapter.class.getMethod(
                "evaluate", ProductSurfaceAuthorityDtos.EvaluateRequest.class);
        Transactional transaction = evaluate.getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);

        evidence(Set.of(), List.of());

        var result = adapter.evaluate(request(ProductSurfaceAuthorityDtos.AccessMode.NORMAL));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(result.reasonCode()).isEqualTo("ROUTE_CAPABILITY_REQUIRED");
        assertThat(result.tenantControlRevision()).isNotBlank();
        verifyNoInteractions(featureActivations);
    }

    @Test
    void appAdoptionActivationAndEnrollmentReasonsNarrowAnOtherwiseAllowedDecision() {
        evidence(authorizedPermissions(), authorizedResponsibilities());
        when(featureActivations.evaluate(any())).thenReturn(new TenantFeatureActivationReader.GateResult(
                TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED,
                "TENANT_APP_ADOPTION_REQUIRED",
                "HRIS_ORGANIZATION_POSITIONS",
                "feature-revision-app-absent"));

        var appMissing = adapter.evaluate(request(ProductSurfaceAuthorityDtos.AccessMode.NORMAL));

        assertThat(appMissing.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ACTIVATION_REQUIRED);
        assertThat(appMissing.reasonCode()).isEqualTo("TENANT_APP_ADOPTION_REQUIRED");
        assertThat(appMissing.effectiveGrants()).isEmpty();
        assertThat(appMissing.tenantControlRevision()).startsWith("tenant-control-v1-");

        when(featureActivations.evaluate(any())).thenReturn(new TenantFeatureActivationReader.GateResult(
                TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED,
                "FEATURE_ACTIVATION_REQUIRED",
                "HRIS_ORGANIZATION_POSITIONS",
                "feature-revision-off"));
        assertThat(adapter.evaluate(request(ProductSurfaceAuthorityDtos.AccessMode.NORMAL)).reasonCode())
                .isEqualTo("FEATURE_ACTIVATION_REQUIRED");

        when(featureActivations.evaluate(any())).thenReturn(new TenantFeatureActivationReader.GateResult(
                TenantFeatureActivationReader.State.TENANT_NOT_ENROLLED,
                "TENANT_FEATURE_ENROLLMENT_REQUIRED",
                "HRIS_ORGANIZATION_POSITIONS",
                "feature-revision-unenrolled"));
        assertThat(adapter.evaluate(request(ProductSurfaceAuthorityDtos.AccessMode.NORMAL)).reasonCode())
                .isEqualTo("TENANT_FEATURE_ENROLLMENT_REQUIRED");
    }

    @Test
    void onlyAnActiveGatePreservesAuthorityAndProviderModeCannotBypassTheGate() {
        evidence(authorizedPermissions(), authorizedResponsibilities());
        when(featureActivations.evaluate(any())).thenReturn(new TenantFeatureActivationReader.GateResult(
                TenantFeatureActivationReader.State.ACTIVE,
                "ACTIVE",
                "HRIS_ORGANIZATION_POSITIONS",
                "feature-revision-active"));

        var allowed = adapter.evaluate(request(ProductSurfaceAuthorityDtos.AccessMode.NORMAL));

        assertThat(allowed.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(allowed.routeGrantRef()).isNotBlank();
        assertThat(allowed.tenantControlRevision()).startsWith("tenant-control-v1-");

        when(featureActivations.evaluate(any())).thenReturn(TenantFeatureActivationReader.GateResult.unavailable());
        var unavailable = adapter.evaluate(request(ProductSurfaceAuthorityDtos.AccessMode.NORMAL));
        assertThat(unavailable.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE);

        clearInvocations(featureActivations);
        var provider = adapter.evaluate(request(ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT));
        assertThat(provider.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SUPPORT_SCOPE_DENIED);
        verifyNoInteractions(featureActivations);

        var providerGate = new ProductAuthorizationDecisionSupport(
                TenantCapabilityOverrideReader.none(), featureActivations, CLOCK)
                .featureGate(
                        request(ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT),
                        50,
                        true);
        assertThat(providerGate.state())
                .isEqualTo(TenantFeatureActivationReader.State.AUTHORITY_UNAVAILABLE);
        verify(featureActivations).evaluate(any());
    }

    private ProductSurfaceAuthorityDtos.EvaluateRequest request(
            ProductSurfaceAuthorityDtos.AccessMode mode) {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(
                10L, 20L, "hcm", "hcm.operations", mode, ROUTE,
                null, null,
                mode == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT
                        ? "support-session" : null,
                mode == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT
                        ? "support-revision" : null,
                mode == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT
                        ? List.of("WORKFORCE_READ") : List.of());
    }

    private void evidence(
            Set<String> permissions,
            List<AppGovernanceDtos.ResourceRole> responsibilities) {
        when(evidenceService.load(10L, 20L)).thenReturn(
                new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                        permissions, Set.of(), responsibilities, List.of(),
                        "auth-test-revision"));
    }

    private Set<String> authorizedPermissions() {
        return Set.of("APP.HCM:VIEW", "DATA.HRIS_ORGANIZATION:VIEW");
    }

    private List<AppGovernanceDtos.ResourceRole> authorizedResponsibilities() {
        return List.of(new AppGovernanceDtos.ResourceRole(
                "HRIS_DUTY_ORGANIZATION_VIEWER", "APP", "APP.HCM",
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                "RS_HCM_ORGANIZATION", null));
    }
}
