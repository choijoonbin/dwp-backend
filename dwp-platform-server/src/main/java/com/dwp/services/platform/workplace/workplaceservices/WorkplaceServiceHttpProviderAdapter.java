package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderHttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceLineAdjustmentProvider.*;

/**
 * Production boundary for reservation-linked service providers. The database supplies only the
 * immutable opaque credential reference captured when the command was accepted; the shared
 * transport leases the runtime bearer credential for one request and clears it afterwards.
 */
@Component
@Primary
public class WorkplaceServiceHttpProviderAdapter implements
        WorkplaceServiceProviderVerifier,
        WorkplaceServiceEphemeralCredentialProvider,
        WorkplaceServiceLineAdjustmentProvider {
    private static final String VERIFY_PATH =
            "/internal/v1/workplace/service-provider/verifications/";
    private static final String ACCESS_PATH =
            "/internal/v1/workplace/service-provider/access-grants/";
    private static final String ADJUSTMENT_PATH =
            "/internal/v1/workplace/service-provider/line-adjustments/";
    private static final Pattern ADAPTER = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final int MAX_REFERENCE = 320;
    private static final int MAX_DETAIL = 1_000;

    private final Optional<WorkplaceProviderHttpTransport> transport;
    private final Set<String> adapterTypes;

    WorkplaceServiceHttpProviderAdapter(
            Optional<WorkplaceProviderHttpTransport> transport,
            @Value("${dwp.workplace.services.provider-relay.adapter-types:}")
            String adapterTypes) {
        this.transport = transport;
        this.adapterTypes = parseAdapterTypes(adapterTypes);
    }

    @Override
    public boolean supports(String adapterType) {
        return adapterType != null && adapterTypes.contains(adapterType);
    }

    @Override
    @Bulkhead(name = "workplaceServiceProviderRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceServiceProviderRelay")
    @Retry(name = "idempotentConnector")
    public VerificationResult verify(VerificationRequest request) {
        WorkplaceProviderHttpTransport relay = requireReady(
                request.providerCode(), request.credentialBindingReference());
        try {
            JsonNode response = relay.get(request.providerCode(),
                    request.credentialBindingReference(),
                    VERIFY_PATH + request.operationId(), request.tenantId());
            return verification(response, request);
        } catch (RestClientException unavailable) {
            throw unavailable("Provider verification is unavailable.");
        } catch (RuntimeException invalidResponse) {
            throw unavailable("Provider verification returned invalid data.");
        }
    }

    @Override
    @Bulkhead(name = "workplaceServiceCredentialRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceServiceCredentialRelay")
    @Retry(name = "idempotentConnector")
    public IssuedCredential issue(IssueRequest request) {
        WorkplaceProviderHttpTransport relay = requireReady(
                request.providerCode(), request.credentialBindingReference());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", request.operationId());
        body.put("tenantId", request.tenantId());
        body.put("serviceOrderId", request.serviceOrderId());
        body.put("serviceOrderLineId", request.serviceOrderLineId());
        body.put("providerCode", request.providerCode());
        body.put("providerConfigurationVersion", request.providerConfigurationVersion());
        body.put("notAfter", request.notAfter());
        try {
            JsonNode response = relay.post(request.providerCode(),
                    request.credentialBindingReference(), ACCESS_PATH + request.operationId()
                            + ":issue", request.tenantId(), request.operationId().toString(),
                    Map.copyOf(body));
            return issued(response, request);
        } catch (RestClientResponseException rejected) {
            throw outcomeUnknown("Credential issue outcome is unknown.", rejected);
        } catch (RestClientException unavailable) {
            throw outcomeUnknown("Credential issue outcome is unknown.", unavailable);
        } catch (RuntimeException invalidResponse) {
            throw outcomeUnknown("Credential provider response is invalid.", invalidResponse);
        }
    }

    @Override
    @Bulkhead(name = "workplaceServiceCredentialRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceServiceCredentialRelay")
    @Retry(name = "idempotentConnector")
    public IssuedCredential lookupIssue(IssueRequest request) {
        WorkplaceProviderHttpTransport relay = requireReady(
                request.providerCode(), request.credentialBindingReference());
        try {
            JsonNode response = relay.get(request.providerCode(),
                    request.credentialBindingReference(), ACCESS_PATH + request.operationId(),
                    request.tenantId());
            return issued(response, request);
        } catch (RuntimeException unavailable) {
            throw outcomeUnknown("Credential issue status is unavailable.", unavailable);
        }
    }

    @Override
    @Bulkhead(name = "workplaceServiceCredentialRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceServiceCredentialRelay")
    @Retry(name = "idempotentConnector")
    public RevokeResult revoke(RevokeRequest request) {
        WorkplaceProviderHttpTransport relay = requireReady(
                request.providerCode(), request.credentialBindingReference());
        try {
            JsonNode response = relay.post(request.providerCode(),
                    request.credentialBindingReference(), ACCESS_PATH + request.operationId()
                            + ":revoke", request.tenantId(), request.operationId().toString(),
                    Map.of("operationId", request.operationId(),
                            "tenantId", request.tenantId(),
                            "providerCode", request.providerCode(),
                            "providerConfigurationVersion",
                            request.providerConfigurationVersion(),
                            "providerGrantReference", request.providerGrantReference()));
            return revoked(response, request);
        } catch (RestClientResponseException rejected) {
            return definitiveRejection(rejected.getStatusCode())
                    ? new RevokeResult(false, false, "PROVIDER_REQUEST_REJECTED")
                    : new RevokeResult(false, true, "PROVIDER_OUTCOME_UNKNOWN");
        } catch (RuntimeException unavailable) {
            return new RevokeResult(false, true, "PROVIDER_OUTCOME_UNKNOWN");
        }
    }

    @Override
    @Bulkhead(name = "workplaceServiceCredentialRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceServiceCredentialRelay")
    @Retry(name = "idempotentConnector")
    public RevokeResult lookupRevoke(RevokeRequest request) {
        WorkplaceProviderHttpTransport relay = requireReady(
                request.providerCode(), request.credentialBindingReference());
        try {
            JsonNode response = relay.get(request.providerCode(),
                    request.credentialBindingReference(), ACCESS_PATH + request.operationId()
                            + "/revocation", request.tenantId());
            return revoked(response, request);
        } catch (RuntimeException unavailable) {
            return new RevokeResult(false, true, "PROVIDER_STATUS_UNAVAILABLE");
        }
    }

    @Override
    @Bulkhead(name = "workplaceServiceAdjustmentRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceServiceAdjustmentRelay")
    @Retry(name = "idempotentConnector")
    public ProviderOutcome cancel(ProviderRequest request) {
        WorkplaceProviderHttpTransport relay;
        try {
            relay = requireReady(request.providerCode(), request.credentialBindingReference());
        } catch (IllegalStateException unconfigured) {
            return notConfigured();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", request.operationId());
        body.put("tenantId", request.tenantId());
        body.put("serviceOrderId", request.serviceOrderId());
        body.put("serviceOrderLineId", request.serviceOrderLineId());
        body.put("providerCode", request.providerCode());
        body.put("providerConfigurationVersion", request.providerConfigurationVersion());
        body.put("cancelQuantity", request.cancelQuantity());
        body.put("refundableAmount", request.refundableAmount());
        body.put("currency", request.currency());
        try {
            JsonNode response = relay.post(request.providerCode(),
                    request.credentialBindingReference(), ADJUSTMENT_PATH
                            + request.operationId() + ":cancel", request.tenantId(),
                    request.operationId().toString(), Map.copyOf(body));
            return adjustment(response, request);
        } catch (RestClientResponseException rejected) {
            if (definitiveRejection(rejected.getStatusCode())) {
                return failed(request, "PROVIDER_REQUEST_REJECTED");
            }
            throw new OutcomeUncertainException(request.operationId().toString(),
                    "PROVIDER_OUTCOME_UNKNOWN", rejected);
        } catch (RestClientException unavailable) {
            throw new OutcomeUncertainException(request.operationId().toString(),
                    "PROVIDER_OUTCOME_UNKNOWN", unavailable);
        } catch (RuntimeException invalidResponse) {
            throw new OutcomeUncertainException(request.operationId().toString(),
                    "PROVIDER_RESPONSE_INVALID", invalidResponse);
        }
    }

    @Override
    @Bulkhead(name = "workplaceServiceAdjustmentRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceServiceAdjustmentRelay")
    @Retry(name = "idempotentConnector")
    public ProviderOutcome lookup(
            ProviderRequest request, String providerOperationReference) {
        WorkplaceProviderHttpTransport relay;
        try {
            relay = requireReady(request.providerCode(), request.credentialBindingReference());
        } catch (IllegalStateException unconfigured) {
            return notConfigured();
        }
        try {
            JsonNode response = relay.get(request.providerCode(),
                    request.credentialBindingReference(), ADJUSTMENT_PATH
                            + request.operationId(), request.tenantId());
            return adjustment(response, request);
        } catch (RuntimeException unavailable) {
            return new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                    normalizeReference(providerOperationReference, request.operationId()),
                    BigDecimal.ZERO, null, "PROVIDER_STATUS_UNAVAILABLE");
        }
    }

    @Override
    public ProviderOutcome reconcile(
            ProviderRequest request, String providerOperationReference) {
        return lookup(request, providerOperationReference);
    }

    private WorkplaceProviderHttpTransport requireReady(String provider, String reference) {
        WorkplaceProviderHttpTransport relay = transport.orElseThrow(() ->
                new IllegalStateException("The Workplace service provider relay is disabled."));
        if (!relay.ready(provider, reference)) {
            throw new IllegalStateException(
                    "The Workplace service provider binding is not configured.");
        }
        return relay;
    }

    private static VerificationResult verification(
            JsonNode response, VerificationRequest request) {
        requireIdentity(response, request.operationId(), request.tenantId(),
                request.providerCode(), request.configurationVersion());
        String state = requiredText(response, "reportedState", 24);
        if (!Set.of("HEALTHY", "DEGRADED", "UNAVAILABLE").contains(state)) {
            throw new IllegalStateException("Provider verification state is invalid.");
        }
        String evidence = requiredText(response, "evidenceReference", MAX_REFERENCE);
        List<String> capabilityEvidence = stringArray(response, "capabilityEvidence", 100, 80);
        if (!new LinkedHashSet<>(request.capabilities()).containsAll(capabilityEvidence)) {
            throw new IllegalStateException("Provider capability evidence is invalid.");
        }
        OffsetDateTime observedAt = date(response, "sourceObservedAt");
        return new VerificationResult(state, evidence, capabilityEvidence, observedAt,
                optionalText(response, "errorCode", 120));
    }

    private static IssuedCredential issued(JsonNode response, IssueRequest request) {
        requireIdentity(response, request.operationId(), request.tenantId(),
                request.providerCode(), request.providerConfigurationVersion());
        if (!request.serviceOrderId().toString().equals(text(response, "serviceOrderId"))
                || !request.serviceOrderLineId().toString()
                    .equals(text(response, "serviceOrderLineId"))
                || !"ISSUED".equals(text(response, "state"))) {
            throw new IllegalStateException("Credential issue response is invalid.");
        }
        String grant = requiredText(response, "providerGrantReference", MAX_REFERENCE);
        String credential = requiredText(response, "oneTimeCredential", 512);
        OffsetDateTime expiresAt = date(response, "expiresAt");
        if (expiresAt.isAfter(request.notAfter())) {
            throw new IllegalStateException("Credential expiry exceeds the requested boundary.");
        }
        return new IssuedCredential(grant, credential, expiresAt);
    }

    private static RevokeResult revoked(JsonNode response, RevokeRequest request) {
        requireIdentity(response, request.operationId(), request.tenantId(),
                request.providerCode(), request.providerConfigurationVersion());
        return switch (text(response, "state")) {
            case "REVOKED" -> new RevokeResult(true, false,
                    optionalText(response, "detailCode", MAX_DETAIL));
            case "FAILED" -> new RevokeResult(false, false,
                    defaultDetail(response, "PROVIDER_REVOKE_FAILED"));
            case "RESULT_UNKNOWN" -> new RevokeResult(false, true,
                    defaultDetail(response, "PROVIDER_RESULT_UNKNOWN"));
            default -> throw new IllegalStateException("Credential revoke response is invalid.");
        };
    }

    private static ProviderOutcome adjustment(JsonNode response, ProviderRequest request) {
        requireIdentity(response, request.operationId(), request.tenantId(),
                request.providerCode(), request.providerConfigurationVersion());
        String reference = optionalText(response, "providerOperationReference", MAX_REFERENCE);
        BigDecimal refunded = decimal(response, "refundedAmount");
        String receipt = optionalText(response, "refundReceiptReference", MAX_REFERENCE);
        String detail = optionalText(response, "detailCode", MAX_DETAIL);
        return switch (text(response, "state")) {
            case "SUCCEEDED" -> new ProviderOutcome(OutcomeState.SUCCEEDED,
                    reference == null ? request.operationId().toString() : reference,
                    refunded, receipt, detail);
            case "FAILED" -> new ProviderOutcome(OutcomeState.FAILED,
                    reference, BigDecimal.ZERO, null,
                    detail == null ? "PROVIDER_OPERATION_FAILED" : detail);
            case "RESULT_UNKNOWN" -> new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                    reference == null ? request.operationId().toString() : reference,
                    BigDecimal.ZERO, null,
                    detail == null ? "PROVIDER_RESULT_UNKNOWN" : detail);
            default -> throw new IllegalStateException("Line-adjustment response is invalid.");
        };
    }

    private static void requireIdentity(
            JsonNode response, UUID operationId, long tenantId,
            String providerCode, long configurationVersion) {
        if (response == null || !response.isObject()
                || !operationId.toString().equals(text(response, "operationId"))
                || response.path("tenantId").asLong(Long.MIN_VALUE) != tenantId
                || !providerCode.equals(text(response, "providerCode"))
                || response.path("providerConfigurationVersion").asLong(Long.MIN_VALUE)
                    != configurationVersion) {
            throw new IllegalStateException("Provider response identity is invalid.");
        }
    }

    private static ProviderOutcome failed(ProviderRequest request, String detail) {
        return new ProviderOutcome(OutcomeState.FAILED, request.operationId().toString(),
                BigDecimal.ZERO, null, detail);
    }

    private static ProviderOutcome notConfigured() {
        return new ProviderOutcome(OutcomeState.NOT_CONFIGURED, null, BigDecimal.ZERO, null,
                "PROVIDER_RELAY_NOT_CONFIGURED");
    }

    private static RuntimeException unavailable(String message) {
        return new IllegalStateException(message);
    }

    private static RuntimeException outcomeUnknown(String message, RuntimeException cause) {
        return new IllegalStateException(message, cause);
    }

    private static boolean definitiveRejection(HttpStatusCode status) {
        return status.is4xxClientError() && status.value() != 408
                && status.value() != 425 && status.value() != 429;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static String requiredText(JsonNode node, String field, int maximum) {
        String value = optionalText(node, field, maximum);
        if (value == null) throw new IllegalStateException("Provider response field is missing.");
        return value;
    }

    private static String optionalText(JsonNode node, String field, int maximum) {
        String value = text(node, field);
        if (value.isBlank()) return null;
        if (value.length() > maximum || containsControl(value)) {
            throw new IllegalStateException("Provider response field is invalid.");
        }
        return value;
    }

    private static String defaultDetail(JsonNode node, String fallback) {
        String detail = optionalText(node, "detailCode", MAX_DETAIL);
        return detail == null ? fallback : detail;
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character));
    }

    private static OffsetDateTime date(JsonNode node, String field) {
        try {
            return OffsetDateTime.parse(requiredText(node, field, 64));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Provider response timestamp is invalid.");
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isNumber()) return BigDecimal.ZERO;
        BigDecimal result = value.decimalValue();
        if (result.scale() > 2 || result.precision() > 14 || result.signum() < 0) {
            throw new IllegalStateException("Provider response amount is invalid.");
        }
        return result;
    }

    private static List<String> stringArray(
            JsonNode node, String field, int maximumItems, int maximumLength) {
        JsonNode values = node == null ? null : node.get(field);
        if (values == null || !values.isArray() || values.size() > maximumItems) {
            throw new IllegalStateException("Provider response list is invalid.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank()
                    || value.textValue().length() > maximumLength
                    || containsControl(value.textValue())) {
                throw new IllegalStateException("Provider response list is invalid.");
            }
            result.add(value.textValue());
        }
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalStateException("Provider response list contains duplicates.");
        }
        return List.copyOf(result);
    }

    private static Set<String> parseAdapterTypes(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : value.split(",")) {
            String adapter = candidate.trim();
            if (!ADAPTER.matcher(adapter).matches() || !result.add(adapter)) {
                throw new IllegalStateException(
                        "Workplace service provider adapter types are invalid.");
            }
        }
        return Set.copyOf(result);
    }

    private static String normalizeReference(String value, UUID fallback) {
        if (value == null || value.isBlank() || value.length() > MAX_REFERENCE) {
            return fallback.toString();
        }
        return value;
    }
}
