package com.dwp.services.auth.service;

import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OidcProviderConfigurationInspectorTest {

    private final IdentityProviderRepository repository = mock(IdentityProviderRepository.class);

    @Test
    void blocksAnEnabledRowWhenLocallyRequiredConfigurationIsIncomplete() {
        IdentityProvider provider = validProvider();
        provider.setTokenUrl(null);
        when(repository.findByTenantIdAndProviderKey(7L, "entra"))
                .thenReturn(Optional.of(provider));
        OidcProviderConfigurationInspector inspector = inspector(
                ignored -> "local-secret");

        OidcProviderConfigurationInspector.Assessment assessment =
                inspector.assess(7L, "entra");

        assertThat(assessment.ready()).isFalse();
        assertThat(assessment.blockingReasons())
                .contains(OidcProviderConfigurationInspector.CONFIGURATION_INCOMPLETE);
    }

    @Test
    void acceptsACompleteLocalConfigurationWithoutContactingTheProvider() {
        IdentityProvider provider = validProvider();
        when(repository.findByTenantIdAndProviderKey(7L, "entra"))
                .thenReturn(Optional.of(provider));
        OidcProviderConfigurationInspector inspector = inspector(
                name -> "DWP_ENTRA_SECRET".equals(name) ? "local-secret" : null);

        OidcProviderConfigurationInspector.Assessment assessment =
                inspector.assess(7L, "entra");

        assertThat(assessment.ready()).isTrue();
        assertThat(assessment.blockingReasons()).isEmpty();
    }

    @Test
    void reportsOnlyStableReasonCodesWhenTheSecretIsUnavailable() {
        IdentityProvider provider = validProvider();
        when(repository.findByTenantIdAndProviderKey(7L, "entra"))
                .thenReturn(Optional.of(provider));

        OidcProviderConfigurationInspector.Assessment assessment =
                inspector(ignored -> null).assess(7L, "entra");

        assertThat(assessment.blockingReasons())
                .containsExactly(OidcProviderConfigurationInspector.CLIENT_SECRET_UNAVAILABLE)
                .doesNotContain(provider.getClientSecretEnv());
    }

    @Test
    void keepsTheExistingHttpsOnlyCallbackBoundaryForLocalConfiguration() {
        IdentityProvider provider = validProvider();
        when(repository.findByTenantIdAndProviderKey(7L, "entra"))
                .thenReturn(Optional.of(provider));
        OidcProviderConfigurationInspector inspector =
                new OidcProviderConfigurationInspector(
                        repository, "idp.example.com", "localhost", false,
                        "http://localhost:4200/auth/oidc/callback", ignored -> "local-secret");

        OidcProviderConfigurationInspector.Assessment assessment =
                inspector.assess(7L, "entra");

        assertThat(assessment.ready()).isFalse();
        assertThat(assessment.blockingReasons())
                .containsExactly(OidcProviderConfigurationInspector.CALLBACK_POLICY_INVALID);
    }

    private OidcProviderConfigurationInspector inspector(
            java.util.function.Function<String, String> environmentReader) {
        return new OidcProviderConfigurationInspector(
                repository, "idp.example.com", "workspace.example.com", false,
                "https://workspace.example.com/auth/oidc/callback", environmentReader);
    }

    private IdentityProvider validProvider() {
        return IdentityProvider.builder()
                .tenantId(7L)
                .providerType("OIDC")
                .providerKey("entra")
                .name("Enterprise Entra")
                .enabled(true)
                .issuerUri("https://idp.example.com")
                .metadataUrl("https://idp.example.com/.well-known/openid-configuration")
                .authUrl("https://idp.example.com/authorize")
                .tokenUrl("https://idp.example.com/token")
                .userInfoUrl("https://idp.example.com/userinfo")
                .clientId("dwp-client")
                .clientSecretEnv("DWP_ENTRA_SECRET")
                .build();
    }
}
