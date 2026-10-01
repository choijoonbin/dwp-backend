package com.dwp.gateway;

import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.productsurface.FeatureRolloutEvaluationClient;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.security.AuthSessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HomeWidgetRuntimeCatalogGatewayTest {

    private static final String CODE_SET_PATH =
            "/api/platform/v1/catalog/code-sets/PLATFORM.HOME_WIDGET";
    private static final List<String> HOME_WIDGET_CODES = List.of(
            "activity",
            "command-rail",
            "daily-brief",
            "focus",
            "focus-balance",
            "meeting-load",
            "schedule");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void classifiesOnlyTheExactHomeWidgetRuntimeReadAsSharedCatalogTraffic() {
        GeneratedProductRouteCatalog catalog = catalog();

        assertThat(catalog.match("GET", CODE_SET_PATH, "locale=ko").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
        assertThat(catalog.match("POST", CODE_SET_PATH, "locale=ko").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(catalog.match("GET", CODE_SET_PATH + ".UNKNOWN", "locale=ko").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void authenticatesHomeWidgetRuntimeReadWithoutProjectingAnAdminPermission() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["MEMBER"],"permissions":[]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier =
                new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get(CODE_SET_PATH + "?locale=ko")
                .header("X-Tenant-ID", "1")
                .build()).block();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().url().getPath()).isEqualTo("/auth/me");
        assertThat(captured.get().url().getQuery()).isNull();
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).isEmpty();
    }

    @Test
    void rolloutFilterForwardsTheExactHomeWidgetReadAndItsSevenValueOwnerPayload()
            throws Exception {
        FeatureRolloutEvaluationClient rolloutClient = mock(FeatureRolloutEvaluationClient.class);
        ProductSurfaceRolloutHeaderFilter filter = new ProductSurfaceRolloutHeaderFilter(
                rolloutClient, catalog(), objectMapper);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get(CODE_SET_PATH + "?locale=ko")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "111")
                .header(ProductSurfaceRolloutHeaderFilter.COHORT_HEADER, "attacker")
                .header(ProductSurfaceRolloutHeaderFilter.REVISION_HEADER, "attacker")
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        String ownerPayload = """
                {"success":true,"data":{"codeSetKey":"PLATFORM.HOME_WIDGET","schemaVersion":14,
                "values":[{"code":"activity"},{"code":"command-rail"},
                {"code":"daily-brief"},{"code":"focus"},{"code":"focus-balance"},
                {"code":"meeting-load"},{"code":"schedule"}]}}
                """;

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            filtered.getResponse().setStatusCode(HttpStatus.OK);
            filtered.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = ownerPayload.getBytes(StandardCharsets.UTF_8);
            return filtered.getResponse().writeWith(Mono.just(
                    filtered.getResponse().bufferFactory().wrap(body)));
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceRolloutHeaderFilter.STATE_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceRolloutHeaderFilter.COHORT_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceRolloutHeaderFilter.REVISION_HEADER)).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(responseBody(exchange));
        assertThat(body.path("data").path("values").findValuesAsText("code"))
                .containsExactlyElementsOf(HOME_WIDGET_CODES);
        verify(rolloutClient, never()).evaluateProducts(anyLong(), any(), any());
    }

    @Test
    void rolloutFilterKeepsUnknownHomeWidgetCodeSetsFailClosed() {
        FeatureRolloutEvaluationClient rolloutClient = mock(FeatureRolloutEvaluationClient.class);
        ProductSurfaceRolloutHeaderFilter filter = new ProductSurfaceRolloutHeaderFilter(
                rolloutClient, catalog(), objectMapper);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get(CODE_SET_PATH + ".UNKNOWN?locale=ko")
                .build());
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(responseBody(exchange)).isEmpty();
        verify(rolloutClient, never()).evaluateProducts(anyLong(), any(), any());
    }

    private GeneratedProductRouteCatalog catalog() {
        return new GeneratedProductRouteCatalog(
                objectMapper,
                new ClassPathResource("product-authorization/product-surfaces-v1.generated.json"));
    }

    private String responseBody(MockServerWebExchange exchange) {
        DataBuffer buffer = DataBufferUtils.join(exchange.getResponse().getBody()).block();
        if (buffer == null) {
            return "";
        }
        byte[] bytes = new byte[buffer.readableByteCount()];
        try {
            buffer.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
