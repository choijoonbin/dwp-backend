package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OidcServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T05:10:00Z");
    private static final String REQUIRED_ACR = "urn:dwp:acr:mfa";

    private IdentityProviderRepository repository;
    private OidcStateStore stateStore;
    private OidcService service;

    @BeforeEach
    void setUp() {
        repository = mock(IdentityProviderRepository.class);
        stateStore = mock(OidcStateStore.class);
        service = new OidcService(
                repository, stateStore, new ObjectMapper().findAndRegisterModules(),
                "idp.example.com", "workspace.example.com", false,
                "https://workspace.example.com/auth/oidc/callback", 30,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(HttpClient.class),
                ignored -> "test-client-secret");
    }

    @Test
    void exposesZeroOneOrManyOnlyWhenEveryStepUpConstraintIsCompatible() {
        IdentityProvider first = provider("first", REQUIRED_ACR, "pwd otp");
        IdentityProvider second = provider("second", REQUIRED_ACR, "webauthn");
        IdentityProvider wrongAcr = provider("wrong-acr", "urn:other:acr", "pwd otp");
        IdentityProvider missingAmr = provider("missing-amr", REQUIRED_ACR, null);
        IdentityProvider insecure = provider("insecure", REQUIRED_ACR, "pwd otp");
        insecure.setAuthUrl("http://idp.example.com/authorize");

        when(repository.findByTenantIdAndEnabledTrueOrderByProviderKey(7L))
                .thenReturn(List.of());
        assertThat(service.enabledStepUpProviderKeys(7L, REQUIRED_ACR)).isEmpty();

        when(repository.findByTenantIdAndEnabledTrueOrderByProviderKey(7L))
                .thenReturn(List.of(first, wrongAcr, missingAmr, insecure));
        assertThat(service.enabledStepUpProviderKeys(7L, REQUIRED_ACR))
                .containsExactly("first");

        when(repository.findByTenantIdAndEnabledTrueOrderByProviderKey(7L))
                .thenReturn(List.of(first, second));
        assertThat(service.enabledStepUpProviderKeys(7L, REQUIRED_ACR))
                .containsExactly("first", "second");
    }

    @Test
    void sendsOnlyTheExactRequiredAcrAndPreservesTheOpaqueFlowReference() {
        IdentityProvider provider = provider(
                "corp", "urn:dwp:acr:strong urn:dwp:acr:mfa", "pwd otp");
        when(repository.findByTenantIdAndEnabledTrueOrderByProviderKey(7L))
                .thenReturn(List.of(provider));
        when(stateStore.createStepUp(any())).thenReturn(new OidcStateStore.AuthorizationRequest(
                "state", "nonce", "verifier", NOW, NOW.plusSeconds(600),
                "3c78d2dd-bb75-47e5-bcec-d70e2a2867ce"));

        OidcService.StepUpAuthorization authorization = service.getStepUpAuthorizationUrl(
                new OidcStateStore.StepUpBinding(
                        7L, null, 19L, UUID.randomUUID(), "token-id", "browser-hash",
                        REQUIRED_ACR, List.of(), 300, "/approvals", "digest", "revision"));

        assertThat(authorization.authorizationUrl())
                .contains("prompt=login", "acr_values=urn:dwp:acr:mfa", "max_age=300")
                .doesNotContain("urn:dwp:acr:strong");
        assertThat(authorization.flowRef())
                .isEqualTo("3c78d2dd-bb75-47e5-bcec-d70e2a2867ce");
    }

    @Test
    void explicitIncompatibleProviderFailsBeforeCreatingBrowserState() {
        IdentityProvider incompatible = provider("corp", REQUIRED_ACR, null);
        when(repository.findByTenantIdAndProviderKey(7L, "corp"))
                .thenReturn(java.util.Optional.of(incompatible));

        assertThatThrownBy(() -> service.getStepUpAuthorizationUrl(
                new OidcStateStore.StepUpBinding(
                        7L, "corp", 19L, UUID.randomUUID(), "token-id", "browser-hash",
                        REQUIRED_ACR, List.of(), 300, "/approvals", "digest", "revision")))
                .isInstanceOf(BaseException.class);
        org.mockito.Mockito.verify(stateStore, org.mockito.Mockito.never())
                .createStepUp(any());
    }

    @Test
    void verifiesExactAcrClosedOriginalAmrAndCeremonyFreshness() {
        OidcStateStore.StateContext context = stepUpContext(
                NOW.minusSeconds(20), List.of("otp", "pwd"), 600);

        assertThat(service.verifyStepUpAssurance(context, jwt(
                NOW.minusSeconds(10), REQUIRED_ACR, List.of("pwd", "otp"))))
                .containsExactly("mfa", "otp", "pwd");

        assertThatThrownBy(() -> service.verifyStepUpAssurance(
                context, jwt(NOW.minusSeconds(10), REQUIRED_ACR, List.of("pwd", "sms"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.verifyStepUpAssurance(
                context, jwt(NOW.minusSeconds(10), REQUIRED_ACR, List.of("pwd"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.verifyStepUpAssurance(
                context, jwt(NOW.minusSeconds(10), REQUIRED_ACR, List.of("PWD", "otp"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.verifyStepUpAssurance(
                context, jwt(NOW.minusSeconds(10), "urn:other:acr", List.of("pwd", "otp"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.verifyStepUpAssurance(
                context, jwt(NOW.minusSeconds(51), REQUIRED_ACR, List.of("pwd", "otp"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.verifyStepUpAssurance(
                context, jwt(NOW.plusSeconds(31), REQUIRED_ACR, List.of("pwd", "otp"))))
                .isInstanceOf(BaseException.class);
        OidcStateStore.StateContext oldCeremony = stepUpContext(
                NOW.minusSeconds(700), List.of("pwd", "otp"), 600);
        assertThatThrownBy(() -> service.verifyStepUpAssurance(
                oldCeremony, jwt(
                        NOW.minusSeconds(601), REQUIRED_ACR, List.of("pwd", "otp"))))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void requiresAClosedKnownProviderPolicyWithAtLeastOneStrongPattern() {
        IdentityProvider weak = provider("weak", REQUIRED_ACR, "pwd");
        IdentityProvider unknown = provider("unknown", REQUIRED_ACR, "pwd vendor-otp");
        IdentityProvider caseVariant = provider("case", REQUIRED_ACR, "pwd OTP");
        IdentityProvider hardware = provider("hardware", REQUIRED_ACR, "hwk");
        when(repository.findByTenantIdAndEnabledTrueOrderByProviderKey(7L))
                .thenReturn(List.of(weak, unknown, caseVariant, hardware));
        when(repository.findAll()).thenReturn(List.of(weak, unknown, caseVariant, hardware));

        assertThat(service.enabledStepUpProviderKeys(7L, REQUIRED_ACR))
                .containsExactly("hardware");
        assertThat(service.incompleteConfiguredStepUpProviderKeys(REQUIRED_ACR))
                .containsExactly("7:case", "7:unknown", "7:weak");
    }

    @Test
    void partialStepUpConfigurationIsExcludedAndFailsProductionInventory() {
        IdentityProvider partial = provider("partial", REQUIRED_ACR, null);
        IdentityProvider loginOnly = provider("login-only", null, null);
        when(repository.findAll()).thenReturn(List.of(partial, loginOnly));

        assertThat(service.incompleteConfiguredStepUpProviderKeys(REQUIRED_ACR))
                .containsExactly("7:partial");
    }

    @Test
    void unavailableClientSecretMakesAConfiguredProviderIncompatible() {
        OidcService withoutSecret = new OidcService(
                repository, stateStore, new ObjectMapper().findAndRegisterModules(),
                "idp.example.com", "workspace.example.com", false,
                "https://workspace.example.com/auth/oidc/callback", 30,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(HttpClient.class), ignored -> null);
        IdentityProvider provider = provider("corp", REQUIRED_ACR, "pwd otp");
        when(repository.findByTenantIdAndEnabledTrueOrderByProviderKey(7L))
                .thenReturn(List.of(provider));

        assertThat(withoutSecret.enabledStepUpProviderKeys(7L, REQUIRED_ACR)).isEmpty();
    }

    @Test
    void rejectsMalformedOrDuplicateHostAllowlists() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OidcService.parseHosts("idp.example.com,,other.example.com"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OidcService.parseHosts("idp.example.com,idp.example.com"));
    }

    private IdentityProvider provider(String key, String acrValues, String acceptedAmrs) {
        return IdentityProvider.builder()
                .tenantId(7L)
                .providerType("OIDC")
                .providerKey(key)
                .name(key)
                .enabled(true)
                .issuerUri("https://idp.example.com")
                .metadataUrl("https://idp.example.com/.well-known/openid-configuration")
                .authUrl("https://idp.example.com/authorize")
                .tokenUrl("https://idp.example.com/token")
                .userInfoUrl("https://idp.example.com/userinfo")
                .clientId("dwp-client")
                .clientSecretEnv("DWP_TEST_IDP_SECRET")
                .stepUpAcrValues(acrValues)
                .stepUpAcceptedAmrValues(acceptedAmrs)
                .stepUpMaxAgeSeconds(600)
                .build();
    }

    private OidcStateStore.StateContext stepUpContext(
            Instant startedAt,
            List<String> acceptedAmrs,
            int maximumAgeSeconds) {
        return new OidcStateStore.StateContext(
                OidcStateStore.Purpose.STEP_UP,
                "3c78d2dd-bb75-47e5-bcec-d70e2a2867ce",
                7L,
                "corp",
                "nonce",
                "verifier",
                19L,
                UUID.randomUUID(),
                "token-id",
                "browser-hash",
                REQUIRED_ACR,
                acceptedAmrs,
                maximumAgeSeconds,
                "/approvals",
                "command-digest",
                "source-revision",
                startedAt,
                NOW.plusSeconds(600));
    }

    private Jwt jwt(Instant authenticatedAt, String acr, List<String> amr) {
        return Jwt.withTokenValue("id-token")
                .header("alg", "RS256")
                .issuer("https://idp.example.com")
                .subject("subject")
                .audience(List.of("dwp-client"))
                .issuedAt(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(300))
                .claim("auth_time", authenticatedAt)
                .claim("acr", acr)
                .claim("amr", amr)
                .build();
    }
}
