package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationScopeAdapterTest extends ProductAuthorizationAuthorityFixture {
    @Test
    void optionalAdminResponsibilityDoesNotRebindPermissionRichApprovalWorkEntry() {
        evidence(Set.of(
                "APP.APPROVALS:VIEW",
                "ACTION.APPROVAL_TASK:VIEW",
                "ACTION.APPROVAL_TASK:UPDATE",
                "ACTION.APPROVAL_TASK:APPROVE",
                "ACTION.APPROVAL_REQUEST:VIEW",
                "ACTION.APPROVAL_REQUEST:CREATE",
                "ACTION.APPROVAL_REQUEST:UPDATE",
                "ACTION.APPROVAL_DELEGATION:VIEW",
                "ACTION.APPROVAL_DELEGATION:MANAGE"), List.of(role(
                        "APP_CONFIG_ADMIN", "APP.APPROVALS", "RS_APPROVALS")));
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceAuthorityPort> ports =
                org.mockito.Mockito.mock(ObjectProvider.class);
        when(ports.orderedStream()).thenAnswer(ignored -> Stream.of(adapter));
        ProductSurfaceAuthorityService service = new ProductSurfaceAuthorityService(ports);
        ProductSurfaceAuthorityDtos.EvaluateRequest approvalWorkRequest =
                request("approvals", "approvals.work");
        ProductSurfaceAuthorityDtos.AuthorityResult approvals = service.evaluate(
                approvalWorkRequest);
        assertThat(approvals.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(approvals.scopes()).hasSize(1);
        ProductSurfaceAuthorityDtos.EffectiveScope selfScope = approvals.scopes().getFirst();
        assertThat(selfScope.kind()).isEqualTo("SELF");
        assertThat(selfScope.isDefault()).isTrue();
        assertThat(approvals.effectiveGrants())
                .filteredOn(ProductSurfaceAuthorityDtos.CapabilityGrant.class::isInstance)
                .map(ProductSurfaceAuthorityDtos.CapabilityGrant.class::cast)
                .hasSize(8)
                .allSatisfy(grant -> {
                    assertThat(grant.responsibilityRequirement())
                            .isEqualTo(
                                    ProductSurfaceAuthorityDtos.ResponsibilityRequirement.NOT_REQUIRED);
                    assertThat(grant.responsibility()).isNull();
                    assertThat(grant.scopeKeys()).containsExactly(selfScope.key());
                });
        assertThat(approvals.effectiveGrants())
                .filteredOn(ProductSurfaceAuthorityDtos.PolicyGrant.class::isInstance)
                .map(ProductSurfaceAuthorityDtos.PolicyGrant.class::cast)
                .singleElement()
                .satisfies(grant -> assertThat(grant.scopeKeys())
                        .containsExactly(selfScope.key()));
        List<String> workPageRoutes = List.of(
                "route.approvals.work.completed.page",
                "route.approvals.work.delegations.page",
                "route.approvals.work.home.page",
                "route.approvals.work.inbox.page",
                "route.approvals.work.request-archive.page",
                "route.approvals.work.request-drafts.page",
                "route.approvals.work.request-needs-info.page",
                "route.approvals.work.request-new.page",
                "route.approvals.work.request-submitted.page");
        for (String route : workPageRoutes) {
            ProductSurfaceAuthorityDtos.AuthorityResult directWithoutContext =
                    service.evaluate(request("approvals", "approvals.work", route));
            assertSelfScopedWorkRoute(directWithoutContext, selfScope.key());
            assertThat(directWithoutContext.contextKey())
                    .isEqualTo(approvals.contextKey());
            ProductSurfaceAuthorityDtos.AuthorityResult direct = service.evaluate(request(
                    "approvals", "approvals.work", route,
                    approvals.contextKey(), selfScope.key()));
            assertSelfScopedWorkRoute(direct, selfScope.key());
            assertThat(direct.contextKey()).isEqualTo(approvals.contextKey());
        }
    }

    @Test
    void optionalHcmResponsibilityDoesNotRebindTeamOrOperationsTargetPopulations() {
        evidence(Set.of(
                "APP.HCM:VIEW",
                "DATA.HR_TIME:VIEW"), List.of(role(
                        "APP_CONFIG_ADMIN", "APP.HCM", "RS_HCM_CONFIG")));
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceAuthorityPort> ports =
                org.mockito.Mockito.mock(ObjectProvider.class);
        when(ports.orderedStream()).thenAnswer(ignored -> Stream.of(adapter));
        ProductSurfaceAuthorityService service = new ProductSurfaceAuthorityService(ports);
        ProductSurfaceAuthorityDtos.AuthorityResult teamTime = service.evaluate(request(
                "hcm", "hcm.team", "route.hcm.team.time.page"));
        ProductSurfaceAuthorityDtos.AuthorityResult operationsTime = service.evaluate(request(
                "hcm", "hcm.operations", "route.hcm.operations.time.page"));
        assertTargetPopulationCapabilityScope(teamTime, "hcm.team.time.read");
        assertTargetPopulationCapabilityScope(
                operationsTime, "hcm.operations.time.read");
    }

    @Test
    void hcmTeamAndOperationsKeepModeSpecificEntryAndPageScopeKinds() {
        evidence(Set.of(
                "APP.HCM:VIEW",
                "DATA.WORKFORCE:VIEW",
                "DATA.HR_TIME:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult teamEntry = evaluate(
                "hcm", "hcm.team", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null, null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult teamTime = evaluate(
                "hcm", "hcm.team", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.team.time.page", null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult operationsEntry = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null, null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult operationsTime = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.time.page", null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult operationsOverview = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.overview.page", null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult operationsPeople = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.people.page", null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult operationsAssignments = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.assignments.page", null, null, null, null, List.of());
        assertThat(List.of(
                teamEntry, teamTime, operationsEntry, operationsTime, operationsOverview,
                operationsPeople, operationsAssignments))
                .allSatisfy(result -> {
                    assertThat(result.decision())
                            .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
                    assertThat(result.requiresProductEligibility()).isTrue();
                });
        assertThat(List.of(teamEntry, teamTime, operationsEntry, operationsTime,
                operationsPeople, operationsAssignments))
                .allSatisfy(result -> assertThat(result.scopes()).isNotEmpty()
                        .extracting(ProductSurfaceAuthorityDtos.EffectiveScope::kind)
                        .containsOnly("TARGET_POPULATION"));
        evidence(Set.of(), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult operationsSupportEntry = evaluate(
                "hcm", "hcm.operations",
                ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                null, null, null, "support-1", "support-rev-1",
                List.of("WORKFORCE_READ"));
        ProductSurfaceAuthorityDtos.AuthorityResult operationsSupportPage = evaluate(
                "hcm", "hcm.operations",
                ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                "route.hcm.operations.overview.page", null, null,
                "support-1", "support-rev-1", List.of("WORKFORCE_READ"));
        assertThat(List.of(operationsSupportEntry, operationsSupportPage))
                .allSatisfy(result -> {
                    assertThat(result.decision())
                            .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
                    assertThat(result.appResourceKey()).isEqualTo("APP.HCM");
                    assertThat(result.scopes()).singleElement().satisfies(scope -> {
                        assertThat(scope.kind()).isEqualTo("SUPPORT_SESSION");
                        assertThat(scope.readOnly()).isTrue();
                    });
                });
        ProductSurfaceAuthorityDtos.AuthorityResult teamSupportEntry = evaluate(
                "hcm", "hcm.team", ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                null, null, null, "support-1", "support-rev-1",
                List.of("WORKFORCE_READ"));
        ProductSurfaceAuthorityDtos.AuthorityResult teamSupportPage = evaluate(
                "hcm", "hcm.team", ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                "route.hcm.team.time.page", null, null,
                "support-1", "support-rev-1", List.of("WORKFORCE_READ"));
        ProductSurfaceAuthorityDtos.AuthorityResult unsupportedOperationsPage = evaluate(
                "hcm", "hcm.operations",
                ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                "route.hcm.operations.time.page", null, null,
                "support-1", "support-rev-1", List.of("WORKFORCE_READ"));
        assertThat(List.of(teamSupportEntry, teamSupportPage, unsupportedOperationsPage))
                .allSatisfy(result -> {
                    assertThat(result.decision())
                            .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SUPPORT_SCOPE_DENIED);
                    assertThat(result.scopes()).isEmpty();
                });
    }

    @Test
    void multipleHcmPersonalEntryPoliciesRemainBoundToTheSelectedDirectPolicy() {
        evidence(Set.of(
                "APP.HCM:VIEW",
                "APP.PEOPLE_DIRECTORY:VIEW",
                "APP.EMPLOYEE_SERVICES:VIEW"), List.of());
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceAuthorityPort> ports =
                org.mockito.Mockito.mock(ObjectProvider.class);
        when(ports.orderedStream()).thenAnswer(ignored -> Stream.of(adapter));
        ProductSurfaceAuthorityService service = new ProductSurfaceAuthorityService(ports);
        ProductSurfaceAuthorityDtos.AuthorityResult entry = service.evaluate(
                request("hcm", "hcm.personal"));
        assertThat(entry.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(entry.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("SELF");
            assertThat(scope.isDefault()).isTrue();
        });
        assertThat(entry.effectiveGrants())
                .filteredOn(ProductSurfaceAuthorityDtos.PolicyGrant.class::isInstance)
                .hasSize(4);
        ProductSurfaceAuthorityDtos.EffectiveScope selfScope = entry.scopes().getFirst();
        ProductSurfaceAuthorityDtos.AuthorityResult directory = service.evaluate(request(
                "hcm", "hcm.personal", "route.hcm.personal.directory.page",
                entry.contextKey(), selfScope.key()));
        assertThat(directory.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(directory.contextKey()).isEqualTo(entry.contextKey());
        assertThat(directory.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.key()).isEqualTo(selfScope.key());
            assertThat(scope.kind()).isEqualTo("SELF");
            assertThat(scope.isDefault()).isTrue();
        });
        assertThat(directory.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.PolicyGrant.class,
                        grant -> assertThat(grant.accessPolicyKey())
                                .isEqualTo("hcm.directory-access.v1"));
    }

    @Test
    void capabilityExpressionReductionIsPermutationIndependentForAnyAndAll()
            throws Exception {
        String route = "route.hcm.configuration.configurations.data";
        Set<String> both = Set.of(
                "CONFIG.HRIS_POLICY:UPDATE", "ACTION.HRIS_POLICY_PUBLISH:PUBLISH");

        ProductSurfaceAuthorityDtos.AuthorityResult anyAllowedForward = expressionResult(
                false, "ANY", both, route);
        ProductSurfaceAuthorityDtos.AuthorityResult anyAllowedReverse = expressionResult(
                true, "ANY", both, route);
        assertThat(anyAllowedForward).isEqualTo(anyAllowedReverse);
        assertThat(anyAllowedForward.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);

        Set<String> activatedOnly = Set.of("ACTION.HRIS_POLICY_PUBLISH:PUBLISH");
        ProductSurfaceAuthorityDtos.AuthorityResult anyChallengeForward = expressionResult(
                false, "ANY", activatedOnly, route);
        ProductSurfaceAuthorityDtos.AuthorityResult anyChallengeReverse = expressionResult(
                true, "ANY", activatedOnly, route);
        assertThat(anyChallengeForward).isEqualTo(anyChallengeReverse);
        assertThat(anyChallengeForward.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);

        ProductSurfaceAuthorityDtos.AuthorityResult allHardDenyForward = expressionResult(
                false, "ALL", activatedOnly, route);
        ProductSurfaceAuthorityDtos.AuthorityResult allHardDenyReverse = expressionResult(
                true, "ALL", activatedOnly, route);
        assertThat(allHardDenyForward).isEqualTo(allHardDenyReverse);
        assertThat(allHardDenyForward.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);

        ProductSurfaceAuthorityDtos.AuthorityResult allChallengeForward = expressionResult(
                false, "ALL", both, route);
        ProductSurfaceAuthorityDtos.AuthorityResult allChallengeReverse = expressionResult(
                true, "ALL", both, route);
        assertThat(allChallengeForward).isEqualTo(allChallengeReverse);
        assertThat(allChallengeForward.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);

        ProductSurfaceAuthorityDtos.AuthorityResult elevatedForward = expressionResult(
                false, "ANY", both, route, ProductSurfaceAuthorityDtos.AccessMode.ELEVATED);
        ProductSurfaceAuthorityDtos.AuthorityResult elevatedReverse = expressionResult(
                true, "ANY", both, route, ProductSurfaceAuthorityDtos.AccessMode.ELEVATED);
        assertThat(elevatedForward).isEqualTo(elevatedReverse);
        assertThat(elevatedForward.effectiveGrants())
                .filteredOn(ProductSurfaceAuthorityDtos.CapabilityGrant.class::isInstance)
                .map(ProductSurfaceAuthorityDtos.CapabilityGrant.class::cast)
                .extracting(ProductSurfaceAuthorityDtos.CapabilityGrant::capabilityContractKey)
                .containsExactly(
                        "hcm.configuration.policy.publish",
                        "hcm.configuration.policy.update");
    }

    @Test
    void grandfatheredMultiProfileRouteAllowsAnApplicableNonActivatedAlternative()
            throws Exception {
        useContract("product-surfaces-v1.bundle-v49.generated.json");
        evidence(Set.of("DATA.HR_TIME:UPDATE", "DATA.HR_TIME:VIEW"), List.of());

        ProductSurfaceAuthorityDtos.AuthorityResult allowed = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.time.timecard.data",
                null, null, null, null, List.of());

        assertThat(allowed.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(allowed.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.capabilityContractKey())
                                .isEqualTo("hcm.personal.time.view"));

        evidence(Set.of("DATA.HR_TIME:UPDATE"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult decisiveChallenge = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.time.timecard.data",
                null, null, null, null, List.of());
        assertThat(decisiveChallenge.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
    }

}
