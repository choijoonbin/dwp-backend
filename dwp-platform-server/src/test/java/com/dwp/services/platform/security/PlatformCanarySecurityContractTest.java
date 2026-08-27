package com.dwp.services.platform.security;

import com.dwp.services.platform.support.PilotAuthorizationFixtureAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformCanarySecurityContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PilotAuthorizationFixtureAdapter fixtureAdapter =
            new PilotAuthorizationFixtureAdapter();
    private final PlatformCanaryPepRegistry registry =
            new PlatformCanaryPepRegistry(objectMapper);
    private final PlatformSecurityFilter filter =
            new PlatformSecurityFilter("trusted", "runtime", objectMapper, registry);

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @MethodSource("canaryCases")
    void enforcesEveryGeneratedCanaryPersonaWithoutAParallelGrantSeed(
            String testId,
            String method,
            String path,
            int expectedStatus) throws Exception {
        MockHttpServletResponse response = invoke(
                FixtureEvidence.from(testId, fixtureAdapter, registry),
                method,
                path,
                null);

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
    }

    @Test
    void genericManageNeverImpliesTheRegisteredOperationsUpdateAction() throws Exception {
        FixtureEvidence fixture = FixtureEvidence.from("PS-C012", fixtureAdapter, registry);
        FixtureEvidence legacyManage = fixture.withPermissions(
                Set.of("ADMIN.SERVICE_OPERATIONS:MANAGE"));

        MockHttpServletResponse response = invoke(
                legacyManage,
                "POST",
                "/v1/admin/services/requests/00000000-0000-0000-0000-000000000001/transition",
                null);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void administratorRoleLabelCannotFallbackIntoACanaryRoute() throws Exception {
        assertThat(fixtureAdapter.project("PS-C004").group()).isEqualTo("CANARY");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/admin/services/catalog");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "101");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "7");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        trustedProductEvidence(request, "route.services.management.catalog.page", false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void unknownMethodAndUnregisteredPathFailClosed() throws Exception {
        FixtureEvidence fixture = FixtureEvidence.from("PS-C009", fixtureAdapter, registry);

        assertThat(invoke(
                fixture, "POST", "/v1/admin/announcements/91", null).getStatus())
                .isEqualTo(503);
        assertThat(invoke(
                fixture, "GET", "/v1/communications/91/future", null).getStatus())
                .isEqualTo(503);
    }

    @Test
    void clientRouteKeyCannotSelectAnotherSurfaceOrOverrideServerResolution() throws Exception {
        FixtureEvidence fixture = FixtureEvidence.from("PS-C005", fixtureAdapter, registry);

        MockHttpServletResponse response = invoke(
                fixture,
                "GET",
                "/v1/services/catalog",
                "route.services.management.catalog.page");

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void exactCapabilityStillRequiresItsAppConfigAdminResourceResponsibility()
            throws Exception {
        FixtureEvidence fixture = FixtureEvidence.from("PS-C002", fixtureAdapter, registry)
                .withResourceRoles("APP_CONFIG_ADMIN@RS_SERVICES");

        MockHttpServletResponse response = invoke(
                fixture, "GET", "/v1/admin/announcements", null);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void supportModeNeverAddsNormalEditorGrantsOrEnablesAWriteProfile() throws Exception {
        FixtureEvidence normalEditor = FixtureEvidence.from(
                "PS-C009", fixtureAdapter, registry);
        FixtureEvidence support = FixtureEvidence.from("PS-C003", fixtureAdapter, registry);
        FixtureEvidence combined = normalEditor.withSupport(
                support.supportSessionId(),
                Set.of("TENANT_CONFIGURATION_READ", "TENANT_CONFIGURATION_WRITE"));

        MockHttpServletResponse response = invoke(
                combined, "POST", "/v1/admin/announcements", null);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void staleAuthorityRevisionFailsClosedForEveryCanaryMutationOwner() throws Exception {
        for (String testId : Set.of("PS-C009", "PS-C012")) {
            FixtureEvidence fixture = FixtureEvidence.from(testId, fixtureAdapter, registry);
            String path = "PS-C009".equals(testId)
                    ? "/v1/admin/announcements"
                    : "/v1/admin/services/requests/00000000-0000-0000-0000-000000000001/transition";

            MockHttpServletResponse response = invoke(
                    fixture, "POST", path, null, true);

            assertThat(response.getStatus()).isEqualTo(409);
        }
    }

    @Test
    void generatedProjectionBindsEveryPlatformOwnedCanaryRouteToBothOpenApiHops() {
        assertThat(registry.bindingContracts()).hasSize(36);
        assertThat(registry.bindingContracts().stream()
                .map(PlatformCanaryPepRegistry.BindingContract::routeContractKey)
                .distinct())
                .hasSize(33)
                .contains("route.communications.work.event.action",
                        "route.services.management.request-transition.action");
        assertThat(registry.bindingContracts())
                .allSatisfy(binding -> {
                    assertThat(binding.publicPath()).startsWith("/api/platform/v1/");
                    assertThat(binding.servicePath()).startsWith("/v1/");
                    assertThat(binding.method()).isIn("GET", "POST", "PUT");
                });
    }

    private MockHttpServletResponse invoke(
            FixtureEvidence evidence,
            String method,
            String path,
            String suppliedRouteKey) throws Exception {
        return invoke(evidence, method, path, suppliedRouteKey, false);
    }

    private MockHttpServletResponse invoke(
            FixtureEvidence evidence,
            String method,
            String path,
            String suppliedRouteKey,
            boolean staleExpectedRevision) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "101");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "7");
        request.addHeader(
                PlatformSecurityFilter.ROLES_HEADER,
                evidence.providerIdentity() ? "PROVIDER_SUPPORT" : "WORKSPACE_MEMBER");
        if (!evidence.permissions().isEmpty()) {
            request.addHeader(
                    PlatformSecurityFilter.PERMISSIONS_HEADER,
                    String.join(",", evidence.permissions()));
        }
        if (!evidence.resourceRoles().isBlank()) {
            request.addHeader(
                    PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                    evidence.resourceRoles());
        }
        if (evidence.supportSessionId() != null) {
            request.addHeader(
                    PlatformSecurityFilter.SUPPORT_SESSION_HEADER,
                    evidence.supportSessionId());
            request.addHeader(
                    PlatformSecurityFilter.SUPPORT_SCOPES_HEADER,
                    String.join(",", evidence.supportScopes()));
            request.addHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "3");
        }
        String routeKey = suppliedRouteKey == null
                ? canonicalRoute(method, path) : suppliedRouteKey;
        boolean stateChanging = routeKey != null && registry.bindingContracts().stream()
                .anyMatch(binding -> routeKey.equals(binding.routeContractKey())
                        && "ACTION".equals(binding.routeKind()));
        trustedProductEvidence(request, routeKey, stateChanging);
        if (staleExpectedRevision) {
            request.removeHeader(PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER);
            request.addHeader(PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                    "psr-" + "f".repeat(64));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private String canonicalRoute(String method, String path) {
        return registry.bindingContracts().stream()
                .filter(binding -> method.equals(binding.method()))
                .filter(binding -> matchesTemplate(binding.servicePath(), path))
                .map(PlatformCanaryPepRegistry.BindingContract::routeContractKey)
                .sorted()
                .findFirst().orElse(null);
    }

    private boolean matchesTemplate(String template, String path) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\{[A-Za-z][A-Za-z0-9]*}").matcher(template);
        StringBuilder expression = new StringBuilder("^");
        int offset = 0;
        while (matcher.find()) {
            expression.append(java.util.regex.Pattern.quote(
                    template.substring(offset, matcher.start()))).append("[^/]+");
            offset = matcher.end();
        }
        expression.append(java.util.regex.Pattern.quote(template.substring(offset))).append('$');
        return path.matches(expression.toString());
    }

    private void trustedProductEvidence(
            MockHttpServletRequest request, String routeKey, boolean stateChanging) {
        request.addHeader(PlatformSecurityFilter.ROLLOUT_STATE_HEADER, "110");
        request.addHeader(PlatformSecurityFilter.ROLLOUT_REVISION_HEADER,
                "rollout-" + "0123456789abcdef".repeat(4));
        request.addHeader(PlatformSecurityFilter.ROLLOUT_COHORT_HEADER, "full");
        if (routeKey == null) return;
        String revision = "psr-" + "0123456789abcdef".repeat(4);
        request.addHeader(PlatformSecurityFilter.ROUTE_CONTRACT_HEADER, routeKey);
        request.addHeader(PlatformSecurityFilter.CURRENT_DECISION_REVISION_HEADER, revision);
        request.addHeader(PlatformSecurityFilter.CURRENT_REVALIDATE_AT_HEADER,
                "2030-01-01T00:00:00Z");
        request.addHeader(PlatformSecurityFilter.CONTEXT_HEADER, "product.test");
        request.addHeader(PlatformSecurityFilter.SCOPE_HEADER, "scope-test-7");
        if (stateChanging) {
            request.addHeader(PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER, revision);
        }
    }

    private static Stream<Arguments> canaryCases() {
        return Stream.of(
                Arguments.of("PS-C001", "GET", "/v1/communications", 200),
                Arguments.of("PS-C001", "GET", "/v1/admin/announcements", 403),
                Arguments.of("PS-C001", "GET", "/v1/services/catalog", 403),
                Arguments.of("PS-C002", "GET", "/v1/admin/announcements", 200),
                Arguments.of("PS-C002", "GET", "/v1/communications", 403),
                Arguments.of("PS-C003", "GET", "/v1/admin/announcements", 403),
                Arguments.of("PS-C003", "POST", "/v1/admin/announcements", 403),
                Arguments.of("PS-C004", "GET", "/v1/admin/announcements", 403),
                Arguments.of("PS-C005", "GET", "/v1/services/requests", 200),
                Arguments.of("PS-C005", "GET", "/v1/admin/services/catalog", 403),
                Arguments.of("PS-C006", "GET", "/v1/admin/services/catalog", 200),
                Arguments.of("PS-C006", "GET", "/v1/admin/services/requests", 403),
                Arguments.of("PS-C007", "GET", "/v1/admin/services/requests", 200),
                Arguments.of("PS-C007", "GET", "/v1/admin/services/catalog", 403),
                Arguments.of("PS-C008", "GET", "/v1/admin/services/catalog", 200),
                Arguments.of("PS-C008", "GET", "/v1/admin/services/requests", 200),
                Arguments.of("PS-C008", "GET", "/v1/services/requests", 403),
                Arguments.of("PS-C009", "PUT", "/v1/communications/91/reader-state", 200),
                Arguments.of("PS-C009", "POST", "/v1/communications/91/events/action", 200),
                Arguments.of("PS-C009", "POST", "/v1/communications/91/events/delete", 403),
                Arguments.of("PS-C009", "POST", "/v1/admin/announcements", 200),
                Arguments.of("PS-C009", "PUT", "/v1/admin/announcements/91", 200),
                Arguments.of("PS-C009", "POST", "/v1/admin/announcements/91/publish", 403),
                Arguments.of("PS-C010", "POST", "/v1/admin/announcements/91/publish", 200),
                Arguments.of("PS-C010", "POST", "/v1/admin/announcements/91/archive", 200),
                Arguments.of("PS-C010", "POST", "/v1/admin/announcements", 403),
                Arguments.of("PS-C011", "POST", "/v1/admin/services/catalog", 200),
                Arguments.of("PS-C011", "PUT", "/v1/admin/services/catalog/service.one", 200),
                Arguments.of("PS-C011", "POST",
                        "/v1/admin/services/requests/00000000-0000-0000-0000-000000000001/transition",
                        403),
                Arguments.of("PS-C012", "POST",
                        "/v1/admin/services/requests/00000000-0000-0000-0000-000000000001/transition",
                        200),
                Arguments.of("PS-C012", "POST", "/v1/admin/services/catalog", 403));
    }

    private record FixtureEvidence(
            Set<String> permissions,
            String resourceRoles,
            String supportSessionId,
            Set<String> supportScopes,
            boolean providerIdentity) {

        static FixtureEvidence from(
                String testId,
                PilotAuthorizationFixtureAdapter adapter,
                PlatformCanaryPepRegistry registry) throws Exception {
            PilotAuthorizationFixtureAdapter.PlatformPepFixture fixture = adapter.project(testId);
            if (!"CANARY".equals(fixture.group()) || !testId.matches("PS-C0(0[1-9]|1[0-2])")) {
                throw new IllegalArgumentException("Only generated PS-C001 through PS-C012 are allowed.");
            }
            Set<String> permissions = new LinkedHashSet<>();
            Set<String> resourceRoles = new LinkedHashSet<>();
            Set<String> supportScopes = new LinkedHashSet<>();
            String supportSession = null;
            boolean providerIdentity = false;
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            for (PilotAuthorizationFixtureAdapter.SourceRecord source : fixture.composition()) {
                if ("PROVIDER_ROLE".equals(source.reference())) providerIdentity = true;
                if (source.canonicalJson() == null) continue;
                JsonNode component = mapper.readTree(source.canonicalJson());
                component.path("appEntitlements").forEach(value ->
                        permissions.add(value.asText()));
                component.path("capabilityContractKeys").forEach(value ->
                        permissions.add(registry.capabilityCode(value.asText())));
                JsonNode responsibility = component.path("responsibility");
                if (responsibility.isObject()) {
                    resourceRoles.add(responsibility.path("code").asText()
                            + "@" + responsibility.path("resourceSetKey").asText());
                }
                String supportRef = component.path("supportSessionRef").asText();
                if (!supportRef.isBlank()) {
                    supportSession = supportRef;
                    JsonNode support = mapper.readTree(adapter.source(supportRef).canonicalJson());
                    supportScopes.add(support.path("supportScope").asText());
                }
            }
            return new FixtureEvidence(
                    Set.copyOf(permissions),
                    String.join(",", resourceRoles),
                    supportSession,
                    Set.copyOf(supportScopes),
                    providerIdentity);
        }

        FixtureEvidence withPermissions(Set<String> values) {
            return new FixtureEvidence(
                    Set.copyOf(values), resourceRoles, supportSessionId, supportScopes,
                    providerIdentity);
        }

        FixtureEvidence withResourceRoles(String values) {
            return new FixtureEvidence(
                    permissions, values, supportSessionId, supportScopes, providerIdentity);
        }

        FixtureEvidence withSupport(String sessionId, Set<String> scopes) {
            return new FixtureEvidence(
                    permissions, resourceRoles, sessionId, Set.copyOf(scopes),
                    providerIdentity);
        }
    }
}
