package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceStepUpChallengeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T05:10:00Z");
    private static final long ACTOR_ID = 19L;
    private static final long TENANT_ID = 7L;
    private static final String REQUIRED_ACR = "urn:dwp:acr:mfa";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ProductSurfaceStepUpRouteResolver routeResolver;
    private ProductSurfaceAuthorityService authorityService;
    private AuthSessionService sessionService;
    private OidcService oidcService;
    private StepUpBrowserBindingService browserBindingService;
    private ProductSurfaceStepUpChallengeService service;
    private KeyPair keyPair;
    private ProductSurfaceStepUpDtos.IssueRequest request;
    private ProductSurfaceStepUpRouteResolver.Resolution route;
    private ProductSurfaceAuthorityDtos.AuthorityResult authority;
    private String decisionRevision;

    @BeforeEach
    void setUp() throws Exception {
        routeResolver = mock(ProductSurfaceStepUpRouteResolver.class);
        authorityService = mock(ProductSurfaceAuthorityService.class);
        sessionService = mock(AuthSessionService.class);
        oidcService = mock(OidcService.class);
        browserBindingService = mock(StepUpBrowserBindingService.class);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        service = new ProductSurfaceStepUpChallengeService(
                routeResolver, authorityService, sessionService, oidcService,
                browserBindingService, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC),
                keyPair.getPrivate(), "https://auth.corp.example.com/product-surface-step-up",
                "prod-stepup-2026-08", REQUIRED_ACR,
                Set.of("dwp-approval-server", "dwp-people-server"), 600, 300);
        String target = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode().put("expectedVersion", 7);
        request = new ProductSurfaceStepUpDtos.IssueRequest(
                "POST", "/api/approvals/v1/admin/workflows/" + target + "/publish",
                "opaque-context", "S_APPROVALS", "WORKFLOW", target, 7L,
                UUID.randomUUID().toString(), payload, null, "/approvals");
        route = new ProductSurfaceStepUpRouteResolver.Resolution(
                "route.approvals.admin.workflow-publish.action", "approvals",
                "approvals.management.design", "approvals.design.publish",
                "STEPUP-MGMT-HIGH-V1", "APP_RESOURCE_SET:RS_APPROVALS", "approval",
                "dwp-approval-server", "WORKFLOW", "workflowId", "COMMAND_BODY",
                "expectedVersion", 4, "a".repeat(64), 4);
        authority = authority("opaque-context", "S_APPROVALS");
        decisionRevision = revision("auth-revision", "policy-revision");
        when(routeResolver.resolve(request)).thenReturn(route);
        when(authorityService.evaluate(any())).thenReturn(authority);
    }

    @Test
    void signsRegistryAudienceCanonicalScopeDirectRevisionAndOriginalAmr() throws Exception {
        when(sessionService.requireFreshAssurance(
                any(), eq(ACTOR_ID), eq(TENANT_ID), eq(REQUIRED_ACR), eq(600L)))
                .thenReturn(new AuthSessionService.AssuranceEvidence(
                        "OIDC_STEP_UP", NOW.minusSeconds(30), REQUIRED_ACR,
                        List.of("mfa", "otp", "pwd")));
        ProductSurfaceStepUpRequestParser.ParsedRequest parsed = parsed(request);

        ProductSurfaceStepUpChallengeService.Outcome outcome = service.issue(
                ACTOR_ID, TENANT_ID, jwt(), parsed, decisionRevision,
                new MockHttpServletResponse());

        ProductSurfaceStepUpChallengeService.Issued issued =
                (ProductSurfaceStepUpChallengeService.Issued) outcome;
        String token = issued.response().challenge();
        assertThat(verifySignature(token)).isTrue();
        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(
                token.split("\\.")[1]));
        LinkedHashSet<String> claimFields = new LinkedHashSet<>();
        claims.fieldNames().forEachRemaining(claimFields::add);
        assertThat(claimFields)
                .containsExactlyInAnyOrderElementsOf(
                        ProductSurfaceStepUpChallengeContract.CLAIM_FIELDS);
        assertThat(claims.path("aud").asText()).isEqualTo("dwp-approval-server");
        assertThat(claims.path("owner_service_key").asText()).isEqualTo("approval");
        assertThat(claims.path("command_contract_key").asText())
                .isEqualTo("route.approvals.admin.workflow-publish.action");
        assertThat(claims.path("context_key").asText()).isEqualTo("opaque-context");
        assertThat(claims.path("scope_ref").asText()).isEqualTo("S_APPROVALS");
        assertThat(claims.path("decision_revision").asText()).isEqualTo(decisionRevision);
        assertThat(claims.path("target_version").asLong()).isEqualTo(7L);
        assertThat(claims.path("command_sha256").asText()).isEqualTo(
                ProductSurfaceStepUpChallengeContract.commandSha256(
                        new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                                route.routeContractKey(), route.ownerServiceKey(), route.audience(),
                                request.commandMethod(), request.commandPath(), "opaque-context",
                                "S_APPROVALS", request.targetType(), request.targetId(),
                                request.expectedObjectVersion(), request.idempotencyKey(),
                                claims.path("payload_sha256").asText(), decisionRevision)));
        assertThat(claims.path("amr")).extracting(JsonNode::asText)
                .containsExactly("mfa", "otp", "pwd");

        ArgumentCaptor<ProductSurfaceAuthorityDtos.EvaluateRequest> evaluation =
                ArgumentCaptor.forClass(ProductSurfaceAuthorityDtos.EvaluateRequest.class);
        verify(authorityService).evaluate(evaluation.capture());
        assertThat(evaluation.getValue().routeContractKey())
                .isEqualTo(route.routeContractKey());
        assertThat(evaluation.getValue().contextKey()).isEqualTo("opaque-context");
        assertThat(evaluation.getValue().contextScopeKey()).isEqualTo("S_APPROVALS");
    }

    @Test
    void continuationReturnsTheSameOpaqueFlowAndBindsDigestAndRevisionInServerState() {
        when(sessionService.requireFreshAssurance(any(), any(), any(), any(), eq(600L)))
                .thenThrow(new BaseException(ErrorCode.STEP_UP_REQUIRED));
        when(oidcService.enabledStepUpProviderKeys(TENANT_ID, REQUIRED_ACR))
                .thenReturn(List.of("corp"));
        when(browserBindingService.create(any()))
                .thenReturn(new StepUpBrowserBindingService.Binding("browser-hash"));
        when(oidcService.getStepUpAuthorizationUrl(any())).thenReturn(
                new OidcService.StepUpAuthorization(
                        "https://idp.example.com/authorize?state=opaque",
                        NOW.plusSeconds(600), "corp",
                        "3c78d2dd-bb75-47e5-bcec-d70e2a2867ce"));

        ProductSurfaceStepUpChallengeService.Outcome outcome = service.issue(
                ACTOR_ID, TENANT_ID, jwt(), parsed(request), decisionRevision,
                new MockHttpServletResponse());

        ProductSurfaceStepUpChallengeService.Continuation continuation =
                (ProductSurfaceStepUpChallengeService.Continuation) outcome;
        assertThat(continuation.response().continuation().flowRef())
                .isEqualTo("3c78d2dd-bb75-47e5-bcec-d70e2a2867ce");
        ArgumentCaptor<OidcStateStore.StepUpBinding> binding =
                ArgumentCaptor.forClass(OidcStateStore.StepUpBinding.class);
        verify(oidcService).getStepUpAuthorizationUrl(binding.capture());
        assertThat(binding.getValue().commandDigest()).hasSize(64);
        assertThat(binding.getValue().sourceRevision()).isEqualTo(decisionRevision);
        assertThat(binding.getValue().actorId()).isEqualTo(ACTOR_ID);
        assertThat(binding.getValue().sessionFamilyId().toString())
                .isEqualTo(jwt().getClaimAsString("sid"));
    }

    @Test
    void uncanonicalizedStrongLookingSessionEvidenceRoutesBackToStepUpInsteadOfSigning() {
        when(sessionService.requireFreshAssurance(any(), any(), any(), any(), eq(600L)))
                .thenReturn(new AuthSessionService.AssuranceEvidence(
                        "OIDC_STEP_UP", NOW.minusSeconds(30), REQUIRED_ACR,
                        List.of("otp", "pwd")));
        when(oidcService.enabledStepUpProviderKeys(TENANT_ID, REQUIRED_ACR))
                .thenReturn(List.of("corp"));
        when(browserBindingService.create(any()))
                .thenReturn(new StepUpBrowserBindingService.Binding("browser-hash"));
        when(oidcService.getStepUpAuthorizationUrl(any())).thenReturn(
                new OidcService.StepUpAuthorization(
                        "https://idp.example.com/authorize?state=opaque",
                        NOW.plusSeconds(600), "corp",
                        "3c78d2dd-bb75-47e5-bcec-d70e2a2867ce"));

        ProductSurfaceStepUpChallengeService.Outcome outcome = service.issue(
                ACTOR_ID, TENANT_ID, jwt(), parsed(request), decisionRevision,
                new MockHttpServletResponse());

        assertThat(outcome).isInstanceOf(ProductSurfaceStepUpChallengeService.Continuation.class);
    }

    @Test
    void normalLoginLiteralMfaCannotBeSignedAndRoutesBackToVerifiedStepUp() {
        when(sessionService.requireFreshAssurance(any(), any(), any(), any(), eq(600L)))
                .thenReturn(new AuthSessionService.AssuranceEvidence(
                        "OIDC", NOW.minusSeconds(30), REQUIRED_ACR, List.of("mfa")));
        when(oidcService.enabledStepUpProviderKeys(TENANT_ID, REQUIRED_ACR))
                .thenReturn(List.of("corp"));
        when(browserBindingService.create(any()))
                .thenReturn(new StepUpBrowserBindingService.Binding("browser-hash"));
        when(oidcService.getStepUpAuthorizationUrl(any())).thenReturn(
                new OidcService.StepUpAuthorization(
                        "https://idp.example.com/authorize?state=opaque",
                        NOW.plusSeconds(600), "corp",
                        "3c78d2dd-bb75-47e5-bcec-d70e2a2867ce"));

        ProductSurfaceStepUpChallengeService.Outcome outcome = service.issue(
                ACTOR_ID, TENANT_ID, jwt(), parsed(request), decisionRevision,
                new MockHttpServletResponse());

        assertThat(outcome).isInstanceOf(ProductSurfaceStepUpChallengeService.Continuation.class);
    }

    @Test
    void multipleCompatibleProvidersRequireExplicitSelectionWithoutCreatingFlowState() {
        when(sessionService.requireFreshAssurance(any(), any(), any(), any(), eq(600L)))
                .thenThrow(new BaseException(ErrorCode.STEP_UP_REQUIRED));
        when(oidcService.enabledStepUpProviderKeys(TENANT_ID, REQUIRED_ACR))
                .thenReturn(List.of("corp-a", "corp-b"));

        ProductSurfaceStepUpChallengeService.Continuation outcome =
                (ProductSurfaceStepUpChallengeService.Continuation) service.issue(
                        ACTOR_ID, TENANT_ID, jwt(), parsed(request), decisionRevision,
                        new MockHttpServletResponse());

        assertThat(outcome.response().continuation().type())
                .isEqualTo("OIDC_PROVIDER_SELECTION");
        assertThat(outcome.response().continuation().providerKeys())
                .containsExactly("corp-a", "corp-b");
        assertThat(outcome.response().continuation().flowRef()).isNull();
        org.mockito.Mockito.verifyNoInteractions(browserBindingService);
    }

    @Test
    void failsClosedForAnUnregisteredAudienceOrNoCompatibleProvider() {
        ProductSurfaceStepUpRouteResolver.Resolution wrongAudience =
                new ProductSurfaceStepUpRouteResolver.Resolution(
                        route.routeContractKey(), route.productKey(), route.surfaceKey(),
                        route.capabilityContractKey(), route.activationPolicy(), route.scopeResolver(),
                        route.ownerServiceKey(), "attacker-service", route.targetType(),
                        route.targetIdPathParameter(), route.expectedObjectVersionSource(),
                        route.expectedObjectVersionName(), 4, "a".repeat(64), 4);
        when(routeResolver.resolve(request)).thenReturn(wrongAudience);
        assertThatThrownBy(() -> service.issue(
                ACTOR_ID, TENANT_ID, jwt(), parsed(request), decisionRevision,
                new MockHttpServletResponse()))
                .isInstanceOf(BaseException.class);

        when(routeResolver.resolve(request)).thenReturn(route);
        when(sessionService.requireFreshAssurance(any(), any(), any(), any(), eq(600L)))
                .thenThrow(new BaseException(ErrorCode.STEP_UP_REQUIRED));
        when(oidcService.enabledStepUpProviderKeys(TENANT_ID, REQUIRED_ACR))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.issue(
                ACTOR_ID, TENANT_ID, jwt(), parsed(request), decisionRevision,
                new MockHttpServletResponse()))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.STEP_UP_REQUIRED));
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult authority(
            String contextKey,
            String scopeKey) {
        OffsetDateTime validUntil = OffsetDateTime.ofInstant(NOW.plusSeconds(900), ZoneOffset.UTC);
        ProductSurfaceAuthorityDtos.EffectiveScope scope =
                new ProductSurfaceAuthorityDtos.EffectiveScope(
                        scopeKey, "APP_RESOURCE_SET", "Approvals", true, false, validUntil);
        ProductSurfaceAuthorityDtos.CapabilityGrant grant =
                new ProductSurfaceAuthorityDtos.CapabilityGrant(
                        "approvals.design.publish", "APPROVAL.DESIGN:PUBLISH",
                        ProductSurfaceAuthorityDtos.CapabilityAuthorityMode.PERMISSION,
                        List.of(), ProductSurfaceAuthorityDtos.ResponsibilityRequirement.REQUIRED,
                        new ProductSurfaceAuthorityDtos.Responsibility(
                                "APP_CONFIG_ADMIN", "RS_APPROVALS"),
                        List.of(scopeKey), true, false,
                        ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE, validUntil);
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED,
                "STEP_UP_REQUIRED", "auth-revision", "policy-revision", contextKey,
                "approvals", "approvals.management.design", "management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT, "APP.APPROVALS",
                List.of(grant), List.of(scope), "route-grant", false, false,
                validUntil, null, REQUIRED_ACR, "STEPUP-MGMT-HIGH-V1",
                validUntil, "evidence");
    }

    private ProductSurfaceStepUpRequestParser.ParsedRequest parsed(
            ProductSurfaceStepUpDtos.IssueRequest value) {
        try {
            return new ProductSurfaceStepUpRequestParser.ParsedRequest(
                    value, objectMapper.writeValueAsBytes(value.payload()));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Jwt jwt() {
        return Jwt.withTokenValue("session")
                .header("alg", "HS256")
                .subject(Long.toString(ACTOR_ID))
                .issuedAt(NOW.minusSeconds(100))
                .expiresAt(NOW.plusSeconds(1000))
                .claim("tenant_id", Long.toString(TENANT_ID))
                .claim("jti", "token-id")
                .claim("sid", "ba2cd67b-c893-44ef-b95b-ec8355268da0")
                .build();
    }

    private String revision(String authRevision, String policyRevision) throws Exception {
        String material = String.join("\n", Long.toString(TENANT_ID), Long.toString(ACTOR_ID),
                "NORMAL", authRevision, policyRevision, "", "", "");
        return "psr-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean verifySignature(String token) throws Exception {
        String[] parts = token.split("\\.");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
    }
}
