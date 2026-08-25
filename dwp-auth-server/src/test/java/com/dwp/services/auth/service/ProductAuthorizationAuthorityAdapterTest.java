package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationAuthorityAdapterTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-21T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProductAuthorizationContractRepository repository;

    @Mock
    private ProductAuthorizationIdentityEvidenceService evidenceService;

    private ProductAuthorizationAuthorityAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        ProductAuthorizationContractDtos.BundleContract contract = new ObjectMapper()
                .findAndRegisterModules()
                .readValue(
                        getClass().getResourceAsStream(
                                "/product-authorization/"
                                        + "product-surfaces-v1.bundle-v3.generated.json"),
                        ProductAuthorizationContractDtos.BundleContract.class);
        UUID bundleId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        ProductAuthorizationContractRepository.StoredBundle stored =
                new ProductAuthorizationContractRepository.StoredBundle(
                        bundleId,
                        contract.bundleKey(),
                        contract.version(),
                        "ACTIVE",
                        contract.schemaVersion(),
                        contract.checksumAlgorithm(),
                        contract.checksum(),
                        contract.owner(),
                        "security-reviewer",
                        now,
                        now,
                        now);
        when(repository.findActive("product-surfaces")).thenReturn(Optional.of(stored));
        when(repository.loadContract(stored)).thenReturn(contract);
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", bundleId, contract.version(), "release", now)));
        adapter = new ProductAuthorizationAuthorityAdapter(repository, evidenceService, CLOCK);
    }

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

        assertThat(List.of(teamEntry, teamTime, operationsEntry, operationsTime))
                .allSatisfy(result -> {
                    assertThat(result.decision())
                            .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
                    assertThat(result.scopes()).isNotEmpty()
                            .extracting(ProductSurfaceAuthorityDtos.EffectiveScope::kind)
                            .containsOnly("TARGET_POPULATION");
                });

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

        ProductSurfaceAuthorityDtos.AuthorityResult stale = evaluate(
                "services", "services.work", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                null, "stale-context", null, null, null, List.of());
        assertThat(stale.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
    }

    private void evidence(
            Set<String> permissions,
            List<AppGovernanceDtos.ResourceRole> responsibilities) {
        evidence(permissions, Set.of(), responsibilities);
    }

    private void assertTargetPopulationCapabilityScope(
            ProductSurfaceAuthorityDtos.AuthorityResult result,
            String capabilityContractKey) {
        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
            assertThat(scope.isDefault()).isTrue();
        });
        String scopeKey = result.scopes().getFirst().key();
        assertThat(result.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> {
                            assertThat(grant.capabilityContractKey())
                                    .isEqualTo(capabilityContractKey);
                            assertThat(grant.responsibility()).isNull();
                            assertThat(grant.scopeKeys()).containsExactly(scopeKey);
                        });
    }

    private void assertSelfScopedWorkRoute(
            ProductSurfaceAuthorityDtos.AuthorityResult result,
            String selfScopeKey) {
        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.key()).isEqualTo(selfScopeKey);
            assertThat(scope.kind()).isEqualTo("SELF");
            assertThat(scope.isDefault()).isTrue();
        });
        assertThat(result.effectiveGrants()).isNotEmpty()
                .allSatisfy(grant -> assertThat(grant.scopeKeys())
                        .containsExactly(selfScopeKey));
        assertThat(result.effectiveGrants())
                .filteredOn(ProductSurfaceAuthorityDtos.CapabilityGrant.class::isInstance)
                .map(ProductSurfaceAuthorityDtos.CapabilityGrant.class::cast)
                .allSatisfy(grant -> assertThat(grant.responsibility()).isNull());
    }

    private void evidence(
            Set<String> permissions,
            Set<String> roles,
            List<AppGovernanceDtos.ResourceRole> responsibilities) {
        evidence(permissions, roles, responsibilities, List.of());
    }

    private void evidence(
            Set<String> permissions,
            Set<String> roles,
            List<AppGovernanceDtos.ResourceRole> responsibilities,
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties) {
        when(evidenceService.load(10L, 20L)).thenReturn(
                new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                        permissions, roles, responsibilities, duties,
                        "auth-test-revision"));
    }

    private AppGovernanceDtos.ResourceRole role(
            String responsibility,
            String resourceKey,
            String resourceSetKey) {
        return new AppGovernanceDtos.ResourceRole(
                responsibility, "APP", resourceKey, scopeId(resourceSetKey),
                resourceSetKey, null);
    }

    private ScopedAdminDutyEvidenceService.EffectiveDuty duty(
            String code,
            String resourceKey,
            String resourceSetKey,
            Map<String, String> capabilities) {
        return duty(code, resourceKey, resourceSetKey, "APP.APPROVALS", capabilities);
    }

    private ScopedAdminDutyEvidenceService.EffectiveDuty duty(
            String code,
            String resourceKey,
            String resourceSetKey,
            String productResourceKey,
            Map<String, String> capabilities) {
        return dutyWithMembers(code, resourceKey, resourceSetKey, capabilities,
                productResourceKey);
    }

    private ScopedAdminDutyEvidenceService.EffectiveDuty dutyWithMembers(
            String code,
            String resourceKey,
            String resourceSetKey,
            Map<String, String> capabilities,
            String... memberResourceKeys) {
        String productResourceKey = memberResourceKeys[0];
        Set<String> conflicts = switch (code) {
            case "APPROVAL_DESIGN_DRAFT" -> Set.of("APPROVAL_DESIGN_PUBLISH");
            case "APPROVAL_DESIGN_PUBLISH" -> Set.of("APPROVAL_DESIGN_DRAFT");
            case "APPROVAL_OPERATIONS_EXECUTE" -> Set.of("APPROVAL_OPERATIONS_AUDIT");
            case "APPROVAL_OPERATIONS_AUDIT" -> Set.of("APPROVAL_OPERATIONS_EXECUTE");
            default -> Set.of();
        };
        return new ScopedAdminDutyEvidenceService.EffectiveDuty(
                10L, 20L, UUID.randomUUID(), code, "approvals", "LEGACY",
                productResourceKey, resourceKey,
                code.endsWith("_AUDIT"), scopeId(resourceSetKey), resourceSetKey,
                capabilities, conflicts,
                java.util.Arrays.stream(memberResourceKeys)
                        .map(member -> new ScopedAdminDutyEvidenceService.ResourceMember(
                                member.substring(0, member.indexOf('.')), member))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                null, "MANUAL", "USER", "20", "duty-test-revision-" + code);
    }

    private UUID scopeId(String resourceSetKey) {
        return UUID.nameUUIDFromBytes(resourceSetKey.getBytes(StandardCharsets.UTF_8));
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            String product,
            String surface,
            ProductSurfaceAuthorityDtos.AccessMode mode,
            String route,
            String context,
            String scope,
            String supportSession,
            String supportRevision,
            List<String> supportScopes) {
        return adapter.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                10L, 20L, product, surface, mode, route, context, scope,
                supportSession, supportRevision, supportScopes));
    }

    private ProductSurfaceAuthorityDtos.EvaluateRequest request(
            String product,
            String surface) {
        return request(product, surface, null);
    }

    private ProductSurfaceAuthorityDtos.EvaluateRequest request(
            String product,
            String surface,
            String route) {
        return request(product, surface, route, null, null);
    }

    private ProductSurfaceAuthorityDtos.EvaluateRequest request(
            String product,
            String surface,
            String route,
            String contextKey,
            String contextScopeKey) {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(
                10L, 20L, product, surface,
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, contextKey, contextScopeKey, null, null, List.of());
    }
}
