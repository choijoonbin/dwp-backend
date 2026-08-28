package com.dwp.services.platform.mail;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MailProductSurfacePepEvidenceTest {

    private static final long TENANT_ID = 7L;
    private static final long ACTOR_ID = 101L;
    private static final String CURRENT_REVISION = "psr-" + "0123456789abcdef".repeat(4);
    private static final String ROLLOUT_REVISION = "rollout-" + "0123456789abcdef".repeat(4);
    private static final String CONTEXT_KEY = "psc-" + "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MailService service = mock(MailService.class);
    private final MailProductSurfaceContract contract = new MailProductSurfaceContract();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service);
        PlatformSecurityFilter platformSecurity = new PlatformSecurityFilter(
                "trusted",
                "runtime",
                false,
                objectMapper,
                new PlatformCanaryPepRegistry(objectMapper),
                new PlatformApprovalsPepRegistry(objectMapper));
        MailProductSurfacePepFilter mailPep = new MailProductSurfacePepFilter(
                true,
                contract,
                new MailProductSurfaceAccessPolicy(),
                objectMapper);
        mvc = MockMvcBuilders.standaloneSetup(new MailController(service))
                .addFilters(platformSecurity, mailPep)
                .build();
    }

    @Test
    void crossTenantScopeCannotReachMailPublicRoute() throws Exception {
        mvc.perform(exactPage(scope(TENANT_ID + 1, ACTOR_ID, "SELF")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void canonicalOpaqueScopeEscapeCannotReachMailPublicRoute() throws Exception {
        String escaped = ProductSurfaceScopeKey.key(
                TENANT_ID,
                ACTOR_ID,
                MailProductSurfaceContract.PRODUCT_ID,
                "mail.management",
                "RS_MAIL",
                "RESOURCE_SET");

        mvc.perform(exactPage(escaped))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void staleAuthorityRevisionFailsClosedBeforeMailMutation() throws Exception {
        MockHttpServletRequestBuilder request = exact(
                post("/v1/mail/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(composeBody()),
                MailProductSurfaceContract.MESSAGE_CREATE_ACTION_ROUTE,
                "APP.MAIL:VIEW,APP.MAIL:CREATE",
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        request.header(MailProductSurfacePepFilter.EXPECTED_REVISION_HEADER,
                "psr-" + "f".repeat(64));

        mvc.perform(request)
                .andExpect(status().isConflict());

        verify(service, never()).compose(anyLong(), anyLong(), any(), any());
    }

    @Test
    void normalAndSupportModesCannotBorrowEachOthersMailAuthority() throws Exception {
        MockHttpServletRequestBuilder providerAsNormal = exactPage(
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        providerAsNormal.with(request -> {
            request.removeHeader("X-DWP-Roles");
            request.addHeader("X-DWP-Roles", "PROVIDER_SUPPORT");
            request.removeHeader(MailProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER);
            request.addHeader(
                    MailProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER,
                    "PROVIDER_SUPPORT");
            return request;
        });
        mvc.perform(providerAsNormal)
                .andExpect(status().isForbidden());

        MockHttpServletRequestBuilder normalWithSupportSession = exactPage(
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        normalWithSupportSession.header("X-DWP-Support-Session-ID", "support-session-1");
        normalWithSupportSession.header("X-DWP-Support-Scopes", "TENANT_CONFIGURATION_READ");
        normalWithSupportSession.header("X-DWP-Actor-Tenant-ID", "3");
        mvc.perform(normalWithSupportSession)
                .andExpect(status().isForbidden());

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
    void nonCanonicalOwnerEvidenceCannotReachMailPublicRoute() throws Exception {
        MockHttpServletRequestBuilder paddedTenant = exactPage(
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        paddedTenant.with(request -> {
            request.removeHeader("X-DWP-Tenant-ID");
            request.addHeader("X-DWP-Tenant-ID", " " + TENANT_ID);
            return request;
        });
        mvc.perform(paddedTenant)
                .andExpect(status().isUnauthorized());

        MockHttpServletRequestBuilder duplicateContext = exactPage(
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        duplicateContext.header(MailProductSurfacePepFilter.CONTEXT_HEADER, CONTEXT_KEY);
        mvc.perform(duplicateContext)
                .andExpect(status().isServiceUnavailable());

        MockHttpServletRequestBuilder paddedPermission = exactPage(
                scope(TENANT_ID, ACTOR_ID, "SELF"));
        paddedPermission.with(request -> {
            request.removeHeader("X-DWP-Permissions");
            request.addHeader("X-DWP-Permissions", "APP.MAIL:VIEW, APP.MAIL:CREATE");
            return request;
        });
        mvc.perform(paddedPermission)
                .andExpect(status().isServiceUnavailable());

        verifyNoInteractions(service);
    }

    @Test
    void pageDataAndActionExecuteThroughPlatformSecurityAndMailOwnerPep() throws Exception {
        mvc.perform(exactPage(scope(TENANT_ID, ACTOR_ID, "SELF")))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        MailProductSurfacePepFilter.RESPONSE_REVISION_HEADER,
                        CURRENT_REVISION));

        mvc.perform(exact(
                        get("/v1/mail/threads"),
                        MailProductSurfaceContract.THREADS_DATA_ROUTE,
                        "APP.MAIL:VIEW",
                        scope(TENANT_ID, ACTOR_ID, "SELF")))
                .andExpect(status().isOk());

        mvc.perform(exact(
                        post("/v1/mail/messages")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(composeBody()),
                        MailProductSurfaceContract.MESSAGE_CREATE_ACTION_ROUTE,
                        "APP.MAIL:VIEW,APP.MAIL:CREATE",
                        scope(TENANT_ID, ACTOR_ID, "SELF"))
                        .header(MailProductSurfacePepFilter.EXPECTED_REVISION_HEADER,
                                CURRENT_REVISION))
                .andExpect(status().isOk());

        verify(service).home(anyLong(), anyLong());
        verify(service).threads(
                anyLong(), anyLong(), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyInt());
        verify(service).compose(anyLong(), anyLong(), any(), any());
    }

    @Test
    void v4DraftContractExposesExactMailConsumerMetadata() throws Exception {
        JsonNode bundle = v4DraftArtifact();
        assertThat(bundle.path("bundleKey").asText()).isEqualTo("product-surfaces");
        assertThat(bundle.path("version").asInt()).isEqualTo(4);
        assertThat(bundle.path("bundleStatus").asText()).isEqualTo("DRAFT");
        assertThat(bundle.path("checksum").asText()).matches("[a-f0-9]{64}");

        Map<String, MailProductSurfaceContract.BindingContract> runtime =
                contract.bindingContracts().stream().collect(Collectors.toUnmodifiableMap(
                        MailProductSurfaceContract.BindingContract::routeContractKey,
                        Function.identity()));
        Map<String, JsonNode> routes = mailRoutes(bundle);
        assertThat(routes.keySet()).containsExactlyInAnyOrderElementsOf(runtime.keySet());
        runtime.forEach((routeKey, binding) -> assertRoute(routes.get(routeKey), binding));

        JsonNode policy = exactNode(
                bundle.path("accessPolicies"),
                "accessPolicyKey",
                MailProductSurfaceContract.ACCESS_POLICY_KEY);
        assertThat(policy.path("productKey").asText())
                .isEqualTo(MailProductSurfaceContract.PRODUCT_ID);
        assertThat(policy.path("surfaceKey").asText())
                .isEqualTo(MailProductSurfaceContract.SURFACE_KEY);
        assertThat(policy.path("scopeResolver").asText()).isEqualTo("SELF");
        assertThat(policy.path("entitlementExpressionKey").asText())
                .isEqualTo("MAIL_WORK_ACCESS_V1");

        JsonNode capability = exactNode(
                bundle.path("capabilities"),
                "contractKey",
                MailProductSurfaceContract.MESSAGE_CREATE_CAPABILITY_KEY);
        assertThat(capability.path("scopeResolver").asText()).isEqualTo("SELF");
        assertThat(capability.path("resolvedCapabilityCode").asText())
                .isEqualTo("APP.MAIL:CREATE");
        assertThat(capability.path("routeContractKeys"))
                .extracting(JsonNode::asText)
                .containsExactly(MailProductSurfaceContract.MESSAGE_CREATE_ACTION_ROUTE);

        JsonNode predicate = exactNode(
                bundle.path("predicatePolicies"),
                "predicatePolicyKey",
                "predicate.mail-self.v1");
        assertThat(predicate.path("ownerServiceKey").asText())
                .isEqualTo(MailProductSurfaceContract.SERVICE_KEY);
        assertThat(predicate.path("targetBindingKinds"))
                .extracting(JsonNode::asText)
                .containsExactly("SELF");
    }

    private void assertRoute(
            JsonNode route, MailProductSurfaceContract.BindingContract binding) {
        assertThat(route).isNotNull();
        assertThat(binding.policyId()).isEqualTo("P-MAIL");
        assertThat(binding.ownerService()).isEqualTo("dwp-platform-server");
        assertThat(route.path("routeKind").asText()).isEqualTo(binding.routeKind().name());
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
                        == MailProductSurfaceContract.AccessContractType.POLICY
                ? "accessPolicyKey" : "capabilityContractKey";
        assertThat(requiredAccess.path(contractKeyField).asText())
                .isEqualTo(binding.accessContractKey());
    }

    private Map<String, JsonNode> mailRoutes(JsonNode bundle) {
        Map<String, JsonNode> routes = new HashMap<>();
        bundle.path("routes").forEach(route -> {
            JsonNode subject = route.path("subject");
            if (MailProductSurfaceContract.PRODUCT_ID.equals(
                    subject.path("productKey").asText())
                    && MailProductSurfaceContract.SURFACE_KEY.equals(
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
                get("/v1/mail/home"),
                MailProductSurfaceContract.HOME_PAGE_ROUTE,
                "APP.MAIL:VIEW",
                scope);
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
                .header(MailProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110")
                .header(MailProductSurfacePepFilter.ROLLOUT_REVISION_HEADER, ROLLOUT_REVISION)
                .header(MailProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full")
                .header(MailProductSurfacePepFilter.ROUTE_CONTRACT_HEADER, routeContractKey)
                .header(MailProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER, "NORMAL")
                .header(MailProductSurfacePepFilter.CURRENT_REVISION_HEADER, CURRENT_REVISION)
                .header(MailProductSurfacePepFilter.CURRENT_REVALIDATE_AT_HEADER,
                        "2099-01-01T00:00:00Z")
                .header(MailProductSurfacePepFilter.CONTEXT_HEADER, CONTEXT_KEY)
                .header(MailProductSurfacePepFilter.SCOPE_HEADER, scope);
    }

    private String scope(long tenantId, long actorId, String source) {
        return ProductSurfaceScopeKey.key(
                tenantId,
                actorId,
                MailProductSurfaceContract.PRODUCT_ID,
                MailProductSurfaceContract.SURFACE_KEY,
                source,
                "SELF");
    }

    private String composeBody() {
        return """
                {
                  "toEmail": "recipient@example.com",
                  "toName": "Recipient",
                  "subject": "PEP protected mail",
                  "body": "Owner-service evidence",
                  "deliveryMode": "SEND",
                  "idempotencyKey": "22222222-2222-2222-2222-222222222222"
                }
                """;
    }
}
