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
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public final class HttpWidgetProviderClient implements WidgetProviderPort {

    private static final Duration MAX_DEADLINE_AHEAD = Duration.ofSeconds(1);
    private static final Set<String> OWNER_PROVIDERS = Set.of(
            "approval", "meeting", "notification", "space", "messaging", "people");

    private final String providerKey;
    private final String serviceToken;
    private final boolean commandsEnabled;
    private final RestClient client;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public HttpWidgetProviderClient(
            String providerKey,
            String baseUrl,
            String serviceToken,
            boolean commandsEnabled,
            Duration timeout,
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads) {
        if (!OWNER_PROVIDERS.contains(providerKey)) {
            throw new IllegalArgumentException("Unknown Home owner provider.");
        }
        this.providerKey = providerKey;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.commandsEnabled = commandsEnabled;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
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

    /** Compatibility constructor for read-only clients and tests. */
    public HttpWidgetProviderClient(
            String providerKey,
            String baseUrl,
            String serviceToken,
            Duration timeout,
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads) {
        this(providerKey, baseUrl, serviceToken, false, timeout, builder, circuitBreakers, bulkheads);
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
        validateRead(context, requests, deadline);
        if (serviceToken.isBlank()) {
            throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_NOT_CONFIGURED", "Home provider transport is not configured.", null);
        }
        Supplier<HomeWidgetProviderContract.BatchResponse> invocation = () ->
                validateBatchEnvelope(context, requests, invoke(context, requests, deadline));
        Supplier<HomeWidgetProviderContract.BatchResponse> isolated =
                CircuitBreaker.decorateSupplier(
                        circuitBreaker,
                        Bulkhead.decorateSupplier(bulkhead, invocation));
        try {
            HomeWidgetProviderContract.BatchResponse response = isolated.get();
            validateContextAndDeadline(context, deadline);
            return response;
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
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
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

    @Override
    public HomeWidgetProviderContract.CommandResponse executeCommand(
            HomeRuntimeContext context,
            HomeWidgetProviderContract.CommandRequest command,
            OffsetDateTime deadline) {
        validateCommand(context, command, deadline);
        if (!commandsEnabled) {
            throw failure(WidgetProviderException.Kind.FORBIDDEN,
                    "COMMAND_FEATURE_DISABLED",
                    "Home provider commands are disabled for this deployment.", null);
        }
        if (serviceToken.isBlank()) {
            throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_NOT_CONFIGURED", "Home provider transport is not configured.", null);
        }
        Supplier<HomeWidgetProviderContract.CommandResponse> invocation = () ->
                validateCommandEnvelope(context, command, invokeCommand(context, command, deadline));
        Supplier<HomeWidgetProviderContract.CommandResponse> isolated =
                CircuitBreaker.decorateSupplier(
                        circuitBreaker,
                        Bulkhead.decorateSupplier(bulkhead, invocation));
        try {
            HomeWidgetProviderContract.CommandResponse response = isolated.get();
            validateContextAndDeadline(context, deadline);
            return response;
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

    private HomeWidgetProviderContract.CommandResponse invokeCommand(
            HomeRuntimeContext context,
            HomeWidgetProviderContract.CommandRequest command,
            OffsetDateTime deadline) {
        try {
            RestClient.RequestBodySpec request = client.post()
                    .uri(HomeWidgetProviderContract.COMMAND_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                    .header(HomeWidgetProviderContract.SERVICE_TOKEN_HEADER, serviceToken)
                    .header(HomeWidgetProviderContract.SERVICE_IDENTITY_HEADER,
                            "dwp-platform-server")
                    .header("Idempotency-Key", command.commandId().toString())
                    .header("X-DWP-Tenant-ID", Long.toString(context.tenantId()))
                    .header("X-DWP-User-ID", Long.toString(context.userId()))
                    .header("X-DWP-Permissions", context.permissionsHeader())
                    .header("X-DWP-Roles", context.rolesHeader())
                    .header("X-DWP-Group-Refs", context.groupsHeader())
                    .header(HomeWidgetProviderContract.AUTHORITY_REVISION_HEADER,
                            context.authorityDecisionRevision())
                    .header("X-DWP-Current-Revalidate-At",
                            context.authorityRevalidateAt().toString())
                    .header("X-DWP-Home-Deadline-At", deadline.toString());
            if (context.personPublicId() != null) {
                request.header("X-DWP-Person-Public-ID", context.personPublicId().toString());
            }
            return request.body(command).retrieve()
                    .body(HomeWidgetProviderContract.CommandResponse.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw failure(WidgetProviderException.Kind.FORBIDDEN,
                        "AUTHORIZATION_PROVIDER_FORBIDDEN",
                        "Home provider denied the command.", exception);
            }
            if (exception.getStatusCode().is5xxServerError()) {
                throw failure(WidgetProviderException.Kind.UNAVAILABLE,
                        "PROVIDER_HTTP_5XX", "Home provider is unavailable.", exception);
            }
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_COMMAND_REJECTED",
                    "Home provider rejected the command contract.", exception);
        } catch (RestClientException exception) {
            WidgetProviderException.Kind kind = causedByTimeout(exception)
                    ? WidgetProviderException.Kind.TIMEOUT : WidgetProviderException.Kind.UNAVAILABLE;
            throw failure(kind, kind == WidgetProviderException.Kind.TIMEOUT
                    ? "PROVIDER_TIMEOUT" : "PROVIDER_TRANSPORT_FAILURE",
                    "Home provider command transport failed.", exception);
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

    private void validateRead(
            HomeRuntimeContext context,
            List<Request> requests,
            OffsetDateTime deadline) {
        validateContextAndDeadline(context, deadline);
        if (requests == null || requests.isEmpty()
                || requests.size() > HomeWidgetProviderContract.MAX_WIDGETS_PER_BATCH) {
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_BATCH_OUT_OF_BOUNDS",
                    "Home provider batch is outside the contract bounds.", null);
        }
        for (Request request : requests) {
            if (request == null || request.instanceId() == null || request.definition() == null
                    || request.itemLimit() < 1
                    || request.itemLimit() > HomeWidgetProviderContract.MAX_ITEM_LIMIT) {
                throw failure(WidgetProviderException.Kind.MALFORMED,
                        "PROVIDER_REQUEST_OUT_OF_BOUNDS",
                        "Home provider request is outside the contract bounds.", null);
            }
        }
    }

    private void validateCommand(
            HomeRuntimeContext context,
            HomeWidgetProviderContract.CommandRequest command,
            OffsetDateTime deadline) {
        validateContextAndDeadline(context, deadline);
        if (command == null || command.commandId() == null || command.instanceId() == null
                || command.definitionKey() == null || command.definitionKey().isBlank()
                || command.actionId() == null || command.actionId().isBlank()
                || command.commandKey() == null || command.commandKey().isBlank()) {
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_COMMAND_OUT_OF_BOUNDS",
                    "Home provider command is outside the contract bounds.", null);
        }
    }

    private void validateContextAndDeadline(
            HomeRuntimeContext context,
            OffsetDateTime deadline) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (context == null) {
            throw failure(WidgetProviderException.Kind.TIMEOUT,
                    "PROVIDER_DEADLINE_EXPIRED",
                    "Home provider authority context is unavailable.", null);
        }
        context.requireAuthorityCurrent();
        if (deadline == null || !deadline.isAfter(now)
                || deadline.isAfter(now.plus(MAX_DEADLINE_AHEAD))
                || deadline.isAfter(context.authorityRevalidateAt())) {
            throw failure(WidgetProviderException.Kind.TIMEOUT,
                    "PROVIDER_DEADLINE_EXPIRED",
                    "Home provider deadline is expired or outside the broker bound.", null);
        }
    }

    private HomeWidgetProviderContract.BatchResponse validateBatchEnvelope(
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
                || response.authorityDecisionRevision() == null
                || !context.authorityDecisionRevision().equals(response.authorityDecisionRevision())
                || response.results() == null
                || response.results().size() > requests.size()
                || response.results().size() > HomeWidgetProviderContract.MAX_WIDGETS_PER_BATCH
                || response.results().stream().anyMatch(result -> result == null
                        || result.instanceId() == null
                        || !requested.contains(result.instanceId())
                        || !returned.add(result.instanceId()))) {
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_ENVELOPE_MISMATCH",
                    "Home provider response envelope does not match its recipient context.", null);
        }
        return response;
    }

    private HomeWidgetProviderContract.CommandResponse validateCommandEnvelope(
            HomeRuntimeContext context,
            HomeWidgetProviderContract.CommandRequest command,
            HomeWidgetProviderContract.CommandResponse response) {
        if (response == null
                || response.schemaVersion() != HomeWidgetProviderContract.SCHEMA_VERSION
                || response.tenantId() != context.tenantId()
                || response.userId() != context.userId()
                || response.authorityDecisionRevision() == null
                || !context.authorityDecisionRevision().equals(response.authorityDecisionRevision())
                || !command.commandId().equals(response.commandId())
                || !command.actionId().equals(response.actionId())
                || !command.commandKey().equals(response.commandKey())) {
            throw failure(WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_COMMAND_ENVELOPE_MISMATCH",
                    "Home provider command receipt does not match its recipient context.", null);
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
