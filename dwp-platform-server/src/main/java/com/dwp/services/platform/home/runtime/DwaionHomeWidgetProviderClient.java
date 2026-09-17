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
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.home.runtime.DwaionHomeWorkloadProtocol.*;

/** Signed, recipient-bound transport for the external DWAI-ON artifact owner. */
final class DwaionHomeWidgetProviderClient implements WidgetProviderPort {

    private static final Duration MAX_DEADLINE_AHEAD = Duration.ofSeconds(1);
    private static final Set<String> ARTIFACT_ITEM_FIELDS = Set.of(
            "artifactId", "title", "artifactType", "state", "revision", "updatedAt");
    private static final Set<String> ARTIFACT_TYPES = Set.of(
            "DOCUMENT", "WORK_PLAN", "COMPARISON");
    private static final Set<String> ARTIFACT_STATES = Set.of("DRAFT", "REVIEW_REQUIRED");
    private static final Set<String> UNAVAILABLE_REASONS = Set.of(
            "PROVIDER_DWAION_ARTIFACT_PROJECTION_NOT_ACTIVATED",
            "PROVIDER_DWAION_ARTIFACT_PROJECTION_UNAVAILABLE",
            "PROVIDER_DWAION_ARTIFACT_TITLE_UNREADABLE");

    private final RestClient client;
    private final ObjectMapper mapper;
    private final DwaionHomeWorkloadAssertionSigner signer;
    private final int maximumResponseBytes;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    DwaionHomeWidgetProviderClient(
            String baseUrl,
            Duration timeout,
            int maximumResponseBytes,
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
        this.maximumResponseBytes = Math.max(
                16_384, Math.min(maximumResponseBytes, 1_048_576));
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
            return request.body(body).exchange((ignored, response) ->
                    decodeBoundedResponse(response));
        } catch (RestClientException exception) {
            throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_TRANSPORT_FAILURE",
                    "DWAI-ON Home provider transport failed.", exception);
        }
    }

    private HomeWidgetProviderContract.BatchResponse decodeBoundedResponse(
            ClientHttpResponse response) throws IOException {
        if (response.getStatusCode() == HttpStatus.FORBIDDEN) {
            close(response.getBody());
            throw failure(WidgetProviderException.Kind.FORBIDDEN,
                    "AUTHORIZATION_PROVIDER_FORBIDDEN",
                    "DWAI-ON denied the Home recipient.", null);
        }
        if (response.getStatusCode().is5xxServerError()) {
            close(response.getBody());
            throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_HTTP_5XX", "DWAI-ON Home provider is unavailable.", null);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            close(response.getBody());
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_HTTP_REJECTED",
                    "DWAI-ON rejected the signed Home contract.", null);
        }
        long declaredLength = response.getHeaders().getContentLength();
        if (declaredLength > maximumResponseBytes) {
            close(response.getBody());
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_RESPONSE_OUT_OF_BOUNDS",
                    "DWAI-ON Home provider response exceeds its payload budget.", null);
        }
        byte[] encoded;
        try (InputStream input = response.getBody()) {
            if (input == null) {
                throw failure(WidgetProviderException.Kind.MALFORMED,
                        "PROVIDER_RESPONSE_MALFORMED",
                        "DWAI-ON Home provider returned no response body.", null);
            }
            encoded = input.readNBytes(maximumResponseBytes + 1);
        }
        if (encoded.length == 0 || encoded.length > maximumResponseBytes) {
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_RESPONSE_OUT_OF_BOUNDS",
                    "DWAI-ON Home provider response exceeds its payload budget.", null);
        }
        try {
            return mapper.readValue(encoded, HomeWidgetProviderContract.BatchResponse.class);
        } catch (JsonProcessingException exception) {
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_RESPONSE_MALFORMED",
                    "DWAI-ON Home provider response is malformed.", exception);
        }
    }

    private void close(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // The stable provider error intentionally omits remote transport details.
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
        validateProjection(requests, response);
        return response;
    }

    private void validateProjection(
            List<Request> requests,
            HomeWidgetProviderContract.BatchResponse response) {
        Map<UUID, Request> expected = requests.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        Request::instanceId, request -> request));
        for (HomeWidgetProviderContract.WidgetResult result : response.results()) {
            Request request = expected.get(result.instanceId());
            HomeWidgetProviderContract.SourceState source = result.source();
            if (source == null || !"DWAION_HOME".equals(source.sourceKey())
                    || result.state() == null) {
                invalidProjection();
            }
            switch (result.state()) {
                case AVAILABLE -> {
                    validateDataPayload(result.payload(), request.itemLimit());
                    validateSourceAction(result.actions());
                    if (!result.redactions().isEmpty() || source.reasonCode() != null
                            || source.retryable() || !validResultVersion(source)) {
                        invalidProjection();
                    }
                }
                case PARTIAL -> {
                    validateDataPayload(result.payload(), request.itemLimit());
                    validateSourceAction(result.actions());
                    if (!List.of("ARTIFACT_TITLE_PROJECTION_UNREADABLE")
                            .equals(result.redactions())
                            || !"PROVIDER_DWAION_ARTIFACT_TITLE_PARTIAL"
                            .equals(source.reasonCode())
                            || source.retryable() || !validResultVersion(source)) {
                        invalidProjection();
                    }
                }
                case EMPTY -> {
                    validateSourceAction(result.actions());
                    if (!result.payload().isEmpty() || !result.redactions().isEmpty()
                            || source.reasonCode() != null || source.retryable()
                            || !validResultVersion(source)) {
                        invalidProjection();
                    }
                }
                case FORBIDDEN -> {
                    if (!result.payload().isEmpty() || !result.actions().isEmpty()
                            || !result.redactions().isEmpty()
                            || !"AUTHORIZATION_DWAION_ARTIFACT_REQUIRED"
                            .equals(source.reasonCode())
                            || source.retryable() || source.resultVersion() != null) {
                        invalidProjection();
                    }
                }
                case UNAVAILABLE -> {
                    if (!result.payload().isEmpty() || !result.actions().isEmpty()
                            || !result.redactions().isEmpty()
                            || !UNAVAILABLE_REASONS.contains(source.reasonCode())
                            || !source.retryable() || source.resultVersion() != null) {
                        invalidProjection();
                    }
                }
                default -> invalidProjection();
            }
        }
    }

    private void validateDataPayload(Map<String, Object> payload, int itemLimit) {
        com.fasterxml.jackson.databind.JsonNode node = mapper.valueToTree(payload);
        if (!node.isObject() || node.size() != 2
                || !node.has("visibleCount") || !node.has("items")
                || !node.get("visibleCount").isIntegralNumber()
                || !node.get("visibleCount").canConvertToInt()
                || node.get("visibleCount").intValue() < 1
                || !node.get("items").isArray()
                || node.get("items").isEmpty()
                || node.get("items").size() > itemLimit
                || node.get("visibleCount").intValue() < node.get("items").size()) {
            invalidProjection();
        }
        Set<UUID> artifactIds = new HashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode item : node.get("items")) {
            if (!item.isObject()) invalidProjection();
            Set<String> names = new HashSet<>();
            item.fieldNames().forEachRemaining(names::add);
            if (!names.equals(ARTIFACT_ITEM_FIELDS)
                    || !text(item, "title", 200)
                    || !text(item, "artifactType", 64)
                    || !text(item, "state", 64)
                    || !ARTIFACT_TYPES.contains(item.get("artifactType").asText())
                    || !ARTIFACT_STATES.contains(item.get("state").asText())
                    || !item.get("revision").isIntegralNumber()
                    || !item.get("revision").canConvertToInt()
                    || item.get("revision").intValue() < 1) {
                invalidProjection();
            }
            try {
                if (!artifactIds.add(UUID.fromString(item.get("artifactId").asText()))) {
                    invalidProjection();
                }
                OffsetDateTime.parse(item.get("updatedAt").asText());
            } catch (RuntimeException exception) {
                invalidProjection();
            }
        }
    }

    private boolean text(com.fasterxml.jackson.databind.JsonNode node, String field, int maximum) {
        return node.has(field) && node.get(field).isTextual()
                && !node.get(field).asText().isBlank()
                && node.get(field).asText().length() <= maximum;
    }

    private boolean validResultVersion(HomeWidgetProviderContract.SourceState source) {
        return source.resultVersion() != null
                && source.resultVersion().matches("v1:[0-9a-f]{32}");
    }

    private void validateSourceAction(List<HomeWidgetProviderContract.Action> actions) {
        if (actions.size() != 1) invalidProjection();
        HomeWidgetProviderContract.Action action = actions.getFirst();
        if (!"open-source".equals(action.actionId())
                || !"home.action.openSource".equals(action.labelKey())
                || action.kind() != HomeWidgetProviderContract.ActionKind.SOURCE_ROUTE
                || !"/dwaion/artifacts".equals(action.sourceRoute())
                || action.commandKey() != null || action.expectedResultVersion() != null
                || action.requiresConfirmation()) {
            invalidProjection();
        }
    }

    private void invalidProjection() {
        throw failure(WidgetProviderException.Kind.MALFORMED,
                "PROVIDER_PROJECTION_INVALID",
                "DWAI-ON response exceeded its title-only projection contract.", null);
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
