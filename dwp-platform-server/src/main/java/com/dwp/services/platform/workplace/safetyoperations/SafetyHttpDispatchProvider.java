package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderHttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

/** Production safety provider boundary backed by the allowlisted Workplace HTTPS relay. */
@Component
class SafetyHttpDispatchProvider implements SafetyDispatchProvider {
    private static final String DISPATCH_PATH =
            "/internal/v1/workplace/safety-provider/dispatches";
    private static final String LOOKUP_PATH =
            "/internal/v1/workplace/safety-provider/dispatches/";
    private static final String RUNTIME_PATH =
            "/internal/v1/workplace/safety-provider/runtime/";

    private final Optional<WorkplaceProviderHttpTransport> transport;
    private final SafetyProviderRelayBindings bindings;

    SafetyHttpDispatchProvider(
            Optional<WorkplaceProviderHttpTransport> transport,
            SafetyProviderRelayBindings bindings) {
        this.transport = transport;
        this.bindings = bindings;
    }

    @Override
    public boolean supports(DeliveryChannel channel) {
        return channel != null;
    }

    @Override
    public boolean ready(ProviderContext context) {
        return context != null && transport.isPresent()
                && transport.orElseThrow().ready(
                        context.providerCode(), context.credentialReference());
    }

    @Override
    @Bulkhead(name = "workplaceSafetyProviderRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceSafetyProviderRelay")
    @Retry(name = "idempotentConnector")
    public DispatchResult dispatch(DispatchRequest request) {
        ProviderContext context = requireReady(request.providerContext());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptId", request.attemptId());
        body.put("tenantId", request.tenantId());
        body.put("incidentId", request.incidentId());
        body.put("channel", request.channel().name());
        body.put("providerCode", context.providerCode());
        body.put("providerConfigurationVersion", context.providerConfigurationVersion());
        body.put("subjectKeySha256", request.subjectKeySha256());
        if (request.subjectUserId() != null) body.put("subjectUserId", request.subjectUserId());
        body.put("severity", request.severity().name());
        body.put("message", request.message());
        body.put("safetyAction", request.safetyAction());
        try {
            JsonNode response = transport.orElseThrow().post(context.providerCode(),
                    context.credentialReference(), DISPATCH_PATH, request.tenantId(),
                    request.attemptId().toString(), Map.copyOf(body));
            return result(response, request.attemptId(), request.tenantId(), context);
        } catch (RestClientResponseException rejected) {
            return definitiveRejection(rejected.getStatusCode())
                    ? failed("PROVIDER_REQUEST_REJECTED")
                    : unknown(null, "PROVIDER_TRANSPORT_OUTCOME_UNKNOWN");
        } catch (RestClientException unavailable) {
            return unknown(null, "PROVIDER_TRANSPORT_OUTCOME_UNKNOWN");
        } catch (RuntimeException invalidResponse) {
            return unknown(null, "PROVIDER_RESPONSE_INVALID");
        }
    }

    @Override
    @Bulkhead(name = "workplaceSafetyProviderRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceSafetyProviderRelay")
    @Retry(name = "idempotentConnector")
    public DispatchResult lookupStatus(LookupRequest request) {
        ProviderContext context = requireReady(request.providerContext());
        try {
            JsonNode response = transport.orElseThrow().get(context.providerCode(),
                    context.credentialReference(), LOOKUP_PATH + request.attemptId(),
                    request.tenantId());
            return result(response, request.attemptId(), request.tenantId(), context);
        } catch (RestClientResponseException rejected) {
            return unknown(request.providerOperationReference(), "STATUS_LOOKUP_UNAVAILABLE");
        } catch (RuntimeException unavailable) {
            return unknown(request.providerOperationReference(), "STATUS_LOOKUP_UNAVAILABLE");
        }
    }

    @Bulkhead(name = "workplaceSafetyProviderRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceSafetyProviderRelay")
    @Retry(name = "idempotentConnector")
    RuntimeObservation observe(
            long tenantId, ConnectorKind kind, ProviderContext context) {
        requireReady(context);
        JsonNode response = transport.orElseThrow().get(context.providerCode(),
                context.credentialReference(), RUNTIME_PATH + kind.name().toLowerCase(), tenantId);
        if (response == null || !response.isObject()
                || response.path("tenantId").asLong(Long.MIN_VALUE) != tenantId
                || !kind.name().equals(text(response, "connectorKind"))
                || !context.providerCode().equals(text(response, "providerCode"))
                || response.path("providerConfigurationVersion").asLong(Long.MIN_VALUE)
                    != context.providerConfigurationVersion()) {
            throw new IllegalStateException("The safety provider runtime response is invalid.");
        }
        ProviderReportedState state;
        try {
            state = ProviderReportedState.valueOf(text(response, "reportedState"));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("The safety provider runtime state is invalid.");
        }
        String evidence = requiredText(response, "evidenceReference", 320);
        OffsetDateTime sourceAt;
        try {
            sourceAt = OffsetDateTime.parse(requiredText(response, "sourceObservedAt", 64));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("The safety provider runtime clock is invalid.");
        }
        return new RuntimeObservation(state, evidence, sourceAt,
                optionalDate(response, "lastSuccessAt"), boundedText(response, "errorCode", 120));
    }

    Optional<ProviderContext> configured(DeliveryChannel channel) {
        return bindings.resolve(channel);
    }

    Optional<ProviderContext> configured(
            DeliveryChannel channel, String provider, long configurationVersion) {
        return bindings.resolve(channel, provider, configurationVersion);
    }

    private ProviderContext requireReady(ProviderContext context) {
        if (!ready(context)) {
            throw new IllegalStateException("The safety provider relay is not configured.");
        }
        return context;
    }

    private static DispatchResult result(
            JsonNode response, java.util.UUID attemptId, long tenantId, ProviderContext context) {
        if (response == null || !response.isObject()
                || !attemptId.toString().equals(text(response, "attemptId"))
                || response.path("tenantId").asLong(Long.MIN_VALUE) != tenantId
                || !context.channel().name().equals(text(response, "channel"))
                || !context.providerCode().equals(text(response, "providerCode"))
                || response.path("providerConfigurationVersion").asLong(Long.MIN_VALUE)
                    != context.providerConfigurationVersion()) {
            return unknown(null, "PROVIDER_RESPONSE_INVALID");
        }
        String reference = boundedText(response, "providerOperationReference", 320);
        String code = boundedText(response, "resultCode", 120);
        String evidence = boundedText(response, "evidenceReference", 320);
        return switch (text(response, "state")) {
            case "DELIVERED" -> evidence == null
                    ? unknown(reference, "PROVIDER_RESPONSE_INVALID")
                    : new DispatchResult(AttemptState.DELIVERED, reference,
                            code == null ? "DELIVERED" : code, evidence);
            case "DELIVERY_FAILED" -> new DispatchResult(AttemptState.DELIVERY_FAILED,
                    reference, code == null ? "PROVIDER_DELIVERY_FAILED" : code, evidence);
            case "RESULT_UNKNOWN" -> unknown(reference,
                    code == null ? "PROVIDER_RESULT_UNKNOWN" : code);
            default -> unknown(reference, "PROVIDER_RESPONSE_INVALID");
        };
    }

    private static DispatchResult failed(String code) {
        return new DispatchResult(AttemptState.DELIVERY_FAILED, null, code, null);
    }

    private static DispatchResult unknown(String reference, String code) {
        return new DispatchResult(AttemptState.RESULT_UNKNOWN, reference, code, null);
    }

    private static boolean definitiveRejection(HttpStatusCode status) {
        return status.is4xxClientError() && status.value() != 408
                && status.value() != 425 && status.value() != 429;
    }

    private static String requiredText(JsonNode node, String field, int maximumLength) {
        String value = boundedText(node, field, maximumLength);
        if (value == null) throw new IllegalStateException("Provider response field is missing.");
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static String nullableText(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() ? null : value;
    }

    private static String boundedText(JsonNode node, String field, int maximumLength) {
        String value = nullableText(node, field);
        if (value != null && value.length() > maximumLength) {
            throw new IllegalStateException("Provider response field is too long.");
        }
        return value;
    }

    private static OffsetDateTime optionalDate(JsonNode node, String field) {
        String value = boundedText(node, field, 64);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("The safety provider runtime clock is invalid.");
        }
    }

    record RuntimeObservation(
            ProviderReportedState reportedState,
            String evidenceReference,
            OffsetDateTime sourceAt,
            OffsetDateTime lastSuccessAt,
            String errorCode) { }
}
