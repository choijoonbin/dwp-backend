package com.dwp.services.platform.home.runtime;

import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;

public final class HttpWidgetProviderClient implements WidgetProviderPort {

    private final String providerKey;
    private final String serviceToken;
    private final RestClient client;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public HttpWidgetProviderClient(
            String providerKey,
            String baseUrl,
            String serviceToken,
            Duration timeout,
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads) {
        this.providerKey = providerKey;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
        CircuitBreakerConfig circuitConfig = CircuitBreakerConfig.from(
                        circuitBreakers.getDefaultConfig())
                .ignoreException(this::ignoredByCircuitBreaker)
                .build();
        this.circuitBreaker = circuitBreakers.circuitBreaker(
                "homeRuntime-" + providerKey, circuitConfig);
        this.bulkhead = bulkheads.bulkhead("homeRuntime-" + providerKey);
    }

    @Override
    public String providerKey() {
        return providerKey;
    }

    @Override
    public HomeWidgetProviderContract.BatchResponse readBatch(
            HomeRuntimeContext context,
            List<Request> requests,
            OffsetDateTime deadline) {
        if (serviceToken.isBlank()) {
            throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_NOT_CONFIGURED", "Home provider transport is not configured.", null);
        }
        Supplier<HomeWidgetProviderContract.BatchResponse> invocation = () -> invoke(
                context, requests, deadline);
        Supplier<HomeWidgetProviderContract.BatchResponse> isolated =
                CircuitBreaker.decorateSupplier(
                        circuitBreaker,
                        Bulkhead.decorateSupplier(bulkhead, invocation));
        try {
            return isolated.get();
        } catch (WidgetProviderException exception) {
            throw exception;
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException exception) {
            throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_CIRCUIT_OPEN", "Home provider circuit is open.", exception);
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException exception) {
            throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_BULKHEAD_FULL", "Home provider bulkhead is full.", exception);
        }
    }

    private HomeWidgetProviderContract.BatchResponse invoke(
            HomeRuntimeContext context,
            List<Request> requests,
            OffsetDateTime deadline) {
        try {
            RestClient.RequestBodySpec request = client.post()
                    .uri(HomeWidgetProviderContract.BATCH_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header(HomeWidgetProviderContract.SERVICE_TOKEN_HEADER, serviceToken)
                    .header(HomeWidgetProviderContract.SERVICE_IDENTITY_HEADER,
                            "dwp-platform-server")
                    .header("X-DWP-Tenant-ID", Long.toString(context.tenantId()))
                    .header("X-DWP-User-ID", Long.toString(context.userId()))
                    .header("X-DWP-Permissions", context.permissionsHeader())
                    .header("X-DWP-Roles", context.rolesHeader())
                    .header("X-DWP-Group-Refs", context.groupsHeader())
                    .header(HomeWidgetProviderContract.AUTHORITY_REVISION_HEADER,
                            context.authorityDecisionRevision())
                    .header("X-DWP-Current-Revalidate-At",
                            context.authorityRevalidateAt().toString())
                    .header("X-DWP-Home-Deadline-At", deadline.toString())
                    .header("Accept-Language", context.locale());
            if (context.personPublicId() != null) {
                request.header("X-DWP-Person-Public-ID", context.personPublicId().toString());
            }
            return request.body(new HomeWidgetProviderContract.BatchRequest(
                            HomeWidgetProviderContract.SCHEMA_VERSION,
                            requests.stream().map(Request::contract).toList()))
                    .retrieve()
                    .body(HomeWidgetProviderContract.BatchResponse.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw failure(WidgetProviderException.Kind.FORBIDDEN,
                        "AUTHORIZATION_PROVIDER_FORBIDDEN",
                        "Home provider denied the recipient.", exception);
            }
            if (exception.getStatusCode().is5xxServerError()) {
                throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                        "PROVIDER_HTTP_5XX", "Home provider is unavailable.", exception);
            }
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_HTTP_REJECTED", "Home provider rejected the broker contract.", exception);
        } catch (RestClientException exception) {
            WidgetProviderException.Kind kind = causedByTimeout(exception)
                    ? WidgetProviderException.Kind.TIMEOUT : WidgetProviderException.Kind.UNAVAILABLE;
            throw failure(kind, kind == WidgetProviderException.Kind.TIMEOUT
                    ? "PROVIDER_TIMEOUT" : "PROVIDER_TRANSPORT_FAILURE",
                    "Home provider transport failed.", exception);
        }
    }

    private boolean causedByTimeout(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean ignoredByCircuitBreaker(Throwable failure) {
        return failure instanceof WidgetProviderException providerFailure
                && (providerFailure.kind() == WidgetProviderException.Kind.FORBIDDEN
                || providerFailure.kind() == WidgetProviderException.Kind.MALFORMED);
    }

    private WidgetProviderException failure(
            WidgetProviderException.Kind kind,
            String code,
            String message,
            Throwable cause) {
        return new WidgetProviderException(kind, code, message, cause);
    }
}
