package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.tenantcapabilityoverride.TenantCapabilityOverrideReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationTenantCapabilityOverrideTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-21T09:00:00Z"), ZoneOffset.UTC);

    @Mock private ProductAuthorizationContractRepository repository;
    @Mock private ProductAuthorizationIdentityEvidenceService evidenceService;
    @Mock private TenantCapabilityOverrideReader overrides;

    private ProductAuthorizationAuthorityAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        ProductAuthorizationContractDtos.BundleContract contract = new ObjectMapper()
                .findAndRegisterModules()
                .readValue(getClass().getResourceAsStream(
                                "/product-authorization/"
                                        + "product-surfaces-v1.bundle-v3.generated.json"),
                        ProductAuthorizationContractDtos.BundleContract.class);
        UUID bundleId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        ProductAuthorizationContractRepository.StoredBundle stored =
                new ProductAuthorizationContractRepository.StoredBundle(
                        bundleId, contract.bundleKey(), contract.version(), "ACTIVE",
                        contract.schemaVersion(), contract.checksumAlgorithm(),
                        contract.checksum(), contract.owner(), "security-reviewer",
                        now, now, now);
        when(repository.findActive("product-surfaces")).thenReturn(Optional.of(stored));
        when(repository.loadContract(stored)).thenReturn(contract);
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", bundleId, contract.version(), "release", now)));
        when(overrides.effectiveRevision(10L, CLOCK.instant())).thenReturn("0");
        adapter = new ProductAuthorizationAuthorityAdapter(
                repository, evidenceService, overrides, CLOCK, "urn:dwp:acr:mfa");
        evidence();
    }

    @Test
    void deniesAnOtherwiseAllowedRouteWhenTheTenantSuppressedItsCapability() {
        when(overrides.isDisabled(
                10L, "services.catalog.read", CLOCK.instant())).thenReturn(true);

        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(null);

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(result.reasonCode()).isEqualTo("TENANT_CAPABILITY_DISABLED");
    }

    @Test
    void invalidatesAnExistingContextWhenTheTenantOverrideRevisionChanges() {
        ProductSurfaceAuthorityDtos.AuthorityResult first = evaluate(null);
        when(overrides.effectiveRevision(10L, CLOCK.instant())).thenReturn("1");

        ProductSurfaceAuthorityDtos.AuthorityResult stale = evaluate(first.contextKey());

        assertThat(stale.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
        assertThat(stale.reasonCode()).isEqualTo("SCOPE_CONTEXT_EXPIRED");
    }

    private void evidence() {
        AppGovernanceDtos.ResourceRole role = new AppGovernanceDtos.ResourceRole(
                "APP_CONFIG_ADMIN", "APP", "APP.EMPLOYEE_SERVICES",
                UUID.nameUUIDFromBytes("RS_SERVICES".getBytes(StandardCharsets.UTF_8)),
                "RS_SERVICES", null);
        when(evidenceService.load(10L, 20L)).thenReturn(
                new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                        Set.of("ADMIN.SERVICE_CATALOG:VIEW"), Set.of(),
                        List.of(role), List.of(), "auth-test-revision"));
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult evaluate(String contextKey) {
        return adapter.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                10L, 20L, "services", "services.management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.services.management.catalog.page", contextKey,
                null, null, null, List.of()));
    }
}
