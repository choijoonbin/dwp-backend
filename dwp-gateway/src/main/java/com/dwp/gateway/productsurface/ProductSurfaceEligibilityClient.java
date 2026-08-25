package com.dwp.gateway.productsurface;

import com.dwp.gateway.filter.VerifiedIdentityFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;

@Component
public class ProductSurfaceEligibilityClient {

    private static final String INTERNAL_PATH =
            "/internal/people/v1/product-surface-eligibility/evaluate";
    private static final String TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    private static final String SERVICE_IDENTITY = "dwp-gateway";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String TRACE_STATE_HEADER = "tracestate";

    private final WebClient peopleClient;
    private final String serviceToken;
    private final Duration timeout;

    public ProductSurfaceEligibilityClient(
            WebClient.Builder webClientBuilder,
            @Value("${SERVICE_PEOPLE_URL:http://localhost:8003}") String peopleServiceUrl,
            @Value("${dwp.people.service-token:}") String serviceToken,
            @Value("${dwp.product-surface.eligibility-timeout:2s}") Duration timeout) {
        this.peopleClient = webClientBuilder.baseUrl(peopleServiceUrl).build();
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.timeout = timeout;
    }

    Mono<ProductSurfaceContextDtos.EligibilityResult> evaluate(
            ProductSurfaceContextDtos.RequestContext context,
            ProductSurfaceContextDtos.AuthorityResult authority,
            String contextScopeKey,
            OffsetDateTime evaluatedAt) {
        if (serviceToken.isBlank()) return Mono.just(unavailable());
        var scopes = authority.scopes().stream()
                .map(scope -> new ProductSurfaceContextDtos.CandidateScope(
                        scope.key(), scope.kind()))
                .toList();
        var request = new ProductSurfaceContextDtos.EligibilityEvaluateRequest(
                context.tenantId(),
                context.actorId(),
                authority.productKey(),
                authority.surfaceKey(),
                context.activeAccessMode(),
                evaluatedAt,
                scopes,
                contextScopeKey);
        return peopleClient.post()
                .uri(INTERNAL_PATH)
                .headers(headers -> trustedHeaders(headers, context))
                .bodyValue(request)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(ProductSurfaceContextDtos.EligibilityResult.class)
                        : response.createException().flatMap(Mono::error))
                .timeout(timeout)
                .onErrorReturn(unavailable());
    }

    private void trustedHeaders(
            HttpHeaders headers,
            ProductSurfaceContextDtos.RequestContext context) {
        headers.set(TOKEN_HEADER, serviceToken);
        headers.set(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY);
        headers.set(USER_HEADER, Long.toString(context.actorId()));
        headers.set(TENANT_HEADER, Long.toString(context.tenantId()));
        copy(headers, VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                context.personPublicId());
        if (!context.roles().isEmpty()) {
            headers.set(VerifiedIdentityFilter.ROLES_HEADER,
                    String.join(",", context.roles()));
        }
        if (!context.permissions().isEmpty()) {
            headers.set(VerifiedIdentityFilter.PERMISSIONS_HEADER,
                    String.join(",", context.permissions()));
        }
        copy(headers, CORRELATION_HEADER, context.correlationId());
        copy(headers, TRACE_PARENT_HEADER, context.traceParent());
        copy(headers, TRACE_STATE_HEADER, context.traceState());
        headers.set(HttpHeaders.ACCEPT, "application/json");
    }

    private void copy(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) headers.set(name, value);
    }

    private ProductSurfaceContextDtos.EligibilityResult unavailable() {
        return new ProductSurfaceContextDtos.EligibilityResult(
                ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE,
                "AUTHORITY_RESOLUTION_UNAVAILABLE",
                null,
                null,
                java.util.List.of(),
                null,
                null);
    }
}
