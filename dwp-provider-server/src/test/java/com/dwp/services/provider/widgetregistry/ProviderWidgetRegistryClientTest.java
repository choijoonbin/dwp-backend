package com.dwp.services.provider.widgetregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.security.ProviderRequestContext;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.mock.web.MockHttpServletRequest;

class ProviderWidgetRegistryClientTest {
    @AfterEach
    void clearActor() {
        ProviderRequestContext.clear();
    }

    @Test
    void forwardsProviderDatabaseAuthorityAndOwnerScopeToPlatform() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProviderWidgetRegistryClient client = new ProviderWidgetRegistryClient(
                builder, "http://platform.test", "platform-secret");
        UUID session = UUID.fromString("40000000-0000-0000-0000-000000000001");
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                9L,
                900001L,
                1L,
                "Widget owner",
                Set.of("PROVIDER_OPERATOR"),
                Set.of("WIDGET_CATALOG_READ", "WIDGET_DEFINITION_WRITE"),
                Set.of("core.work"),
                session));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/admin/widget-definitions");
        request.setQueryString("page=0&size=25");

        server.expect(once(), requestTo(
                        "http://platform.test/v1/admin/widget-definitions?page=0&size=25"))
                .andExpect(header("X-DWP-Service-Token", "platform-secret"))
                .andExpect(header("X-DWP-User-ID", "900001"))
                .andExpect(header("X-DWP-Tenant-ID", "1"))
                .andExpect(header("X-DWP-Roles", "PROVIDER_OPERATOR"))
                .andExpect(header(
                        "X-DWP-Permissions",
                        "WIDGET_CATALOG_READ,WIDGET_DEFINITION_WRITE"))
                .andExpect(header("X-DWP-Auth-Session-ID", session.toString()))
                .andExpect(header("X-DWP-Identity-Plane", "PROVIDER"))
                .andExpect(header("X-DWP-Control-Plane", "WIDGET_REGISTRY_PROVIDER"))
                .andExpect(header(ProviderWidgetRegistryClient.OWNER_SCOPE_HEADER, "core.work"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        var response = client.forward(request, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"success\":true}");
        server.verify();
    }

    @Test
    void failsClosedBeforeCallingPlatformWithoutAnExplicitOwnerScope() {
        RestClient.Builder builder = RestClient.builder();
        ProviderWidgetRegistryClient client = new ProviderWidgetRegistryClient(
                builder, "http://platform.test", "platform-secret");
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                9L,
                900001L,
                1L,
                "Unscoped operator",
                Set.of("PROVIDER_ADMIN"),
                Set.of("WIDGET_CATALOG_READ"),
                Set.of(),
                UUID.randomUUID()));

        assertThatThrownBy(() -> client.forward(new MockHttpServletRequest(
                        "GET", "/v1/admin/widget-definitions"), null))
                .isInstanceOf(BaseException.class);
    }
}
