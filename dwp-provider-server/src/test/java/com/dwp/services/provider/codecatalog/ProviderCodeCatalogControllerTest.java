package com.dwp.services.provider.codecatalog;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderCodeCatalogControllerTest {

    private final ProductCatalogClient client = mock(ProductCatalogClient.class);
    private final ProviderCodeCatalogController controller = new ProviderCodeCatalogController(client);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void returnsTheGlobalCatalogForAnAuthorizedProviderOperator() {
        JsonNode catalog = objectMapper.createObjectNode().put("catalogScope", "GLOBAL_PRODUCT");
        ProviderRequestContext.set(actor("PROVIDER_OPERATOR", Set.of("CATALOG_READ")));
        when(client.catalog()).thenReturn(catalog);

        assertThat(controller.catalog().getData()).isEqualTo(catalog);
        verify(client).catalog();
    }

    @Test
    void rejectsProviderSupportWithoutTheCatalogPermission() {
        ProviderRequestContext.set(actor("PROVIDER_SUPPORT", Set.of("SUPPORT_SESSION_WRITE")));

        assertThatThrownBy(controller::catalog).isInstanceOf(BaseException.class);
        verify(client, never()).catalog();
    }

    private ProviderRequestContext.Actor actor(String role, Set<String> permissions) {
        return new ProviderRequestContext.Actor(
                9L, 17L, 3L, "Provider operator", Set.of(role), permissions);
    }
}
