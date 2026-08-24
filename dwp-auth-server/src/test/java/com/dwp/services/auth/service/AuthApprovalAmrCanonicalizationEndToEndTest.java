package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthApprovalAmrCanonicalizationEndToEndTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final long ACTOR_ID = 19L;
    private static final long TENANT_ID = 7L;
    private static final String REQUIRED_ACR = "urn:dwp:acr:mfa";
    private static final String ISSUER =
            "https://auth.corp.example.com/product-surface-step-up";
    private static final String KEY_ID = "prod-stepup-2026-08";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private OidcService oidcService;
    private AuthSessionService sessionService;
    private ProductSurfaceStepUpChallengeService challengeService;
    private ProductSurfaceStepUpRouteResolver.Resolution route;
    private ProductSurfaceStepUpDtos.IssueRequest request;
    private KeyPair keyPair;
    private String decisionRevision;

    @BeforeEach
    void setUp() throws Exception {
        oidcService = new OidcService(
                mock(com.dwp.services.auth.repository.IdentityProviderRepository.class),
                mock(OidcStateStore.class), objectMapper,
                "idp.example.com", "workspace.example.com", false,
                "https://workspace.example.com/auth/oidc/callback", 30,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(HttpClient.class),
                ignored -> "test-client-secret");
        sessionService = mock(AuthSessionService.class);
        ProductSurfaceStepUpRouteResolver routeResolver =
                mock(ProductSurfaceStepUpRouteResolver.class);
        ProductSurfaceAuthorityService authorityService =
                mock(ProductSurfaceAuthorityService.class);
        StepUpBrowserBindingService browserBindingService =
                mock(StepUpBrowserBindingService.class);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        challengeService = new ProductSurfaceStepUpChallengeService(
                routeResolver, authorityService, sessionService, oidcService,
                browserBindingService, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC),
                keyPair.getPrivate(), ISSUER, KEY_ID, REQUIRED_ACR,
                Set.of("dwp-approval-server"), 600, 300);
        String targetId = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode().put("expectedVersion", 7);
        request = new ProductSurfaceStepUpDtos.IssueRequest(
                "POST", "/api/approvals/v1/admin/workflows/" + targetId + "/publish",
                "opaque-context", "S_APPROVALS", "WORKFLOW", targetId, 7L,
                UUID.randomUUID().toString(), payload, null, "/approvals");
        route = new ProductSurfaceStepUpRouteResolver.Resolution(
                "route.approvals.admin.workflow-publish.action", "approvals",
                "approvals.management.design", "approvals.design.publish",
                "STEPUP-MGMT-HIGH-V1", "APP_RESOURCE_SET:RS_APPROVALS", "approval",
                "dwp-approval-server", "WORKFLOW", "workflowId", "COMMAND_BODY",
                "expectedVersion", 4, "a".repeat(64), 4);
        decisionRevision = revision("auth-revision", "policy-revision");
        when(routeResolver.resolve(request)).thenReturn(route);
        when(authorityService.evaluate(any())).thenReturn(authority());
    }

    static Stream<Arguments> amrTruthTable() {
        return Stream.of(
                Arguments.of("pwd+otp", List.of("pwd", "otp"),
                        List.of("pwd", "otp"), List.of("mfa", "otp", "pwd"), true),
                Arguments.of("hwk", List.of("hwk"),
                        List.of("hwk"), List.of("hwk", "mfa"), true),
                Arguments.of("pwd-only", List.of("pwd", "otp"),
                        List.of("pwd"), List.of(), false),
                Arguments.of("unknown", List.of("pwd", "otp"),
                        List.of("pwd", "magic"), List.of(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("amrTruthTable")
    void actualAuthCanonicalizationAndSignerInteroperateWithApprovalVerifier(
            String scenario,
            List<String> acceptedProviderAmr,
            List<String> actualProviderAmr,
            List<String> expectedCanonicalAmr,
            boolean accepted) throws Exception {
        OidcStateStore.StateContext state = stepUpState(acceptedProviderAmr);
        Jwt idToken = idToken(actualProviderAmr);
        if (!accepted) {
            assertThatThrownBy(() -> oidcService.verifyStepUpAssurance(state, idToken))
                    .as(scenario)
                    .isInstanceOf(BaseException.class);
            verifyNoInteractions(sessionService);
            return;
        }

        List<String> canonicalAmr = oidcService.verifyStepUpAssurance(state, idToken);
        assertThat(canonicalAmr).isEqualTo(expectedCanonicalAmr);
        when(sessionService.requireFreshAssurance(
                any(), eq(ACTOR_ID), eq(TENANT_ID), eq(REQUIRED_ACR), eq(600L)))
                .thenReturn(new AuthSessionService.AssuranceEvidence(
                        "OIDC_STEP_UP", NOW.minusSeconds(10), REQUIRED_ACR, canonicalAmr));
        ProductSurfaceStepUpChallengeService.Issued issued =
                (ProductSurfaceStepUpChallengeService.Issued) challengeService.issue(
                        ACTOR_ID, TENANT_ID, sessionJwt(), parsed(), decisionRevision,
                        new MockHttpServletResponse());
        String token = issued.response().challenge();
        JsonNode claims = claims(token);
        assertThat(claims.path("amr")).extracting(JsonNode::asText)
                .containsExactlyElementsOf(expectedCanonicalAmr);

        ApprovalStepUpVerifier verifier = new ApprovalStepUpVerifier(
                objectMapper, publicKeyPem(), ISSUER, "dwp-approval-server", KEY_ID,
                REQUIRED_ACR, 600, 900);
        assertThatCode(() -> verifier.verify(token, binding(claims))).doesNotThrowAnyException();
    }

    private OidcStateStore.StateContext stepUpState(List<String> acceptedProviderAmr) {
        return new OidcStateStore.StateContext(
                OidcStateStore.Purpose.STEP_UP, UUID.randomUUID().toString(),
                TENANT_ID, "corp", "nonce", "verifier", ACTOR_ID, UUID.randomUUID(),
                "token-id", "browser-hash", REQUIRED_ACR, acceptedProviderAmr, 600,
                "/approvals", "command-digest", "source-revision",
                NOW.minusSeconds(20), NOW.plusSeconds(600));
    }

    private Jwt idToken(List<String> amr) {
        return Jwt.withTokenValue("id-token")
                .header("alg", "RS256")
                .issuer("https://idp.example.com")
                .subject("subject")
                .audience(List.of("dwp-client"))
                .issuedAt(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(300))
                .claim("auth_time", NOW.minusSeconds(10))
                .claim("acr", REQUIRED_ACR)
                .claim("amr", amr)
                .build();
    }

    private Jwt sessionJwt() {
        return Jwt.withTokenValue("session")
                .header("alg", "HS256")
                .subject(Long.toString(ACTOR_ID))
                .issuedAt(NOW.minusSeconds(100))
                .expiresAt(NOW.plusSeconds(1_000))
                .claim("tenant_id", Long.toString(TENANT_ID))
                .claim("jti", "token-id")
                .claim("sid", "ba2cd67b-c893-44ef-b95b-ec8355268da0")
                .build();
    }

    private ProductSurfaceStepUpRequestParser.ParsedRequest parsed() throws Exception {
        return new ProductSurfaceStepUpRequestParser.ParsedRequest(
                request, objectMapper.writeValueAsBytes(request.payload()));
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult authority() {
        OffsetDateTime validUntil = OffsetDateTime.ofInstant(
                NOW.plusSeconds(900), ZoneOffset.UTC);
        ProductSurfaceAuthorityDtos.EffectiveScope scope =
                new ProductSurfaceAuthorityDtos.EffectiveScope(
                        "S_APPROVALS", "APP_RESOURCE_SET", "Approvals", true, false,
                        validUntil);
        ProductSurfaceAuthorityDtos.CapabilityGrant grant =
                new ProductSurfaceAuthorityDtos.CapabilityGrant(
                        "approvals.design.publish", "APPROVAL.DESIGN:PUBLISH",
                        ProductSurfaceAuthorityDtos.CapabilityAuthorityMode.PERMISSION,
                        List.of(), ProductSurfaceAuthorityDtos.ResponsibilityRequirement.REQUIRED,
                        new ProductSurfaceAuthorityDtos.Responsibility(
                                "APP_CONFIG_ADMIN", "RS_APPROVALS"),
                        List.of("S_APPROVALS"), true, false,
                        ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE, validUntil);
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED,
                "STEP_UP_REQUIRED", "auth-revision", "policy-revision", "opaque-context",
                "approvals", "approvals.management.design", "management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT, "APP.APPROVALS",
                List.of(grant), List.of(scope), "route-grant", false, false,
                validUntil, null, REQUIRED_ACR, "STEPUP-MGMT-HIGH-V1",
                validUntil, "evidence");
    }

    private ApprovalStepUpVerifier.CommandBinding binding(JsonNode claims) {
        return new ApprovalStepUpVerifier.CommandBinding(
                ACTOR_ID, TENANT_ID, route.routeContractKey(), "opaque-context",
                route.activationPolicy(), route.capabilityContractKey(), "S_APPROVALS",
                request.targetType(), request.targetId(), request.expectedObjectVersion(),
                request.commandMethod(), request.commandPath(), request.idempotencyKey(),
                claims.path("payload_sha256").asText(), decisionRevision);
    }

    private JsonNode claims(String token) throws Exception {
        return objectMapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private String publicKeyPem() {
        String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    private String revision(String authRevision, String policyRevision) throws Exception {
        String material = String.join("\n", Long.toString(TENANT_ID),
                Long.toString(ACTOR_ID), "NORMAL", authRevision, policyRevision, "", "", "");
        return "psr-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8)));
    }
}
