package com.dwp.services.platform.widgetregistry.internal.security;

import com.dwp.services.platform.provisioning.ProviderProvisioningSecurityFilter;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryRequestBinding.ActualBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.AssertionReplayStore;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.AssertionKind;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.CommandBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.CommandTargetBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.JoseProof;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.JwtIdentity;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.OriginalArtifactBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ProviderAssertionVerifier;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ProviderAssertionClaims;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ReconcileBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ReplayDecision;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ReplayKey;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ServiceTokenClaims;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ServiceTokenVerifier;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.SignedRequestBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.VerificationException;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.VerificationFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class WidgetRegistryInternalSecurityFilterTest {

    private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PATH = "/internal/provider/v1/widget-registry/definitions";
    private static final String COMMAND_PATH = "/internal/provider/v1/widget-registry/commands";
    private static final String COMMAND_ID = "10000000-0000-4000-8000-000000000001";
    private static final String COMPLETION_PATH =
            "/internal/provider/v1/widget-registry/command-completions/" + COMMAND_ID;
    private static final String SEAL_PATH = COMPLETION_PATH + "/seal-not-executed";
    private static final String CORRELATION_ID = "30000000-0000-4000-8000-000000000001";
    private static final String IDEMPOTENCY_KEY = "20000000-0000-4000-8000-000000000001";
    private static final String SERVICE_JTI = "service-jti-0001";
    private static final String ASSERTION_JTI = "assertion-jti-0001";
    private static final String SERVICE_COMPACT = "service.payload.signature";
    private static final String ASSERTION_COMPACT = "assertion.payload.signature";
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String PUBLIC_REQUEST_FINGERPRINT = "b".repeat(64);
    private static final String COMMAND_TARGET_ID =
            "ade48b8b145ad48e418f91090f7cd696c0765dfadf2de68922cbd7e2f29cbb7e";
    private static final String PERMISSION_SET_HASH =
            "e247d97b778b6fe9b89d2642e1f2d712562400644ac98d7d95f03cbf2ca9a2ca";
    private static final String REASON_DIGEST =
            "ae73fc312b46a81604a5e269f75cfee71d8c9bc9c52f81668bf9c631baa5e499";
    private static final List<String> SOD_ARTIFACT_IDS = List.of("sod_approval_0001", "sod_case_0001");

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void allowsOnlyValidDualProofAndExposesSanitizedContextToTheTerminalController() throws Exception {
        SignedRequestBinding binding = signedBinding("GET", PATH, null, null, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness harness = harness(trust);

        harness.mvc().perform(validGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeOperationId")
                        .value("listWidgetRegistryDefinitionsInternal"))
                .andExpect(jsonPath("$.requiredServiceScope").value("widget-registry.read"))
                .andExpect(jsonPath("$.requiredProviderPermission").value("WIDGET_CATALOG_READ"))
                .andExpect(jsonPath("$.serviceTokenJti").value(SERVICE_JTI))
                .andExpect(jsonPath("$.providerAssertionJti").value(ASSERTION_JTI));

        assertThat(harness.controller().terminalCalls()).isEqualTo(1);
        assertThat(trust.replayStore.calls()).isEqualTo(1);
    }

    @Test
    void registersHeadWithTheSameTrustContractAndBindsTheActualHeadMethod() throws Exception {
        SignedRequestBinding binding = signedBinding("HEAD", PATH, null, null, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness harness = harness(trust);

        harness.mvc().perform(head(PATH)
                        .secure(true)
                        .header("Authorization", "Bearer " + SERVICE_COMPACT)
                        .header("X-DWP-Widget-Assertion", ASSERTION_COMPACT)
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isOk());

        assertThat(harness.controller().terminalCalls()).isEqualTo(1);
        assertThat(trust.replayStore.calls()).isEqualTo(1);
    }

    @Test
    void acceptsProviderAssertionUpToSixteenKibButKeepsTheServiceTokenAtEightKib() throws Exception {
        SignedRequestBinding binding = signedBinding("GET", PATH, null, null, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness harness = harness(trust);
        String assertionUnderLimit = "a." + "b".repeat(9000) + ".c";

        harness.mvc().perform(get(PATH)
                        .secure(true)
                        .header("Authorization", "Bearer " + SERVICE_COMPACT)
                        .header("X-DWP-Widget-Assertion", assertionUnderLimit)
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isOk());

        TrustFixture oversizedTrust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness oversizedHarness = harness(oversizedTrust);
        String assertionOverLimit = "a." + "b".repeat(16_384) + ".c";
        oversizedHarness.mvc().perform(get(PATH)
                        .secure(true)
                        .header("Authorization", "Bearer " + SERVICE_COMPACT)
                        .header("X-DWP-Widget-Assertion", assertionOverLimit)
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_DUAL_PROOF_REQUIRED"));
        assertThat(oversizedHarness.controller().terminalCalls()).isZero();

        TrustFixture oversizedServiceTrust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness oversizedServiceHarness = harness(oversizedServiceTrust);
        String serviceOverLimit = "a." + "b".repeat(8192) + ".c";
        oversizedServiceHarness.mvc().perform(get(PATH)
                        .secure(true)
                        .header("Authorization", "Bearer " + serviceOverLimit)
                        .header("X-DWP-Widget-Assertion", ASSERTION_COMPACT)
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isUnauthorized());
        assertThat(oversizedServiceHarness.controller().terminalCalls()).isZero();
    }

    @Test
    void rejectsTheGenericProvisioningTokenBeforeItCanReachTheGenericFilter() throws Exception {
        TrustFixture trust = new TrustFixture(null, null);
        Harness harness = harness(trust);

        harness.mvc().perform(get(PATH)
                        .secure(true)
                        .header("X-DWP-Provisioning-Token", "legacy-static-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode")
                        .value("WIDGET_REGISTRY_PROVISIONING_TOKEN_FORBIDDEN"));

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void failsClosedWhenProductionTrustAdaptersAreNotConfigured() throws Exception {
        TerminalController controller = new TerminalController();
        WidgetRegistryInternalSecurityFilter filter = new WidgetRegistryInternalSecurityFilter(
                objectMapper, null, null, null, CLOCK);
        MockMvc mvc = mvc(controller, filter);

        mvc.perform(validGet())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_TRUST_UNAVAILABLE"));

        assertThat(controller.terminalCalls()).isZero();
    }

    @Test
    void failsClosedWhenPrimaryAndSecondaryTrustAdaptersBothExist() throws Exception {
        SignedRequestBinding binding = signedBinding("GET", PATH, null, null, null, CORRELATION_ID);
        ProviderAssertionClaims assertion =
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    "primaryWidgetServiceVerifier",
                    ServiceTokenVerifier.class,
                    () -> compact -> validServiceToken("widget-registry.read"),
                    definition -> definition.setPrimary(true));
            context.registerBean(
                    "secondaryWidgetServiceVerifier",
                    ServiceTokenVerifier.class,
                    () -> compact -> validServiceToken("widget-registry.read"));
            context.registerBean(
                    ProviderAssertionVerifier.class,
                    () -> (compact, kind) -> assertion);
            context.registerBean(
                    AssertionReplayStore.class,
                    () -> (key, retainUntil) -> ReplayDecision.ACCEPTED);
            context.refresh();

            TerminalController controller = new TerminalController();
            WidgetRegistryInternalSecurityFilter filter = new WidgetRegistryInternalSecurityFilter(
                    objectMapper,
                    context.getBeanProvider(ServiceTokenVerifier.class),
                    context.getBeanProvider(ProviderAssertionVerifier.class),
                    context.getBeanProvider(AssertionReplayStore.class));

            mvc(controller, filter).perform(validGet())
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_TRUST_UNAVAILABLE"));
            assertThat(controller.terminalCalls()).isZero();
        }
    }

    @Test
    void rejectsBrowserAndGatewayAuthorityHeadersBeforeReplay() throws Exception {
        SignedRequestBinding binding = signedBinding("GET", PATH, null, null, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness harness = harness(trust);

        for (String header : List.of(
                "X-DWP-User", "X-DWP-User-ID", "X-DWP-Tenant", "X-DWP-Tenant-ID",
                "X-DWP-Roles", "X-DWP-Permissions", "X-DWP-Resource-Roles",
                "X-DWP-Person-Public-ID", "X-DWP-Group-Refs", "X-DWP-Support-Session-ID",
                "X-DWP-Auth-Session-ID", "X-DWP-Identity-Plane",
                "X-DWP-Legacy-Role-Fallback", "X-DWP-Route-Contract-Key",
                "X-DWP-Rollout-Cohort", "X-DWP-Rollout-Revision", "X-DWP-Rollout-Context",
                "X-DWP-Context-Key", "X-DWP-Context-Revision", "x-dwp-future-authority")) {
            harness.mvc().perform(validGet().header(header, "forged-browser-authority"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode")
                            .value("WIDGET_REGISTRY_AUTHORITY_HEADERS_FORBIDDEN"));
        }

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void wrongAssertionPlaneStillFailsAsAnInvalidDualProofCombination() throws Exception {
        Harness widgetHarness = harness(new TrustFixture(null, null));
        widgetHarness.mvc().perform(validGet()
                        .header("X-DWP-Widget-Reconcile-Assertion", ASSERTION_COMPACT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_DUAL_PROOF_REQUIRED"));

        Harness reconcileHarness = harness(new TrustFixture(null, null));
        reconcileHarness.mvc().perform(validCompletion()
                        .header("X-DWP-Widget-Assertion", ASSERTION_COMPACT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_DUAL_PROOF_REQUIRED"));

        assertThat(widgetHarness.controller().terminalCalls()).isZero();
        assertThat(reconcileHarness.controller().terminalCalls()).isZero();
    }

    @Test
    void unavailableHeaderEnumerationFailsClosedBeforeReplay() throws Exception {
        SignedRequestBinding binding = signedBinding("GET", PATH, null, null, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        WidgetRegistryInternalSecurityFilter filter = new WidgetRegistryInternalSecurityFilter(
                objectMapper,
                trust::verifyServiceToken,
                trust::verifyAssertion,
                trust.replayStore,
                CLOCK);
        MockHttpServletRequest base = new MockHttpServletRequest("GET", PATH);
        base.setSecure(true);
        base.addHeader("Authorization", "Bearer " + SERVICE_COMPACT);
        base.addHeader("X-DWP-Widget-Assertion", ASSERTION_COMPACT);
        base.addHeader("X-Correlation-ID", CORRELATION_ID);
        HttpServletRequestWrapper request = new HttpServletRequestWrapper(base) {
            @Override
            public java.util.Enumeration<String> getHeaderNames() {
                return null;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger downstreamCalls = new AtomicInteger();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                downstreamCalls.incrementAndGet());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.getContentAsByteArray()).path("errorCode").textValue())
                .isEqualTo("WIDGET_REGISTRY_AUTHORITY_HEADERS_FORBIDDEN");
        assertThat(downstreamCalls).hasValue(0);
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void closesUnknownPathsAndUnsupportedMethodsBeforeTrustResolution() throws Exception {
        Harness harness = harness(new TrustFixture(null, null));

        harness.mvc().perform(get(WidgetRegistryInternalRoutes.PREFIX + "/unregistered").secure(true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode")
                        .value("WIDGET_REGISTRY_INTERNAL_ROUTE_NOT_FOUND"));
        harness.mvc().perform(post(PATH).secure(true))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode")
                        .value("WIDGET_REGISTRY_INTERNAL_METHOD_NOT_ALLOWED"));

        assertThat(harness.controller().terminalCalls()).isZero();
    }

    @Test
    void ambiguousCanonicalizationCandidatesCannotUseTheStaticProvisioningTokenInMockMvc() throws Exception {
        Harness harness = harness(new TrustFixture(null, null));
        List<String> ambiguousPaths = List.of(
                "/internal/provider/v1/widget-registry;matrix/definitions",
                "/internal/provider/v1/widget%2Dregistry/definitions",
                "/internal/provider/v1/widget-registry%2Fdefinitions",
                "/internal/provider/v1/widget-registry/definitions;matrix",
                "/internal/provider//v1/widget-registry/definitions",
                "/internal/provider/./v1/widget-registry/definitions",
                "/internal/provider/v1/other/../widget-registry/definitions");

        for (String rawPath : ambiguousPaths) {
            harness.mvc().perform(get("/")
                            .secure(true)
                            .with(request -> {
                                request.setRequestURI(rawPath);
                                return request;
                            })
                            .header("X-DWP-Provisioning-Token", "trusted"))
                    .andExpect(status().is4xxClientError());
        }

        assertThat(harness.controller().terminalCalls()).isZero();
    }

    @ParameterizedTest(name = "invalid service token claim: {0}")
    @EnumSource(ServiceFault.class)
    void rejectsInvalidServiceTokenClaimsBeforeReplay(ServiceFault fault) throws Exception {
        SignedRequestBinding binding = signedBinding("GET", PATH, null, null, null, CORRELATION_ID);
        ServiceTokenClaims serviceToken = corrupt(validServiceToken("widget-registry.read"), fault);
        TrustFixture trust = new TrustFixture(
                serviceToken,
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness harness = harness(trust);

        harness.mvc().perform(validGet())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_SERVICE_TOKEN_INVALID"));

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @ParameterizedTest(name = "invalid Provider assertion claim: {0}")
    @EnumSource(AssertionFault.class)
    void rejectsInvalidAssertionAndRequestBindingClaimsBeforeReplay(AssertionFault fault) throws Exception {
        SignedRequestBinding binding = signedBinding("GET", PATH, null, null, null, CORRELATION_ID);
        ProviderAssertionClaims assertion = corrupt(
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null), fault);
        TrustFixture trust = new TrustFixture(validServiceToken("widget-registry.read"), assertion);
        Harness harness = harness(trust);

        harness.mvc().perform(validGet())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_ASSERTION_INVALID"));

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void rejectsUnknownDuplicateAndMalformedRawQueryBeforeReplay() throws Exception {
        SignedRequestBinding binding = signedBinding(
                "GET", PATH, "page=0", null, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness harness = harness(trust);

        for (String query : List.of("unknown=1", "page=0&page=1", "q=%ZZ", "q=%FF")) {
            harness.mvc().perform(validGet()
                            .with(request -> {
                                request.setQueryString(query);
                                return request;
                            })
                            .header("X-Test-Case", query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode")
                            .value("WIDGET_REGISTRY_REQUEST_BINDING_INVALID"));
        }

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void acceptsOnlyWhenTheSignedDigestMatchesTheExactUtf8RawQueryTarget() throws Exception {
        String query = "page=0&q=%ED%95%9C%EA%B8%80";
        SignedRequestBinding binding = signedBinding(
                "GET", PATH, query, null, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness harness = harness(trust);

        harness.mvc().perform(validGet().with(request -> {
            request.setQueryString(query);
            return request;
        })).andExpect(status().isOk());

        assertThat(harness.controller().terminalCalls()).isEqualTo(1);
        assertThat(trust.replayStore.calls()).isEqualTo(1);
    }

    @Test
    void bindsCommandScopeBodyIdempotencyCorrelationAndJcsIndependentOfObjectOrder() throws Exception {
        String signedBody = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID);
        String transmittedBody = signedBody
                .replace(
                        "{\"targetType\":\"DEFINITION_KEY_HASH\",\"targetId\":\""
                                + COMMAND_TARGET_ID + "\"}",
                        "{\"targetId\":\"" + COMMAND_TARGET_ID
                                + "\",\"targetType\":\"DEFINITION_KEY_HASH\"}");
        SignedRequestBinding binding = signedBinding(
                "POST", COMMAND_PATH, null, signedBody, IDEMPOTENCY_KEY, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.write"),
                validAssertion(
                        binding,
                        "WIDGET_DEFINITION_WRITE",
                        null,
                        "createWidgetDefinition",
                        "CREATE_DEFINITION"));
        Harness harness = harness(trust);

        harness.mvc().perform(validCommand(transmittedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.requiredServiceScope").value("widget-registry.write"))
                .andExpect(jsonPath("$.context.idempotencyKey").value(IDEMPOTENCY_KEY))
                .andExpect(jsonPath("$.body.payload.definitionKey")
                        .value("core.workspace.command-rail"));

        assertThat(harness.controller().terminalCalls()).isEqualTo(1);
    }

    @Test
    void acceptsAnEmptySodSetButRejectsMoreThanThirtyTwoSortedArtifactsBeforeReplay()
            throws Exception {
        String emptyBody = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID).replace(
                "[\"sod_approval_0001\",\"sod_case_0001\"]", "[]");
        SignedRequestBinding emptyBinding = signedBinding(
                "POST", COMMAND_PATH, null, emptyBody, IDEMPOTENCY_KEY, CORRELATION_ID);
        CommandBinding emptyCommand = new CommandBinding(
                COMMAND_ID,
                commandTarget(COMMAND_TARGET_ID),
                0L,
                PUBLIC_REQUEST_FINGERPRINT,
                REASON_DIGEST,
                List.of());
        TrustFixture emptyTrust = commandTrust(emptyBinding, emptyCommand);
        Harness emptyHarness = harness(emptyTrust);

        emptyHarness.mvc().perform(validCommand(emptyBody)).andExpect(status().isOk());
        assertThat(emptyTrust.replayStore.calls()).isEqualTo(1);

        List<String> oversized = java.util.stream.IntStream.range(0, 33)
                .mapToObj(index -> "artifact-%02d".formatted(index))
                .toList();
        String oversizedBody = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID).replace(
                "[\"sod_approval_0001\",\"sod_case_0001\"]",
                objectMapper.writeValueAsString(oversized));

        assertThatThrownBy(() -> preparedCommand(oversizedBody))
                .isInstanceOf(WidgetRegistryRequestBinding.BindingException.class);
    }

    @Test
    void createDefinitionPayloadOwnerMustBelongToTheSignedCurrentOwnerProducts()
            throws Exception {
        List<String> otherOwners = List.of("other.product");
        String body = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID)
                .replace(PERMISSION_SET_HASH, authorityHash(otherOwners));
        SignedRequestBinding binding = signedBinding(
                "POST", COMMAND_PATH, null, body, IDEMPOTENCY_KEY, CORRELATION_ID);
        ProviderAssertionClaims assertion = validAssertion(
                binding,
                "WIDGET_DEFINITION_WRITE",
                null,
                "createWidgetDefinition",
                "CREATE_DEFINITION");
        assertion = copyOperator(
                assertion,
                otherOwners,
                assertion.actorRef(),
                assertion.sessionRef(),
                assertion.providerAuthorityRevision(),
                assertion.authenticatedAt());
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.write"),
                withCommand(assertion, validCommandBinding()));
        Harness harness = harness(trust);

        harness.mvc().perform(validCommand(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_ASSERTION_INVALID"));

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void canonicalizerFollowsOfficialRfc8785NumberAndStringVectors() throws Exception {
        String raw = "{\"text\":\"€\\n\","
                + "\"numbers\":[333333333.33333329,4.50,2e-3,1e-27,4.9406564584124654e-324]}";
        String canonical = "{\"numbers\":[333333333.3333333,4.5,0.002,1e-27,5e-324],"
                + "\"text\":\"€\\n\"}";

        assertThat(WidgetRegistryRequestBinding.canonicalJson(raw.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(canonical.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnsafeIntegerAndNonFiniteBinary64SpellingsBeforeReplay() throws Exception {
        String validBody = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID);
        SignedRequestBinding binding = signedBinding(
                "POST", COMMAND_PATH, null, validBody, IDEMPOTENCY_KEY, CORRELATION_ID);
        TrustFixture trust = commandTrust(binding, validCommandBinding());
        Harness harness = harness(trust);

        for (String number : List.of(
                "9007199254740992",
                "9007199254740993",
                "9007199254740993.0",
                "9.007199254740993e15",
                "1e400",
                "1e-400",
                "NaN",
                "01",
                "-0",
                "-0.0",
                "-0e0")) {
            harness.mvc().perform(validCommand(withExpectedVersion(validBody, number)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode")
                            .value("WIDGET_REGISTRY_REQUEST_BINDING_INVALID"));
        }

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void acceptsJsonSchemaMathematicalIntegerSpellingsForTheEnvelopeVersion() throws Exception {
        for (String spelling : List.of("1.0", "1e0")) {
            String body = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID).replace(
                    "\"schemaVersion\":1,", "\"schemaVersion\":" + spelling + ",");
            assertThat(preparedCommand(body).command()).isNotNull();
        }
    }

    @Test
    void opaqueLengthUsesUnicodeCodePointsInsteadOfUtf16CodeUnits() throws Exception {
        String base = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID);
        String accepted = base.replace("actor_ref_widget_admin", "😀".repeat(128));
        String rejected = base.replace("actor_ref_widget_admin", "😀".repeat(129));

        assertThat(preparedCommand(accepted).command().operatorRef().codePointCount(0, 256))
                .isEqualTo(128);
        assertThatThrownBy(() -> preparedCommand(rejected))
                .isInstanceOf(WidgetRegistryRequestBinding.BindingException.class);
    }

    @Test
    void rejectsDuplicateAfterNullEscapeAliasAndMalformedUtf8BeforeReplay() throws Exception {
        String validBody = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID);
        SignedRequestBinding binding = signedBinding(
                "POST", COMMAND_PATH, null, validBody, IDEMPOTENCY_KEY, CORRELATION_ID);
        TrustFixture trust = commandTrust(binding, validCommandBinding());
        Harness harness = harness(trust);

        for (String member : List.of(
                "\"collision\":null,\"collision\":1",
                "\"\\u0063ollision\":null,\"collision\":1")) {
            harness.mvc().perform(validCommand(withPayloadMember(validBody, member)))
                    .andExpect(status().isBadRequest());
        }
        byte[] malformedUtf8 = validBody.getBytes(StandardCharsets.UTF_8);
        malformedUtf8[validBody.indexOf("Create")] = (byte) 0xc3;
        harness.mvc().perform(validCommand(malformedUtf8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value("WIDGET_REGISTRY_REQUEST_BINDING_INVALID"));

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void rejectsLoneSurrogatesWithoutCollidingWithLiteralQuestionMark() throws Exception {
        String base = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID);
        String literalQuestionMark = base.replace("Create widget definition.", "?");
        SignedRequestBinding binding = signedBinding(
                "POST", COMMAND_PATH, null, literalQuestionMark, IDEMPOTENCY_KEY, CORRELATION_ID);
        TrustFixture trust = commandTrust(binding, new CommandBinding(
                COMMAND_ID,
                commandTarget(COMMAND_TARGET_ID),
                0L,
                PUBLIC_REQUEST_FINGERPRINT,
                reasonDigest("?"),
                SOD_ARTIFACT_IDS));
        Harness harness = harness(trust);

        harness.mvc().perform(validCommand(base.replace("Create widget definition.", "\\ud800")))
                .andExpect(status().isBadRequest());
        harness.mvc().perform(validCommand(withPayloadMember(base, "\"\\ud800\":1")))
                .andExpect(status().isBadRequest());
        harness.mvc().perform(validCommand(base.replace(
                        "Create widget definition.", "Cafe\\u0301")))
                .andExpect(status().isBadRequest());
        harness.mvc().perform(validCommand(literalQuestionMark))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.payload.reasonText").value("?"));

        assertThat(harness.controller().terminalCalls()).isEqualTo(1);
        assertThat(trust.replayStore.calls()).isEqualTo(1);
    }

    @Test
    void commandClaimsMustExactlyMatchTargetVersionReasonAndSodEvidence() throws Exception {
        String body = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID);
        SignedRequestBinding binding = signedBinding(
                "POST", COMMAND_PATH, null, body, IDEMPOTENCY_KEY, CORRELATION_ID);
        CommandBinding valid = validCommandBinding();
        List<CommandBinding> mismatches = List.of(
                new CommandBinding(
                        "10000000-0000-4000-8000-000000000002", valid.target(), valid.expectedVersion(),
                        valid.publicRequestFingerprint(), valid.reasonDigest(), valid.sodArtifactIds()),
                new CommandBinding(
                        valid.commandId(), commandTarget("f".repeat(64)), valid.expectedVersion(),
                        valid.publicRequestFingerprint(), valid.reasonDigest(), valid.sodArtifactIds()),
                new CommandBinding(
                        valid.commandId(), valid.target(), 1L, valid.publicRequestFingerprint(),
                        valid.reasonDigest(), valid.sodArtifactIds()),
                new CommandBinding(
                        valid.commandId(), valid.target(), valid.expectedVersion(), "0".repeat(64),
                        valid.reasonDigest(), valid.sodArtifactIds()),
                new CommandBinding(
                        valid.commandId(), valid.target(), valid.expectedVersion(),
                        valid.publicRequestFingerprint(), "0".repeat(64), valid.sodArtifactIds()),
                new CommandBinding(
                        valid.commandId(), valid.target(), valid.expectedVersion(),
                        valid.publicRequestFingerprint(), valid.reasonDigest(), List.of("sod_other")));

        for (CommandBinding mismatch : mismatches) {
            TrustFixture trust = commandTrust(binding, mismatch);
            Harness harness = harness(trust);
            harness.mvc().perform(validCommand(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_ASSERTION_INVALID"));
            assertThat(harness.controller().terminalCalls()).isZero();
            assertThat(trust.replayStore.calls()).isZero();
        }
    }

    @Test
    void commandTargetMustMatchTheClosedOperationRequirement() throws Exception {
        String versionId = "40000000-0000-4000-8000-000000000001";
        String body = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID).replace(
                "{\"targetType\":\"DEFINITION_KEY_HASH\",\"targetId\":\"" + COMMAND_TARGET_ID + "\"}",
                "{\"targetType\":\"VERSION\",\"targetId\":\"" + versionId
                        + "\",\"versionId\":\"" + versionId + "\"}");
        SignedRequestBinding binding = signedBinding(
                "POST", COMMAND_PATH, null, body, IDEMPOTENCY_KEY, CORRELATION_ID);
        CommandTargetBinding target = new CommandTargetBinding(
                Set.of("targetType", "targetId", "versionId"),
                "VERSION",
                versionId,
                null,
                versionId,
                null,
                null,
                null,
                null,
                null,
                null);
        CommandBinding signedCommand = new CommandBinding(
                COMMAND_ID,
                target,
                0L,
                PUBLIC_REQUEST_FINGERPRINT,
                REASON_DIGEST,
                SOD_ARTIFACT_IDS);
        TrustFixture trust = commandTrust(binding, signedCommand);
        Harness harness = harness(trust);

        harness.mvc().perform(validCommand(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_ASSERTION_INVALID"));

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void signedArbitraryHashCannotSubstituteForTheDefinitionKeyDerivedTarget() throws Exception {
        String substitutedHash = "f".repeat(64);
        String body = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID)
                .replace(COMMAND_TARGET_ID, substitutedHash);
        SignedRequestBinding binding = signedBinding(
                "POST", COMMAND_PATH, null, body, IDEMPOTENCY_KEY, CORRELATION_ID);
        TrustFixture trust = commandTrust(binding, new CommandBinding(
                COMMAND_ID,
                commandTarget(substitutedHash),
                0L,
                PUBLIC_REQUEST_FINGERPRINT,
                REASON_DIGEST,
                SOD_ARTIFACT_IDS));
        Harness harness = harness(trust);

        harness.mvc().perform(validCommand(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_ASSERTION_INVALID"));

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void requiresTheFullReconcileBindingBeforeCompletionDataCanReachDownstream() throws Exception {
        SignedRequestBinding binding = signedBinding(
                "GET", COMPLETION_PATH, null, null, null, CORRELATION_ID);
        ProviderAssertionClaims assertion = validReconcileAssertion(
                binding, "READ_COMPLETION", null);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.reconcile"), assertion);
        Harness harness = harness(trust);

        harness.mvc().perform(validCompletion())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconcile.commandId").value(COMMAND_ID))
                .andExpect(jsonPath("$.reconcile.publicRequestFingerprint").value("c".repeat(64)))
                .andExpect(jsonPath("$.requiredServiceScope").value("widget-registry.reconcile"));

        assertThat(harness.controller().terminalCalls()).isEqualTo(1);
    }

    @Test
    void rejectsCompletionAssertionWithoutLedgerBindingClaimsBeforeReplay() throws Exception {
        SignedRequestBinding binding = signedBinding(
                "GET", COMPLETION_PATH, null, null, null, CORRELATION_ID);
        ProviderAssertionClaims assertion = copyReconcile(
                validReconcileAssertion(binding, "READ_COMPLETION", null), null);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.reconcile"), assertion);
        Harness harness = harness(trust);

        harness.mvc().perform(validCompletion())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_ASSERTION_INVALID"));

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void sealRequiresSignedOriginalArtifactReferencesAndTheDedicatedBodyLimit() throws Exception {
        OriginalArtifactBinding originals = new OriginalArtifactBinding(
                sha256("a.b.c"),
                "original-service-jti",
                NOW.minusSeconds(90),
                sha256("d.e.f"),
                "original-assertion-jti",
                NOW.minusSeconds(60));
        String body = sealBody(originals, "a.b.c", "d.e.f");
        SignedRequestBinding binding = signedBinding(
                "POST", SEAL_PATH, null, body, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.reconcile"),
                validReconcileAssertion(binding, "SEAL_NOT_EXECUTED", originals));
        Harness harness = harness(trust);

        harness.mvc().perform(validSeal(body)).andExpect(status().isOk());
        assertThat(harness.controller().terminalCalls()).isEqualTo(1);

        String oversized = "{\"padding\":\"" + "x".repeat(32 * 1024) + "\"}";
        TrustFixture oversizedTrust = new TrustFixture(
                validServiceToken("widget-registry.reconcile"),
                validReconcileAssertion(binding, "SEAL_NOT_EXECUTED", originals));
        Harness oversizedHarness = harness(oversizedTrust);
        oversizedHarness.mvc().perform(validSeal(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_PAYLOAD_TOO_LARGE"));
        assertThat(oversizedHarness.controller().terminalCalls()).isZero();
        assertThat(oversizedTrust.replayStore.calls()).isZero();

        String oversizedEmbeddedToken = body.replace(
                "a.b.c", "a." + "b".repeat(8192) + ".c");
        TrustFixture embeddedLimitTrust = new TrustFixture(
                validServiceToken("widget-registry.reconcile"),
                validReconcileAssertion(binding, "SEAL_NOT_EXECUTED", originals));
        Harness embeddedLimitHarness = harness(embeddedLimitTrust);
        embeddedLimitHarness.mvc().perform(validSeal(oversizedEmbeddedToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value("WIDGET_REGISTRY_REQUEST_BINDING_INVALID"));
        assertThat(embeddedLimitHarness.controller().terminalCalls()).isZero();
        assertThat(embeddedLimitTrust.replayStore.calls()).isZero();

        TrustFixture missingOriginalsTrust = new TrustFixture(
                validServiceToken("widget-registry.reconcile"),
                validReconcileAssertion(binding, "SEAL_NOT_EXECUTED", null));
        Harness missingOriginalsHarness = harness(missingOriginalsTrust);
        missingOriginalsHarness.mvc().perform(validSeal(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_ASSERTION_INVALID"));
        assertThat(missingOriginalsHarness.controller().terminalCalls()).isZero();
        assertThat(missingOriginalsTrust.replayStore.calls()).isZero();

        OriginalArtifactBinding mismatchedOriginals = new OriginalArtifactBinding(
                "0".repeat(64),
                originals.serviceTokenJti(),
                originals.serviceTokenExpiresAt(),
                originals.widgetAssertionSha256(),
                originals.widgetAssertionJti(),
                originals.widgetAssertionExpiresAt());
        TrustFixture mismatchedOriginalsTrust = new TrustFixture(
                validServiceToken("widget-registry.reconcile"),
                validReconcileAssertion(binding, "SEAL_NOT_EXECUTED", mismatchedOriginals));
        Harness mismatchedOriginalsHarness = harness(mismatchedOriginalsTrust);
        mismatchedOriginalsHarness.mvc().perform(validSeal(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_ASSERTION_INVALID"));
        assertThat(mismatchedOriginalsTrust.replayStore.calls()).isZero();
    }

    @Test
    void sealBodyCannotSubstituteAnotherCommandWhileReusingTheSameArtifacts() throws Exception {
        OriginalArtifactBinding originals = new OriginalArtifactBinding(
                sha256("a.b.c"),
                "original-service-jti",
                NOW.minusSeconds(90),
                sha256("d.e.f"),
                "original-assertion-jti",
                NOW.minusSeconds(60));
        String substitutedBody = sealBody(originals, "a.b.c", "d.e.f").replace(
                COMMAND_ID,
                "10000000-0000-4000-8000-000000000002");
        SignedRequestBinding binding = signedBinding(
                "POST", SEAL_PATH, null, substitutedBody, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.reconcile"),
                validReconcileAssertion(binding, "SEAL_NOT_EXECUTED", originals));
        Harness harness = harness(trust);

        harness.mvc().perform(validSeal(substitutedBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_ASSERTION_INVALID"));

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void rejectsCommandHeaderBodyAndSignedCommandBindingMismatches() throws Exception {
        String body = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID);
        SignedRequestBinding binding = signedBinding(
                "POST", COMMAND_PATH, null, body, IDEMPOTENCY_KEY, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.write"),
                validAssertion(
                        binding,
                        "WIDGET_DEFINITION_WRITE",
                        null,
                        "createWidgetDefinition",
                        "CREATE_DEFINITION"));
        Harness harness = harness(trust);

        harness.mvc().perform(validCommand(commandBody(
                                "20000000-0000-4000-8000-000000000099", CORRELATION_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value("WIDGET_REGISTRY_REQUEST_BINDING_INVALID"));
        String duplicateKeyBody = commandBody(IDEMPOTENCY_KEY, CORRELATION_ID)
                .replace("{\"schemaVersion\":1", "{\"schemaVersion\":1,\"schemaVersion\":1");
        harness.mvc().perform(validCommand(duplicateKeyBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value("WIDGET_REGISTRY_REQUEST_BINDING_INVALID"));

        assertThat(harness.controller().terminalCalls()).isZero();
        assertThat(trust.replayStore.calls()).isZero();
    }

    @Test
    void consumesAssertionJtiOnlyAfterFullValidationAndRejectsReplay() throws Exception {
        SignedRequestBinding binding = signedBinding("GET", PATH, null, null, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness harness = harness(trust);

        harness.mvc().perform(validGet()).andExpect(status().isOk());
        harness.mvc().perform(validGet())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_ASSERTION_REPLAYED"));

        assertThat(harness.controller().terminalCalls()).isEqualTo(1);
        assertThat(trust.replayStore.calls()).isEqualTo(2);
    }

    @Test
    void forgedThenValidSameJtiDoesNotBurnNonceButValidReplayDoes() throws Exception {
        SignedRequestBinding binding = signedBinding("GET", PATH, null, null, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        trust.assertionFailure = VerificationFailure.INVALID;
        Harness harness = harness(trust);

        harness.mvc().perform(validGet()).andExpect(status().isUnauthorized());
        trust.assertionFailure = null;
        harness.mvc().perform(validGet()).andExpect(status().isOk());
        harness.mvc().perform(validGet()).andExpect(status().isConflict());

        assertThat(harness.controller().terminalCalls()).isEqualTo(1);
        assertThat(trust.replayStore.calls()).isEqualTo(2);
    }

    @Test
    void jwksAndReplayStoreFailuresRemainFailClosed() throws Exception {
        SignedRequestBinding binding = signedBinding("GET", PATH, null, null, null, CORRELATION_ID);
        TrustFixture trust = new TrustFixture(
                validServiceToken("widget-registry.read"),
                validAssertion(binding, "WIDGET_CATALOG_READ", null, null, null));
        Harness harness = harness(trust);

        trust.assertionFailure = VerificationFailure.TRUST_UNAVAILABLE;
        harness.mvc().perform(validGet())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_TRUST_UNAVAILABLE"));
        assertThat(trust.replayStore.calls()).isZero();

        trust.assertionFailure = null;
        trust.replayStore.unavailable = true;
        harness.mvc().perform(validGet())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("WIDGET_REGISTRY_TRUST_UNAVAILABLE"));

        assertThat(harness.controller().terminalCalls()).isZero();
    }

    private Harness harness(TrustFixture trust) {
        TerminalController controller = new TerminalController();
        WidgetRegistryInternalSecurityFilter filter = new WidgetRegistryInternalSecurityFilter(
                objectMapper,
                trust::verifyServiceToken,
                trust::verifyAssertion,
                trust.replayStore,
                CLOCK);
        return new Harness(mvc(controller, filter), controller);
    }

    private MockMvc mvc(TerminalController controller, WidgetRegistryInternalSecurityFilter filter) {
        return standaloneSetup(controller)
                .addFilters(filter, new ProviderProvisioningSecurityFilter("trusted", objectMapper))
                .build();
    }

    private MockHttpServletRequestBuilder validGet() {
        return get(PATH)
                .secure(true)
                .header("Authorization", "Bearer " + SERVICE_COMPACT)
                .header("X-DWP-Widget-Assertion", ASSERTION_COMPACT)
                .header("X-Correlation-ID", CORRELATION_ID);
    }

    private MockHttpServletRequestBuilder validCommand(String body) {
        return post(COMMAND_PATH)
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", "Bearer " + SERVICE_COMPACT)
                .header("X-DWP-Widget-Assertion", ASSERTION_COMPACT)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Correlation-ID", CORRELATION_ID);
    }

    private MockHttpServletRequestBuilder validCommand(byte[] body) {
        return post(COMMAND_PATH)
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", "Bearer " + SERVICE_COMPACT)
                .header("X-DWP-Widget-Assertion", ASSERTION_COMPACT)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Correlation-ID", CORRELATION_ID);
    }

    private MockHttpServletRequestBuilder validCompletion() {
        return get(COMPLETION_PATH)
                .secure(true)
                .header("Authorization", "Bearer " + SERVICE_COMPACT)
                .header("X-DWP-Widget-Reconcile-Assertion", ASSERTION_COMPACT)
                .header("X-Correlation-ID", CORRELATION_ID);
    }

    private MockHttpServletRequestBuilder validSeal(String body) {
        return post(SEAL_PATH)
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", "Bearer " + SERVICE_COMPACT)
                .header("X-DWP-Widget-Reconcile-Assertion", ASSERTION_COMPACT)
                .header("X-Correlation-ID", CORRELATION_ID);
    }

    private SignedRequestBinding signedBinding(
            String method,
            String path,
            String query,
            String body,
            String idempotencyKey,
            String correlationId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setSecure(true);
        request.setQueryString(query);
        request.addHeader("X-Correlation-ID", correlationId);
        if (idempotencyKey != null) request.addHeader("Idempotency-Key", idempotencyKey);
        if (body != null) {
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        WidgetRegistryInternalRoutes.Match match =
                WidgetRegistryInternalRoutes.resolve(path, method).match();
        ActualBinding actual = new WidgetRegistryRequestBinding(objectMapper)
                .prepare(request, match)
                .binding();
        return new SignedRequestBinding(
                actual.method(),
                actual.pathTemplate(),
                actual.actualPath(),
                actual.requestTargetSha256(),
                actual.bodySha256(),
                actual.idempotencyKey(),
                actual.correlationId());
    }

    private WidgetRegistryRequestBinding.PreparedRequest preparedCommand(String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", COMMAND_PATH);
        request.setSecure(true);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader("Idempotency-Key", IDEMPOTENCY_KEY);
        request.addHeader("X-Correlation-ID", CORRELATION_ID);
        WidgetRegistryInternalRoutes.Match match =
                WidgetRegistryInternalRoutes.resolve(COMMAND_PATH, "POST").match();
        return new WidgetRegistryRequestBinding(objectMapper).prepare(request, match);
    }

    private static ServiceTokenClaims validServiceToken(String scope) {
        return new ServiceTokenClaims(
                new JoseProof("ES256", "identity-key-1", FINGERPRINT),
                new JwtIdentity(
                        "dwp-internal-identity",
                        "dwp-provider-server",
                        "dwp-platform-widget-registry",
                        NOW.minusSeconds(10),
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(120),
                        SERVICE_JTI),
                "dwp-provider-server",
                Set.of(scope));
    }

    private static ProviderAssertionClaims validAssertion(
            SignedRequestBinding binding,
            String permission,
            String purpose,
            String operationId,
            String commandType) {
        return new ProviderAssertionClaims(
                new JoseProof("ES256", "provider-key-1", FINGERPRINT),
                new JwtIdentity(
                        "dwp-provider-server",
                        "dwp-provider-server",
                        "dwp-platform-widget-registry",
                        NOW.minusSeconds(5),
                        NOW.minusSeconds(5),
                        NOW.plusSeconds(30),
                        ASSERTION_JTI),
                SERVICE_JTI,
                binding,
                permission == null ? List.of() : List.of(permission),
                permission == null ? List.of() : List.of("core.workspace"),
                permission == null ? null : "actor_ref_widget_admin",
                permission == null ? null : "session_ref_widget_admin_20260827",
                permission == null ? null : "provider_authority_revision_0042",
                permission == null ? null : NOW.minusSeconds(30),
                operationId == null ? null : validCommandBinding(),
                null,
                purpose,
                operationId,
                commandType);
    }

    private static TrustFixture commandTrust(
            SignedRequestBinding binding,
            CommandBinding command) {
        ProviderAssertionClaims assertion = validAssertion(
                binding,
                "WIDGET_DEFINITION_WRITE",
                null,
                "createWidgetDefinition",
                "CREATE_DEFINITION");
        return new TrustFixture(
                validServiceToken("widget-registry.write"),
                withCommand(assertion, command));
    }

    private static ProviderAssertionClaims validReconcileAssertion(
            SignedRequestBinding binding,
            String purpose,
            OriginalArtifactBinding originalArtifacts) {
        ReconcileBinding reconcile = new ReconcileBinding(
                COMMAND_ID,
                "c".repeat(64),
                "d".repeat(64),
                "publishWidgetDefinitionVersion",
                "VERSION",
                "40000000-0000-4000-8000-000000000001",
                NOW.minusSeconds(90),
                originalArtifacts);
        return new ProviderAssertionClaims(
                new JoseProof("ES256", "provider-key-1", FINGERPRINT),
                new JwtIdentity(
                        "dwp-provider-server",
                        "dwp-provider-server",
                        "dwp-platform-widget-registry",
                        NOW.minusSeconds(5),
                        NOW.minusSeconds(5),
                        NOW.plusSeconds(30),
                        ASSERTION_JTI),
                SERVICE_JTI,
                binding,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                reconcile,
                purpose,
                reconcile.operationId(),
                null);
    }

    private static ServiceTokenClaims corrupt(ServiceTokenClaims value, ServiceFault fault) {
        JoseProof proof = value.proof();
        JwtIdentity identity = value.identity();
        return switch (fault) {
            case ALGORITHM -> copy(value, new JoseProof("HS256", proof.keyId(), proof.keyFingerprint()), identity,
                    value.authorizedParty(), value.scopes());
            case KEY_ID -> copy(value, new JoseProof("ES256", "", proof.keyFingerprint()), identity,
                    value.authorizedParty(), value.scopes());
            case ISSUER -> copy(value, proof, copy(identity, "wrong-issuer", identity.subject(),
                    identity.audience(), identity.expiresAt()), value.authorizedParty(), value.scopes());
            case SUBJECT -> copy(value, proof, copy(identity, identity.issuer(), "wrong-subject",
                    identity.audience(), identity.expiresAt()), value.authorizedParty(), value.scopes());
            case AUDIENCE -> copy(value, proof, copy(identity, identity.issuer(), identity.subject(),
                    "wrong-audience", identity.expiresAt()), value.authorizedParty(), value.scopes());
            case AUTHORIZED_PARTY -> copy(value, proof, identity, "wrong-client", value.scopes());
            case SCOPE -> copy(value, proof, identity, value.authorizedParty(),
                    Set.of("widget-registry.read", "widget-registry.write"));
            case TIME -> copy(value, proof, copy(identity, identity.issuer(), identity.subject(),
                    identity.audience(), NOW.minusSeconds(31)), value.authorizedParty(), value.scopes());
        };
    }

    private static ProviderAssertionClaims corrupt(ProviderAssertionClaims value, AssertionFault fault) {
        JoseProof proof = value.proof();
        JwtIdentity identity = value.identity();
        SignedRequestBinding request = value.request();
        return switch (fault) {
            case ALGORITHM -> copy(value, new JoseProof("HS256", proof.keyId(), proof.keyFingerprint()), identity,
                    value.serviceTokenJti(), request, value.permissionCodes(), value.purpose(),
                    value.operationId(), value.commandType());
            case KEY_ID -> copy(value, new JoseProof("ES256", "", proof.keyFingerprint()), identity,
                    value.serviceTokenJti(), request, value.permissionCodes(), value.purpose(),
                    value.operationId(), value.commandType());
            case ISSUER -> copy(value, proof, copy(identity, "wrong-issuer", identity.subject(),
                    identity.audience(), identity.expiresAt()), value.serviceTokenJti(), request,
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case SUBJECT -> copy(value, proof, copy(identity, identity.issuer(), "wrong-subject",
                    identity.audience(), identity.expiresAt()), value.serviceTokenJti(), request,
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case AUDIENCE -> copy(value, proof, copy(identity, identity.issuer(), identity.subject(),
                    "wrong-audience", identity.expiresAt()), value.serviceTokenJti(), request,
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case TIME -> copy(value, proof, copy(identity, identity.issuer(), identity.subject(),
                    identity.audience(), NOW.minusSeconds(31)), value.serviceTokenJti(), request,
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case SERVICE_TOKEN_JTI -> copy(value, proof, identity, "different-service-jti", request,
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case METHOD -> copy(value, proof, identity, value.serviceTokenJti(),
                    copy(request, "POST", request.pathTemplate(), request.actualPath(),
                            request.requestTargetSha256(), request.bodySha256(), request.idempotencyKey(),
                            request.correlationId()),
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case PATH_TEMPLATE -> copy(value, proof, identity, value.serviceTokenJti(),
                    copy(request, request.method(), "/internal/provider/v1/widget-registry/other",
                            request.actualPath(), request.requestTargetSha256(), request.bodySha256(),
                            request.idempotencyKey(), request.correlationId()),
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case ACTUAL_PATH -> copy(value, proof, identity, value.serviceTokenJti(),
                    copy(request, request.method(), request.pathTemplate(), PATH + "/other",
                            request.requestTargetSha256(), request.bodySha256(), request.idempotencyKey(),
                            request.correlationId()),
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case RAW_QUERY_DIGEST -> copy(value, proof, identity, value.serviceTokenJti(),
                    copy(request, request.method(), request.pathTemplate(), request.actualPath(),
                            "b".repeat(64), request.bodySha256(), request.idempotencyKey(),
                            request.correlationId()),
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case BODY_DIGEST -> copy(value, proof, identity, value.serviceTokenJti(),
                    copy(request, request.method(), request.pathTemplate(), request.actualPath(),
                            request.requestTargetSha256(), "b".repeat(64), request.idempotencyKey(),
                            request.correlationId()),
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case IDEMPOTENCY -> copy(value, proof, identity, value.serviceTokenJti(),
                    copy(request, request.method(), request.pathTemplate(), request.actualPath(),
                            request.requestTargetSha256(), request.bodySha256(), IDEMPOTENCY_KEY,
                            request.correlationId()),
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case CORRELATION -> copy(value, proof, identity, value.serviceTokenJti(),
                    copy(request, request.method(), request.pathTemplate(), request.actualPath(),
                            request.requestTargetSha256(), request.bodySha256(), request.idempotencyKey(),
                            "30000000-0000-4000-8000-000000000099"),
                    value.permissionCodes(), value.purpose(), value.operationId(), value.commandType());
            case PERMISSION -> copy(value, proof, identity, value.serviceTokenJti(), request,
                    List.of("WIDGET_DEFINITION_WRITE"), value.purpose(), value.operationId(),
                    value.commandType());
            case PURPOSE -> copy(value, proof, identity, value.serviceTokenJti(), request,
                    value.permissionCodes(), "READ_COMPLETION", value.operationId(), value.commandType());
            case OWNER_PRODUCT_KEYS -> copyOperator(value, List.of(), value.actorRef(), value.sessionRef(),
                    value.providerAuthorityRevision(), value.authenticatedAt());
            case PROVIDER_AUTHORITY_REVISION -> copyOperator(
                    value, value.ownerProductKeys(), value.actorRef(), value.sessionRef(), "",
                    value.authenticatedAt());
            case AUTHENTICATED_AT -> copyOperator(
                    value, value.ownerProductKeys(), value.actorRef(), value.sessionRef(),
                    value.providerAuthorityRevision(), NOW.plusSeconds(31));
        };
    }

    private static ServiceTokenClaims copy(
            ServiceTokenClaims ignored,
            JoseProof proof,
            JwtIdentity identity,
            String authorizedParty,
            Set<String> scopes) {
        return new ServiceTokenClaims(proof, identity, authorizedParty, scopes);
    }

    private static ProviderAssertionClaims copy(
            ProviderAssertionClaims ignored,
            JoseProof proof,
            JwtIdentity identity,
            String serviceTokenJti,
            SignedRequestBinding request,
            List<String> permissions,
            String purpose,
            String operationId,
            String commandType) {
        return new ProviderAssertionClaims(
                proof,
                identity,
                serviceTokenJti,
                request,
                permissions,
                ignored.ownerProductKeys(),
                ignored.actorRef(),
                ignored.sessionRef(),
                ignored.providerAuthorityRevision(),
                ignored.authenticatedAt(),
                ignored.command(),
                ignored.reconcile(),
                purpose,
                operationId,
                commandType);
    }

    private static ProviderAssertionClaims copyOperator(
            ProviderAssertionClaims value,
            List<String> ownerProductKeys,
            String actorRef,
            String sessionRef,
            String authorityRevision,
            Instant authenticatedAt) {
        return new ProviderAssertionClaims(
                value.proof(),
                value.identity(),
                value.serviceTokenJti(),
                value.request(),
                value.permissionCodes(),
                ownerProductKeys,
                actorRef,
                sessionRef,
                authorityRevision,
                authenticatedAt,
                value.command(),
                value.reconcile(),
                value.purpose(),
                value.operationId(),
                value.commandType());
    }

    private static ProviderAssertionClaims copyReconcile(
            ProviderAssertionClaims value,
            ReconcileBinding reconcile) {
        return new ProviderAssertionClaims(
                value.proof(),
                value.identity(),
                value.serviceTokenJti(),
                value.request(),
                value.permissionCodes(),
                value.ownerProductKeys(),
                value.actorRef(),
                value.sessionRef(),
                value.providerAuthorityRevision(),
                value.authenticatedAt(),
                value.command(),
                reconcile,
                value.purpose(),
                value.operationId(),
                value.commandType());
    }

    private static ProviderAssertionClaims withCommand(
            ProviderAssertionClaims value,
            CommandBinding command) {
        return new ProviderAssertionClaims(
                value.proof(),
                value.identity(),
                value.serviceTokenJti(),
                value.request(),
                value.permissionCodes(),
                value.ownerProductKeys(),
                value.actorRef(),
                value.sessionRef(),
                value.providerAuthorityRevision(),
                value.authenticatedAt(),
                command,
                value.reconcile(),
                value.purpose(),
                value.operationId(),
                value.commandType());
    }

    private static JwtIdentity copy(
            JwtIdentity value,
            String issuer,
            String subject,
            String audience,
            Instant expiresAt) {
        return new JwtIdentity(
                issuer,
                subject,
                audience,
                value.issuedAt(),
                value.notBefore(),
                expiresAt,
                value.jwtId());
    }

    private static SignedRequestBinding copy(
            SignedRequestBinding ignored,
            String method,
            String pathTemplate,
            String actualPath,
            String requestTargetSha256,
            String bodySha256,
            String idempotencyKey,
            String correlationId) {
        return new SignedRequestBinding(
                method,
                pathTemplate,
                actualPath,
                requestTargetSha256,
                bodySha256,
                idempotencyKey,
                correlationId);
    }

    private static String commandBody(String publicIdempotencyKey, String correlationId) {
        return """
                {"schemaVersion":1,"commandId":"10000000-0000-4000-8000-000000000001",\
                "operationId":"createWidgetDefinition","commandType":"CREATE_DEFINITION",\
                "target":{"targetType":"DEFINITION_KEY_HASH","targetId":"%s"},\
                "payload":{"definitionKey":"core.workspace.command-rail",\
                "ownerProductKey":"core.workspace","ownerTeamKey":"workspace-experience",\
                "riskTier":"MEDIUM","dataClassification":"INTERNAL",\
                "reasonCode":"CREATE_APPROVED",\
                "reasonText":"Create widget definition.","expectedVersion":0},\
                "publicIdempotencyKey":"%s","publicRequestFingerprint":"%s",\
                "expectedVersion":0,"correlationId":"%s",\
                "operatorRef":"actor_ref_widget_admin",\
                "sessionRef":"session_ref_widget_admin_20260827",\
                "permissionSetHash":"%s",\
                "sodArtifactIds":["sod_approval_0001","sod_case_0001"]}
                """.formatted(
                COMMAND_TARGET_ID,
                publicIdempotencyKey,
                PUBLIC_REQUEST_FINGERPRINT,
                correlationId,
                PERMISSION_SET_HASH);
    }

    private static String sealBody(
            OriginalArtifactBinding originals,
            String serviceTokenCompact,
            String widgetAssertionCompact) {
        return """
                {"schemaVersion":1,"commandId":"10000000-0000-4000-8000-000000000001",\
                "operationId":"publishWidgetDefinitionVersion",\
                "target":{"targetType":"VERSION",\
                "targetId":"40000000-0000-4000-8000-000000000001"},\
                "publicRequestFingerprint":"%s","actorRefSha256":"%s",\
                "originalServiceTokenSha256":"%s",\
                "originalServiceTokenJti":"%s","originalServiceTokenExpiresAt":"%s",\
                "originalWidgetAssertionSha256":"%s",\
                "originalWidgetAssertionJti":"%s","originalWidgetAssertionExpiresAt":"%s",\
                "providerReceiptCreatedAt":"%s",\
                "originalArtifacts":{"serviceTokenCompact":"%s",\
                "widgetAssertionCompact":"%s"}}
                """.formatted(
                "c".repeat(64),
                "d".repeat(64),
                originals.serviceTokenSha256(),
                originals.serviceTokenJti(),
                originals.serviceTokenExpiresAt(),
                originals.widgetAssertionSha256(),
                originals.widgetAssertionJti(),
                originals.widgetAssertionExpiresAt(),
                NOW.minusSeconds(90),
                serviceTokenCompact,
                widgetAssertionCompact);
    }

    private static CommandBinding validCommandBinding() {
        return new CommandBinding(
                COMMAND_ID,
                commandTarget(COMMAND_TARGET_ID),
                0L,
                PUBLIC_REQUEST_FINGERPRINT,
                REASON_DIGEST,
                SOD_ARTIFACT_IDS);
    }

    private static CommandTargetBinding commandTarget(String targetId) {
        return new CommandTargetBinding(
                Set.of("targetType", "targetId"),
                "DEFINITION_KEY_HASH",
                targetId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static String withPayloadMember(String body, String member) {
        String anchor = "\"reasonText\":\"Create widget definition.\",\"expectedVersion\":0}";
        String replacement = "\"reasonText\":\"Create widget definition.\"," + member
                + ",\"expectedVersion\":0}";
        return body.replace(anchor, replacement);
    }

    private static String withExpectedVersion(String body, String value) {
        return body.replace("\"expectedVersion\":0", "\"expectedVersion\":" + value);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String authorityHash(List<String> ownerProductKeys) throws Exception {
        var authority = objectMapper.createObjectNode();
        authority.put("schemaVersion", 1);
        var permissions = authority.putArray("permissionCodes");
        permissions.add("WIDGET_DEFINITION_WRITE");
        var owners = authority.putArray("ownerProductKeys");
        ownerProductKeys.forEach(owners::add);
        authority.put("providerAuthorityRevision", "provider_authority_revision_0042");
        byte[] canonical = WidgetRegistryRequestBinding.canonicalJson(
                objectMapper.writeValueAsBytes(authority));
        return sha256(new String(canonical, StandardCharsets.UTF_8));
    }

    private String reasonDigest(String reasonText) throws Exception {
        var reason = objectMapper.createObjectNode();
        reason.put("reasonCode", "CREATE_APPROVED");
        reason.put("reasonText", reasonText);
        byte[] canonical = WidgetRegistryRequestBinding.canonicalJson(
                objectMapper.writeValueAsBytes(reason));
        return sha256(new String(canonical, StandardCharsets.UTF_8));
    }

    private enum ServiceFault {
        ALGORITHM,
        KEY_ID,
        ISSUER,
        SUBJECT,
        AUDIENCE,
        AUTHORIZED_PARTY,
        SCOPE,
        TIME
    }

    private enum AssertionFault {
        ALGORITHM,
        KEY_ID,
        ISSUER,
        SUBJECT,
        AUDIENCE,
        TIME,
        SERVICE_TOKEN_JTI,
        METHOD,
        PATH_TEMPLATE,
        ACTUAL_PATH,
        RAW_QUERY_DIGEST,
        BODY_DIGEST,
        IDEMPOTENCY,
        CORRELATION,
        PERMISSION,
        PURPOSE,
        OWNER_PRODUCT_KEYS,
        PROVIDER_AUTHORITY_REVISION,
        AUTHENTICATED_AT
    }

    private record Harness(MockMvc mvc, TerminalController controller) {
    }

    private static final class TrustFixture {
        private final ServiceTokenClaims serviceToken;
        private final ProviderAssertionClaims assertion;
        private final InMemoryReplayStore replayStore = new InMemoryReplayStore();
        private VerificationFailure serviceFailure;
        private VerificationFailure assertionFailure;

        private TrustFixture(ServiceTokenClaims serviceToken, ProviderAssertionClaims assertion) {
            this.serviceToken = serviceToken;
            this.assertion = assertion;
        }

        private ServiceTokenClaims verifyServiceToken(String compact) throws VerificationException {
            if (serviceFailure != null) throw new VerificationException(serviceFailure);
            return serviceToken;
        }

        private ProviderAssertionClaims verifyAssertion(String compact, AssertionKind kind)
                throws VerificationException {
            if (assertionFailure != null) throw new VerificationException(assertionFailure);
            return assertion;
        }
    }

    private static final class InMemoryReplayStore
            implements WidgetRegistryTrustPorts.AssertionReplayStore {
        private final Set<ReplayKey> accepted = new HashSet<>();
        private int calls;
        private boolean unavailable;

        @Override
        public ReplayDecision claim(ReplayKey key, Instant retainUntil) {
            calls++;
            if (unavailable) return ReplayDecision.UNAVAILABLE;
            return accepted.add(key) ? ReplayDecision.ACCEPTED : ReplayDecision.REPLAYED;
        }

        private int calls() {
            return calls;
        }
    }

    @RestController
    private static final class TerminalController {
        private final AtomicInteger terminalCalls = new AtomicInteger();

        @GetMapping(PATH)
        WidgetRegistryTrustedRequestContext definitions(HttpServletRequest request) {
            terminalCalls.incrementAndGet();
            return (WidgetRegistryTrustedRequestContext) request.getAttribute(
                    WidgetRegistryTrustedRequestContext.REQUEST_ATTRIBUTE);
        }

        @PostMapping(COMMAND_PATH)
        Map<String, Object> command(HttpServletRequest request, @RequestBody JsonNode body) {
            terminalCalls.incrementAndGet();
            return Map.of(
                    "context",
                    request.getAttribute(WidgetRegistryTrustedRequestContext.REQUEST_ATTRIBUTE),
                    "body",
                    body);
        }

        @GetMapping(COMPLETION_PATH)
        WidgetRegistryTrustedRequestContext completion(HttpServletRequest request) {
            terminalCalls.incrementAndGet();
            return (WidgetRegistryTrustedRequestContext) request.getAttribute(
                    WidgetRegistryTrustedRequestContext.REQUEST_ATTRIBUTE);
        }

        @PostMapping(SEAL_PATH)
        WidgetRegistryTrustedRequestContext seal(HttpServletRequest request, @RequestBody JsonNode body) {
            terminalCalls.incrementAndGet();
            return (WidgetRegistryTrustedRequestContext) request.getAttribute(
                    WidgetRegistryTrustedRequestContext.REQUEST_ATTRIBUTE);
        }

        int terminalCalls() {
            return terminalCalls.get();
        }
    }
}
