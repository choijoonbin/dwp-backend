package com.dwp.services.platform.workplace.workplacevisits;

import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderHttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.ProviderKind;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitProviderPort.*;

/**
 * Production boundary for visit/access provider traffic. Only opaque secret-manager references
 * are configured here; {@link WorkplaceProviderHttpTransport} leases credentials at request time.
 */
@Component
public class WorkplaceVisitProviderRelayAdapter
        implements WorkplaceVisitProviderPort, WorkplaceVisitGuestRefVerificationPort {
    private static final Pattern PROVIDER = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final Pattern SECRET_REFERENCE =
            Pattern.compile("secret-manager://[A-Za-z0-9._/-]{1,143}");
    private static final Pattern GUEST_REFERENCE_PREFIX =
            Pattern.compile("[a-z][a-z0-9+.-]{1,31}://[A-Za-z0-9._~/-]{1,160}");
    private static final String DISPATCH_PATH =
            "/internal/v1/workplace/visit-provider/operations:dispatch";
    private static final String LOOKUP_PATH =
            "/internal/v1/workplace/visit-provider/operations/";
    private static final String VERIFY_PATH =
            "/internal/v1/workplace/guest-references:verify";

    private final Optional<WorkplaceProviderHttpTransport> transport;
    private final Map<ProviderConfiguration, String> credentialReferences;
    private final String guestProviderCode;
    private final long guestProviderConfigurationVersion;
    private final List<String> guestReferencePrefixes;

    WorkplaceVisitProviderRelayAdapter(
            Optional<WorkplaceProviderHttpTransport> transport,
            @Value("${dwp.workplace.visits.provider-relay.credential-references:}")
            String credentialReferences,
            @Value("${dwp.workplace.visits.provider-relay.guest-provider-code:}")
            String guestProviderCode,
            @Value("${dwp.workplace.visits.provider-relay.guest-provider-configuration-version:0}")
            long guestProviderConfigurationVersion,
            @Value("${dwp.workplace.visits.provider-relay.guest-reference-prefixes:}")
            String guestReferencePrefixes) {
        this.transport = transport;
        this.credentialReferences = parseCredentialReferences(credentialReferences);
        this.guestProviderCode = normalizeProvider(guestProviderCode);
        this.guestProviderConfigurationVersion = guestProviderConfigurationVersion;
        this.guestReferencePrefixes = guestPrefixes(guestReferencePrefixes);
        if (!this.guestProviderCode.isEmpty()
                && (guestProviderConfigurationVersion < 1
                || !this.credentialReferences.containsKey(new ProviderConfiguration(
                        this.guestProviderCode, guestProviderConfigurationVersion)))) {
            throw new IllegalStateException(
                    "The guest-reference provider has no opaque credential reference.");
        }
    }

    @Override
    public boolean supports(ProviderKind kind, String providerCode) {
        return providerCode != null && credentialReferences.keySet().stream()
                .anyMatch(configuration -> configuration.code().equals(providerCode));
    }

    @Override
    @Bulkhead(name = "workplaceVisitProviderRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceVisitProviderRelay")
    @Retry(name = "idempotentConnector")
    public ProviderOutcome dispatch(ProviderOperation operation) {
        return execute(operation, false);
    }

    @Override
    @Bulkhead(name = "workplaceVisitProviderRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceVisitProviderRelay")
    @Retry(name = "idempotentConnector")
    public ProviderOutcome lookup(ProviderOperation operation) {
        return execute(operation, true);
    }

    @Override
    public boolean supports(String opaqueGuestRef) {
        if (opaqueGuestRef == null || guestProviderCode.isEmpty()) return false;
        return guestReferencePrefixes.stream().anyMatch(opaqueGuestRef::startsWith);
    }

    @Override
    @Bulkhead(name = "workplaceVisitGuestReferenceRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceVisitGuestReferenceRelay")
    @Retry(name = "idempotentConnector")
    public Verification verify(VerificationRequest request) {
        String credential = credentialReferences.get(new ProviderConfiguration(
                guestProviderCode, guestProviderConfigurationVersion));
        WorkplaceProviderHttpTransport relay = requireTransport(guestProviderCode, credential);
        UUID verificationId = UUID.nameUUIDFromBytes(
                ("workplace-guest-ref:" + request.tenantId() + ":" + request.opaqueGuestRef())
                        .getBytes(StandardCharsets.UTF_8));
        try {
            JsonNode response = relay.post(guestProviderCode, credential, VERIFY_PATH,
                    request.tenantId(), verificationId.toString(), Map.of(
                            "verificationId", verificationId,
                            "tenantId", request.tenantId(),
                            "providerCode", guestProviderCode,
                            "providerConfigurationVersion",
                            guestProviderConfigurationVersion,
                            "opaqueGuestReference", request.opaqueGuestRef()));
            return guestVerification(response, verificationId, request.tenantId(),
                    guestProviderCode, guestProviderConfigurationVersion);
        } catch (RestClientResponseException rejected) {
            if (definitiveGuestRejection(rejected.getStatusCode())) {
                return new Verification(false, null, "GUEST_REF_INVALID");
            }
            throw new IllegalStateException("Guest-reference verification is unavailable.");
        } catch (RestClientException unavailable) {
            throw new IllegalStateException("Guest-reference verification is unavailable.");
        }
    }

    private ProviderOutcome execute(ProviderOperation operation, boolean lookup) {
        String credential = credentialReferences.get(new ProviderConfiguration(
                operation.providerCode(), operation.providerConfigurationVersion()));
        WorkplaceProviderHttpTransport relay;
        try {
            relay = requireTransport(operation.providerCode(), credential);
        } catch (IllegalStateException notConfigured) {
            // A lookup is reconciliation for a mutation which may already have completed.
            // Losing an old credential/relay after rotation is therefore not authoritative
            // failure evidence and must leave the original operation recoverable.
            return lookup
                    ? unknown(operation, "PROVIDER_BINDING_UNAVAILABLE")
                    : failed(operation, "PROVIDER_RELAY_NOT_CONFIGURED");
        }
        try {
            JsonNode response = lookup
                    ? relay.get(operation.providerCode(), credential,
                            LOOKUP_PATH + operation.operationId(), operation.tenantId())
                    : relay.post(operation.providerCode(), credential, DISPATCH_PATH,
                            operation.tenantId(), operation.operationId().toString(), Map.of(
                                    "operationId", operation.operationId(),
                                    "tenantId", operation.tenantId(),
                                    "visitId", operation.visitId(),
                                    "providerKind", operation.providerKind().name(),
                                    "providerCode", operation.providerCode(),
                                    "providerConfigurationVersion",
                                    operation.providerConfigurationVersion(),
                                    "operationType", operation.operationType()));
            return providerOutcome(response, operation);
        } catch (RestClientResponseException rejected) {
            // HTTP rejection is definitive only before an initial mutation is accepted. A
            // lookup 4xx can equally mean stale routing, eventual consistency, or an expired
            // credential and cannot terminalize the already uncertain mutation.
            return !lookup && definitiveProviderRejection(rejected.getStatusCode())
                    ? failed(operation, "PROVIDER_REQUEST_REJECTED")
                    : unknown(operation, "PROVIDER_TRANSPORT_OUTCOME_UNKNOWN");
        } catch (RestClientException unavailable) {
            return unknown(operation, "PROVIDER_TRANSPORT_OUTCOME_UNKNOWN");
        } catch (RuntimeException invalidResponse) {
            return unknown(operation, "PROVIDER_RESPONSE_INVALID");
        }
    }

    private WorkplaceProviderHttpTransport requireTransport(String provider, String credential) {
        WorkplaceProviderHttpTransport relay = transport.orElseThrow(() ->
                new IllegalStateException("The Workplace provider relay is not enabled."));
        if (!relay.ready(provider, credential)) {
            throw new IllegalStateException(
                    "The Workplace provider or credential reference is not configured.");
        }
        return relay;
    }

    private static ProviderOutcome providerOutcome(JsonNode response, ProviderOperation operation) {
        if (response == null || !response.isObject()
                || !operation.operationId().toString().equals(text(response, "operationId"))
                || response.path("tenantId").asLong(Long.MIN_VALUE) != operation.tenantId()
                || !operation.providerKind().name().equals(text(response, "providerKind"))
                || !operation.providerCode().equals(text(response, "providerCode"))
                || response.path("providerConfigurationVersion").asLong(Long.MIN_VALUE)
                    != operation.providerConfigurationVersion()) {
            return unknown(operation, "PROVIDER_RESPONSE_INVALID");
        }
        String evidence = nullableText(response, "evidenceReference");
        String detail = nullableText(response, "detailCode");
        return switch (text(response, "state")) {
            case "SUCCEEDED" -> evidence == null
                    ? unknown(operation, "PROVIDER_RESPONSE_INVALID")
                    : new ProviderOutcome(OutcomeState.SUCCEEDED, evidence, null);
            case "FAILED" -> new ProviderOutcome(OutcomeState.FAILED, evidence,
                    detail == null ? "PROVIDER_OPERATION_FAILED" : detail);
            case "RESULT_UNKNOWN" -> new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                    evidence == null ? operationEvidence(operation) : evidence,
                    detail == null ? "PROVIDER_RESULT_UNKNOWN" : detail);
            default -> unknown(operation, "PROVIDER_RESPONSE_INVALID");
        };
    }

    private static Verification guestVerification(
            JsonNode response, UUID verificationId, long tenantId,
            String providerCode, long providerConfigurationVersion) {
        if (response == null || !response.isObject()
                || !verificationId.toString().equals(text(response, "verificationId"))
                || response.path("tenantId").asLong(Long.MIN_VALUE) != tenantId
                || !providerCode.equals(text(response, "providerCode"))
                || response.path("providerConfigurationVersion").asLong(Long.MIN_VALUE)
                    != providerConfigurationVersion
                || !response.has("valid") || !response.get("valid").isBoolean()) {
            throw new IllegalStateException("Guest-reference verification returned invalid data.");
        }
        boolean valid = response.get("valid").booleanValue();
        String evidence = nullableText(response, "evidenceReference");
        String limitation = nullableText(response, "limitationCode");
        return new Verification(valid, evidence,
                valid ? null : limitation == null ? "GUEST_REF_INVALID" : limitation);
    }

    private static ProviderOutcome failed(ProviderOperation operation, String detail) {
        return new ProviderOutcome(OutcomeState.FAILED, operationEvidence(operation), detail);
    }

    private static ProviderOutcome unknown(ProviderOperation operation, String detail) {
        return new ProviderOutcome(OutcomeState.RESULT_UNKNOWN,
                operationEvidence(operation), detail);
    }

    private static String operationEvidence(ProviderOperation operation) {
        return "operation:" + operation.operationId();
    }

    private static boolean definitiveProviderRejection(HttpStatusCode status) {
        return status.is4xxClientError() && status.value() != 408 && status.value() != 429;
    }

    private static boolean definitiveGuestRejection(HttpStatusCode status) {
        return status.value() == 404 || status.value() == 410 || status.value() == 422;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static String nullableText(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() ? null : value;
    }

    private static Map<ProviderConfiguration, String> parseCredentialReferences(String value) {
        Map<ProviderConfiguration, String> result = new LinkedHashMap<>();
        for (String item : csv(value)) {
            int separator = item.indexOf('=');
            String identity = separator < 1 ? "" : item.substring(0, separator).trim();
            String reference = separator < 1 ? "" : item.substring(separator + 1).trim();
            int versionSeparator = identity.lastIndexOf('@');
            String provider = versionSeparator < 1 ? ""
                    : identity.substring(0, versionSeparator);
            long version;
            try {
                version = versionSeparator < 1 ? 0
                        : Long.parseLong(identity.substring(versionSeparator + 1));
            } catch (NumberFormatException invalid) {
                version = 0;
            }
            ProviderConfiguration configuration = new ProviderConfiguration(provider, version);
            if (!PROVIDER.matcher(provider).matches() || version < 1
                    || !SECRET_REFERENCE.matcher(reference).matches()
                    || result.putIfAbsent(configuration, reference) != null) {
                throw new IllegalStateException(
                        "Workplace visit provider credential references are invalid.");
            }
        }
        return Map.copyOf(result);
    }

    private static String normalizeProvider(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim();
        if (!PROVIDER.matcher(normalized).matches()) {
            throw new IllegalStateException("The guest-reference provider code is invalid.");
        }
        return normalized;
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        Set<String> unique = new java.util.LinkedHashSet<>();
        for (String item : value.split(",")) {
            String normalized = item.trim();
            if (!normalized.isEmpty()) unique.add(normalized);
        }
        return List.copyOf(unique);
    }

    private static List<String> guestPrefixes(String value) {
        List<String> prefixes = csv(value);
        if (prefixes.stream().anyMatch(prefix -> !GUEST_REFERENCE_PREFIX.matcher(prefix).matches())) {
            throw new IllegalStateException("Guest-reference prefixes are invalid.");
        }
        return prefixes;
    }

    private record ProviderConfiguration(String code, long version) { }
}
