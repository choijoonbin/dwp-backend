package com.dwp.services.platform.productivity;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.productivity.ProductivityTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrosoftGraphClientTest {

    @Test
    void buildsPkceAuthorizationOnlyOnTrustedMicrosoftHost() {
        MicrosoftGraphClient client = new MicrosoftGraphClient(
                RestClient.builder(),
                "https://login.microsoftonline.com",
                "https://graph.microsoft.com");

        URI uri = client.authorizationUri(connector(), "state-value", "challenge-value");

        assertThat(uri.getHost()).isEqualTo("login.microsoftonline.com");
        assertThat(uri.getPath()).isEqualTo("/organizations/oauth2/v2.0/authorize");
        assertThat(uri.getRawQuery())
                .contains("code_challenge=challenge-value")
                .contains("code_challenge_method=S256")
                .contains("Mail.ReadBasic")
                .doesNotContain("client_secret");
    }

    @Test
    void refusesEndpointOverridesThatCouldExfiltrateCredentials() {
        assertThatThrownBy(() -> new MicrosoftGraphClient(
                RestClient.builder(),
                "https://login.example.test",
                "https://graph.microsoft.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Untrusted");
    }

    private ProductivityRepository.ConnectorRecord connector() {
        return new ProductivityRepository.ConnectorRecord(
                UUID.randomUUID(), 1L, "MICROSOFT_365", "Microsoft 365",
                ProviderType.MICROSOFT_GRAPH, AuthMode.DELEGATED,
                "organizations", "11111111-1111-1111-1111-111111111111",
                "env:DWP_MS_GRAPH_CLIENT_SECRET", "https://localhost/callback",
                List.of("openid", "offline_access", "Mail.ReadBasic", "Calendars.Read"),
                List.of("DELTA_SYNC"), ConnectorLifecycle.ACTIVE,
                ConnectorHealth.DEGRADED, PolicyState.APPROVED,
                null, null, null, 0, 0);
    }
}
