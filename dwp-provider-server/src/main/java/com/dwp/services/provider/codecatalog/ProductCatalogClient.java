package com.dwp.services.provider.codecatalog;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProductCatalogClient {

    private static final String TOKEN_HEADER = "X-DWP-Provisioning-Token";

    private final RestClient platform;
    private final String provisioningToken;

    public ProductCatalogClient(
            RestClient.Builder builder,
            @Value("${dwp.services.platform-url:http://localhost:8002}") String platformUrl,
            @Value("${dwp.provider.provisioning-token:}") String provisioningToken) {
        this.platform = builder.clone().baseUrl(platformUrl).build();
        this.provisioningToken = provisioningToken == null ? "" : provisioningToken.trim();
    }

    @Bulkhead(name = "platformCatalog", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "platformCatalog")
    @Retry(name = "idempotentInternal")
    public JsonNode catalog() {
        return get("/internal/provider/v1/code-catalog/code-sets");
    }

    @Bulkhead(name = "platformCatalog", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "platformCatalog")
    @Retry(name = "idempotentInternal")
    public JsonNode codeSet(String codeSetKey, String locale) {
        requireConfigured();
        PlatformResponse response = platform.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/provider/v1/code-catalog/code-sets/{codeSetKey}")
                        .queryParam("locale", locale)
                        .build(codeSetKey))
                .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                .header(TOKEN_HEADER, provisioningToken)
                .retrieve()
                .body(PlatformResponse.class);
        return data(response);
    }

    private JsonNode get(String path) {
        requireConfigured();
        PlatformResponse response = platform.get()
                .uri(path)
                .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                .header(TOKEN_HEADER, provisioningToken)
                .retrieve()
                .body(PlatformResponse.class);
        return data(response);
    }

    private JsonNode data(PlatformResponse response) {
        if (response == null || !Boolean.TRUE.equals(response.success()) || response.data() == null) {
            throw unavailable(response == null ? null : response.message());
        }
        return response.data();
    }

    private void requireConfigured() {
        if (provisioningToken.isBlank()) {
            throw unavailable("Provider service identity is not configured.");
        }
    }

    private BaseException unavailable(String detail) {
        String message = detail == null || detail.isBlank()
                ? "Product contract catalog is unavailable."
                : detail;
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }

    private record PlatformResponse(Boolean success, String message, JsonNode data) {
    }
}
