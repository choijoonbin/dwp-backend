package com.dwp.services.people.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrisCredentialResolverTest {

    @Test
    void resolvesStructuredOauthCredentialFromEnvironmentReference() {
        Map<String, String> environment = Map.of(
                "WORKDAY_CREDENTIAL",
                """
                {"tokenUri":"https://auth.example.com/token","clientId":"client","clientSecret":"secret","scope":"workers.read"}
                """);
        HrisCredentialResolver resolver = new HrisCredentialResolver(
                new ObjectMapper(), environment::get);

        HrisCredentialResolver.Credential credential = resolver.resolve(
                "OAUTH2_CLIENT_CREDENTIALS", "env://WORKDAY_CREDENTIAL");

        assertThat(credential.tokenUri()).isEqualTo("https://auth.example.com/token");
        assertThat(credential.clientId()).isEqualTo("client");
        assertThat(credential.clientSecret()).isEqualTo("secret");
        assertThat(credential.scope()).isEqualTo("workers.read");
    }

    @Test
    void blocksUnavailableSecretProviderWithoutEchoingReference() {
        HrisCredentialResolver resolver = new HrisCredentialResolver(
                new ObjectMapper(), ignored -> null);

        assertThatThrownBy(() -> resolver.resolve(
                "OAUTH2_CLIENT_CREDENTIALS", "vault://tenant/hris/workday"))
                .isInstanceOf(HrisConnectorBlockedException.class)
                .hasMessageNotContaining("tenant/hris/workday");
    }
}
