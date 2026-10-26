package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationOwnerSecurityContractTest {

    private static final String SERVICE_TOKEN = "sys008-trusted-gateway-token";
    private static final String DIGEST = "a".repeat(64);
    private static final String DECISION = "psr-" + DIGEST;
    private static final String CONTEXT = "psc-" + "b".repeat(64);
    private static final UUID SUBJECT = UUID.fromString(
            "4f2b4fa0-3e26-4f54-9258-ff5ef45c4fc1");
    private static final UUID CORRELATION = UUID.fromString(
            "742fcf44-7837-4bc5-91e8-47a3237a22f8");
    private static final UUID CONNECTOR = UUID.fromString(
            "22471092-26a7-4f05-b464-1160d50f1d99");
    private static final String UUID_RX = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
            + "[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
            + "[0-9a-fA-F]{12})";
    private static final String VERSION_RX = "([1-9][0-9]{0,15})";
    private static final Map<String, String> EXPECTED_ACTION_BY_ROUTE = Map.ofEntries(
            Map.entry("route.hcm.configuration.connector-definition-create.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors"),
            Map.entry("route.hcm.configuration.connector-test.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.test"),
            Map.entry("route.hcm.configuration.connector-mapping-dry-run.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.dry-runs"),
            Map.entry("route.hcm.configuration.connector-execution-create.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions"),
            Map.entry("route.hcm.configuration.connector-definition-validate.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.validate"),
            Map.entry("route.hcm.configuration.connector-definition-submit.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.submit"),
            Map.entry("route.hcm.configuration.connector-definition-approve.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.approve"),
            Map.entry("route.hcm.configuration.connector-definition-activate.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.activate"),
            Map.entry("route.hcm.configuration.connector-mapping-version-create.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings"),
            Map.entry("route.hcm.configuration.connector-mapping-version-validate.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.validate"),
            Map.entry("route.hcm.configuration.connector-mapping-version-submit.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.submit"),
            Map.entry("route.hcm.configuration.connector-mapping-version-approve.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.approve"),
            Map.entry("route.hcm.configuration.connector-mapping-version-activate.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.activate"),
            Map.entry("route.hcm.configuration.connector-execution-reconcile.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.reconcile"),
            Map.entry("route.hcm.configuration.connector-execution-retry.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.retry"),
            Map.entry("route.hcm.configuration.connector-execution-cancel.action",
                    "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.cancel"));

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final IntegrationOwnerAuthorityFilter filter =
            new IntegrationOwnerAuthorityFilter(SERVICE_TOKEN, json);

    @Test
    void exactTwentyMethodPathRouteAndActionContractsRemainClosed() {
        Set<RouteContract> expected = Set.of(
                route("GET", "^/v1/hris/workbench/settings-integrations$",
                        "route.hcm.configuration.settings-integrations.page", false, false, false),
                route("GET", "^/v1/hris/workbench/dwp-integrations$",
                        "route.hcm.configuration.dwp-integrations.data", false, false, false),
                route("GET", "^/v1/admin/hris/connectors$",
                        "route.hcm.configuration.connector-catalog.data", false, false, false),
                route("POST", "^/v1/admin/hris/connectors$",
                        "route.hcm.configuration.connector-definition-create.action", true, false, false),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/test$",
                        "route.hcm.configuration.connector-test.action", true, false, false),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                                + "/mappings/" + VERSION_RX + "/dry-runs$",
                        "route.hcm.configuration.connector-mapping-dry-run.action", true, true, false),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/executions$",
                        "route.hcm.configuration.connector-execution-create.action", true, false, false),
                route("GET", "^/v1/admin/hris/connector-executions/" + UUID_RX + "$",
                        "route.hcm.configuration.connector-execution-detail.data", false, false, false),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/validate$",
                        "route.hcm.configuration.connector-definition-validate.action", true, true, false),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/submit$",
                        "route.hcm.configuration.connector-definition-submit.action", true, true, false),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/approve$",
                        "route.hcm.configuration.connector-definition-approve.action", true, true, true),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/activate$",
                        "route.hcm.configuration.connector-definition-activate.action", true, true, true),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/mappings$",
                        "route.hcm.configuration.connector-mapping-version-create.action", true, false, false),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                                + "/mappings/" + VERSION_RX + "/validate$",
                        "route.hcm.configuration.connector-mapping-version-validate.action", true, true, false),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                                + "/mappings/" + VERSION_RX + "/submit$",
                        "route.hcm.configuration.connector-mapping-version-submit.action", true, true, false),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                                + "/mappings/" + VERSION_RX + "/approve$",
                        "route.hcm.configuration.connector-mapping-version-approve.action", true, true, true),
                route("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                                + "/mappings/" + VERSION_RX + "/activate$",
                        "route.hcm.configuration.connector-mapping-version-activate.action", true, true, true),
                route("POST", "^/v1/admin/hris/connector-executions/" + UUID_RX + "/reconcile$",
                        "route.hcm.configuration.connector-execution-reconcile.action", true, true, true),
                route("POST", "^/v1/admin/hris/connector-executions/" + UUID_RX + "/retry$",
                        "route.hcm.configuration.connector-execution-retry.action", true, true, true),
                route("POST", "^/v1/admin/hris/connector-executions/" + UUID_RX + "/cancel$",
                        "route.hcm.configuration.connector-execution-cancel.action", true, true, true));

        Set<RouteContract> actual = IntegrationOwnerAuthorityFilter.bindingContracts()
                .stream().map(binding -> new RouteContract(
                        binding.method(), binding.path().pattern(), binding.routeKey(),
                        binding.action(), binding.cas(), binding.stepUp(),
                        IntegrationService.actionForRoute(binding.routeKey()))).collect(
                        java.util.stream.Collectors.toUnmodifiableSet());

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).hasSize(20);
        assertThat(actual.stream().filter(RouteContract::action).count()).isEqualTo(16);
        assertThat(actual.stream().filter(RouteContract::cas).count()).isEqualTo(12);
        assertThat(actual.stream().filter(RouteContract::stepUp).count()).isEqualTo(7);
        assertThat(actual.stream().filter(RouteContract::action)
                .map(RouteContract::originatingAction)).containsExactlyInAnyOrderElementsOf(
                        Sys008ProjectionContract.ACTION_KEYS);
        assertThat(actual.stream().filter(contract -> !contract.action())
                .map(RouteContract::originatingAction)).containsOnly("");
    }

    @Test
    void controllerMethodsExposeTheSameExactTwentyServicePaths() {
        Set<HttpPath> actual = new LinkedHashSet<>();
        RequestMapping controllerMapping =
                IntegrationController.class.getAnnotation(RequestMapping.class);
        assertThat(controllerMapping).isNotNull();
        assertThat(controllerMapping.value()).containsExactly("/v1");
        String prefix = controllerMapping.value()[0];
        Arrays.stream(IntegrationController.class.getDeclaredMethods()).forEach(method -> {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                Arrays.stream(get.value()).forEach(path ->
                        actual.add(new HttpPath("GET", prefix + path)));
            }
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null) {
                Arrays.stream(post.value()).forEach(path ->
                        actual.add(new HttpPath("POST", prefix + path)));
            }
        });

        assertThat(actual).containsExactlyInAnyOrderElementsOf(Set.of(
                path("GET", "/v1/hris/workbench/settings-integrations"),
                path("GET", "/v1/hris/workbench/dwp-integrations"),
                path("GET", "/v1/admin/hris/connectors"),
                path("POST", "/v1/admin/hris/connectors"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/test"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/mappings/{version}/dry-runs"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/executions"),
                path("GET", "/v1/admin/hris/connector-executions/{executionId}"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/validate"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/submit"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/approve"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/activate"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/mappings"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/mappings/{version}/validate"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/mappings/{version}/submit"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/mappings/{version}/approve"),
                path("POST", "/v1/admin/hris/connectors/{connectorId}/mappings/{version}/activate"),
                path("POST", "/v1/admin/hris/connector-executions/{executionId}/reconcile"),
                path("POST", "/v1/admin/hris/connector-executions/{executionId}/retry"),
                path("POST", "/v1/admin/hris/connector-executions/{executionId}/cancel")));
        assertThat(actual).hasSize(20);
    }

    @Test
    void trustedReaderHeadersCreateTenantPrincipalPurposeAndRevisionBoundAuthority()
            throws Exception {
        MockHttpServletRequest request = readerRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(reached));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        Object value = request.getAttribute(IntegrationOwnerAuthorityFilter.AUTHORITY_ATTRIBUTE);
        assertThat(value).isInstanceOf(IntegrationOwnerAuthorityFilter.Authority.class);
        IntegrationOwnerAuthorityFilter.Authority authority =
                (IntegrationOwnerAuthorityFilter.Authority) value;
        assertThat(authority.tenantId()).isEqualTo(71L);
        assertThat(authority.actorId()).isEqualTo(910L);
        assertThat(authority.subjectPrincipalPublicId()).isEqualTo(SUBJECT);
        assertThat(authority.purposeCode()).isEqualTo("HRIS_INTEGRATION_ADMIN");
        assertThat(authority.routeContractKey()).isEqualTo(
                "route.hcm.configuration.settings-integrations.page");
        assertThat(authority.decisionRevision()).isEqualTo(DECISION);
        assertThat(authority.revalidateAt()).isAfter(OffsetDateTime.now());
        assertThat(authority.contextKey()).isEqualTo(CONTEXT);
        assertThat(authority.contextScopeKey())
                .isEqualTo("tenant:71:hris-integration");
        assertThat(authority.correlationId()).isEqualTo(CORRELATION);
        assertThat(authority.scopeRevision()).isEqualTo(21L);
        assertThat(authority.fieldPolicyRevision()).isEqualTo(22L);
        assertThat(authority.authorizationRevision()).isEqualTo(23L);
        assertThat(authority.populationScopeDigest()).matches("^[a-f0-9]{64}$");
        assertThat(authority.populationKind()).isEqualTo("TENANT");
        assertThat(authority.legalEntityPublicId()).isNull();
        assertThat(authority.reader()).isTrue();
        assertThat(authority.idempotencyKey()).isNull();
        assertThat(authority.expectedObjectRevision()).isNull();
    }

    @Test
    void targetBoundExecutorActionRequiresCasAndProducesStepUpSeal() throws Exception {
        MockHttpServletRequest request = actionRequest("approve");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(reached));

        assertThat(reached).isTrue();
        IntegrationOwnerAuthorityFilter.Authority authority =
                (IntegrationOwnerAuthorityFilter.Authority) request.getAttribute(
                        IntegrationOwnerAuthorityFilter.AUTHORITY_ATTRIBUTE);
        assertThat(authority.executor()).isTrue();
        assertThat(authority.routeContractKey()).isEqualTo(
                "route.hcm.configuration.connector-definition-approve.action");
        assertThat(authority.idempotencyKey()).isEqualTo(
                "sys008-approve-idempotency-0001");
        assertThat(authority.expectedObjectRevision()).isEqualTo(37L);
        assertThat(authority.stepUpEvidenceDigest()).matches("^[a-f0-9]{64}$");
    }

    @Test
    void filterFailsClosedForUntrustedTenantPrincipalPurposeRevisionAndScope()
            throws Exception {
        assertDenied(readerRequest(), "X-DWP-Tenant-ID", "0");
        assertDenied(readerRequest(), "X-DWP-Subject-Principal-Public-ID", "not-a-uuid");
        assertDenied(readerRequest(), "X-Purpose-Code", "GENERAL_BROWSING");
        assertDenied(readerRequest(), "X-DWP-Authorization-Revision", "0");
        assertDenied(readerRequest(), "X-DWP-Scope-Revision", "0");
        assertDenied(readerRequest(), "X-DWP-Field-Policy-Revision", "0");
        assertDenied(readerRequest(), "X-DWP-Context-Scope-Key", " ");
        assertDenied(readerRequest(), "X-DWP-Population-Kind", "tenant");
    }

    @Test
    void legalEntityPopulationIsGatewayVerifiedAndCannotBeWidenedOrBrowserSupplied()
            throws Exception {
        UUID legalEntity = UUID.fromString("13074d68-9a4b-4b78-ac81-b46332570625");
        MockHttpServletRequest scoped = readerRequest();
        scoped.removeHeader("X-DWP-Population-Kind");
        scoped.addHeader("X-DWP-Population-Kind", "LEGAL_ENTITY");
        scoped.addHeader("X-DWP-Verified-Legal-Entity-Public-ID", legalEntity.toString());
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(scoped, new MockHttpServletResponse(), recordingChain(reached));

        assertThat(reached).isTrue();
        IntegrationOwnerAuthorityFilter.Authority authority =
                (IntegrationOwnerAuthorityFilter.Authority) scoped.getAttribute(
                        IntegrationOwnerAuthorityFilter.AUTHORITY_ATTRIBUTE);
        assertThat(authority.populationKind()).isEqualTo("LEGAL_ENTITY");
        assertThat(authority.legalEntityPublicId()).isEqualTo(legalEntity);

        MockHttpServletRequest missingVerifiedOwner = readerRequest();
        missingVerifiedOwner.removeHeader("X-DWP-Population-Kind");
        missingVerifiedOwner.addHeader("X-DWP-Population-Kind", "LEGAL_ENTITY");
        assertRejected(missingVerifiedOwner);

        MockHttpServletRequest widenedTenant = readerRequest();
        widenedTenant.addHeader(
                "X-DWP-Verified-Legal-Entity-Public-ID", legalEntity.toString());
        assertRejected(widenedTenant);

        MockHttpServletRequest browserSupplied = readerRequest();
        browserSupplied.addHeader("X-DWP-Legal-Entity-Public-ID", legalEntity.toString());
        assertRejected(browserSupplied);
    }

    @Test
    void readRoutesRejectEveryCommandOnlyHeader() throws Exception {
        for (String header : List.of(
                "Idempotency-Key", "If-Match", "X-DWP-Expected-Object-Version")) {
            MockHttpServletRequest request = readerRequest();
            request.addHeader(header, "X-DWP-Expected-Object-Version".equals(header)
                    ? "7" : "command-only-value-0001");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean();

            filter.doFilter(request, response, recordingChain(reached));

            assertThat(reached).as(header).isFalse();
            assertThat(response.getStatus()).as(header).isEqualTo(400);
        }
    }

    private void assertDenied(
            MockHttpServletRequest request, String header, String replacement)
            throws Exception {
        request.removeHeader(header);
        request.addHeader(header, replacement);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(reached));

        assertThat(reached).as(header).isFalse();
        assertThat(response.getStatus()).as(header).isBetween(400, 499);
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, recordingChain(reached));
        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isBetween(400, 499);
    }

    private static FilterChain recordingChain(AtomicBoolean reached) {
        return (request, response) -> reached.set(true);
    }

    private static MockHttpServletRequest readerRequest() {
        MockHttpServletRequest request = common(
                "GET", "/v1/hris/workbench/settings-integrations",
                "route.hcm.configuration.settings-integrations.page", "NORMAL");
        request.addHeader("X-DWP-Permissions", "CONFIG.HRIS_POLICY:VIEW");
        request.addHeader("X-DWP-Roles", "HRIS_CONFIG_AUTHOR");
        request.addHeader("X-DWP-Resource-Roles",
                "HRIS_DUTY_CONFIGURATION_WORKBENCH_READER");
        return request;
    }

    private static MockHttpServletRequest actionRequest(String operation) {
        MockHttpServletRequest request = common(
                "POST", "/v1/admin/hris/connectors/" + CONNECTOR + "/" + operation,
                "route.hcm.configuration.connector-definition-" + operation + ".action",
                "ELEVATED");
        request.addHeader("X-DWP-Permissions",
                "ACTION.HRIS_INTEGRATION_CONTROL_PLANE:EXECUTE");
        request.addHeader("X-DWP-Roles",
                "HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR");
        request.addHeader("X-DWP-Resource-Roles",
                "HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR");
        request.addHeader("X-DWP-Expected-Decision-Revision", DECISION);
        request.addHeader("X-DWP-Expected-Object-Version", "37");
        request.addHeader("Idempotency-Key", "sys008-approve-idempotency-0001");
        return request;
    }

    private static MockHttpServletRequest common(
            String method, String uri, String route, String mode) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("X-DWP-Service-Token", SERVICE_TOKEN);
        request.addHeader("X-DWP-Route-Contract-Key", route);
        request.addHeader("X-DWP-Tenant-ID", "71");
        request.addHeader("X-DWP-User-ID", "910");
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader("X-DWP-Subject-Principal-Public-ID", SUBJECT.toString());
        request.addHeader("X-DWP-Current-Decision-Revision", DECISION);
        request.addHeader("X-DWP-Current-Revalidate-At",
                OffsetDateTime.now().plusHours(2).toString());
        request.addHeader("X-DWP-Population-Kind", "TENANT");
        request.addHeader("X-DWP-Context-Key", CONTEXT);
        request.addHeader("X-DWP-Context-Scope-Key", "tenant:71:hris-integration");
        request.addHeader("X-DWP-Scope-Revision", "21");
        request.addHeader("X-DWP-Field-Policy-Revision", "22");
        request.addHeader("X-DWP-Authorization-Revision", "23");
        request.addHeader("X-Correlation-ID", CORRELATION.toString());
        request.addHeader("X-Purpose-Code", "HRIS_INTEGRATION_ADMIN");
        request.addHeader("X-DWP-Active-Access-Mode", mode);
        return request;
    }

    private static RouteContract route(
            String method, String pattern, String routeKey,
            boolean action, boolean cas, boolean stepUp) {
        return new RouteContract(method, pattern, routeKey, action, cas, stepUp,
                EXPECTED_ACTION_BY_ROUTE.getOrDefault(routeKey, ""));
    }

    private static HttpPath path(String method, String path) {
        return new HttpPath(method, path);
    }

    private record RouteContract(
            String method,
            String path,
            String routeKey,
            boolean action,
            boolean cas,
            boolean stepUp,
            String originatingAction) {}

    private record HttpPath(String method, String path) {}
}
