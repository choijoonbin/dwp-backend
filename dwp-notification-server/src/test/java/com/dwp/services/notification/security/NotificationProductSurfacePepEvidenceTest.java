package com.dwp.services.notification.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.notification.api.NotificationAppSummaryController;
import com.dwp.services.notification.api.NotificationController;
import com.dwp.services.notification.domain.NotificationAppSummaryService;
import com.dwp.services.notification.domain.NotificationService;
import com.dwp.services.notification.realtime.NotificationStreamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationProductSurfacePepEvidenceTest {

    private static final long TENANT_ID = 97_101;
    private static final long OTHER_TENANT_ID = 97_102;
    private static final long ACTOR_ID = 87_101;
    private static final String SERVICE_TOKEN =
            "notification-owner-service-token-at-least-24";
    private static final String CURRENT_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    private static final String STALE_REVISION = "psr-" + "f".repeat(64);
    private static final String ROLLOUT_REVISION =
            "rollout-" + "0123456789abcdef".repeat(4);
    private static final String CONTEXT_KEY =
            "psc-" + "0123456789abcdef".repeat(4);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final NotificationProductSurfaceContract contract =
            new NotificationProductSurfaceContract();
    private final NotificationService service = mock(NotificationService.class);
    private final NotificationAppSummaryService appSummary =
            mock(NotificationAppSummaryService.class);
    private final NotificationStreamService streamService =
            mock(NotificationStreamService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, appSummary, streamService);
        NotificationSecurityFilter identityFilter = new NotificationSecurityFilter(
                SERVICE_TOKEN, "dwp-gateway", "", "", objectMapper);
        NotificationProductSurfacePepFilter pepFilter =
                new NotificationProductSurfacePepFilter(
                        true,
                        contract,
                        new NotificationProductSurfaceScopeGuard(),
                        objectMapper);
        mvc = MockMvcBuilders.standaloneSetup(
                        new NotificationController(service, streamService),
                        new NotificationAppSummaryController(appSummary))
                .addFilters(publicGatewayRoute(), identityFilter, pepFilter)
                .build();
    }

    @Test
    void crossTenantScopeFailsClosedAtNotificationOwnerPep() throws Exception {
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.GET,
                "/api/notifications/v1/inbox",
                canonicalScope(OTHER_TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL");

        mvc.perform(request).andExpect(status().isForbidden());

        verifyNoInteractions(service, appSummary, streamService);
    }

    @Test
    void canonicalOpaqueScopeEscapeFailsClosedAtNotificationOwnerPep() throws Exception {
        String escapedScope = ProductSurfaceScopeKey.key(
                TENANT_ID,
                ACTOR_ID,
                NotificationProductSurfaceContract.PRODUCT_KEY,
                "notifications.management",
                "SELF",
                "SELF");
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.GET,
                "/api/notifications/v1/inbox",
                escapedScope,
                CURRENT_REVISION,
                "NORMAL");

        mvc.perform(request).andExpect(status().isForbidden());

        verifyNoInteractions(service, appSummary, streamService);
    }

    @Test
    void staleAuthorityRevisionFailsClosedBeforeNotificationAction() throws Exception {
        UUID notificationId = UUID.randomUUID();
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.POST,
                publicReadPath(notificationId),
                canonicalScope(TENANT_ID, ACTOR_ID),
                STALE_REVISION,
                "NORMAL");

        mvc.perform(request).andExpect(status().isConflict());

        verifyNoInteractions(service, appSummary, streamService);
    }

    @Test
    void normalAndSupportModesCannotConfuseNotificationOwnerPep() throws Exception {
        MockHttpServletRequestBuilder supportAsNormal = exactRequest(
                HttpMethod.GET,
                "/api/notifications/v1/inbox",
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL")
                .header(NotificationProductSurfacePepFilter.SUPPORT_SESSION_HEADER,
                        "support-session-1");
        mvc.perform(supportAsNormal).andExpect(status().isForbidden());

        MockHttpServletRequestBuilder normalAsSupport = exactRequest(
                HttpMethod.GET,
                "/api/notifications/v1/inbox",
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "PROVIDER_SUPPORT");
        mvc.perform(normalAsSupport).andExpect(status().isForbidden());

        verifyNoInteractions(service, appSummary, streamService);
    }

    @Test
    void spoofedInternalAuthorityHeadersCannotBypassNotificationServiceIdentity()
            throws Exception {
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.GET,
                "/api/notifications/v1/inbox",
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL");
        request.with(raw -> {
            raw.removeHeader(NotificationSecurityFilter.SERVICE_TOKEN_HEADER);
            raw.addHeader(NotificationSecurityFilter.SERVICE_TOKEN_HEADER,
                    "spoofed-service-token");
            return raw;
        });

        mvc.perform(request).andExpect(status().isUnauthorized());

        verifyNoInteractions(service, appSummary, streamService);
    }

    @Test
    void uppercaseNonLegacyVersionUuidRemainsOwnedAndAuthorizedAtNotificationPep()
            throws Exception {
        String routeParameter = "550E8400-E29B-81D4-A716-446655440000";
        UUID notificationId = UUID.fromString(routeParameter);
        String publicPath = "/api/notifications/v1/inbox/" + routeParameter + "/read";

        mvc.perform(exactRequest(
                        HttpMethod.POST,
                        publicPath,
                        canonicalScope(TENANT_ID, ACTOR_ID),
                        CURRENT_REVISION,
                        "NORMAL"))
                .andExpect(status().isOk());

        verify(service).mutate(
                any(), eq(notificationId), eq("READ"), eq(1L), isNull(), eq("pep-read-1"));
    }

    @Test
    void nonCanonicalUuidCandidateFailsClosedAtNotificationOwnerPep() throws Exception {
        MockHttpServletRequestBuilder request = requestWithClaims(
                HttpMethod.POST,
                "/api/notifications/v1/inbox/not-a-canonical-uuid/read",
                NotificationProductSurfaceContract.READ_ACTION_ROUTE,
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL");

        mvc.perform(request).andExpect(status().isServiceUnavailable());

        verifyNoInteractions(service, appSummary, streamService);
    }

    @Test
    void whitespaceAndDuplicateTrustedClaimsFailClosedAtNotificationOwnerPep()
            throws Exception {
        MockHttpServletRequestBuilder whitespace = exactRequest(
                HttpMethod.GET,
                "/api/notifications/v1/inbox",
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL");
        whitespace.with(raw -> {
            raw.removeHeader(NotificationProductSurfacePepFilter.CURRENT_CONTEXT_HEADER);
            raw.addHeader(NotificationProductSurfacePepFilter.CURRENT_CONTEXT_HEADER,
                    " " + CONTEXT_KEY);
            return raw;
        });
        mvc.perform(whitespace).andExpect(status().isServiceUnavailable());

        MockHttpServletRequestBuilder duplicate = exactRequest(
                HttpMethod.GET,
                "/api/notifications/v1/inbox",
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL");
        duplicate.header(NotificationProductSurfacePepFilter.ROUTE_CONTRACT_HEADER,
                NotificationProductSurfaceContract.CENTER_PAGE_ROUTE);
        mvc.perform(duplicate).andExpect(status().isServiceUnavailable());

        MockHttpServletRequestBuilder invalidContext = exactRequest(
                HttpMethod.GET,
                "/api/notifications/v1/inbox",
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL");
        invalidContext.with(raw -> {
            raw.removeHeader(NotificationProductSurfacePepFilter.CURRENT_CONTEXT_HEADER);
            raw.addHeader(NotificationProductSurfacePepFilter.CURRENT_CONTEXT_HEADER,
                    "ctx-notifications-work");
            return raw;
        });
        mvc.perform(invalidContext).andExpect(status().isServiceUnavailable());

        verifyNoInteractions(service, appSummary, streamService);
    }

    @Test
    void v4DraftPageDataAndActionBindingsReachActualNotificationRoutes() throws Exception {
        mvc.perform(exactRequest(
                        HttpMethod.GET,
                        "/api/notifications/v1/inbox",
                        canonicalScope(TENANT_ID, ACTOR_ID),
                        CURRENT_REVISION,
                        "NORMAL"))
                .andExpect(status().isOk());

        mvc.perform(exactRequest(
                        HttpMethod.GET,
                        "/api/notifications/v1/summary/by-app",
                        canonicalScope(TENANT_ID, ACTOR_ID),
                        CURRENT_REVISION,
                        "ELEVATED"))
                .andExpect(status().isOk());

        UUID notificationId = UUID.randomUUID();
        mvc.perform(exactRequest(
                        HttpMethod.POST,
                        publicReadPath(notificationId),
                        canonicalScope(TENANT_ID, ACTOR_ID),
                        CURRENT_REVISION,
                        "NORMAL"))
                .andExpect(status().isOk());

        verify(service).inbox(
                any(), eq("PRIORITY"), eq(50), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull());
        verify(appSummary).summary(any());
        verify(service).mutate(
                any(), eq(notificationId), eq("READ"), eq(1L), isNull(), eq("pep-read-1"));
        assertThat(contract.descriptors().stream()
                .map(NotificationProductSurfaceContract.BindingDescriptor::routeKind)
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("PAGE", "DATA", "ACTION"));
    }

    private MockHttpServletRequestBuilder exactRequest(
            HttpMethod method,
            String publicPath,
            String scope,
            String expectedRevision,
            String accessMode) {
        NotificationProductSurfaceContract.ResolvedBinding binding = contract
                .resolvePublic(method.name(), publicPath)
                .orElseThrow();
        return requestWithClaims(
                method,
                publicPath,
                binding.routeContractKey(),
                scope,
                expectedRevision,
                accessMode);
    }

    private MockHttpServletRequestBuilder requestWithClaims(
            HttpMethod method,
            String publicPath,
            String routeContractKey,
            String scope,
            String expectedRevision,
            String accessMode) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, publicPath)
                .header(NotificationSecurityFilter.SERVICE_TOKEN_HEADER, SERVICE_TOKEN)
                .header(NotificationSecurityFilter.SOURCE_SERVICE_HEADER, "dwp-gateway")
                .header(NotificationSecurityFilter.USER_HEADER, Long.toString(ACTOR_ID))
                .header(NotificationSecurityFilter.TENANT_HEADER, Long.toString(TENANT_ID))
                .header(NotificationSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER")
                .header(NotificationSecurityFilter.PERMISSIONS_HEADER,
                        "APP.NOTIFICATIONS:VIEW")
                .header(NotificationProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110")
                .header(NotificationProductSurfacePepFilter.ROLLOUT_REVISION_HEADER,
                        ROLLOUT_REVISION)
                .header(NotificationProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full")
                .header(NotificationProductSurfacePepFilter.ROUTE_CONTRACT_HEADER,
                        routeContractKey)
                .header(NotificationProductSurfacePepFilter.CURRENT_DECISION_REVISION_HEADER,
                        CURRENT_REVISION)
                .header(
                        NotificationProductSurfacePepFilter
                                .CURRENT_DECISION_REVALIDATE_AT_HEADER,
                        "2099-01-01T00:00:00Z")
                .header(NotificationProductSurfacePepFilter.CURRENT_CONTEXT_HEADER,
                        CONTEXT_KEY)
                .header(NotificationProductSurfacePepFilter.CURRENT_SCOPE_HEADER, scope)
                .header(NotificationProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER,
                        accessMode);
        if (method == HttpMethod.POST) {
            request.header(
                            NotificationProductSurfacePepFilter
                                    .EXPECTED_DECISION_REVISION_HEADER,
                            expectedRevision)
                    .header("Idempotency-Key", "pep-read-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedVersion\":\"1\"}");
        }
        return request;
    }

    private Filter publicGatewayRoute() {
        return (request, response, chain) -> {
            HttpServletRequest publicRequest = (HttpServletRequest) request;
            NotificationProductSurfaceContract.ResolvedBinding binding = contract
                    .resolvePublic(publicRequest.getMethod(), publicRequest.getRequestURI())
                    .orElse(null);
            String ownerPath = binding == null
                    ? contract.ownerPathForPublicCandidate(
                            publicRequest.getMethod(), publicRequest.getRequestURI())
                            .orElseThrow()
                    : binding.ownerPath();
            HttpServletRequest ownerRequest = new HttpServletRequestWrapper(publicRequest) {
                @Override
                public String getRequestURI() {
                    return ownerPath;
                }

                @Override
                public String getServletPath() {
                    return ownerPath;
                }
            };
            chain.doFilter(ownerRequest, response);
        };
    }

    private String publicReadPath(UUID notificationId) {
        return "/api/notifications/v1/inbox/" + notificationId + "/read";
    }

    private String canonicalScope(long tenantId, long actorId) {
        return ProductSurfaceScopeKey.key(
                tenantId,
                actorId,
                NotificationProductSurfaceContract.PRODUCT_KEY,
                NotificationProductSurfaceContract.SURFACE_KEY,
                "SELF",
                "SELF");
    }
}
