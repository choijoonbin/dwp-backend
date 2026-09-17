package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static com.dwp.services.platform.home.runtime.DwaionHomeWorkloadProtocol.*;

/** Signed, recipient-bound transport for the external DWAI-ON artifact owner. */
final class DwaionHomeWidgetProviderClient implements WidgetProviderPort {

    private static final Duration MAX_DEADLINE_AHEAD = Duration.ofSeconds(1);

    private final RestClient client;
    private final ObjectMapper mapper;
    private final DwaionHomeWorkloadAssertionSigner signer;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    DwaionHomeWidgetProviderClient(
            String baseUrl,
            Duration timeout,
            RestClient.Builder builder,
            ObjectMapper mapper,
            DwaionHomeWorkloadAssertionSigner signer,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.mapper = mapper;
        this.signer = signer;
        CircuitBreakerConfig circuitConfig = CircuitBreakerConfig.from(
                        circuitBreakers.getDefaultConfig())
                .ignoreException(this::ignoredByCircuitBreaker)
                .build();
        this.circuitBreaker = circuitBreakers.circuitBreaker("homeRuntime-dwaion", circuitConfig);
        this.bulkhead = bulkheads.bulkhead("homeRuntime-dwaion");
    }

    @Override
    public String providerKey() {
        return "dwaion";
    }

    @Override
    public HomeWidgetProviderContract.BatchResponse readBatch(
            HomeRuntimeContext context,
            List<Request> requests,
            OffsetDateTime deadline) {
        validate(context, requests, deadline);
        Supplier<HomeWidgetProviderContract.BatchResponse> invocation = () ->
                validateEnvelope(context, requests, invoke(context, requests, deadline));
        Supplier<HomeWidgetProviderContract.BatchResponse> isolated =
                CircuitBreaker.decorateSupplier(circuitBreaker,
                        Bulkhead.decorateSupplier(bulkhead, invocation));
        try {
            HomeWidgetProviderContract.BatchResponse response = isolated.get();
            validateDeadline(context, deadline);
            return response;
        } catch (WidgetProviderException exception) {
            throw exception;
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException exception) {
            throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_CIRCUIT_OPEN", "DWAI-ON Home provider circuit is open.", exception);
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException exception) {
            throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_BULKHEAD_FULL", "DWAI-ON Home provider bulkhead is full.", exception);
        }
    }

    private HomeWidgetProviderContract.BatchResponse invoke(
            HomeRuntimeContext context,
            List<Request> requests,
            OffsetDateTime deadline) {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new HomeWidgetProviderContract.BatchRequest(
                    HomeWidgetProviderContract.SCHEMA_VERSION,
                    requests.stream().map(Request::contract).toList()));
        } catch (JsonProcessingException exception) {
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_REQUEST_SERIALIZATION_FAILED",
                    "DWAI-ON Home provider request cannot be serialized.", exception);
        }
        if (body.length > 262_144) {
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_REQUEST_OUT_OF_BOUNDS",
                    "DWAI-ON Home provider request exceeds its signed body budget.", null);
        }
        String assertion = signer.sign(context, deadline, body);
        try {
            RestClient.RequestBodySpec request = client.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.set("X-Correlation-ID", context.correlationId());
                        if (context.traceparent() != null) {
                            headers.set("traceparent", context.traceparent());
                        }
                        if (context.tracestate() != null) {
                            headers.set("tracestate", context.tracestate());
                        }
                    })
                    .header(ASSERTION_HEADER, assertion)
                    .header("X-DWP-Tenant-ID", Long.toString(context.tenantId()))
                    .header("X-DWP-User-ID", Long.toString(context.userId()))
                    .header("X-DWP-Identity-Plane", "TENANT")
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
            return request.body(body).retrieve()
                    .body(HomeWidgetProviderContract.BatchResponse.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw failure(WidgetProviderException.Kind.FORBIDDEN,
                        "AUTHORIZATION_PROVIDER_FORBIDDEN",
                        "DWAI-ON denied the Home recipient.", exception);
            }
            if (exception.getStatusCode().is5xxServerError()) {
                throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                        "PROVIDER_HTTP_5XX", "DWAI-ON Home provider is unavailable.", exception);
            }
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_HTTP_REJECTED",
                    "DWAI-ON rejected the signed Home contract.", exception);
        } catch (RestClientException exception) {
            throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_TRANSPORT_FAILURE",
                    "DWAI-ON Home provider transport failed.", exception);
        }
    }

    private void validate(
            HomeRuntimeContext context,
            List<Request> requests,
            OffsetDateTime deadline) {
        validateDeadline(context, deadline);
        if (requests == null || requests.isEmpty()
                || requests.size() > HomeWidgetProviderContract.MAX_WIDGETS_PER_BATCH) {
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_BATCH_OUT_OF_BOUNDS", "DWAI-ON batch is outside bounds.", null);
        }
        if (!context.has("APP.ASK:VIEW") || !context.has("APP.DWAION_ARTIFACTS:VIEW")) {
            throw failure(WidgetProviderException.Kind.FORBIDDEN,
                    "AUTHORIZATION_DWAION_ARTIFACT_REQUIRED",
                    "Current recipient authority cannot read DWAI-ON artifacts.", null);
        }
        for (Request request : requests) {
            if (request == null || request.instanceId() == null || request.definition() == null
                    || request.itemLimit() < 1
                    || request.itemLimit() > HomeWidgetProviderContract.MAX_ITEM_LIMIT
                    || !"dwaion.artifact".equals(request.definition().definitionKey())
                    || !"APP.DWAION_ARTIFACTS".equals(
                            request.definition().sourceAppResourceKey())
                    || !DEFINITION_VERSION.equals(request.definition().semanticVersion())
                    || !DEFINITION_MANIFEST_HASH.equals(request.definition().manifestHash())
                    || request.definition().rendererBindingRevision() == null
                    || !request.definition().rendererBindingRevision()
                            .matches("[0-9a-f]{64}")) {
                throw failure(WidgetProviderException.Kind.MALFORMED,
                        "DEFINITION_NOT_SUPPORTED",
                        "DWAI-ON does not own the requested Home definition.", null);
            }
        }
    }

    private void validateDeadline(HomeRuntimeContext context, OffsetDateTime deadline) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (context == null) {
            throw failure(WidgetProviderException.Kind.TIMEOUT,
                    "PROVIDER_DEADLINE_EXPIRED", "Home authority context is unavailable.", null);
        }
        context.requireAuthorityCurrent();
        if (deadline == null || !deadline.isAfter(now)
                || deadline.isAfter(now.plus(MAX_DEADLINE_AHEAD))
                || deadline.isAfter(context.authorityRevalidateAt())) {
            throw failure(WidgetProviderException.Kind.TIMEOUT,
                    "PROVIDER_DEADLINE_EXPIRED",
                    "DWAI-ON provider deadline is outside the broker bound.", null);
        }
    }

    private HomeWidgetProviderContract.BatchResponse validateEnvelope(
            HomeRuntimeContext context,
            List<Request> requests,
            HomeWidgetProviderContract.BatchResponse response) {
        Set<java.util.UUID> requested = new HashSet<>();
        requests.forEach(request -> requested.add(request.instanceId()));
        Set<java.util.UUID> returned = new HashSet<>();
        if (response == null
                || response.schemaVersion() != HomeWidgetProviderContract.SCHEMA_VERSION
                || response.tenantId() != context.tenantId()
                || response.userId() != context.userId()
                || !context.authorityDecisionRevision().equals(
                        response.authorityDecisionRevision())
                || response.results() == null
                || response.results().size() != requests.size()
                || response.results().stream().anyMatch(result -> result == null
                        || result.instanceId() == null
                        || !requested.contains(result.instanceId())
                        || !returned.add(result.instanceId()))) {
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_ENVELOPE_MISMATCH",
                    "DWAI-ON response does not match the recipient request.", null);
        }
        return response;
    }

    private boolean ignoredByCircuitBreaker(Throwable failure) {
        return failure instanceof WidgetProviderException providerFailure
                && providerFailure.kind() == WidgetProviderException.Kind.FORBIDDEN;
    }

    private WidgetProviderException failure(
            WidgetProviderException.Kind kind,
            String code,
            String message,
            Throwable cause) {
        return new WidgetProviderException(kind, code, message, cause);
    }
}
