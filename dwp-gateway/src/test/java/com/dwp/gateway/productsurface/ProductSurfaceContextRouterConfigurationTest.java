package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductSurfaceContextRouterConfigurationTest {

    @Test
    void registersTheThreeExactGatewayOwnedHandlers() {
        ProductSurfaceContextHandler handler = mock(ProductSurfaceContextHandler.class);
        when(handler.contexts(any())).thenReturn(org.springframework.web.reactive.function.server
                .ServerResponse.ok().bodyValue("contexts"));
        when(handler.evaluateProduct(any())).thenReturn(org.springframework.web.reactive.function.server
                .ServerResponse.ok().bodyValue("product"));
        when(handler.evaluateGoverned(any())).thenReturn(org.springframework.web.reactive.function.server
                .ServerResponse.ok().bodyValue("governed"));
        var routes = new ProductSurfaceContextRouterConfiguration()
                .productSurfaceContextRoutes(handler);
        WebTestClient client = WebTestClient.bindToRouterFunction(routes).build();

        client.get().uri(ProductSurfaceContextRouterConfiguration.CONTEXTS_HANDLER_PATH)
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("contexts");
        client.post().uri(ProductSurfaceContextRouterConfiguration.PRODUCT_EVALUATION_HANDLER_PATH)
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("product");
        client.post().uri(ProductSurfaceContextRouterConfiguration.GOVERNED_EVALUATION_HANDLER_PATH)
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("governed");
    }

    @Test
    void declaresExactRoutesBeforeTheAuthCatchAll() throws Exception {
        String application = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        int contexts = application.indexOf("id: product-surface-contexts");
        int product = application.indexOf("id: product-surface-access-evaluation");
        int governed = application.indexOf("id: governed-route-access-evaluation");
        int reviewDetail = application.indexOf("id: assigned-access-review-detail");
        int reviewDecision = application.indexOf("id: assigned-access-review-decision");
        int catchAll = application.indexOf("id: auth-server");

        assertThat(contexts).isGreaterThanOrEqualTo(0).isLessThan(catchAll);
        assertThat(product).isGreaterThan(contexts).isLessThan(catchAll);
        assertThat(governed).isGreaterThan(product).isLessThan(reviewDetail);
        assertThat(reviewDetail).isGreaterThan(governed).isLessThan(reviewDecision);
        assertThat(reviewDecision).isGreaterThan(reviewDetail).isLessThan(catchAll);
        assertThat(application.substring(contexts, product)).contains(
                "uri: forward:/_gateway/product-surface-contexts",
                "Path=/api/auth/product-surface-contexts",
                "Method=GET");
        assertThat(application.substring(product, governed)).contains(
                "uri: forward:/_gateway/product-surface-access/evaluate",
                "Path=/api/auth/product-surface-access/evaluate",
                "Method=POST");
        assertThat(application.substring(governed, reviewDetail)).contains(
                "uri: forward:/_gateway/governed-route-access/evaluate",
                "Path=/api/auth/governed-route-access/evaluate",
                "Method=POST");
        assertThat(application.substring(reviewDetail, reviewDecision)).contains(
                "uri: ${SERVICE_AUTH_URL:http://localhost:8001}",
                "Path=/api/auth/work/access-review-items/{workItemRef}",
                "Method=GET",
                "StripPrefix=1");
        assertThat(application.substring(reviewDecision, catchAll)).contains(
                "uri: ${SERVICE_AUTH_URL:http://localhost:8001}",
                "Path=/api/auth/work/access-review-items/{workItemRef}/decision",
                "Method=PUT",
                "StripPrefix=1");
    }
}
