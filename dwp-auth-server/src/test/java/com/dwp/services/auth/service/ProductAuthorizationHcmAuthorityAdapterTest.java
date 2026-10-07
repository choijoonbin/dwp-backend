package com.dwp.services.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationHcmAuthorityAdapterTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-21T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProductAuthorizationContractRepository repository;
    @Mock
    private ProductAuthorizationIdentityEvidenceService evidenceService;
    private ProductAuthorizationAuthorityAdapter adapter;

    @Test
    void resolvesTheV33HcmSystemPageToAnExplicitTenantScope() throws IOException {
        useContract("product-surfaces-v1.bundle-v33.generated.json");
        evidence(Set.of("APP.HCM:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult allowed = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.management.system.page",
                null, null, null, null, List.of());
        assertThat(allowed.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(allowed.plane()).isEqualTo("management");
        assertThat(allowed.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("TENANT");
            assertThat(scope.isDefault()).isTrue();
            assertThat(scope.readOnly()).isTrue();
        });
        assertThat(allowed.effectiveGrants())
                .filteredOn(ProductSurfaceAuthorityDtos.PolicyGrant.class::isInstance)
                .map(ProductSurfaceAuthorityDtos.PolicyGrant.class::cast)
                .singleElement().satisfies(grant -> {
                    assertThat(grant.accessPolicyKey())
                            .isEqualTo("hcm.management-system-access.v1");
                    assertThat(grant.scopeKeys())
                            .containsExactly(allowed.scopes().getFirst().key());
                });
    }

    @Test
    void v33HcmPeopleOwnedScopesRequireEligibilityAcrossExecutionServices() throws IOException {
        useContract("product-surfaces-v1.bundle-v33.generated.json");
        evidence(Set.of("DATA.HR_PAY:PUBLISH"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult publishWithoutProduct = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.payroll-foundation-publish.action",
                null, null, null, null, List.of());
        assertThat(publishWithoutProduct.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        evidence(Set.of(
                "APP.HCM:VIEW", "DATA.HR_PAY:VIEW", "DATA.HR_PAY:PUBLISH",
                "DATA.HR_TIME:VIEW", "ACTION.WORKFORCE_DATA_OPERATIONS:VIEW"),
                List.of(role("APP_CONFIG_ADMIN", "APP.HCM", "RS_HCM_CONFIG")));
        ProductSurfaceAuthorityDtos.AuthorityResult payrollList = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.payroll-foundation-configurations.data",
                null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult payrollPublish = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.payroll-foundation-publish.action",
                null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult timeList = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.work-plans-list.data",
                null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult personalPreference = evaluate(
                "hcm", "hcm.personal", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.personal.home-preference.data",
                null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult managementCodeSet = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.management.integration-code-sets.data",
                null, null, null, null, List.of());
        assertThat(List.of(payrollList, timeList)).allSatisfy(result -> {
            assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            assertThat(result.requiresProductEligibility()).isTrue();
            assertThat(result.scopes()).singleElement().satisfies(scope ->
                    assertThat(scope.kind()).isEqualTo("TARGET_POPULATION"));
        });
        assertThat(payrollPublish.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(payrollPublish.requiresProductEligibility()).isTrue();
        assertThat(payrollPublish.scopes()).singleElement().satisfies(scope ->
                assertThat(scope.kind()).isEqualTo("TARGET_POPULATION"));
        assertThat(personalPreference.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(personalPreference.requiresProductEligibility()).isTrue();
        assertThat(personalPreference.scopes()).singleElement().satisfies(scope ->
                assertThat(scope.kind()).isEqualTo("SELF"));
        assertThat(managementCodeSet.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(managementCodeSet.requiresProductEligibility()).isTrue();
        assertThat(managementCodeSet.scopes()).singleElement().satisfies(scope ->
                assertThat(scope.kind()).isEqualTo("RESOURCE_SET"));
    }

    @Test
    void v33HcmTenantAndSupportScopesStayOutsidePeopleEligibility() throws IOException {
        useContract("product-surfaces-v1.bundle-v33.generated.json");
        evidence(Set.of("APP.HCM:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult managementSystem = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.management.system.page", null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult personalConfiguration = evaluate(
                "hcm", "hcm.personal", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.personal.configuration-projection.data",
                null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult personalAccessSnapshot = evaluate(
                "hcm", "hcm.personal", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.personal.product-access-snapshot.data",
                null, null, null, null, List.of());
        assertThat(List.of(managementSystem, personalConfiguration, personalAccessSnapshot))
                .allSatisfy(result -> {
                    assertThat(result.decision())
                            .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
                    assertThat(result.requiresProductEligibility()).isFalse();
                    assertThat(result.scopes()).singleElement().satisfies(scope ->
                            assertThat(scope.kind()).isEqualTo("TENANT"));
                });
        evidence(Set.of(), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult support = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                "route.hcm.operations.overview.page", null, null,
                "support-1", "support-rev-1", List.of("WORKFORCE_READ"));
        assertThat(support.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(support.requiresProductEligibility()).isFalse();
        assertThat(support.scopes()).singleElement().satisfies(scope ->
                assertThat(scope.kind()).isEqualTo("SUPPORT_SESSION"));
    }

    @Test
    void platformOwnedHcmCatalogRouteKeepsTheSurfacePeopleEligibilityBoundary()
            throws IOException {
        useContract("product-surfaces-v1.bundle-v32.generated.json");
        evidence(Set.of("ACTION.WORKFORCE_DATA_OPERATIONS:VIEW"), List.of(role(
                "APP_CONFIG_ADMIN", "APP.HCM", "RS_HCM_CONFIG")));
        ProductSurfaceAuthorityDtos.AuthorityResult entry = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null, null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult route = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.management.integration-code-sets.data",
                entry.contextKey(), "people-owned-scope", null, null, List.of());
        assertThat(entry.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(route.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(route.contextKey()).isEqualTo(entry.contextKey());
        assertThat(route.requiresProductEligibility()).isTrue();
        evidence(Set.of("ADMIN.COMMUNICATIONS:VIEW"), List.of(role(
                "APP_CONFIG_ADMIN", "APP.COMMUNICATIONS", "RS_COMMUNICATIONS")));
        ProductSurfaceAuthorityDtos.AuthorityResult communications = evaluate(
                "communications", "communications.management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.communications.management.code-sets.data",
                null, null, null, null, List.of());
        assertThat(communications.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(communications.requiresProductEligibility()).isFalse();
    }

    private void useContract(String resource) throws IOException {
        ProductAuthorizationContractDtos.BundleContract contract = new ObjectMapper()
                .findAndRegisterModules().readValue(getClass().getResourceAsStream(
                        "/product-authorization/" + resource),
                        ProductAuthorizationContractDtos.BundleContract.class);
        UUID bundleId = UUID.fromString("22222222-2222-2222-2222-222222222222");
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
        adapter = new ProductAuthorizationAuthorityAdapter(
                repository, evidenceService, CLOCK, "urn:dwp:acr:mfa");
    }

    private void evidence(
            Set<String> permissions, List<AppGovernanceDtos.ResourceRole> responsibilities) {
        when(evidenceService.load(10L, 20L)).thenReturn(
                new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                        permissions, Set.of(), responsibilities, List.of(),
                        "auth-test-revision"));
    }

    private AppGovernanceDtos.ResourceRole role(
            String responsibility, String resourceKey, String resourceSetKey) {
        return new AppGovernanceDtos.ResourceRole(
                responsibility, "APP", resourceKey,
                UUID.nameUUIDFromBytes(resourceSetKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                resourceSetKey, null);
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            String product, String surface, ProductSurfaceAuthorityDtos.AccessMode mode,
            String route, String context, String scope, String supportSession,
            String supportRevision, List<String> supportScopes) {
        return adapter.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                10L, 20L, product, surface, mode, route, context, scope,
                supportSession, supportRevision, supportScopes));
    }
}
