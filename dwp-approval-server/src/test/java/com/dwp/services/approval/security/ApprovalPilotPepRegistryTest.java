package com.dwp.services.approval.security;

import com.dwp.core.security.ScopedAuthorityToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPilotPepRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void loadsTheW1aV2ProjectionAndExactBindingClosure() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);

        assertThat(registry.bindingContracts()).hasSize(47);
        assertThat(registry.bindingContracts())
                .extracting(ApprovalPilotPepRegistry.BindingContract::routeContractKey)
                .contains("route.approvals.work.home.page",
                        "route.approvals.admin.workflow-publish.action",
                        "route.approvals.admin.forms-workflow-reference.data");
    }

    @Test
    void permitsWorkEntitlementButDoesNotInferManagement() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);

        ApprovalPilotPepRegistry.Decision work = registry.authorize(evidence(
                "GET", "/v1/home", Set.of("APP.APPROVALS:VIEW"), "", Set.of(), null));
        ApprovalPilotPepRegistry.Decision management = registry.authorize(evidence(
                "POST", "/v1/admin/workflows", Set.of("APP.APPROVALS:VIEW"),
                "", Set.of(), null));

        assertThat(work.allowed()).isTrue();
        assertThat(management.allowed()).isFalse();
    }

    @Test
    void requiresExactCapabilityAndAppResponsibilityWithoutManageFallback() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);

        ApprovalPilotPepRegistry.Decision exact = registry.authorize(evidence(
                "POST", "/v1/admin/workflows",
                Set.of("ADMIN.APPROVAL_DESIGN:CREATE"),
                scoped("approvals.design.create", "ADMIN.APPROVAL_DESIGN:CREATE"),
                Set.of(), null));
        ApprovalPilotPepRegistry.Decision manage = registry.authorize(evidence(
                "POST", "/v1/admin/workflows",
                Set.of("ADMIN.APPROVAL_DESIGN:MANAGE"),
                "APP_CONFIG_ADMIN@RS_APPROVALS", Set.of(), null));
        ApprovalPilotPepRegistry.Decision unscoped = registry.authorize(evidence(
                "POST", "/v1/admin/workflows",
                Set.of("ADMIN.APPROVAL_DESIGN:CREATE"),
                "", Set.of(), null));
        ApprovalPilotPepRegistry.Decision wrongResponsibility = registry.authorize(evidence(
                "POST", "/v1/admin/workflows",
                Set.of("ADMIN.APPROVAL_DESIGN:CREATE"),
                "APP_OWNER@RS_APPROVALS," + ScopedAuthorityToken.wireToken(
                        "approvals.design.create", "ADMIN.APPROVAL_DESIGN:CREATE",
                        "RS_APPROVALS"), Set.of(), null));

        assertThat(exact.allowed()).isTrue();
        assertThat(manage.allowed()).isFalse();
        assertThat(unscoped.allowed()).isFalse();
        assertThat(wrongResponsibility.allowed()).isFalse();
    }

    @Test
    void rejectsStaticMakerPublisherAssignmentConflictOnTheSameScope() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);
        String workflow = "/v1/admin/workflows/14d7b229-4752-4a50-8ac1-ecc129620649/publish";

        ApprovalPilotPepRegistry.Decision publisher = registry.authorize(evidence(
                "POST", workflow,
                Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH"),
                scoped("approvals.design.publish", "ADMIN.APPROVAL_DESIGN:PUBLISH"),
                Set.of(), null));
        ApprovalPilotPepRegistry.Decision conflict = registry.authorize(evidence(
                "POST", workflow,
                Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH", "ADMIN.APPROVAL_DESIGN:UPDATE"),
                scoped("approvals.design.publish", "ADMIN.APPROVAL_DESIGN:PUBLISH")
                        + ',' + ScopedAuthorityToken.wireToken(
                                "approvals.design.update", "ADMIN.APPROVAL_DESIGN:UPDATE",
                                "RS_APPROVALS"), Set.of(), null));

        assertThat(publisher.allowed()).isTrue();
        assertThat(publisher.authorities()).singleElement().satisfies(authority -> {
            assertThat(authority.highRisk()).isTrue();
            assertThat(authority.activationPolicy()).isEqualTo("STEPUP-MGMT-HIGH-V1");
            assertThat(authority.sodPolicyId()).isEqualTo("SOD-APR-DESIGN-PUBLISH-V1");
        });
        assertThat(conflict.allowed()).isFalse();
    }

    @Test
    void allowsMakerAndPublisherAuthoritiesWhenTheirDynamicSetsAreDisjoint() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);
        String workflow = "/v1/admin/workflows/14d7b229-4752-4a50-8ac1-ecc129620649/publish";
        String roles = "APP_CONFIG_ADMIN@RS_DESIGN_A,APP_CONFIG_ADMIN@RS_DESIGN_B,"
                + ScopedAuthorityToken.wireToken(
                        "approvals.design.update", "ADMIN.APPROVAL_DESIGN:UPDATE",
                        "RS_DESIGN_A") + ','
                + ScopedAuthorityToken.wireToken(
                        "approvals.design.publish", "ADMIN.APPROVAL_DESIGN:PUBLISH",
                        "RS_DESIGN_B");

        ApprovalPilotPepRegistry.Decision decision = registry.authorize(evidence(
                "POST", workflow,
                Set.of("ADMIN.APPROVAL_DESIGN:UPDATE", "ADMIN.APPROVAL_DESIGN:PUBLISH"),
                roles, Set.of(), null));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void rejectsSameScopeAuditorButAllowsAValidDisjointOperationsScope() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);
        String retry = "/v1/admin/operations/events/"
                + "14d7b229-4752-4a50-8ac1-ecc129620649/retry";

        ApprovalPilotPepRegistry.Decision operator = registry.authorize(evidence(
                "POST", retry, Set.of("ADMIN.APPROVAL_OPERATIONS:EXECUTE"),
                scoped("approvals.operations.execute",
                        "ADMIN.APPROVAL_OPERATIONS:EXECUTE"), Set.of(), null));
        ApprovalPilotPepRegistry.Decision auditorConflict = registry.authorize(evidence(
                "POST", retry, Set.of("ADMIN.APPROVAL_OPERATIONS:EXECUTE"),
                scoped("approvals.operations.execute",
                        "ADMIN.APPROVAL_OPERATIONS:EXECUTE")
                        + ',' + ScopedAuthorityToken.wireToken(
                                "approvals.audit.operations.read",
                                "ADMIN.APPROVAL_OPERATIONS:VIEW", "RS_APPROVALS"),
                Set.of("AUDITOR"), null));
        ApprovalPilotPepRegistry.Decision otherScope = registry.authorize(evidence(
                "POST", retry, Set.of("ADMIN.APPROVAL_OPERATIONS:EXECUTE"),
                "APP_CONFIG_ADMIN@RS_OTHER," + ScopedAuthorityToken.wireToken(
                        "approvals.operations.execute",
                        "ADMIN.APPROVAL_OPERATIONS:EXECUTE", "RS_OTHER"),
                Set.of("AUDITOR"), null));

        assertThat(operator.allowed()).isTrue();
        assertThat(auditorConflict.allowed()).isFalse();
        assertThat(otherScope.allowed()).isTrue();
    }

    @Test
    void appliesLegacyOversightProfileOnlyToTenantAdminBeforeSunset() {
        ApprovalPilotPepRegistry active = new ApprovalPilotPepRegistry(
                objectMapper,
                Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
        ApprovalPilotPepRegistry expired = new ApprovalPilotPepRegistry(
                objectMapper,
                Clock.fixed(Instant.parse("2027-03-01T00:00:00Z"), ZoneOffset.UTC));

        ApprovalPilotPepRegistry.RequestEvidence evidence = evidence(
                "GET", "/v1/admin/signatures",
                Set.of("ADMIN.APPROVAL_SIGNATURE:VIEW"), "",
                Set.of("TENANT_ADMIN"), null);

        assertThat(active.authorize(evidence).allowed()).isTrue();
        assertThat(active.authorize(evidence).authorities())
                .extracting(ApprovalPilotPepRegistry.RouteAuthority::profileKey)
                .containsOnly("legacy-oversight");
        assertThat(expired.authorize(evidence).allowed()).isFalse();
    }

    @Test
    void failsClosedForUnknownMethodCrossSurfaceAndTrustedRouteMismatch() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);

        assertThat(registry.authorize(evidence(
                "DELETE", "/v1/home", Set.of("APP.APPROVALS:VIEW"), "", Set.of(), null))
                .allowed()).isFalse();
        assertThat(registry.authorize(evidence(
                "GET", "/v1/admin/announcements", Set.of("APP.APPROVALS:VIEW"),
                "", Set.of(), null)).allowed()).isFalse();
        assertThat(registry.authorize(evidence(
                "GET", "/v1/home", Set.of("APP.APPROVALS:VIEW"), "", Set.of(),
                "route.approvals.admin.workflows.page")).denialCode())
                .isEqualTo("UNKNOWN_METHOD_PATH_BINDING");
    }

    @Test
    void preservesDistinctWorkflowPageAndFormsReferenceResponseProjections() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);

        Set<String> permissions = Set.of("ADMIN.APPROVAL_DESIGN:VIEW");
        ApprovalPilotPepRegistry.Decision pageDecision = registry.authorize(
                new ApprovalPilotPepRegistry.RequestEvidence(
                        "GET", "/v1/admin/workflows", permissions,
                        scoped("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"), Set.of(),
                        "route.approvals.admin.workflows.page", null));
        ApprovalPilotPepRegistry.Decision referenceDecision = registry.authorize(
                new ApprovalPilotPepRegistry.RequestEvidence(
                        "GET", "/v1/admin/workflows", permissions,
                        scoped("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"), Set.of(),
                        "route.approvals.admin.forms-workflow-reference.data",
                        "view=reference"));

        assertThat(pageDecision.allowed()).isTrue();
        assertThat(referenceDecision.allowed()).isTrue();
        ApprovalPilotPepRegistry.RouteAuthority reference =
                referenceDecision.authorities().getFirst();
        ApprovalPilotPepRegistry.RouteAuthority page = pageDecision.authorities().getFirst();
        assertThat(reference.projectionPolicyKey())
                .isEqualTo("route.approvals.admin.forms-workflow-reference.data.full-management.projection.v1")
                .isNotEqualTo(page.projectionPolicyKey());
        assertThat(reference.responseSchemaKey())
                .isEqualTo("route.approvals.admin.forms-workflow-reference.data.response.v1")
                .isNotEqualTo(page.responseSchemaKey());
    }

    @Test
    void carriesExactClosedSchemaMetadataForLegacyOversightAndAuditorAuthorities() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);

        ApprovalPilotPepRegistry.RouteAuthority oversight = registry.authorize(
                new ApprovalPilotPepRegistry.RequestEvidence(
                        "GET", "/v1/admin/workflows",
                        Set.of("ADMIN.APPROVAL_DESIGN:VIEW"), "", Set.of("TENANT_ADMIN"),
                        "route.approvals.admin.workflows.page", null))
                .authorities().getFirst();
        ApprovalPilotPepRegistry.RouteAuthority auditor = registry.authorize(
                new ApprovalPilotPepRegistry.RequestEvidence(
                        "GET", "/v1/admin/operations",
                        Set.of("ADMIN.APPROVAL_OPERATIONS:VIEW"),
                        ScopedAuthorityToken.wireToken(
                                "approvals.audit.operations.read",
                                "ADMIN.APPROVAL_OPERATIONS:VIEW", "RS_APPROVALS"), Set.of(),
                        "route.approvals.admin.operations.page", null))
                .authorities().getFirst();

        assertProjectionMetadata(
                oversight, "legacy-oversight", "ApprovalOversightWorkflowV1");
        assertProjectionMetadata(
                auditor, "auditor", "ApprovalAuditorOperationsV1");
    }

    @Test
    void collapsesValidatedWireAliasesToOneCanonicalAuthority() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);

        ApprovalPilotPepRegistry.Decision decision = registry.authorize(
                new ApprovalPilotPepRegistry.RequestEvidence(
                        "GET", "/v1/tasks",
                        Set.of("ACTION.APPROVAL_TASK:VIEW"), "", Set.of(),
                        "route.approvals.work.inbox.page"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authorities()).singleElement()
                .extracting(ApprovalPilotPepRegistry.RouteAuthority::routeContractKey)
                .isEqualTo("route.approvals.work.completed.page");
    }

    @Test
    void rejectsQueryProfileSwapsAndDuplicateConstrainedParameters() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);
        Set<String> permissions = Set.of("ADMIN.APPROVAL_DESIGN:VIEW");

        assertThat(registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence(
                "GET", "/v1/admin/workflows", permissions,
                scoped("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"), Set.of(),
                "route.approvals.admin.workflows.page", "view=reference")).allowed())
                .isFalse();
        assertThat(registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence(
                "GET", "/v1/admin/workflows", permissions,
                scoped("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"), Set.of(),
                "route.approvals.admin.forms-workflow-reference.data", null)).allowed())
                .isFalse();
        assertThat(registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence(
                "GET", "/v1/admin/workflows", permissions,
                scoped("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"), Set.of(),
                "route.approvals.admin.forms-workflow-reference.data",
                "view=reference&view=reference")).allowed()).isFalse();
    }

    private ApprovalPilotPepRegistry.RequestEvidence evidence(
            String method,
            String path,
            Set<String> permissions,
            String resourceRoles,
            Set<String> roles,
            String routeKey) {
        return new ApprovalPilotPepRegistry.RequestEvidence(
                method, path, permissions, resourceRoles, roles,
                routeKey == null ? canonicalRoute(method, path) : routeKey);
    }

    private String scoped(String contractKey, String resolvedCapabilityCode) {
        return "APP_CONFIG_ADMIN@RS_APPROVALS," + ScopedAuthorityToken.wireToken(
                contractKey, resolvedCapabilityCode, "RS_APPROVALS");
    }

    private void assertProjectionMetadata(
            ApprovalPilotPepRegistry.RouteAuthority authority,
            String profileKey,
            String schemaKey) {
        assertThat(authority.profileKey()).isEqualTo(profileKey);
        assertThat(authority.responseSchemaKey()).isEqualTo(schemaKey);
        assertThat(authority.projectionSchemaVersion()).isEqualTo(1);
        assertThat(authority.openApiSchemaSha256())
                .isEqualTo(ApprovalProjectionSchemaContract.expectedSha256(schemaKey));
        assertThat(authority.projectionAdditionalProperties()).isFalse();
    }

    private String canonicalRoute(String method, String path) {
        if ("GET".equals(method) && "/v1/home".equals(path)) {
            return "route.approvals.work.home.page";
        }
        if ("POST".equals(method) && "/v1/admin/workflows".equals(path)) {
            return "route.approvals.admin.workflow-create.action";
        }
        if ("POST".equals(method) && path.matches(
                "^/v1/admin/workflows/[^/]+/publish$")) {
            return "route.approvals.admin.workflow-publish.action";
        }
        if ("GET".equals(method) && "/v1/admin/signatures".equals(path)) {
            return "route.approvals.admin.signatures.page";
        }
        if ("POST".equals(method) && path.matches(
                "^/v1/admin/operations/events/[^/]+/retry$")) {
            return "route.approvals.admin.operations.retry.action";
        }
        return null;
    }
}
