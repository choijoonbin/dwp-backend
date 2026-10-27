package com.dwp.services.platform.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;


/** Shared setup and evidence builders for HcmPlatformPepSecurityContractTest. */
abstract class HcmPlatformPepFixture {
    protected static final String DECISION_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    protected static final String OTHER_DECISION_REVISION =
            "psr-" + "fedcba9876543210".repeat(4);
    protected static final String SUBJECT =
            "10000000-0000-4000-8000-000000000012";
    protected static final String OTHER_SUBJECT =
            "10000000-0000-4000-8000-000000000013";
    protected static final String EXPLORER_CONTEXT =
            "psc-" + "0123456789abcdef".repeat(4);
    protected static final String OTHER_EXPLORER_CONTEXT =
            "psc-" + "fedcba9876543210".repeat(4);
    protected static final String SYS002_RESOURCE_SET = "RS_HCM_OPERATIONS_ALL";
    protected static final String SYS002_SCOPE = ProductSurfaceScopeKey.key(
            7, 101, "hcm", "hcm.operations", SYS002_RESOURCE_SET,
            "RESOURCE_SET");
    protected static final String SYS002_SETTINGS_SCOPE = ProductSurfaceScopeKey.key(
            7, 101, "hcm", "hcm.management",
            "APP_RESOURCE_SET:RS_HCM_CONFIG", "RESOURCE_SET");

    protected final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    protected final HcmPlatformPepRegistry registry =
            new HcmPlatformPepRegistry(objectMapper);
    protected final PlatformSecurityFilter filter = new PlatformSecurityFilter(
            "trusted", "runtime", false, objectMapper,
            new PlatformCanaryPepRegistry(objectMapper),
            new PlatformApprovalsPepRegistry(objectMapper),
            registry);

    protected HcmPlatformPepRegistry.RequestEvidence explorerRequest(
            String method,
            String path,
            Set<String> permissions,
            Set<String> roles,
            Set<String> resourceRoles,
            String route,
            HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence) {
        return explorerRequest(method, path, permissions, roles, resourceRoles,
                route, ownerEvidence, EXPLORER_CONTEXT);
    }

    protected HcmPlatformPepRegistry.RequestEvidence explorerRequest(
            String method,
            String path,
            Set<String> permissions,
            Set<String> roles,
            Set<String> resourceRoles,
            String route,
            HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence,
            String requestContext) {
        return new HcmPlatformPepRegistry.RequestEvidence(
                method, path, permissions, roles, resourceRoles,
                DECISION_REVISION, requestContext, "tenant-7",
                ownerEvidence, route);
    }

    protected HcmPlatformPepRegistry.RequestEvidence navigationShellRequest(
            String path,
            String route,
            Set<String> permissions,
            Set<String> resourceRoles) {
        return navigationRuntimeRequest(
                path, route, permissions, resourceRoles, "SELF");
    }

    protected HcmPlatformPepRegistry.RequestEvidence navigationRuntimeRequest(
            String path,
            String route,
            Set<String> permissions,
            Set<String> resourceRoles,
            String populationKind) {
        return new HcmPlatformPepRegistry.RequestEvidence(
                "GET", path, permissions, Set.of(), resourceRoles,
                DECISION_REVISION, "psc-" + "1".repeat(64), "self",
                populationKind, null, null, route);
    }

    protected HcmPlatformPepRegistry.RequestEvidence sys002Request(
            String path,
            String route,
            Set<String> permissions,
            Set<String> roles,
            Set<String> resourceRoles,
            String populationKind,
            String purpose,
            String scope) {
        return new HcmPlatformPepRegistry.RequestEvidence(
                "GET", path, permissions, roles, resourceRoles,
                DECISION_REVISION, "psc-" + "2".repeat(64), scope,
                populationKind, purpose, null, route);
    }

    protected HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence() {
        return ownerEvidence(
                DECISION_REVISION, 17, 29,
                Set.of("route.hcm.personal.me.page"), null);
    }

    protected HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence(
            String contextKey) {
        return ownerEvidence(
                DECISION_REVISION, contextKey, 17, 29,
                Set.of("route.hcm.personal.me.page"), null);
    }

    protected HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence(
            String decisionRevision,
            long fieldPolicyRevision,
            long authorizationRevision,
            Set<String> allowedDescendantPageRouteKeys,
            HcmPlatformPepRegistry.ExplorerReceiptEvidence receiptEvidence) {
        return ownerEvidence(decisionRevision, EXPLORER_CONTEXT,
                fieldPolicyRevision, authorizationRevision,
                allowedDescendantPageRouteKeys, receiptEvidence);
    }

    protected HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence(
            String decisionRevision,
            String contextKey,
            long fieldPolicyRevision,
            long authorizationRevision,
            Set<String> allowedDescendantPageRouteKeys,
            HcmPlatformPepRegistry.ExplorerReceiptEvidence receiptEvidence) {
        return new HcmPlatformPepRegistry.ExplorerOwnerEvidence(
                decisionRevision, contextKey, "tenant-7",
                "HRIS_APP_DISCOVERY", SUBJECT,
                fieldPolicyRevision, authorizationRevision,
                allowedDescendantPageRouteKeys, receiptEvidence);
    }

    protected MockHttpServletRequest explorerHttpRequest() {
        MockHttpServletRequest request = base("GET", "/v1/hris/explorer");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_EMPLOYEE");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.HCM:VIEW,DATA.HRIS_APP_SHELL:VIEW");
        request.addHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "HRIS_DUTY_APP_SHELL_READER@SELF");
        trustedEvidence(request, "route.hcm.utility.explorer.page", false);
        request.removeHeader(PlatformSecurityFilter.CONTEXT_HEADER);
        request.addHeader(PlatformSecurityFilter.CONTEXT_HEADER, EXPLORER_CONTEXT);
        return request;
    }

    protected MockHttpServletRequest sys002HttpRequest() {
        MockHttpServletRequest request = base(
                "GET", "/v1/admin/hris/operations/exceptions");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_AUDITOR");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.HCM:VIEW,DATA.HRIS_OPERATION_EXCEPTION:VIEW");
        request.addHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "HRIS_DUTY_OPERATION_EXCEPTION_READER@" + SYS002_RESOURCE_SET);
        request.addHeader(PlatformSecurityHeaders.POPULATION_KIND,
                "TARGET_POPULATION");
        request.addHeader(PlatformSecurityHeaders.PURPOSE, "HRIS_OPERATIONS");
        trustedEvidence(request, "route.hcm.operations.exception.data", false);
        request.removeHeader(PlatformSecurityFilter.SCOPE_HEADER);
        request.addHeader(PlatformSecurityFilter.SCOPE_HEADER, SYS002_SCOPE);
        return request;
    }

    protected MockHttpServletResponse apply(MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    protected HcmPlatformPepRegistry.RequestEvidence transferRequest(
            String method,
            Set<String> permissions,
            Set<String> roles,
            Set<String> resourceRoles,
            String purpose,
            String route) {
        return new HcmPlatformPepRegistry.RequestEvidence(
                method, "/v1/admin/hris/file-transfers", permissions,
                roles, resourceRoles, DECISION_REVISION,
                "psc-" + "1".repeat(64), "tenant-7", purpose,
                null, route);
    }

    protected MockHttpServletResponse invokeControlledTransfer(
            String method,
            boolean appEntitlement,
            boolean scopedDuty,
            boolean purpose,
            boolean elevated) throws Exception {
        boolean create = "POST".equals(method);
        MockHttpServletRequest request = base(
                method, "/v1/admin/hris/file-transfers");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER,
                create ? "HRIS_INTEGRATION_EXECUTOR" : "HRIS_INTEGRATION_AUTHOR");
        String capability = create
                ? "ACTION.HRIS_CONTROLLED_TRANSFER:CREATE"
                : "DATA.HRIS_CONTROLLED_TRANSFER:VIEW";
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                appEntitlement ? "APP.HCM:VIEW," + capability : capability);
        if (scopedDuty) {
            request.addHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                    (create ? "HRIS_DUTY_CONTROLLED_TRANSFER_CREATOR"
                            : "HRIS_DUTY_CONTROLLED_TRANSFER_READER")
                            + "@RS_HCM_CONFIG");
        }
        if (purpose) request.addHeader(
                PlatformSecurityHeaders.PURPOSE, "HRIS_INTEGRATION_ADMIN");
        if (elevated) request.addHeader(
                "X-DWP-Active-Access-Mode", "ELEVATED");
        trustedEvidence(request,
                create
                        ? "route.hcm.management.controlled-transfer-create.action"
                        : "route.hcm.management.controlled-transfer.data",
                create);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    protected MockHttpServletResponse invokeNavigationShell(
            String path,
            String route,
            boolean appEntitlement,
            boolean shellDuty) throws Exception {
        MockHttpServletRequest request = base("GET", path);
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                (appEntitlement ? "APP.HCM:VIEW," : "")
                        + "DATA.HRIS_APP_SHELL:VIEW");
        if (shellDuty) {
            request.addHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                    "HRIS_DUTY_APP_SHELL_READER@SELF");
        }
        request.addHeader(PlatformSecurityHeaders.POPULATION_KIND, "SELF");
        trustedEvidence(request, route, false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    protected MockHttpServletResponse invokeNavigationRuntime(
            String path,
            String route,
            String populationKind,
            boolean operationsAuthority) throws Exception {
        MockHttpServletRequest request = base("GET", path);
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.HCM:VIEW"
                        + (operationsAuthority ? ",DATA.WORKFORCE:VIEW" : ""));
        request.addHeader(PlatformSecurityHeaders.POPULATION_KIND, populationKind);
        trustedEvidence(request, route, false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    protected MockHttpServletResponse invoke(
            String method,
            String path,
            String route,
            String permissions,
            boolean stateChanging,
            boolean elevated) throws Exception {
        MockHttpServletRequest request = base(method, path);
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_CONFIG_AUTHOR");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, permissions);
        trustedEvidence(request, route, stateChanging);
        if (elevated) request.addHeader("X-DWP-Active-Access-Mode", "ELEVATED");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    protected MockHttpServletRequest base(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "101");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "7");
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        return request;
    }

    protected MockHttpServletRequest hcmAuditRequest() {
        MockHttpServletRequest request = base(
                "GET", "/v1/admin/audit-control/events");
        request.setParameter("product", "HCM");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_AUDITOR");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.HCM:VIEW,ADMIN.AUDIT_VIEW:VIEW");
        request.addHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "HRIS_DUTY_AUDITOR@RS_HCM_CONFIG");
        request.addHeader("X-DWP-Active-Access-Mode", "ELEVATED");
        request.addHeader("X-Purpose-Code", "HRIS_AUDIT_EVIDENCE");
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                "10000000-0000-4000-8000-000000000001");
        request.addHeader("X-DWP-Field-Policy-Revision", "17");
        request.addHeader("X-DWP-Authorization-Revision", "29");
        trustedEvidence(request, "route.hcm.audit.evidence.page", false);
        request.removeHeader(PlatformSecurityFilter.CONTEXT_HEADER);
        request.addHeader(PlatformSecurityFilter.CONTEXT_HEADER, "hcm.management");
        return request;
    }

    protected void trustedEvidence(
            MockHttpServletRequest request,
            String route,
            boolean stateChanging) {
        request.addHeader(PlatformSecurityFilter.ROLLOUT_STATE_HEADER, "100");
        request.addHeader(PlatformSecurityFilter.ROLLOUT_REVISION_HEADER,
                "rollout-" + "0123456789abcdef".repeat(4));
        request.addHeader(PlatformSecurityFilter.ROLLOUT_COHORT_HEADER, "baseline");
        request.addHeader(PlatformSecurityFilter.ROUTE_CONTRACT_HEADER, route);
        request.addHeader(PlatformSecurityFilter.CURRENT_DECISION_REVISION_HEADER,
                DECISION_REVISION);
        request.addHeader(PlatformSecurityFilter.CURRENT_REVALIDATE_AT_HEADER,
                "2030-01-01T00:00:00Z");
        request.addHeader(PlatformSecurityFilter.CONTEXT_HEADER, "hcm.configuration");
        request.addHeader(PlatformSecurityFilter.SCOPE_HEADER, "tenant-7");
        if (stateChanging) {
            request.addHeader(
                    PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                    DECISION_REVISION);
        }
    }
}
