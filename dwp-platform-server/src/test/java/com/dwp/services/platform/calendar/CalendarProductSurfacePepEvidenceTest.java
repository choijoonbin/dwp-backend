package com.dwp.services.platform.calendar;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.platform.security.PlatformApprovalsPepRegistry;
import com.dwp.services.platform.security.PlatformCanaryPepRegistry;
import com.dwp.services.platform.security.PlatformSecurityFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CalendarProductSurfacePepEvidenceTest {

    private static final long TENANT_ID = 7L;
    private static final long ACTOR_ID = 101L;
    private static final String CURRENT_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    private static final String ROLLOUT_REVISION =
            "rollout-" + "0123456789abcdef".repeat(4);
    private static final String CONTEXT_KEY = "psc-" + "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CalendarService service = mock(CalendarService.class);
    private final CalendarProductSurfaceContract contract =
            new CalendarProductSurfaceContract();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service);
        mvc = mockMvc(true);
    }

    private MockMvc mockMvc(boolean enabled) {
        PlatformSecurityFilter platformSecurity = new PlatformSecurityFilter(
                "trusted",
                "runtime",
                false,
                objectMapper,
                new PlatformCanaryPepRegistry(objectMapper),
                new PlatformApprovalsPepRegistry(objectMapper));
        CalendarProductSurfacePepFilter calendarPep = new CalendarProductSurfacePepFilter(
                enabled,
                contract,
                new CalendarProductSurfaceAccessPolicy(),
                objectMapper);
        return MockMvcBuilders.standaloneSetup(new CalendarController(service))
                .addFilters(platformSecurity, calendarPep)
                .build();
    }

    @Test
    void crossTenantScopeCannotReachCalendarPublicRoute() throws Exception {
        mvc.perform(exactPage(scope(TENANT_ID + 1, ACTOR_ID, "SELF")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void canonicalOpaqueScopeEscapeCannotReachCalendarPublicRoute() throws Exception {
        String escaped = ProductSurfaceScopeKey.key(
                TENANT_ID,
                ACTOR_ID,
                CalendarProductSurfaceContract.PRODUCT_ID,
                "calendar.management",
                "RS_CALENDAR",
                "RESOURCE_SET");

        mvc.perform(exactPage(escaped))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void springHeadSemanticsCannotBypassCalendarOwnerPep() throws Exception {
        mvc.perform(exact(
                        head("/v1/calendar/home"),
                        CalendarProductSurfaceContract.HOME_PAGE_ROUTE,
                        "APP.CALENDAR:VIEW",
                        scope(TENANT_ID, ACTOR_ID, "SELF")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void springMatrixParameterSemanticsCannotBypassCalendarOwnerPep() throws Exception {
        mvc.perform(exact(
                        get("/v1/calendar/home;source=spoof"),
                        CalendarProductSurfaceContract.HOME_PAGE_ROUTE,
                        "APP.CALENDAR:VIEW",
                        scope(TENANT_ID, ACTOR_ID, "SELF")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void springPercentEncodedRouteAliasCannotBypassCalendarOwnerPep() throws Exception {
        mvc.perform(exact(
                        get(URI.create("/v1/calendar/%65vents"))
                                .param("from", "2026-08-28T09:00:00+09:00")
                                .param("to", "2026-08-28T18:00:00+09:00"),
                        CalendarProductSurfaceContract.SCHEDULE_DATA_ROUTE,
                        "APP.CALENDAR:VIEW",
                        scope(TENANT_ID, ACTOR_ID, "SELF")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void encodedSlashInsideMatrixValueCannotBypassCalendarOwnerPep() throws Exception {
        mvc.perform(exact(
                        get(URI.create("/v1/calendar;source=%2Fignored/home")),
                        CalendarProductSurfaceContract.HOME_PAGE_ROUTE,
                        "APP.CALENDAR:VIEW",
                        scope(TENANT_ID, ACTOR_ID, "SELF")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void nonCanonicalCalendarIdentityNumbersFailClosed() throws Exception {
        for (String tenantAlias : new String[]{"+7", "007"}) {
            mvc.perform(replaceHeader(
                            exactPage(scope(TENANT_ID, ACTOR_ID, "SELF")),
                            "X-DWP-Tenant-ID",
                            tenantAlias))
                    .andExpect(status().isServiceUnavailable());
        }
        for (String actorAlias : new String[]{"+101", "00101"}) {
            mvc.perform(replaceHeader(
                            exactPage(scope(TENANT_ID, ACTOR_ID, "SELF")),
                            "X-DWP-User-ID",
                            actorAlias))
                    .andExpect(status().isServiceUnavailable());
        }

        verifyNoInteractions(service);
    }

    @Test
    void nonCanonicalCalendarRoleAndPermissionEvidenceFailsClosed() throws Exception {
        for (String roles : new String[]{
                "workspace_member", "WORKSPACE_MEMBER,WORKSPACE_MEMBER", "WORKSPACE_MEMBER,"
        }) {
            mvc.perform(replaceHeader(
                            exactPage(scope(TENANT_ID, ACTOR_ID, "SELF")),
                            "X-DWP-Roles",
                            roles))
                    .andExpect(status().isServiceUnavailable());
        }
        for (String permissions : new String[]{
                "app.calendar:view", "APP.CALENDAR:VIEW,APP.CALENDAR:VIEW",
                "APP.CALENDAR:VIEW,", " APP.CALENDAR:VIEW"
        }) {
            mvc.perform(replaceHeader(
                            exactPage(scope(TENANT_ID, ACTOR_ID, "SELF")),
                            "X-DWP-Permissions",
                            permissions))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(service);
    }

    @Test
    void duplicateCalendarAuthorityHeadersFailClosed() throws Exception {
        mvc.perform(exactPage(scope(TENANT_ID, ACTOR_ID, "SELF"))
                        .header("X-DWP-Tenant-ID", Long.toString(TENANT_ID)))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(exactPage(scope(TENANT_ID, ACTOR_ID, "SELF"))
                        .header("X-DWP-User-ID", Long.toString(ACTOR_ID)))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(exactPage(scope(TENANT_ID, ACTOR_ID, "SELF"))
                        .header("X-DWP-Roles", "WORKSPACE_MEMBER"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(exactPage(scope(TENANT_ID, ACTOR_ID, "SELF"))
                        .header("X-DWP-Permissions", "APP.CALENDAR:VIEW"))
                .andExpect(status().isForbidden());
        mvc.perform(exactPage(scope(TENANT_ID, ACTOR_ID, "SELF"))
                        .header(CalendarProductSurfacePepFilter.CONTEXT_HEADER, CONTEXT_KEY))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(exactPage(scope(TENANT_ID, ACTOR_ID, "SELF"))
                        .header(
                                CalendarProductSurfacePepFilter.ROUTE_CONTRACT_HEADER,
                                CalendarProductSurfaceContract.HOME_PAGE_ROUTE))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void calendarRolloutTransitionPreservesLegacyAndFailsClosedAtExactEnforcement()
            throws Exception {
        mvc = mockMvc(false);
        MockHttpServletRequestBuilder noRollout = exactPage(
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        noRollout.with(request -> {
            request.removeHeader(CalendarProductSurfacePepFilter.ROLLOUT_STATE_HEADER);
            request.removeHeader(CalendarProductSurfacePepFilter.ROLLOUT_REVISION_HEADER);
            request.removeHeader(CalendarProductSurfacePepFilter.ROLLOUT_COHORT_HEADER);
            return request;
        });
        mvc.perform(noRollout).andExpect(status().isOk());
        mvc.perform(withRolloutState("000")).andExpect(status().isOk());
        mvc.perform(withRolloutState("100")).andExpect(status().isOk());
        mvc.perform(withRolloutState("110")).andExpect(status().isServiceUnavailable());
        mvc.perform(withRolloutState("111")).andExpect(status().isServiceUnavailable());

        verify(service, times(3)).home(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void exactCalendarEnforcementRejectsMalformedRolloutAndDecisionEvidence()
            throws Exception {
        mvc.perform(replaceHeader(
                        exactPage(scope(TENANT_ID, ACTOR_ID, "SELF")),
                        CalendarProductSurfacePepFilter.ROLLOUT_REVISION_HEADER,
                        "rollout-invalid"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(exactPage(scope(TENANT_ID, ACTOR_ID, "SELF"))
                        .header(CalendarProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(replaceHeader(
                        exactPage(scope(TENANT_ID, ACTOR_ID, "SELF")),
                        CalendarProductSurfacePepFilter.CURRENT_REVISION_HEADER,
                        "psr-invalid"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(exactPage(scope(TENANT_ID, ACTOR_ID, "SELF"))
                        .header(CalendarProductSurfacePepFilter.CURRENT_REVISION_HEADER,
                                CURRENT_REVISION))
                .andExpect(status().isServiceUnavailable());

        verifyNoInteractions(service);
    }

    @Test
    void staleAuthorityRevisionFailsClosedBeforeCalendarMutation() throws Exception {
        MockHttpServletRequestBuilder request = exact(
                post("/v1/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createEventBody()),
                CalendarProductSurfaceContract.EVENT_CREATE_ACTION_ROUTE,
                "APP.CALENDAR:VIEW,APP.CALENDAR:CREATE",
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        request.header(CalendarProductSurfacePepFilter.EXPECTED_REVISION_HEADER,
                "psr-" + "f".repeat(64));

        mvc.perform(request)
                .andExpect(status().isConflict());

        verify(service, never()).create(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void normalAndSupportModesCannotBorrowEachOthersCalendarAuthority() throws Exception {
        MockHttpServletRequestBuilder providerAsNormal = exactPage(
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        providerAsNormal.with(request -> {
            request.removeHeader("X-DWP-Roles");
            request.addHeader("X-DWP-Roles", "PROVIDER_SUPPORT");
            return request;
        });

        mvc.perform(providerAsNormal)
                .andExpect(status().isForbidden());

        MockHttpServletRequestBuilder workspaceAsProvider = exactPage(
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        workspaceAsProvider.with(request -> {
            request.removeHeader(CalendarProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER);
            request.addHeader(
                    CalendarProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER,
                    "PROVIDER_SUPPORT");
            return request;
        });

        mvc.perform(workspaceAsProvider)
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void supportSessionCannotBorrowNormalCalendarAuthorityAtOwnerPolicy() {
        CalendarProductSurfaceContract.Binding binding = contract.resolveOwner(
                "GET", "/v1/calendar/home").orElseThrow();
        CalendarProductSurfaceAccessPolicy.Decision decision =
                new CalendarProductSurfaceAccessPolicy().authorize(
                        new CalendarProductSurfaceAccessPolicy.Evidence(
                                TENANT_ID,
                                ACTOR_ID,
                                Set.of("WORKSPACE_MEMBER"),
                                true,
                                "NORMAL",
                                CONTEXT_KEY,
                                scope(TENANT_ID, ACTOR_ID, "SELF"),
                                Set.of("APP.CALENDAR:VIEW"),
                                binding));

        assertThat(decision.status())
                .isEqualTo(CalendarProductSurfaceAccessPolicy.Status.DENIED);
        assertThat(decision.reasonCode()).isEqualTo("CALENDAR_ACCESS_MODE_DENIED");
        verifyNoInteractions(service);
    }

    @Test
    void spoofedInternalAuthorityHeadersCannotBypassPlatformServiceIdentity() throws Exception {
        MockHttpServletRequestBuilder request = exactPage(
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        request.with(mockRequest -> {
            mockRequest.removeHeader("X-DWP-Service-Token");
            mockRequest.addHeader("X-DWP-Service-Token", "spoofed");
            return mockRequest;
        });

        mvc.perform(request)
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void pageDataAndActionExecuteThroughPlatformSecurityAndCalendarOwnerPep()
            throws Exception {
        mvc.perform(exactPage(scope(TENANT_ID, ACTOR_ID, "SELF")))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        CalendarProductSurfacePepFilter.RESPONSE_REVISION_HEADER,
                        CURRENT_REVISION));

        MockHttpServletRequestBuilder elevatedData = exact(
                get("/v1/calendar/events")
                        .param("from", "2026-08-28T09:00:00+09:00")
                        .param("to", "2026-08-28T18:00:00+09:00"),
                CalendarProductSurfaceContract.SCHEDULE_DATA_ROUTE,
                "APP.CALENDAR:VIEW",
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        elevatedData.with(request -> {
            request.removeHeader(CalendarProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER);
            request.addHeader(
                    CalendarProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER,
                    "ELEVATED");
            return request;
        });
        mvc.perform(elevatedData)
                .andExpect(status().isOk())
                .andExpect(header().string(
                        CalendarProductSurfacePepFilter.RESPONSE_REVISION_HEADER,
                        CURRENT_REVISION));

        mvc.perform(exact(
                        post("/v1/calendar/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createEventBody()),
                        CalendarProductSurfaceContract.EVENT_CREATE_ACTION_ROUTE,
                        "APP.CALENDAR:VIEW,APP.CALENDAR:CREATE",
                        scope(TENANT_ID, ACTOR_ID, "SELF"))
                        .header(CalendarProductSurfacePepFilter.EXPECTED_REVISION_HEADER,
                                CURRENT_REVISION))
                .andExpect(status().isOk());

        verify(service).home(anyLong(), anyLong(), any(), any(), any(), any());
        verify(service).events(
                anyLong(), anyLong(), any(), any(), any(), any(), any());
        verify(service).create(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void v4DraftContractExposesExactCalendarConsumerMetadata() throws Exception {
        JsonNode bundle = v4DraftArtifact();
        assertThat(bundle.path("bundleKey").asText()).isEqualTo("product-surfaces");
        assertThat(bundle.path("schemaVersion").asInt()).isOne();
        assertThat(bundle.path("version").asInt()).isEqualTo(4);
        assertThat(bundle.path("bundleStatus").asText()).isEqualTo("DRAFT");
        assertThat(bundle.path("checksumAlgorithm").asText()).isEqualTo("SHA-256");
        assertThat(bundle.path("checksum").asText()).matches("[a-f0-9]{64}");

        Map<String, CalendarProductSurfaceContract.BindingContract> runtime =
                contract.bindingContracts().stream().collect(Collectors.toUnmodifiableMap(
                        CalendarProductSurfaceContract.BindingContract::routeContractKey,
                        Function.identity()));
        Map<String, JsonNode> routes = calendarRoutes(bundle);
        assertThat(routes.keySet()).containsExactlyInAnyOrderElementsOf(runtime.keySet());
        runtime.forEach((routeKey, binding) -> assertRoute(routes.get(routeKey), binding));

        JsonNode policy = exactNode(
                bundle.path("accessPolicies"),
                "accessPolicyKey",
                CalendarProductSurfaceContract.ACCESS_POLICY_KEY);
        assertThat(policy.path("productKey").asText())
                .isEqualTo(CalendarProductSurfaceContract.PRODUCT_ID);
        assertThat(policy.path("surfaceKey").asText())
                .isEqualTo(CalendarProductSurfaceContract.SURFACE_KEY);
        assertThat(policy.path("scopeResolver").asText()).isEqualTo("SELF");
        assertThat(policy.path("entitlementExpressionKey").asText())
                .isEqualTo("CALENDAR_WORK_ACCESS_V1");
        assertThat(policy.path("routeContractKeys"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        CalendarProductSurfaceContract.HOME_PAGE_ROUTE,
                        CalendarProductSurfaceContract.SCHEDULE_DATA_ROUTE);

        JsonNode entitlement = exactNode(
                bundle.path("entitlementExpressions"),
                "expressionKey",
                "CALENDAR_WORK_ACCESS_V1");
        assertThat(entitlement.path("expression").path("type").asText())
                .isEqualTo("LEAF");
        assertThat(entitlement.path("expression").path("entitlement").asText())
                .isEqualTo("APP.CALENDAR:VIEW");

        JsonNode capability = exactNode(
                bundle.path("capabilities"),
                "contractKey",
                CalendarProductSurfaceContract.EVENT_CREATE_CAPABILITY_KEY);
        assertThat(capability.path("productKey").asText())
                .isEqualTo(CalendarProductSurfaceContract.PRODUCT_ID);
        assertThat(capability.path("surfaceKey").asText())
                .isEqualTo(CalendarProductSurfaceContract.SURFACE_KEY);
        assertThat(capability.path("scopeResolver").asText()).isEqualTo("SELF");
        assertThat(capability.path("resolvedCapabilityCode").asText())
                .isEqualTo("APP.CALENDAR:CREATE");
        assertThat(capability.path("routeContractKeys"))
                .extracting(JsonNode::asText)
                .containsExactly(CalendarProductSurfaceContract.EVENT_CREATE_ACTION_ROUTE);

        JsonNode predicate = exactNode(
                bundle.path("predicatePolicies"),
                "predicatePolicyKey",
                "predicate.calendar-self.v1");
        assertThat(predicate.path("ownerServiceKey").asText())
                .isEqualTo(CalendarProductSurfaceContract.SERVICE_KEY);
        assertThat(predicate.path("targetBindingKinds"))
                .extracting(JsonNode::asText)
                .containsExactly("SELF");
    }

    private void assertRoute(
            JsonNode route,
            CalendarProductSurfaceContract.BindingContract binding) {
        assertThat(route).isNotNull();
        assertThat(binding.policyId()).isEqualTo("P-CALENDAR");
        assertThat(binding.ownerService()).isEqualTo("dwp-platform-server");
        assertThat(route.path("routeKind").asText())
                .isEqualTo(binding.routeKind().name());
        assertThat(route.path("subject").path("productKey").asText())
                .isEqualTo(binding.productId());
        assertThat(route.path("subject").path("surfaceKey").asText())
                .isEqualTo(binding.surfaceKey());

        JsonNode gateway = route.path("gatewayApiBindings").get(0);
        assertThat(gateway.path("method").asText()).isEqualTo(binding.method());
        assertThat(gateway.path("path").asText()).isEqualTo(binding.gatewayPath());

        JsonNode owner = route.path("servicePepBindings").get(0);
        assertThat(owner.path("serviceKey").asText()).isEqualTo(binding.serviceKey());
        assertThat(owner.path("method").asText()).isEqualTo(binding.method());
        assertThat(owner.path("path").asText()).isEqualTo(binding.servicePath());

        JsonNode profile = route.path("accessProfiles").get(0);
        assertThat(profile.path("readOnly").asBoolean()).isEqualTo(binding.readOnly());
        assertThat(profile.path("activeAccessModes"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("NORMAL", "ELEVATED");
        assertThat(profile.path("targetBindingKinds"))
                .extracting(JsonNode::asText)
                .containsExactly("SELF");
        JsonNode requiredAccess = profile.path("requiredAccess");
        assertThat(requiredAccess.path("type").asText())
                .isEqualTo(binding.accessContractType().name());
        String contractKeyField = binding.accessContractType()
                        == CalendarProductSurfaceContract.AccessContractType.POLICY
                ? "accessPolicyKey" : "capabilityContractKey";
        assertThat(requiredAccess.path(contractKeyField).asText())
                .isEqualTo(binding.accessContractKey());
    }

    private Map<String, JsonNode> calendarRoutes(JsonNode bundle) {
        Map<String, JsonNode> routes = new HashMap<>();
        bundle.path("routes").forEach(route -> {
            JsonNode subject = route.path("subject");
            if (CalendarProductSurfaceContract.PRODUCT_ID.equals(
                    subject.path("productKey").asText())
                    && CalendarProductSurfaceContract.SURFACE_KEY.equals(
                    subject.path("surfaceKey").asText())) {
                routes.put(route.path("routeContractKey").asText(), route);
            }
        });
        return routes;
    }

    private JsonNode exactNode(JsonNode values, String key, String expected) {
        JsonNode match = null;
        for (JsonNode value : values) {
            if (expected.equals(value.path(key).asText())) {
                assertThat(match).as("duplicate %s=%s", key, expected).isNull();
                match = value;
            }
        }
        assertThat(match).as("missing %s=%s", key, expected).isNotNull();
        return match;
    }

    private JsonNode v4DraftArtifact() throws IOException {
        return objectMapper.readTree(Files.readString(contractArtifact(
                "contracts/product-authorization/product-surfaces-v1.bundle-v4.json")));
    }

    private Path contractArtifact(String relativePath) {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException("Product authorization contract artifact is unavailable.");
    }

    private MockHttpServletRequestBuilder exactPage(String scope) {
        return exact(
                get("/v1/calendar/home"),
                CalendarProductSurfaceContract.HOME_PAGE_ROUTE,
                "APP.CALENDAR:VIEW",
                scope);
    }

    private MockHttpServletRequestBuilder withRolloutState(String state) {
        return replaceHeader(
                exactPage(scope(TENANT_ID, ACTOR_ID, "SELF")),
                CalendarProductSurfacePepFilter.ROLLOUT_STATE_HEADER,
                state);
    }

    private MockHttpServletRequestBuilder replaceHeader(
            MockHttpServletRequestBuilder request, String name, String value) {
        return request.with(mockRequest -> {
            mockRequest.removeHeader(name);
            mockRequest.addHeader(name, value);
            return mockRequest;
        });
    }

    private MockHttpServletRequestBuilder exact(
            MockHttpServletRequestBuilder request,
            String routeContractKey,
            String permissions,
            String scope) {
        return request
                .header("X-DWP-Service-Token", "trusted")
                .header("X-DWP-User-ID", Long.toString(ACTOR_ID))
                .header("X-DWP-Tenant-ID", Long.toString(TENANT_ID))
                .header("X-DWP-Roles", "WORKSPACE_MEMBER")
                .header("X-DWP-Permissions", permissions)
                .header(CalendarProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110")
                .header(CalendarProductSurfacePepFilter.ROLLOUT_REVISION_HEADER,
                        ROLLOUT_REVISION)
                .header(CalendarProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full")
                .header(CalendarProductSurfacePepFilter.ROUTE_CONTRACT_HEADER,
                        routeContractKey)
                .header(CalendarProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER, "NORMAL")
                .header(CalendarProductSurfacePepFilter.CURRENT_REVISION_HEADER,
                        CURRENT_REVISION)
                .header(CalendarProductSurfacePepFilter.CURRENT_REVALIDATE_AT_HEADER,
                        "2099-01-01T00:00:00Z")
                .header(CalendarProductSurfacePepFilter.CONTEXT_HEADER, CONTEXT_KEY)
                .header(CalendarProductSurfacePepFilter.SCOPE_HEADER, scope);
    }

    private String scope(long tenantId, long actorId, String source) {
        return ProductSurfaceScopeKey.key(
                tenantId,
                actorId,
                CalendarProductSurfaceContract.PRODUCT_ID,
                CalendarProductSurfaceContract.SURFACE_KEY,
                source,
                "SELF");
    }

    private String createEventBody() {
        return """
                {
                  "title": "PEP protected planning",
                  "type": "MEETING",
                  "startsAt": "2026-08-28T10:00:00+09:00",
                  "endsAt": "2026-08-28T11:00:00+09:00",
                  "timeZone": "Asia/Seoul",
                  "allDay": false,
                  "visibility": "DEFAULT",
                  "recurrence": "NONE",
                  "recurrenceInterval": 1,
                  "responseRequired": false,
                  "attendees": [],
                  "idempotencyKey": "22222222-2222-2222-2222-222222222222"
                }
                """;
    }
}
