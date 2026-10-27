package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationAuthorityAdapterTest extends ProductAuthorizationAuthorityFixture {

    @Test
    void allowsWorkEntryFromExactProductEntitlement() {
        evidence(Set.of("APP.COMMUNICATIONS:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                "communications", "communications.work",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL, null,
                null, null, null, null, List.of());
        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.plane()).isEqualTo("work");
        assertThat(result.accessSource())
                .isEqualTo(ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT);
    }

    @Test
    void requiresResponsibilityInTheDeclaredResourceSet() {
        evidence(Set.of("ADMIN.SERVICE_CATALOG:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult denied = evaluate(
                "services", "services.management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.services.management.catalog.page",
                null, null, null, null, List.of());
        assertThat(denied.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        evidence(Set.of("ADMIN.SERVICE_CATALOG:VIEW"), List.of(role(
                "APP_CONTENT_EDITOR", "ADMIN.SERVICE_CATALOG", "RS_SERVICES")));
        ProductSurfaceAuthorityDtos.AuthorityResult wrongResponsibility = evaluate(
                "services", "services.management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.services.management.catalog.page",
                null, null, null, null, List.of());
        assertThat(wrongResponsibility.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        evidence(Set.of("ADMIN.SERVICE_CATALOG:VIEW"), List.of(role(
                "APP_CONFIG_ADMIN", "APP.OTHER", "RS_SERVICES")));
        ProductSurfaceAuthorityDtos.AuthorityResult wrongResource = evaluate(
                "services", "services.management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.services.management.catalog.page",
                null, null, null, null, List.of());
        assertThat(wrongResource.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        evidence(Set.of("ADMIN.SERVICE_CATALOG:VIEW"), List.of(role(
                "APP_CONFIG_ADMIN", "APP.EMPLOYEE_SERVICES", "RS_SERVICES")));
        ProductSurfaceAuthorityDtos.AuthorityResult allowed = evaluate(
                "services", "services.management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.services.management.catalog.page",
                null, null, null, null, List.of());
        assertThat(allowed.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(allowed.plane()).isEqualTo("management");
        assertThat(allowed.routeGrantRef()).startsWith("grant-");
        assertThat(allowed.effectiveGrants())
                .filteredOn(ProductSurfaceAuthorityDtos.CapabilityGrant.class::isInstance)
                .singleElement()
                .satisfies(value -> assertThat(
                        ((ProductSurfaceAuthorityDtos.CapabilityGrant) value)
                                .responsibility().resourceSetKey())
                        .isEqualTo("RS_SERVICES")
                        .doesNotStartWith("scope-"));
    }

    @Test
    void doesNotLetAChildCapabilityBypassItsProductEntitlement() {
        evidence(Set.of("ACTION.APPROVAL_TASK:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult denied = evaluate(
                "approvals", "approvals.work",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.approvals.work.inbox.page",
                null, null, null, null, List.of());
        assertThat(denied.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        evidence(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_TASK:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult allowed = evaluate(
                "approvals", "approvals.work",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.approvals.work.inbox.page",
                null, null, null, null, List.of());
        assertThat(allowed.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
    }

    @Test
    void hcmConfigurationScopeRequiresExactResponsibilityCodeAndSet() {
        evidence(Set.of("ACTION.WORKFORCE_REFERENCE:VIEW"), List.of(role(
                "APP_CONTENT_EDITOR", "ACTION.WORKFORCE_REFERENCE", "RS_HCM_CONFIG")));
        ProductSurfaceAuthorityDtos.AuthorityResult wrongResponsibility = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.management.reference.page",
                null, null, null, null, List.of());
        assertThat(wrongResponsibility.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        evidence(Set.of("ACTION.WORKFORCE_REFERENCE:VIEW"), List.of(role(
                "APP_CONFIG_ADMIN", "APP.HCM", "RS_HCM_CONFIG")));
        ProductSurfaceAuthorityDtos.AuthorityResult allowed = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.management.reference.page",
                null, null, null, null, List.of());
        assertThat(allowed.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
    }

    @Test
    void keepsSupportModeExclusiveAndReadOnly() {
        evidence(Set.of(), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult allowed = evaluate(
                "communications", "communications.management",
                ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                "route.communications.management.content.page",
                null, null, "support-1", "support-rev-1",
                List.of("TENANT_CONFIGURATION_READ"));
        assertThat(allowed.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(allowed.accessSource())
                .isEqualTo(ProductSurfaceAuthorityDtos.AccessSource.SUPPORT);
        assertThat(allowed.appResourceKey()).isEqualTo("APP.COMMUNICATIONS");
        assertThat(allowed.effectiveReadOnly()).isTrue();
        evidence(Set.of("ADMIN.COMMUNICATIONS:VIEW"), List.of(role(
                "APP_CONTENT_EDITOR", "ADMIN.COMMUNICATIONS", "RS_COMMUNICATIONS")));
        ProductSurfaceAuthorityDtos.AuthorityResult denied = evaluate(
                "communications", "communications.management",
                ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                "route.communications.management.content.page",
                null, null, null, null, List.of());
        assertThat(denied.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SUPPORT_SCOPE_DENIED);
    }

    @Test
    void supportEntryNeverUnionsNormalMutationAuthority() {
        evidence(Set.of(
                "ADMIN.COMMUNICATIONS:VIEW",
                "ADMIN.COMMUNICATIONS:CREATE",
                "ADMIN.COMMUNICATIONS:UPDATE"), List.of(role(
                "APP_CONFIG_ADMIN", "ADMIN.COMMUNICATIONS", "RS_COMMUNICATIONS")));
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                "communications", "communications.management",
                ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                null, null, null, "support-1", "support-rev-1",
                List.of("TENANT_CONFIGURATION_WRITE"));
        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.accessSource())
                .isEqualTo(ProductSurfaceAuthorityDtos.AccessSource.SUPPORT);
        assertThat(result.appResourceKey()).isEqualTo("APP.COMMUNICATIONS");
        assertThat(result.effectiveReadOnly()).isTrue();
        assertThat(result.scopes()).isNotEmpty()
                .allMatch(ProductSurfaceAuthorityDtos.EffectiveScope::readOnly);
        assertThat(result.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(ProductSurfaceAuthorityDtos.PolicyGrant.class, grant -> {
                    assertThat(grant.authorityMode())
                            .isEqualTo(ProductSurfaceAuthorityDtos.PolicyAuthorityMode.SUPPORT_SESSION);
                    assertThat(grant.readOnly()).isTrue();
                });
    }

    @Test
    void marksTeamAsWorkAndDefersRelationshipEvidenceToPeopleOwner() {
        evidence(Set.of("APP.HCM:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                "hcm", "hcm.team", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null, null, null, null, null, List.of());
        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.plane()).isEqualTo("work");
        assertThat(result.accessSource())
                .isEqualTo(ProductSurfaceAuthorityDtos.AccessSource.RELATIONSHIP);
        assertThat(result.requiresProductEligibility()).isTrue();
        assertThat(result.appResourceKey()).isEqualTo("APP.HCM");
        ProductSurfaceAuthorityDtos.AuthorityResult direct = evaluate(
                "hcm", "hcm.team", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.team.home.page", null, null, null, null, List.of());
        assertThat(direct.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(direct.appResourceKey()).isEqualTo("APP.HCM");
    }

    @Test
    void doesNotValidatePeopleOwnedScopeAgainstAuthPlaceholder() {
        evidence(Set.of("APP.HCM:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                "hcm", "hcm.team", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.team.home.page", null, "people-owned-scope",
                null, null, List.of());
        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.requiresProductEligibility()).isTrue();
    }

    @Test
    void explicitReviewScopeSelectsTheMatchingDualProfileWithoutUsingReviewId() throws IOException {
        useContract("product-surfaces-v1.bundle-v42.generated.json");
        evidence(Set.of(
                "APP.HCM:VIEW",
                "DATA.HRIS_PERFORMANCE_REVIEW:UPDATE"), List.of());
        String route = "route.hcm.performance.assigned-review.data";

        ProductSurfaceAuthorityDtos.AuthorityResult assigned = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, null, null, null, null, List.of());
        assertThat(assigned.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(assigned.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.capabilityContractKey())
                                .isEqualTo("hcm.team.performance.review"));
        assertThat(assigned.scopes()).singleElement()
                .extracting(ProductSurfaceAuthorityDtos.EffectiveScope::kind)
                .isEqualTo("TARGET_POPULATION");

        ProductSurfaceAuthorityDtos.EvaluateRequest selfRequest = request(
                "hcm", "hcm.operations", route);
        String selfScope = ProductAuthorizationAuthoritySupport.scopeKey(
                selfRequest, "SELF", "SELF");
        ProductSurfaceAuthorityDtos.AuthorityResult self = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, null, selfScope, null, null, List.of());
        assertThat(self.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(self.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.capabilityContractKey())
                                .isEqualTo("per.review.self.submit"));
        assertThat(self.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.key()).isEqualTo(selfScope);
            assertThat(scope.kind()).isEqualTo("SELF");
        });

        ProductSurfaceAuthorityDtos.AuthorityResult stale = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, null, "scope-stale", null, null, List.of());
        assertThat(stale.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
        assertThat(stale.routeGrantRef()).isNull();
    }

    @Test
    void requiresStepUpForActivationPolicyAndAllowsVerifiedElevatedPermission() {
        evidence(Set.of("ACTION.WORKFORCE_CONTROLLED_EXPORT:EXPORT"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult challenged = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.management.controlled-export-create.action",
                null, null, null, null, List.of());
        assertThat(challenged.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(challenged.requestPolicyRef()).isEqualTo("STEPUP-MGMT-CRITICAL-V1");
        ProductSurfaceAuthorityDtos.AuthorityResult elevated = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                "route.hcm.management.controlled-export-create.action",
                null, null, null, null, List.of());
        assertThat(elevated.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
    }

    @Test
    void doesNotRevealAHighRouteChallengeBeforeExactEligibilityAndStaticSod() {
        String route = "route.approvals.admin.workflow-publish.action";
        evidence(Set.of(), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult missingPermission = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, null, null, null, null, List.of());
        assertThat(missingPermission.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(missingPermission.requestPolicyRef()).isNull();
        evidence(Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH"), Set.of(), List.of(), List.of(
                duty("APPROVAL_DESIGN_PUBLISH", "ADMIN.APPROVAL_DESIGN",
                        "RS_APPROVALS", Map.of(
                                "approvals.design.publish",
                                "ADMIN.APPROVAL_DESIGN:PUBLISH"))));
        ProductSurfaceAuthorityDtos.AuthorityResult missingResponsibility = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, null, null, null, null, List.of());
        assertThat(missingResponsibility.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(missingResponsibility.requestPolicyRef()).isNull();
        List<AppGovernanceDtos.ResourceRole> scope = List.of(role(
                "APP_CONFIG_ADMIN", "APP.APPROVALS", "RS_APPROVALS"));
        evidence(Set.of(
                "ADMIN.APPROVAL_DESIGN:PUBLISH",
                "ADMIN.APPROVAL_DESIGN:UPDATE"), Set.of(), scope, List.of(
                duty("APPROVAL_DESIGN_PUBLISH", "ADMIN.APPROVAL_DESIGN",
                        "RS_APPROVALS", Map.of("approvals.design.publish",
                                "ADMIN.APPROVAL_DESIGN:PUBLISH")),
                duty("APPROVAL_DESIGN_DRAFT", "ADMIN.APPROVAL_DESIGN",
                        "RS_APPROVALS", Map.of("approvals.design.update",
                                "ADMIN.APPROVAL_DESIGN:UPDATE"))));
        ProductSurfaceAuthorityDtos.AuthorityResult sodConflict = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, null, null, null, null, List.of());
        assertThat(sodConflict.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SOD_CONFLICT);
        assertThat(sodConflict.reasonCode()).isEqualTo("SOD_CONFLICT");
        assertThat(sodConflict.requestPolicyRef()).isNull();
        evidence(Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH"), Set.of(), scope, List.of(
                duty("APPROVAL_DESIGN_PUBLISH", "ADMIN.APPROVAL_DESIGN",
                        "RS_APPROVALS", Map.of("approvals.design.publish",
                                "ADMIN.APPROVAL_DESIGN:PUBLISH"))));
        ProductSurfaceAuthorityDtos.AuthorityResult eligible = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, null, null, null, null, List.of());
        assertThat(eligible.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(eligible.requestPolicyRef()).isEqualTo("STEPUP-MGMT-HIGH-V1");
    }

    @Test
    void allowsAssignedPublishReviewRejectionWithoutActivatingActualPublish() throws IOException {
        useContract("product-surfaces-v1.bundle-v14.generated.json");
        List<AppGovernanceDtos.ResourceRole> scope = List.of(role(
                "APP_CONFIG_ADMIN", "APP.APPROVALS", "RS_APPROVALS"));
        evidence(Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH"), Set.of(), scope, List.of(
                duty("APPROVAL_DESIGN_PUBLISH", "ADMIN.APPROVAL_DESIGN",
                        "RS_APPROVALS", Map.of("approvals.design.publish",
                                "ADMIN.APPROVAL_DESIGN:PUBLISH"))));
        ProductSurfaceAuthorityDtos.AuthorityResult rejection = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.approvals.admin.form-publish-review-reject.action",
                null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult publication = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.approvals.admin.form-reviewed-publish.action",
                null, null, null, null, List.of());
        assertThat(rejection.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(rejection.requestPolicyRef()).isNull();
        assertThat(rejection.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.activationState())
                                .isEqualTo(ProductSurfaceAuthorityDtos.ActivationState.ACTIVE));
        assertThat(publication.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(publication.requestPolicyRef()).isEqualTo("STEPUP-MGMT-HIGH-V1");
    }

    @Test
    void globalApprovalPermissionAndConfigResponsibilityCannotReplaceScopedDuty() {
        evidence(Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH"), Set.of(
                "APPROVAL_PUBLISHER"), List.of(role(
                        "APP_CONFIG_ADMIN", "APP.APPROVALS", "RS_APPROVALS")),
                List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.approvals.admin.workflow-publish.action",
                null, null, null, null, List.of());
        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(result.requestPolicyRef()).isNull();
    }

    @Test
    void scopedDutyAuthorityMustMatchTheCanonicalResolvedCapabilityExactly() {
        evidence(Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH"), Set.of(), List.of(role(
                "APP_CONFIG_ADMIN", "APP.APPROVALS", "RS_APPROVALS")), List.of(
                duty("APPROVAL_DESIGN_PUBLISH", "ADMIN.APPROVAL_DESIGN",
                        "RS_APPROVALS", Map.of(
                                "approvals.design.publish",
                                "ADMIN.APPROVAL_DESIGN:UPDATE"))));
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.approvals.admin.workflow-publish.action",
                null, null, null, null, List.of());
        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(result.requestPolicyRef()).isNull();
    }

    @Test
    void scopedAuditDutyIsAnExplicitAuthorityWithoutConfigOrGlobalAuditorRole() {
        evidence(Set.of("ADMIN.APPROVAL_OPERATIONS:VIEW"), Set.of(), List.of(), List.of(
                duty("APPROVAL_OPERATIONS_AUDIT", "ADMIN.APPROVAL_OPERATIONS",
                        "RS_APPROVALS", Map.of(
                                "approvals.audit.operations.read",
                                "ADMIN.APPROVAL_OPERATIONS:VIEW"))));
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.approvals.admin.operations.page",
                null, null, null, null, List.of());
        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.effectiveGrants()).singleElement()
                .isInstanceOf(ProductSurfaceAuthorityDtos.CapabilityGrant.class);
    }

    @Test
    void rejectsApprovalOperationsExecutionForAuditorInTheSameResourceSet() {
        String route = "route.approvals.admin.operations.retry.action";
        List<AppGovernanceDtos.ResourceRole> exactScope = List.of(role(
                "APP_CONFIG_ADMIN", "APP.APPROVALS", "RS_APPROVALS"));
        evidence(Set.of("ADMIN.APPROVAL_OPERATIONS:EXECUTE"), Set.of(), exactScope, List.of(
                duty("APPROVAL_OPERATIONS_EXECUTE", "ADMIN.APPROVAL_OPERATIONS",
                        "RS_APPROVALS", Map.of("approvals.operations.execute",
                                "ADMIN.APPROVAL_OPERATIONS:EXECUTE")),
                duty("APPROVAL_OPERATIONS_AUDIT", "ADMIN.APPROVAL_OPERATIONS",
                        "RS_APPROVALS", Map.of("approvals.audit.operations.read",
                                "ADMIN.APPROVAL_OPERATIONS:VIEW"))));
        ProductSurfaceAuthorityDtos.AuthorityResult conflict = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, null, null, null, null, List.of());
        assertThat(conflict.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SOD_CONFLICT);
        assertThat(conflict.requestPolicyRef()).isNull();
        evidence(Set.of("ADMIN.APPROVAL_OPERATIONS:EXECUTE"), Set.of(), exactScope, List.of(
                duty("APPROVAL_OPERATIONS_EXECUTE", "ADMIN.APPROVAL_OPERATIONS",
                        "RS_APPROVALS", Map.of("approvals.operations.execute",
                                "ADMIN.APPROVAL_OPERATIONS:EXECUTE")),
                duty("APPROVAL_OPERATIONS_AUDIT", "ADMIN.APPROVAL_OPERATIONS",
                        "RS_OTHER", "APP.OTHER", Map.of("approvals.audit.operations.read",
                                "ADMIN.APPROVAL_OPERATIONS:VIEW"))));
        ProductSurfaceAuthorityDtos.AuthorityResult nonOverlapping = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, null, null, null, null, List.of());
        assertThat(nonOverlapping.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
    }

    @Test
    void dynamicDisjointApprovalScopesAllowButSharedChildScopeConflicts() {
        var draft = dutyWithMembers(
                "APPROVAL_DESIGN_DRAFT", "ADMIN.APPROVAL_DESIGN", "RS_DESIGN_A",
                Map.of("approvals.design.update", "ADMIN.APPROVAL_DESIGN:UPDATE"),
                "APP.APPROVALS", "ADMIN.WORKFLOW_A");
        var publishDisjoint = dutyWithMembers(
                "APPROVAL_DESIGN_PUBLISH", "ADMIN.APPROVAL_DESIGN", "RS_DESIGN_B",
                Map.of("approvals.design.publish", "ADMIN.APPROVAL_DESIGN:PUBLISH"),
                "APP.APPROVALS", "ADMIN.WORKFLOW_B");
        List<AppGovernanceDtos.ResourceRole> responsibilities = List.of(
                role("APP_CONFIG_ADMIN", "APP.APPROVALS", "RS_DESIGN_A"),
                role("APP_CONFIG_ADMIN", "APP.APPROVALS", "RS_DESIGN_B"));
        evidence(Set.of(
                "ADMIN.APPROVAL_DESIGN:UPDATE",
                "ADMIN.APPROVAL_DESIGN:PUBLISH"), Set.of(), responsibilities,
                List.of(draft, publishDisjoint));
        ProductSurfaceAuthorityDtos.AuthorityResult disjoint = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.approvals.admin.workflow-publish.action",
                null, null, null, null, List.of());
        assertThat(disjoint.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(disjoint.scopes()).singleElement().satisfies(scope ->
                assertThat(scope.kind()).isEqualTo("RESOURCE_SET"));
        var publishPartial = dutyWithMembers(
                "APPROVAL_DESIGN_PUBLISH", "ADMIN.APPROVAL_DESIGN", "RS_DESIGN_B",
                Map.of("approvals.design.publish", "ADMIN.APPROVAL_DESIGN:PUBLISH"),
                "APP.APPROVALS", "ADMIN.WORKFLOW_A");
        evidence(Set.of(
                "ADMIN.APPROVAL_DESIGN:UPDATE",
                "ADMIN.APPROVAL_DESIGN:PUBLISH"), Set.of(), responsibilities,
                List.of(draft, publishPartial));
        ProductSurfaceAuthorityDtos.AuthorityResult partial = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.approvals.admin.workflow-publish.action",
                null, null, null, null, List.of());
        assertThat(partial.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SOD_CONFLICT);
    }

    @Test
    void aggregatesEveryExactApprovalGrantIntoTheAllowedSurfaceContext() {
        evidence(Set.of(
                "APP.APPROVALS:VIEW",
                "ACTION.APPROVAL_TASK:VIEW",
                "ACTION.APPROVAL_TASK:UPDATE",
                "ACTION.APPROVAL_TASK:APPROVE",
                "ACTION.APPROVAL_REQUEST:VIEW",
                "ACTION.APPROVAL_REQUEST:CREATE",
                "ACTION.APPROVAL_REQUEST:UPDATE",
                "ACTION.APPROVAL_DELEGATION:VIEW",
                "ACTION.APPROVAL_DELEGATION:MANAGE"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                "approvals", "approvals.work", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null, null, null, null, null, List.of());
        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.effectiveGrants().stream()
                .filter(ProductSurfaceAuthorityDtos.CapabilityGrant.class::isInstance)
                .map(ProductSurfaceAuthorityDtos.CapabilityGrant.class::cast)
                .map(ProductSurfaceAuthorityDtos.CapabilityGrant::capabilityContractKey))
                .containsExactlyInAnyOrder(
                        "approvals.work.task.read",
                        "approvals.work.task.update",
                        "approvals.work.task.approve",
                        "approvals.work.request.read",
                        "approvals.work.request.create",
                        "approvals.work.request.update",
                        "approvals.work.delegation.read",
                        "approvals.work.delegation.manage");
        assertThat(result.effectiveReadOnly()).isFalse();
        assertThat(result.scopes()).anyMatch(scope -> !scope.readOnly());
    }

    @Test
    void permissionRichPilotWorkEntriesRemainValidWhenTheyAggregateMultipleScopes() {
        evidence(Set.of(
                "APP.APPROVALS:VIEW",
                "ACTION.APPROVAL_TASK:VIEW",
                "ACTION.APPROVAL_TASK:UPDATE",
                "ACTION.APPROVAL_TASK:APPROVE",
                "ACTION.APPROVAL_REQUEST:VIEW",
                "ACTION.APPROVAL_REQUEST:CREATE",
                "ACTION.APPROVAL_REQUEST:UPDATE",
                "ACTION.APPROVAL_DELEGATION:VIEW",
                "ACTION.APPROVAL_DELEGATION:MANAGE",
                "APP.HCM:VIEW",
                "DATA.HR_ABSENCE:VIEW",
                "DATA.HR_ABSENCE:APPROVE",
                "DATA.HR_TIME:VIEW",
                "DATA.HR_TIME:APPROVE"), List.of());
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceAuthorityPort> ports =
                org.mockito.Mockito.mock(ObjectProvider.class);
        when(ports.orderedStream()).thenAnswer(ignored -> Stream.of(adapter));
        ProductSurfaceAuthorityService service = new ProductSurfaceAuthorityService(ports);
        ProductSurfaceAuthorityDtos.AuthorityResult approvals = service.evaluate(
                request("approvals", "approvals.work"));
        ProductSurfaceAuthorityDtos.AuthorityResult team = service.evaluate(
                request("hcm", "hcm.team"));
        ProductSurfaceAuthorityDtos.AuthorityResult approvalInbox = service.evaluate(
                request("approvals", "approvals.work",
                        "route.approvals.work.inbox.page"));
        assertThat(approvals.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(team.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(approvals.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("SELF");
            assertThat(scope.isDefault()).isTrue();
        });
        assertThat(team.scopes()).hasSizeGreaterThan(1)
                .noneMatch(ProductSurfaceAuthorityDtos.EffectiveScope::isDefault);
        assertThat(approvalInbox.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(approvalInbox.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("SELF");
            assertThat(scope.isDefault()).isTrue();
        });
    }

    @Test
    void exposesEligibleHighGrantWithoutMakingItsScopeMutableUntilElevated() {
        List<AppGovernanceDtos.ResourceRole> scope = List.of(role(
                "APP_CONFIG_ADMIN", "APP.APPROVALS", "RS_APPROVALS"));
        evidence(Set.of(
                "ADMIN.APPROVAL_DESIGN:VIEW",
                "ADMIN.APPROVAL_DESIGN:PUBLISH"), Set.of(), scope, List.of(
                duty("APPROVAL_DESIGN_PUBLISH", "ADMIN.APPROVAL_DESIGN",
                        "RS_APPROVALS", Map.of(
                                "approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW",
                                "approvals.design.publish",
                                "ADMIN.APPROVAL_DESIGN:PUBLISH"))));
        ProductSurfaceAuthorityDtos.AuthorityResult normal = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null, null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.CapabilityGrant eligible = normal.effectiveGrants().stream()
                .filter(ProductSurfaceAuthorityDtos.CapabilityGrant.class::isInstance)
                .map(ProductSurfaceAuthorityDtos.CapabilityGrant.class::cast)
                .filter(grant -> grant.capabilityContractKey()
                        .equals("approvals.design.publish"))
                .findFirst().orElseThrow();
        assertThat(normal.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(eligible.activationState())
                .isEqualTo(ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE);
        assertThat(normal.scopes()).allMatch(ProductSurfaceAuthorityDtos.EffectiveScope::readOnly);

        ProductSurfaceAuthorityDtos.AuthorityResult elevated = evaluate(
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                null, null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.CapabilityGrant active = elevated.effectiveGrants().stream()
                .filter(ProductSurfaceAuthorityDtos.CapabilityGrant.class::isInstance)
                .map(ProductSurfaceAuthorityDtos.CapabilityGrant.class::cast)
                .filter(grant -> grant.capabilityContractKey()
                        .equals("approvals.design.publish"))
                .findFirst().orElseThrow();
        assertThat(active.activationState())
                .isEqualTo(ProductSurfaceAuthorityDtos.ActivationState.ACTIVE);
        assertThat(elevated.scopes()).anyMatch(value -> !value.readOnly());
    }

    @Test
    void failsClosedForUnknownRouteAndStaleContext() {
        evidence(Set.of("APP.EMPLOYEE_SERVICES:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult unknown = evaluate(
                "services", "services.work", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.services.work.not-registered.page",
                null, null, null, null, List.of());
        assertThat(unknown.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(unknown.reasonCode()).isEqualTo("ROUTE_NOT_REGISTERED");

        ProductSurfaceAuthorityDtos.AuthorityResult stale = evaluate(
                "services", "services.work", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null, "stale-context", null, null, null, List.of());
        assertThat(stale.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
    }

    @Test
    void distinguishesActivePointerProductAbsenceFromParticipatingContractDrift() {
        evidence(Set.of(), List.of());

        ProductSurfaceAuthorityDtos.AuthorityResult absentEntry = evaluate(
                "calendar", "calendar.work", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null, null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult absentRoute = evaluate(
                "calendar", "calendar.work", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.calendar.work.home.page",
                null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult driftedSurface = evaluate(
                "services", "services.future", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null, null, null, null, null, List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult driftedRoute = evaluate(
                "services", "services.work", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.services.work.not-registered.page",
                null, null, null, null, List.of());

        assertThat(absentEntry.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED);
        assertThat(absentEntry.reasonCode()).isEqualTo("PRODUCT_NOT_REGISTERED");
        assertThat(absentRoute.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(absentRoute.reasonCode()).isEqualTo("PRODUCT_NOT_REGISTERED");
        assertThat(driftedSurface.reasonCode()).isEqualTo("SURFACE_NOT_REGISTERED");
        assertThat(driftedRoute.reasonCode()).isEqualTo("ROUTE_NOT_REGISTERED");
    }

    @Test
    void hcmAuditEvidenceNormalIsChallengeOnlyAndElevatedIsTheOnlyAllowedMode()
            throws Exception {
        useContract("product-surfaces-v1.bundle-v42.generated.json");
        AppGovernanceDtos.ResourceRole duty = role(
                "HRIS_DUTY_AUDITOR", "APP.HCM", "RS_HCM_CONFIG");
        evidence(
                Set.of("APP.HCM:VIEW", "ADMIN.AUDIT_VIEW:VIEW"),
                Set.of("HRIS_AUDITOR"), List.of(duty));

        ProductSurfaceAuthorityDtos.AuthorityResult challenge = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.audit.evidence.page",
                null, null, null, null, List.of());
        assertThat(challenge.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(challenge.routeGrantRef()).isNull();
        assertThat(challenge.requiredAssurance()).isEqualTo("urn:dwp:acr:mfa");
        assertThat(challenge.effectiveReadOnly()).isTrue();

        ProductSurfaceAuthorityDtos.AuthorityResult allowed = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                "route.hcm.audit.evidence.page",
                null, null, null, null, List.of());
        assertThat(allowed.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(allowed.routeGrantRef()).startsWith("grant-");
        assertThat(allowed.appResourceKey()).isEqualTo("APP.HCM");
        assertThat(allowed.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("RESOURCE_SET");
            assertThat(scope.readOnly()).isTrue();
        });

        evidence(
                Set.of("ADMIN.AUDIT_VIEW:VIEW"),
                Set.of("HRIS_AUDITOR"), List.of(duty));
        ProductSurfaceAuthorityDtos.AuthorityResult unqualified = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.audit.evidence.page",
                null, null, null, null, List.of());
        assertThat(unqualified.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(unqualified.decision())
                .isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);

        evidence(
                Set.of("APP.HCM:VIEW", "ADMIN.AUDIT_VIEW:VIEW"),
                Set.of("HRIS_AUDITOR", "HRIS_CONFIG_AUTHOR"), List.of(duty));
        ProductSurfaceAuthorityDtos.AuthorityResult normalConflict = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.audit.evidence-detail.data",
                null, null, null, null, List.of());
        assertThat(normalConflict.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SOD_CONFLICT);
        assertThat(normalConflict.decision())
                .isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        ProductSurfaceAuthorityDtos.AuthorityResult conflicted = evaluate(
                "hcm", "hcm.management", ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                "route.hcm.audit.evidence-detail.data",
                null, null, null, null, List.of());
        assertThat(conflicted.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SOD_CONFLICT);
        assertThat(conflicted.routeGrantRef()).isNull();
    }

    @Test
    void payrollSupportReadsChallengeNormalAndEvaluateElevatedAgainstExactAuthority()
            throws Exception {
        useContract("product-surfaces-v1.bundle-v49.generated.json");
        evidence(Set.of("DATA.HRIS_PAYROLL_STATUS:VIEW"), List.of());

        for (String route : PayrollV49ElevatedAuthorityPolicy.LEGACY_READ_ROUTES) {
            ProductSurfaceAuthorityDtos.AuthorityResult challenge = evaluate(
                    "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                    route, null, null, null, null, List.of());
            assertThat(challenge.decision()).as(route)
                    .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
            assertThat(challenge.requiredAssurance()).as(route)
                    .isEqualTo("urn:dwp:acr:mfa");
            assertThat(challenge.effectiveReadOnly()).as(route).isTrue();
            assertThat(challenge.scopes()).as(route).singleElement().satisfies(scope -> {
                assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
                assertThat(scope.readOnly()).isTrue();
            });

            ProductSurfaceAuthorityDtos.AuthorityResult elevated = evaluate(
                    "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                    route, null, null, null, null, List.of());
            assertThat(elevated.decision()).as(route)
                    .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            assertThat(elevated.routeGrantRef()).as(route).startsWith("grant-");
            assertThat(elevated.effectiveGrants()).as(route).singleElement()
                    .isInstanceOfSatisfying(
                            ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                            grant -> {
                                assertThat(grant.capabilityContractKey())
                                        .isEqualTo(PayrollV49ElevatedAuthorityPolicy
                                                .SUPPORT_CAPABILITY);
                                assertThat(grant.predicatePolicyKeys())
                                        .containsExactly(
                                                PayrollV49ElevatedAuthorityPolicy
                                                        .POPULATION_PREDICATE);
                                assertThat(grant.activationState())
                                        .isEqualTo(ProductSurfaceAuthorityDtos.ActivationState
                                                .ACTIVE);
                                assertThat(grant.readOnly()).isTrue();
                            });
        }

        evidence(Set.of("ACTION.HRIS_PAYROLL_RUN:EXECUTE"), List.of());
        for (String route : PayrollV49ElevatedAuthorityPolicy.COMMAND_ROUTES) {
            ProductSurfaceAuthorityDtos.AuthorityResult challenge = evaluate(
                    "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                    route, null, null, null, null, List.of());
            assertThat(challenge.decision()).as(route)
                    .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
            assertThat(challenge.requiredAssurance()).as(route)
                    .isEqualTo("urn:dwp:acr:mfa");
            assertThat(challenge.effectiveReadOnly()).as(route).isFalse();
            assertThat(challenge.effectiveGrants()).as(route).singleElement()
                    .isInstanceOfSatisfying(
                            ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                            grant -> assertThat(grant.capabilityContractKey())
                                    .isEqualTo(PayrollV49ElevatedAuthorityPolicy
                                            .RUN_CAPABILITY));

            ProductSurfaceAuthorityDtos.AuthorityResult elevated = evaluate(
                    "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                    route, null, null, null, null, List.of());
            assertThat(elevated.decision()).as(route)
                    .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        }

        evidence(Set.of("DATA.HRIS_PAYROLL_STATUS:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult expressionChallenge = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.payroll-run.data",
                null, null, null, null, List.of());
        assertThat(expressionChallenge.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(expressionChallenge.requestPolicyRef())
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
        ProductSurfaceAuthorityDtos.AuthorityResult expressionElevated = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                "route.hcm.operations.payroll-run.data",
                null, null, null, null, List.of());
        assertThat(expressionElevated.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);

        evidence(Set.of(), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult unqualified = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.payroll-support.data",
                null, null, null, null, List.of());
        assertThat(unqualified.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED)
                .isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);

        evidence(Set.of("DATA.HRIS_PAYROLL_STATUS:VIEW"), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult stale = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.payroll-support.data",
                "psc-client-forged", null, null, null, List.of());
        assertThat(stale.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
    }

    @Test
    void v54StatutoryResultReadsChallengeNormalAndAllowOnlyExactElevatedAuthority()
            throws Exception {
        useContract("product-surfaces-v1.bundle-v54.generated.json");
        evidence(Set.of("DATA.HRIS_PAYROLL_STATUS:VIEW"), List.of());

        for (String route : PayrollV49ElevatedAuthorityPolicy.STATUTORY_READ_ROUTES) {
            ProductSurfaceAuthorityDtos.AuthorityResult challenge = evaluate(
                    "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                    route, null, null, null, null, List.of());
            assertThat(challenge.decision()).as(route)
                    .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
            assertThat(challenge.requiredAssurance()).as(route)
                    .isEqualTo("urn:dwp:acr:mfa");
            assertThat(challenge.scopes()).as(route).singleElement().satisfies(scope -> {
                assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
                assertThat(scope.readOnly()).isTrue();
            });

            ProductSurfaceAuthorityDtos.AuthorityResult elevated = evaluate(
                    "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                    route, null, null, null, null, List.of());
            assertThat(elevated.decision()).as(route)
                    .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
            assertThat(elevated.effectiveGrants()).as(route).singleElement()
                    .isInstanceOfSatisfying(
                            ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                            grant -> {
                                assertThat(grant.capabilityContractKey())
                                        .isEqualTo(PayrollV49ElevatedAuthorityPolicy
                                                .SUPPORT_CAPABILITY);
                                assertThat(grant.predicatePolicyKeys())
                                        .containsExactly(PayrollV49ElevatedAuthorityPolicy
                                                .POPULATION_PREDICATE);
                                assertThat(grant.activationState()).isEqualTo(
                                        ProductSurfaceAuthorityDtos.ActivationState.ACTIVE);
                            });
        }

        evidence(Set.of(), List.of());
        ProductSurfaceAuthorityDtos.AuthorityResult unqualified = evaluate(
                "hcm", "hcm.operations", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.pay-tax-social-insurance.data",
                null, null, null, null, List.of());
        assertThat(unqualified.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
    }

}
