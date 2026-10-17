package com.dwp.services.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dwp.core.security.ProductSurfaceScopeKey;
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
    void v45NavigationScopeSplitReturnsTenantSelfAndOperationsPopulation()
            throws IOException {
        useContract("product-surfaces-v1.bundle-v45.generated.json");
        evidence(Set.of(
                "APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW",
                "DATA.WORKFORCE:VIEW"), List.of(role(
                "HRIS_DUTY_APP_SHELL_READER", "APP.HCM",
                "RS_HCM_UTILITY_SELF")));

        ProductSurfaceAuthorityDtos.AuthorityResult tenant = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.shell.navigation-aggregate.data",
                null, null, null, null, List.of());

        // Legal-entity navigation is authorized by the canonical operations authority alone;
        // it must not inherit the tenant shell duty as a hidden prerequisite.
        evidence(Set.of("APP.HCM:VIEW", "DATA.WORKFORCE:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult legalEntity = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.shell.navigation-aggregate-legal-entity.data",
                null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult wrongSurface = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.shell.navigation-aggregate-legal-entity.data",
                null, null, null, null, List.of());

        assertThat(tenant.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(tenant.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("SELF");
            assertThat(scope.key()).isEqualTo(ProductSurfaceScopeKey.key(
                    10, 20, "hcm", "hcm.utility", "SELF", "SELF"));
        });
        assertThat(tenant.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.capabilityContractKey())
                                .isEqualTo("hcm.app.shell.view"));

        assertThat(legalEntity.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(legalEntity.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
            assertThat(scope.key()).isEqualTo(ProductSurfaceScopeKey.key(
                    10, 20, "hcm", "hcm.operations",
                    "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION"));
        });
        assertThat(legalEntity.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> {
                            assertThat(grant.capabilityContractKey())
                                    .isEqualTo("hcm.operations.workforce.read");
                            // The runtime node projection consumes this same canonical code;
                            // an HRIS-prefixed alias must not create a second authority dialect.
                            assertThat(grant.resolvedCapabilityCode())
                                    .isEqualTo("DATA.WORKFORCE:VIEW");
                        });
        assertThat(wrongSurface.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
    }

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

    @Test
    void v43SkipsPeopleOnlyForTheSelectedOriginatingCommandReceiptGrant()
            throws IOException {
        useContract("product-surfaces-v1.bundle-v43.generated.json");
        evidence(Set.of("DATA.HRIS_COMMAND_RECEIPT:VIEW"), List.of());

        ProductSurfaceAuthorityDtos.AuthorityResult explorerReceipt = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer-command-receipt.data",
                null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult assignmentReceipt = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.assignment-command-receipt.data",
                null, null, null, null, List.of());

        assertThat(explorerReceipt.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(explorerReceipt.requiresProductEligibility()).isFalse();
        assertThat(explorerReceipt.effectiveGrants())
                .singleElement().isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.capabilityContractKey())
                                .isEqualTo("hcm.command-receipt.view"));
        String originatingCommandScope = ProductSurfaceScopeKey.key(
                10, 20, "hcm", "hcm.utility",
                "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION");
        assertThat(explorerReceipt.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
            assertThat(scope.key()).isEqualTo(originatingCommandScope);
        });
        assertThat(explorerReceipt.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.scopeKeys())
                                .containsExactly(originatingCommandScope));

        assertThat(assignmentReceipt.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(assignmentReceipt.requiresProductEligibility()).isFalse();
        String operationsReceiptScope = ProductSurfaceScopeKey.key(
                10, 20, "hcm", "hcm.operations",
                "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION");
        assertThat(assignmentReceipt.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.key()).isEqualTo(operationsReceiptScope);
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
            assertThat(scope.readOnly()).isTrue();
        });
        assertThat(assignmentReceipt.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> {
                            assertThat(grant.capabilityContractKey())
                                    .isEqualTo("hcm.command-receipt.view");
                            assertThat(grant.scopeKeys())
                                    .containsExactly(operationsReceiptScope);
                        });
    }

    @Test
    void v43EmployeeChangeSelectorChoosesTheExactProfileAndHighRiskMode()
            throws IOException {
        useContract("product-surfaces-v1.bundle-v43.generated.json");
        evidence(Set.of(
                "DATA.HRIS_WORKFORCE:UPDATE",
                "ACTION.HRIS_PEOPLE_CHANGE_REQUEST:CREATE",
                "DATA.HRIS_SELF_PROFILE:UPDATE",
                "ACTION.HRIS_WORKFORCE_CHANGE_APPROVE:APPROVE"), List.of());
        String operations = ProductSurfaceScopeKey.key(
                10, 20, "hcm", "hcm.operations",
                "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION");
        String team = ProductSurfaceScopeKey.key(
                10, 20, "hcm", "hcm.operations",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TARGET_POPULATION");
        String self = ProductSurfaceScopeKey.key(
                10, 20, "hcm", "hcm.operations", "SELF", "SELF");

        ProductSurfaceAuthorityDtos.AuthorityResult operationsResult = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.employee-change-request-create.action",
                null, operations, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult teamResult = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.employee-change-request-create.action",
                null, team, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult selfResult = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.employee-change-request-submit.action",
                null, self, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult wrongActor = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.employee-change-request-create.action", null,
                ProductSurfaceScopeKey.key(
                        10, 21, "hcm", "hcm.operations", "SELF", "SELF"),
                null, null, List.of());

        assertCapability(operationsResult, "hcm.operations.people.update", operations,
                ProductSurfaceAuthorityDtos.ActivationState.ACTIVE);
        assertCapability(teamResult, "hcm.team.people-change.request", team,
                ProductSurfaceAuthorityDtos.ActivationState.ACTIVE);
        assertCapability(selfResult, "hcm.personal.profile.update", self,
                ProductSurfaceAuthorityDtos.ActivationState.ACTIVE);
        assertThat(wrongActor.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);

        ProductSurfaceAuthorityDtos.AuthorityResult normalHighRisk = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.assignment-event-publish.action",
                null, operations, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult elevatedHighRisk = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                "route.hcm.operations.employee-change-request-apply.action",
                null, operations, null, null, List.of());

        assertThat(normalHighRisk.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(normalHighRisk.reasonCode()).isEqualTo("STEP_UP_REQUIRED");
        assertThat(normalHighRisk.requiredAssurance()).isEqualTo("urn:dwp:acr:mfa");
        assertThat(normalHighRisk.requestPolicyRef()).isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertCapability(normalHighRisk, "hcm.operations.people-change.approve", operations,
                ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE);
        assertThat(elevatedHighRisk.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(elevatedHighRisk.requiredAssurance()).isNull();
        assertThat(elevatedHighRisk.requestPolicyRef()).isNull();
        assertCapability(elevatedHighRisk, "hcm.operations.people-change.approve", operations,
                ProductSurfaceAuthorityDtos.ActivationState.ACTIVE);
    }

    @Test
    void v43ExplorerShellAndOrdinaryHcmScopesStillRequirePeopleEligibility()
            throws IOException {
        useContract("product-surfaces-v1.bundle-v43.generated.json");
        OffsetDateTime validTo = OffsetDateTime.now(CLOCK).plusMinutes(20);
        evidence(Set.of("APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW"), List.of(role(
                "HRIS_DUTY_APP_SHELL_READER", "APP.HCM",
                "RS_HCM_UTILITY_SELF", validTo)));

        ProductSurfaceAuthorityDtos.AuthorityResult explorerPage = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer.page",
                null, null, null, null, List.of());

        assertThat(explorerPage.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(explorerPage.requiresProductEligibility()).isTrue();
        assertThat(explorerPage.effectiveGrants())
                .singleElement().isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> {
                            assertThat(grant.capabilityContractKey())
                                    .isEqualTo("hcm.app.shell.view");
                            assertThat(grant.responsibility())
                                    .isEqualTo(new ProductSurfaceAuthorityDtos.Responsibility(
                                            "HRIS_DUTY_APP_SHELL_READER",
                                            "RS_HCM_UTILITY_SELF"));
                            assertThat(grant.validUntil()).isEqualTo(validTo);
                        });
        assertThat(explorerPage.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("SELF");
            assertThat(scope.key()).isEqualTo(ProductSurfaceScopeKey.key(
                    10, 20, "hcm", "hcm.utility", "SELF", "SELF"));
            assertThat(scope.validUntil()).isEqualTo(validTo);
            assertThat(explorerPage.effectiveGrants().getFirst().scopeKeys())
                    .containsExactly(scope.key());
        });
    }

    @Test
    void v43ExactCanonicalReceiptScopeSelectsPlatformProfileForOverlapOnly()
            throws IOException {
        useContract("product-surfaces-v1.bundle-v43.generated.json");
        evidence(Set.of(
                "APP.HCM:VIEW", "DATA.HRIS_APP_SHELL:VIEW",
                "DATA.HRIS_COMMAND_RECEIPT:VIEW"), List.of(role(
                "HRIS_DUTY_APP_SHELL_READER", "APP.HCM",
                "RS_HCM_UTILITY_SELF")));
        String selector = ProductSurfaceScopeKey.key(
                10, 20, "hcm", "hcm.utility",
                "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION");

        ProductSurfaceAuthorityDtos.AuthorityResult selected = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer-command-receipt.data",
                null, selector, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult ordinary = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer-command-receipt.data",
                null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult explorerScoped = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer-command-receipt.data",
                null, "hcm-scope-" + "b".repeat(40), null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult forged = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer-command-receipt.data",
                null, "scope-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult malformed = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer-command-receipt.data",
                null, "scope-bad", null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult selectorOnExplorerPage = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer.page",
                null, selector, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult otherTenantSelector = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer.page", null,
                ProductSurfaceScopeKey.key(
                        11, 20, "hcm", "hcm.utility",
                        "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION"),
                null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult otherActorSelector = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer.page", null,
                ProductSurfaceScopeKey.key(
                        10, 21, "hcm", "hcm.utility",
                        "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION"),
                null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult otherTenantReceiptSelector = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer-command-receipt.data", null,
                ProductSurfaceScopeKey.key(
                        11, 20, "hcm", "hcm.utility",
                        "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION"),
                null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult otherActorReceiptSelector = evaluate(
                "hcm", "hcm.utility", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.utility.explorer-command-receipt.data", null,
                ProductSurfaceScopeKey.key(
                        10, 21, "hcm", "hcm.utility",
                        "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION"),
                null, null, List.of());

        assertThat(selected.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(selected.requiresProductEligibility()).isFalse();
        assertThat(selected.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.key()).isEqualTo(selector);
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
        });
        assertThat(selected.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.capabilityContractKey())
                                .isEqualTo("hcm.command-receipt.view"));
        assertThat(ordinary.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.capabilityContractKey())
                                .isEqualTo("hcm.app.shell.view"));
        assertThat(explorerScoped.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.capabilityContractKey())
                                .isEqualTo("hcm.app.shell.view"));
        assertThat(forged.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
        assertThat(malformed.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
        assertThat(selectorOnExplorerPage.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
        assertThat(otherTenantSelector.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(otherActorSelector.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(otherTenantReceiptSelector.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
        assertThat(otherActorReceiptSelector.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
    }

    @Test
    void v52SharedCertificateActionSelectsOneProfileAndKeepsRouteWideHigh()
            throws IOException {
        useContract("product-surfaces-v1.bundle-v52.generated.json");
        evidence(Set.of(
                "APP.HCM:VIEW",
                "ACTION.HRIS_CERTIFICATE:CREATE",
                "ACTION.HRIS_CERTIFICATE:ISSUE"), List.of());
        String operations = ProductSurfaceScopeKey.key(
                10, 20, "hcm", "hcm.operations",
                "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION");
        String self = ProductSurfaceScopeKey.key(
                10, 20, "hcm", "hcm.operations", "SELF", "SELF");
        String team = ProductSurfaceScopeKey.key(
                10, 20, "hcm", "hcm.operations",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TARGET_POPULATION");

        var operationsResult = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.certificate-issue.action",
                null, operations, null, null, List.of());
        var selfResult = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.certificate-issue.action",
                null, self, null, null, List.of());
        var elevatedSelf = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                "route.hcm.operations.certificate-issue.action",
                null, self, null, null, List.of());
        var deferredTeam = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.certificate-issue.action",
                null, team, null, null, List.of());

        assertThat(operationsResult.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(operationsResult.requestPolicyRef()).isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertCapability(operationsResult, "hcm.operations.certificate.issue", operations,
                ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE);

        assertThat(selfResult.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(selfResult.requestPolicyRef()).isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertCapability(selfResult, "hcm.personal.certificate.request", self,
                ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE);

        assertThat(elevatedSelf.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertCapability(elevatedSelf, "hcm.personal.certificate.request", self,
                ProductSurfaceAuthorityDtos.ActivationState.ACTIVE);
        assertThat(deferredTeam.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
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
        return role(responsibility, resourceKey, resourceSetKey, null);
    }

    private AppGovernanceDtos.ResourceRole role(
            String responsibility, String resourceKey, String resourceSetKey,
            OffsetDateTime validTo) {
        return new AppGovernanceDtos.ResourceRole(
                responsibility, "APP", resourceKey,
                UUID.nameUUIDFromBytes(resourceSetKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                resourceSetKey, validTo);
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            String product, String surface, ProductSurfaceAuthorityDtos.AccessMode mode,
            String route, String context, String scope, String supportSession,
            String supportRevision, List<String> supportScopes) {
        return adapter.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                10L, 20L, product, surface, mode, route, context, scope,
                supportSession, supportRevision, supportScopes));
    }

    private void assertCapability(
            ProductSurfaceAuthorityDtos.AuthorityResult result,
            String capability,
            String scope,
            ProductSurfaceAuthorityDtos.ActivationState activationState) {
        assertThat(result.decision()).isIn(
                ProductSurfaceAuthorityDtos.Decision.ALLOWED,
                ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(result.scopes()).singleElement().satisfies(value ->
                assertThat(value.key()).isEqualTo(scope));
        assertThat(result.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> {
                            assertThat(grant.capabilityContractKey()).isEqualTo(capability);
                            assertThat(grant.scopeKeys()).containsExactly(scope);
                            assertThat(grant.activationState()).isEqualTo(activationState);
                        });
    }
}
