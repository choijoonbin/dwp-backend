package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderHttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyDispatchProvider.ProviderContext;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.CommandState;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.DeliveryChannel;

/** External emergency handoff adapter. It never receives or returns provider credentials. */
@Component
class SafetyEmergencyHandoffProvider {
    private static final String HANDOFF_PATH =
            "/internal/v1/workplace/safety-provider/emergency-handoffs";

    private final Optional<WorkplaceProviderHttpTransport> transport;
    private final SafetyProviderRelayBindings bindings;

    SafetyEmergencyHandoffProvider(
            Optional<WorkplaceProviderHttpTransport> transport,
            SafetyProviderRelayBindings bindings) {
        this.transport = transport;
        this.bindings = bindings;
    }

    boolean ready(String providerCode, long configurationVersion) {
        ProviderContext context = context(providerCode, configurationVersion).orElse(null);
        return context != null && transport.isPresent()
                && transport.orElseThrow().ready(providerCode, context.credentialReference());
    }

    Result handoff(
            UUID handoffId,
            long tenantId,
            UUID incidentId,
            UUID contactId,
            String providerCode,
            long configurationVersion) {
        ProviderContext context = requireReady(providerCode, configurationVersion);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("handoffId", handoffId);
        body.put("tenantId", tenantId);
        body.put("incidentId", incidentId);
        body.put("contactId", contactId);
        body.put("providerCode", providerCode);
        body.put("providerConfigurationVersion", configurationVersion);
        try {
            JsonNode response = transport.orElseThrow().post(providerCode,
                    context.credentialReference(), HANDOFF_PATH, tenantId,
                    handoffId.toString(), Map.copyOf(body));
            return result(response, handoffId, tenantId, providerCode, configurationVersion);
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

    Result lookup(
            UUID handoffId,
            long tenantId,
            String providerCode,
            long configurationVersion,
            String providerOperationReference) {
        ProviderContext context = requireReady(providerCode, configurationVersion);
        try {
            JsonNode response = transport.orElseThrow().get(providerCode,
                    context.credentialReference(), HANDOFF_PATH + "/" + handoffId, tenantId);
            return result(response, handoffId, tenantId, providerCode, configurationVersion);
        } catch (RuntimeException unavailable) {
            return unknown(providerOperationReference, "STATUS_LOOKUP_UNAVAILABLE");
        }
    }

    private Optional<ProviderContext> context(String providerCode, long configurationVersion) {
        return bindings.resolve(DeliveryChannel.APP_PUSH, providerCode, configurationVersion);
    }

    private ProviderContext requireReady(String providerCode, long configurationVersion) {
        ProviderContext context = context(providerCode, configurationVersion).orElse(null);
        if (context == null || transport.isEmpty()
                || !transport.orElseThrow().ready(providerCode, context.credentialReference())) {
            throw new IllegalStateException("The emergency handoff provider is not configured.");
        }
        return context;
    }

    private static Result result(
            JsonNode response,
            UUID handoffId,
            long tenantId,
            String providerCode,
            long configurationVersion) {
        if (response == null || !response.isObject()
                || !handoffId.toString().equals(text(response, "handoffId"))
                || response.path("tenantId").asLong(Long.MIN_VALUE) != tenantId
                || !providerCode.equals(text(response, "providerCode"))
                || response.path("providerConfigurationVersion").asLong(Long.MIN_VALUE)
                    != configurationVersion) {
            return unknown(null, "PROVIDER_RESPONSE_INVALID");
        }
        String reference = bounded(response, "providerOperationReference", 320);
        String code = bounded(response, "resultCode", 120);
        String evidence = bounded(response, "evidenceReference", 320);
        return switch (text(response, "state")) {
            case "SUCCEEDED" -> evidence == null
                    ? unknown(reference, "PROVIDER_RESPONSE_INVALID")
                    : new Result(CommandState.SUCCEEDED,
                            code == null ? "HANDOFF_ACCEPTED" : code, reference, evidence);
            case "FAILED" -> new Result(CommandState.FAILED,
                    code == null ? "PROVIDER_HANDOFF_FAILED" : code, reference, evidence);
            case "RESULT_UNKNOWN" -> unknown(reference,
                    code == null ? "PROVIDER_RESULT_UNKNOWN" : code);
            default -> unknown(reference, "PROVIDER_RESPONSE_INVALID");
        };
    }

    private static Result failed(String code) {
        return new Result(CommandState.FAILED, code, null, null);
    }

    private static Result unknown(String reference, String code) {
        return new Result(CommandState.RESULT_UNKNOWN, code, reference, null);
    }

    private static boolean definitiveRejection(HttpStatusCode status) {
        return status.is4xxClientError() && status.value() != 408
                && status.value() != 425 && status.value() != 429;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static String bounded(JsonNode node, String field, int maximumLength) {
        String value = text(node, field);
        if (value.isBlank()) return null;
        if (value.length() > maximumLength) {
            throw new IllegalStateException("The provider response is too long.");
        }
        return value;
    }

    record Result(
            CommandState state,
            String resultCode,
            String providerOperationReference,
            String providerEvidenceReference) { }
}
