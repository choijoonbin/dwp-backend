package com.dwp.services.messaging.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.messaging.api.MessagingController;
import com.dwp.services.messaging.collaboration.ConversationMembershipRepository;
import com.dwp.services.messaging.domain.MessagingDtos;
import com.dwp.services.messaging.domain.MessagingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingProductSurfacePepPostgresIntegrationTest {

    private static final long TENANT_ID = 96_101;
    private static final long OTHER_TENANT_ID = 96_102;
    private static final long ACTOR_ID = 86_101;
    private static final String SERVICE_TOKEN = "messaging-owner-service-token";
    private static final String CURRENT_REVISION = "psr-" + "0123456789abcdef".repeat(4);
    private static final String STALE_REVISION = "psr-" + "f".repeat(64);
    private static final String ROLLOUT_REVISION =
            "rollout-" + "0123456789abcdef".repeat(4);
    private static final String CONTEXT_KEY = "psc-" + "a".repeat(64);

    private static JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MessagingProductSurfaceContract contract =
            new MessagingProductSurfaceContract();

    private MessagingService service;
    private MockMvc mvc;
    private UUID conversationId;

    @BeforeAll
    static void migrateDatabase() {
        String url = System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL");
        String username = System.getenv().getOrDefault(
                "DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault(
                "DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id IN (?, ?)",
                TENANT_ID, OTHER_TENANT_ID);
        jdbc.update("DELETE FROM msg_people_snapshot WHERE tenant_id IN (?, ?)",
                TENANT_ID, OTHER_TENANT_ID);

        person(TENANT_ID, ACTOR_ID);
        conversationId = conversation(TENANT_ID);
        membership(TENANT_ID, conversationId, ACTOR_ID);

        service = mock(MessagingService.class);
        when(service.home()).thenReturn(new MessagingDtos.HomeResponse(
                OffsetDateTime.parse("2026-08-28T00:00:00Z"),
                new MessagingDtos.HomeMetrics(0, 0, 0, 0, 0),
                List.of(), List.of(), List.of()));
        when(service.messages(eq(conversationId), eq(null), eq(50)))
                .thenReturn(new MessagingDtos.MessagePage(List.of(), false, null));

        mvc = ownerMvc(new ConversationMembershipRepository(jdbc));
    }

    @Test
    void crossTenantConversationScopeFailsClosedAtMessagingOwnerPep() throws Exception {
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.GET,
                publicConversationMessagesPath(),
                "APP.MESSAGING:VIEW",
                OTHER_TENANT_ID,
                ACTOR_ID,
                canonicalScope(OTHER_TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL");

        mvc.perform(request).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void canonicalOpaqueScopeEscapeFailsClosedAtMessagingOwnerPep() throws Exception {
        String escapedScope = ProductSurfaceScopeKey.key(
                TENANT_ID,
                ACTOR_ID,
                MessagingProductSurfaceContract.PRODUCT_KEY,
                "messaging.admin",
                "SELF",
                "SELF");
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.GET,
                publicConversationMessagesPath(),
                "APP.MESSAGING:VIEW",
                TENANT_ID,
                ACTOR_ID,
                escapedScope,
                CURRENT_REVISION,
                "NORMAL");

        mvc.perform(request).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void staleAuthorityRevisionFailsClosedBeforeMessagingAction() throws Exception {
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.POST,
                publicConversationMessagesPath(),
                "APP.MESSAGING:CREATE",
                TENANT_ID,
                ACTOR_ID,
                canonicalActionScope(TENANT_ID, ACTOR_ID),
                STALE_REVISION,
                "NORMAL");

        mvc.perform(request).andExpect(status().isConflict());

        verifyNoInteractions(service);
    }

    @Test
    void normalAndProviderSupportModesCannotBecomeConfusedDeputiesAtMessagingOwnerPep()
            throws Exception {
        MockHttpServletRequestBuilder normalWithSupportSession = exactRequest(
                HttpMethod.GET,
                "/api/messaging/v1/home",
                "APP.MESSAGING:VIEW",
                TENANT_ID,
                ACTOR_ID,
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL")
                .header("X-DWP-Support-Session-ID", "support-session-1");
        MockHttpServletRequestBuilder supportWithNormalCapability = exactRequest(
                HttpMethod.GET,
                "/api/messaging/v1/home",
                "APP.MESSAGING:VIEW",
                TENANT_ID,
                ACTOR_ID,
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "PROVIDER_SUPPORT");

        mvc.perform(normalWithSupportSession).andExpect(status().isForbidden());
        mvc.perform(supportWithNormalCapability).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void spoofedInternalHeadersCannotBypassMessagingGatewayServiceIdentity() throws Exception {
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.GET,
                "/api/messaging/v1/home",
                "APP.MESSAGING:VIEW",
                TENANT_ID,
                ACTOR_ID,
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL")
                .with(requestContext -> {
                    requestContext.removeHeader("X-DWP-Service-Token");
                    requestContext.addHeader(
                            "X-DWP-Service-Token", "spoofed-service-token");
                    return requestContext;
                });

        mvc.perform(request).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void pageDataAndActionBindingsReachMessagingOwnerOnlyWithExactEvidence() throws Exception {
        mvc.perform(exactRequest(
                        HttpMethod.GET,
                        "/api/messaging/v1/home",
                        "APP.MESSAGING:VIEW",
                        TENANT_ID,
                        ACTOR_ID,
                        canonicalScope(TENANT_ID, ACTOR_ID),
                        CURRENT_REVISION,
                        "NORMAL"))
                .andExpect(status().isOk());
        mvc.perform(exactRequest(
                        HttpMethod.GET,
                        publicConversationMessagesPath(),
                        "APP.MESSAGING:VIEW",
                        TENANT_ID,
                        ACTOR_ID,
                        canonicalScope(TENANT_ID, ACTOR_ID),
                        CURRENT_REVISION,
                        "NORMAL"))
                .andExpect(status().isOk());
        mvc.perform(exactRequest(
                        HttpMethod.POST,
                        publicConversationMessagesPath(),
                        "APP.MESSAGING:CREATE",
                        TENANT_ID,
                        ACTOR_ID,
                        canonicalActionScope(TENANT_ID, ACTOR_ID),
                        CURRENT_REVISION,
                        "NORMAL"))
                .andExpect(status().isOk());

        verify(service).home();
        verify(service).messages(conversationId, null, 50);
        verify(service).sendMessage(eq(conversationId), any(), eq(null));
    }

    @Test
    void draftCandidateDoesNotActivateExactPepAtBaselineRollout() throws Exception {
        MockHttpServletRequestBuilder request = baseRequest(
                HttpMethod.GET,
                "/api/messaging/v1/home",
                "APP.MESSAGING:VIEW",
                TENANT_ID,
                ACTOR_ID)
                .header("X-DWP-Rollout-State", "100")
                .header("X-DWP-Rollout-Revision", ROLLOUT_REVISION)
                .header("X-DWP-Rollout-Cohort", "baseline");

        mvc.perform(request).andExpect(status().isOk());

        verify(service).home();
    }

    @Test
    void elevatedWorkModeDeclaredByDraftContractReachesMessagingOwner() throws Exception {
        mvc.perform(exactRequest(
                        HttpMethod.GET,
                        "/api/messaging/v1/home",
                        "APP.MESSAGING:VIEW",
                        TENANT_ID,
                        ACTOR_ID,
                        canonicalScope(TENANT_ID, ACTOR_ID),
                        CURRENT_REVISION,
                        "ELEVATED"))
                .andExpect(status().isOk());

        verify(service).home();
    }

    @Test
    void scopeRevalidationFailureReturnsAuthorityUnavailableWithoutOwnerCall() throws Exception {
        ConversationMembershipRepository unavailableMemberships =
                mock(ConversationMembershipRepository.class);
        when(unavailableMemberships.conversationAccess(
                TENANT_ID, conversationId, ACTOR_ID))
                .thenThrow(new IllegalStateException("database unavailable"));
        mvc = ownerMvc(unavailableMemberships);

        mvc.perform(exactRequest(
                        HttpMethod.GET,
                        publicConversationMessagesPath(),
                        "APP.MESSAGING:VIEW",
                        TENANT_ID,
                        ACTOR_ID,
                        canonicalScope(TENANT_ID, ACTOR_ID),
                        CURRENT_REVISION,
                        "NORMAL"))
                .andExpect(status().isServiceUnavailable());

        verifyNoInteractions(service);
    }

    @Test
    void malformedConversationCandidateCannotBypassMessagingOwnerPep() throws Exception {
        mvc = ownerMvc(new ConversationMembershipRepository(jdbc), false);
        MockHttpServletRequestBuilder request = baseRequest(
                HttpMethod.GET,
                "/v1/conversations/not-a-uuid/messages",
                "APP.MESSAGING:VIEW",
                TENANT_ID,
                ACTOR_ID)
                .header("X-DWP-Rollout-State", "110")
                .header("X-DWP-Rollout-Revision", ROLLOUT_REVISION)
                .header("X-DWP-Rollout-Cohort", "full")
                .header("X-DWP-Route-Contract-Key",
                        MessagingProductSurfaceContract.CONVERSATION_MESSAGES_DATA_ROUTE)
                .header("X-DWP-Current-Decision-Revision", CURRENT_REVISION)
                .header("X-DWP-Current-Revalidate-At", "2099-01-01T00:00:00Z")
                .header("X-DWP-Context-Key", CONTEXT_KEY)
                .header("X-DWP-Context-Scope-Key", canonicalScope(TENANT_ID, ACTOR_ID))
                .header("X-DWP-Active-Access-Mode", "NORMAL");

        mvc.perform(request).andExpect(status().isServiceUnavailable());

        verifyNoInteractions(service);
    }

    @Test
    void duplicateTrustedHeaderFailsClosedBeforeMessagingControllerOrRepository()
            throws Exception {
        ConversationMembershipRepository memberships = mock(ConversationMembershipRepository.class);
        mvc = ownerMvc(memberships);
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.GET,
                publicConversationMessagesPath(),
                "APP.MESSAGING:VIEW",
                TENANT_ID,
                ACTOR_ID,
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL")
                .header("X-DWP-Route-Contract-Key",
                        MessagingProductSurfaceContract.CONVERSATION_MESSAGES_DATA_ROUTE);

        mvc.perform(request).andExpect(status().isServiceUnavailable());

        verifyNoInteractions(service, memberships);
    }

    @Test
    void whitespacePaddedTrustedHeaderFailsClosedBeforeMessagingControllerOrRepository()
            throws Exception {
        ConversationMembershipRepository memberships = mock(ConversationMembershipRepository.class);
        mvc = ownerMvc(memberships);
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.GET,
                publicConversationMessagesPath(),
                "APP.MESSAGING:VIEW",
                TENANT_ID,
                ACTOR_ID,
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL")
                .with(requestContext -> {
                    requestContext.removeHeader("X-DWP-Current-Decision-Revision");
                    requestContext.addHeader(
                            "X-DWP-Current-Decision-Revision", " " + CURRENT_REVISION);
                    return requestContext;
                });

        mvc.perform(request).andExpect(status().isServiceUnavailable());

        verifyNoInteractions(service, memberships);
    }

    @Test
    void invalidContextKeyFailsClosedBeforeMessagingControllerOrRepository() throws Exception {
        ConversationMembershipRepository memberships = mock(ConversationMembershipRepository.class);
        mvc = ownerMvc(memberships);
        MockHttpServletRequestBuilder request = exactRequest(
                HttpMethod.GET,
                publicConversationMessagesPath(),
                "APP.MESSAGING:VIEW",
                TENANT_ID,
                ACTOR_ID,
                canonicalScope(TENANT_ID, ACTOR_ID),
                CURRENT_REVISION,
                "NORMAL")
                .with(requestContext -> {
                    requestContext.removeHeader("X-DWP-Context-Key");
                    requestContext.addHeader("X-DWP-Context-Key", "psc-" + "A".repeat(64));
                    return requestContext;
                });

        mvc.perform(request).andExpect(status().isServiceUnavailable());

        verifyNoInteractions(service, memberships);
    }

    private MockHttpServletRequestBuilder exactRequest(
            HttpMethod method,
            String publicPath,
            String permission,
            long tenantId,
            long actorId,
            String scope,
            String expectedRevision,
            String accessMode) {
        MessagingProductSurfaceContract.ResolvedBinding binding = contract
                .resolvePublic(method.name(), publicPath)
                .orElseThrow();
        MockHttpServletRequestBuilder request = baseRequest(
                method, publicPath, permission, tenantId, actorId)
                .header("X-DWP-Rollout-State", "110")
                .header("X-DWP-Rollout-Revision", ROLLOUT_REVISION)
                .header("X-DWP-Rollout-Cohort", "full")
                .header("X-DWP-Route-Contract-Key", binding.routeContractKey())
                .header("X-DWP-Current-Decision-Revision", CURRENT_REVISION)
                .header("X-DWP-Current-Revalidate-At", "2099-01-01T00:00:00Z")
                .header("X-DWP-Context-Key", CONTEXT_KEY)
                .header("X-DWP-Context-Scope-Key", scope)
                .header("X-DWP-Active-Access-Mode", accessMode);
        if (method == HttpMethod.POST) {
            request.header("X-DWP-Expected-Decision-Revision", expectedRevision)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "body": "Exact Messaging action",
                              "idempotencyKey": "7345f4bc-ef63-4ddb-a595-c9d82f55854e"
                            }
                            """);
        }
        return request;
    }

    private MockHttpServletRequestBuilder baseRequest(
            HttpMethod method,
            String publicPath,
            String permission,
            long tenantId,
            long actorId) {
        return MockMvcRequestBuilders.request(method, publicPath)
                .header("X-DWP-Service-Token", SERVICE_TOKEN)
                .header("X-DWP-User-ID", Long.toString(actorId))
                .header("X-DWP-Tenant-ID", Long.toString(tenantId))
                .header("X-DWP-Roles", "WORKSPACE_MEMBER")
                .header("X-DWP-Permissions", permission);
    }

    private Filter publicGatewayRoute() {
        return (request, response, chain) -> {
            HttpServletRequest publicRequest = (HttpServletRequest) request;
            MessagingProductSurfaceContract.ResolvedBinding binding = contract.resolvePublic(
                    publicRequest.getMethod(), publicRequest.getRequestURI()).orElseThrow();
            HttpServletRequest ownerRequest = new HttpServletRequestWrapper(publicRequest) {
                @Override
                public String getRequestURI() {
                    return binding.ownerPath();
                }

                @Override
                public String getServletPath() {
                    return binding.ownerPath();
                }
            };
            chain.doFilter(ownerRequest, response);
        };
    }

    private MockMvc ownerMvc(ConversationMembershipRepository memberships) {
        return ownerMvc(memberships, true);
    }

    private MockMvc ownerMvc(
            ConversationMembershipRepository memberships, boolean throughPublicGateway) {
        MessagingProductSurfaceScopeGuard scopeGuard =
                new MessagingProductSurfaceScopeGuard(memberships);
        MessagingSecurityFilter identityFilter =
                new MessagingSecurityFilter(SERVICE_TOKEN, objectMapper);
        MessagingProductSurfacePepFilter pepFilter = new MessagingProductSurfacePepFilter(
                true, contract, scopeGuard, objectMapper);
        var builder = MockMvcBuilders.standaloneSetup(new MessagingController(service));
        return (throughPublicGateway
                ? builder.addFilters(publicGatewayRoute(), identityFilter, pepFilter)
                : builder.addFilters(identityFilter, pepFilter)).build();
    }

    private String publicConversationMessagesPath() {
        return "/api/messaging/v1/conversations/" + conversationId + "/messages";
    }

    private String canonicalScope(long tenantId, long actorId) {
        return ProductSurfaceScopeKey.key(
                tenantId,
                actorId,
                MessagingProductSurfaceContract.PRODUCT_KEY,
                MessagingProductSurfaceContract.SURFACE_KEY,
                "SELF",
                "SELF");
    }

    private String canonicalActionScope(long tenantId, long actorId) {
        return ProductSurfaceScopeKey.key(
                tenantId,
                actorId,
                MessagingProductSurfaceContract.PRODUCT_KEY,
                MessagingProductSurfaceContract.SURFACE_KEY,
                "CONVERSATION_MEMBERSHIP",
                "TARGET_POPULATION");
    }

    private void person(long tenantId, long userId) {
        jdbc.update("""
                INSERT INTO msg_people_snapshot (
                    tenant_id, user_id, person_public_id, email_address,
                    display_name, job_title, organization_name,
                    presence_state, lifecycle_state)
                VALUES (?, ?, ?, ?, 'PEP actor', 'Security engineer', 'DWP',
                        'AVAILABLE', 'ACTIVE')
                """, tenantId, userId, UUID.randomUUID(), "pep-actor@dwp.test");
    }

    private UUID conversation(long tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'CHANNEL', 'PEP evidence', 'PRIVATE',
                        'INTERNAL', 'ACTIVE', ?, ?)
                """, id, tenantId, "pep:" + id, ACTOR_ID, ACTOR_ID);
        return id;
    }

    private void membership(long tenantId, UUID id, long userId) {
        jdbc.update("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, person_public_id,
                    member_role, membership_source, lifecycle_state,
                    history_start_sequence, membership_started_at,
                    created_by, updated_by)
                SELECT ?, ?, person.user_id, person.person_public_id,
                       'MEMBER', 'DIRECT', 'ACTIVE', 1, CURRENT_TIMESTAMP, ?, ?
                  FROM msg_people_snapshot person
                 WHERE person.tenant_id = ? AND person.user_id = ?
                """, tenantId, id, userId, userId, tenantId, userId);
    }
}
