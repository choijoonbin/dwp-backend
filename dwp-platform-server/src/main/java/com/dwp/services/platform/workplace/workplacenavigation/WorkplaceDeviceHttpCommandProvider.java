package com.dwp.services.platform.workplace.workplacenavigation;

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

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;

/**
 * Production device-command relay. Configuration contains opaque secret-manager references only;
 * raw credentials are leased by {@link WorkplaceProviderHttpTransport} for one request.
 */
@Component
public class WorkplaceDeviceHttpCommandProvider implements WorkplaceDeviceCommandProvider {
    private static final Pattern PROVIDER = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final Pattern SECRET_REFERENCE =
            Pattern.compile("secret-manager://[A-Za-z0-9._/-]{1,143}");
    private static final String DISPATCH_PATH =
            "/internal/v1/workplace/device-provider/commands:dispatch";
    private static final String STATUS_PATH =
            "/internal/v1/workplace/device-provider/commands/";
    private static final String RUNTIME_PATH =
            "/internal/v1/workplace/device-provider/runtime/";

    private final Optional<WorkplaceProviderHttpTransport> transport;
    private final Map<ProviderConfiguration, String> credentials;

    WorkplaceDeviceHttpCommandProvider(
            Optional<WorkplaceProviderHttpTransport> transport,
            @Value("${dwp.workplace.navigation.device-provider.credential-references:}")
            String credentialReferences) {
        this.transport = transport;
        this.credentials = parseCredentialReferences(credentialReferences);
    }

    @Override
    public Optional<ProviderBinding> binding(String providerCode, long configurationVersion) {
        String reference = credentials.get(
                new ProviderConfiguration(providerCode, configurationVersion));
        return reference == null ? Optional.empty() : Optional.of(
                new ProviderBinding(providerCode, configurationVersion, reference));
    }

    @Override
    public boolean ready(ProviderBinding binding) {
        return binding != null && transport.isPresent()
                && transport.get().ready(binding.providerCode(), binding.credentialReference());
    }

    @Override
    @Bulkhead(name = "workplaceDeviceProviderRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceDeviceProviderRelay")
    @Retry(name = "idempotentConnector")
    public ProviderCommandOutcome execute(ProviderCommand command) {
        if (!ready(command.binding())) return failed("PROVIDER_RELAY_NOT_CONFIGURED");
        try {
            JsonNode response = transport.orElseThrow().post(
                    command.binding().providerCode(), command.binding().credentialReference(),
                    DISPATCH_PATH, command.tenantId(), command.commandId().toString(), Map.of(
                            "commandId", command.commandId(),
                            "tenantId", command.tenantId(),
                            "deviceId", command.deviceId(),
                            "commandType", command.type().name(),
                            "payload", Map.copyOf(command.payload()),
                            "providerCode", command.binding().providerCode(),
                            "providerConfigurationVersion",
                            command.binding().configurationVersion()));
            return outcome(response, command);
        } catch (RestClientResponseException rejected) {
            return definitiveRejection(rejected.getStatusCode())
                    ? failed("PROVIDER_REQUEST_REJECTED")
                    : unknown(command, "PROVIDER_TRANSPORT_OUTCOME_UNKNOWN");
        } catch (RestClientException unavailable) {
            return unknown(command, "PROVIDER_TRANSPORT_OUTCOME_UNKNOWN");
        } catch (RuntimeException invalid) {
            return unknown(command, "PROVIDER_RESPONSE_INVALID");
        }
    }

    @Override
    @Bulkhead(name = "workplaceDeviceProviderRelay", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceDeviceProviderRelay")
    @Retry(name = "idempotentConnector")
    public ProviderCommandOutcome status(ProviderCommand command) {
        if (!ready(command.binding())) {
            return unknown(command, "PROVIDER_RELAY_NOT_CONFIGURED");
        }
        try {
            JsonNode response = transport.orElseThrow().get(
                    command.binding().providerCode(), command.binding().credentialReference(),
                    STATUS_PATH + command.commandId(), command.tenantId());
            return outcome(response, command);
        } catch (RestClientResponseException rejected) {
            return unknown(command, "PROVIDER_STATUS_UNAVAILABLE");
        } catch (RestClientException unavailable) {
            return unknown(command, "PROVIDER_STATUS_UNAVAILABLE");
        } catch (RuntimeException invalid) {
            return unknown(command, "PROVIDER_RESPONSE_INVALID");
        }
    }

    @Override
    @Bulkhead(name = "workplaceDeviceProviderObservation", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "workplaceDeviceProviderObservation")
    @Retry(name = "idempotentConnector")
    public Optional<ProviderRuntimeObservation> observe(ProviderObservationCommand command) {
        if (!ready(command.binding())) return Optional.empty();
        try {
            JsonNode response = transport.orElseThrow().get(
                    command.binding().providerCode(), command.binding().credentialReference(),
                    RUNTIME_PATH + command.capability().name().toLowerCase(java.util.Locale.ROOT),
                    command.tenantId());
            if (response == null || !response.isObject()
                    || response.path("tenantId").asLong(Long.MIN_VALUE) != command.tenantId()
                    || !command.capability().name().equals(text(response, "capability"))
                    || !command.binding().providerCode().equals(text(response, "providerCode"))
                    || response.path("providerConfigurationVersion").asLong(Long.MIN_VALUE)
                        != command.binding().configurationVersion()) {
                return Optional.empty();
            }
            ProviderReportedState state = enumValue(
                    ProviderReportedState.class, text(response, "reportedState"));
            String evidence = boundedRequired(response, "evidenceReference", 320);
            OffsetDateTime sourceAt = OffsetDateTime.parse(
                    boundedRequired(response, "sourceAt", 80));
            String lastSuccess = bounded(response, "lastSuccessAt", 80);
            String errorCode = bounded(response, "errorCode", 120);
            return Optional.of(new ProviderRuntimeObservation(state, evidence, sourceAt,
                    lastSuccess == null ? null : OffsetDateTime.parse(lastSuccess), errorCode));
        } catch (RuntimeException unavailableOrInvalid) {
            return Optional.empty();
        }
    }

    private static ProviderCommandOutcome outcome(JsonNode response, ProviderCommand command) {
        if (response == null || !response.isObject()
                || !command.commandId().toString().equals(text(response, "commandId"))
                || response.path("tenantId").asLong(Long.MIN_VALUE) != command.tenantId()
                || !command.deviceId().toString().equals(text(response, "deviceId"))
                || !command.type().name().equals(text(response, "commandType"))
                || !command.binding().providerCode().equals(text(response, "providerCode"))
                || response.path("providerConfigurationVersion").asLong(Long.MIN_VALUE)
                    != command.binding().configurationVersion()) {
            return unknown(command, "PROVIDER_RESPONSE_INVALID");
        }
        OutcomeState state;
        try {
            state = OutcomeState.valueOf(text(response, "state"));
        } catch (RuntimeException invalid) {
            return unknown(command, "PROVIDER_RESPONSE_INVALID");
        }
        String reference = bounded(response, "providerOperationReference", 320);
        String resultCode = bounded(response, "resultCode", 120);
        if (state == OutcomeState.SUCCEEDED && reference == null) {
            return unknown(command, "PROVIDER_RESPONSE_INVALID");
        }
        return new ProviderCommandOutcome(state,
                reference == null ? "command:" + command.commandId() : reference,
                resultCode == null ? defaultCode(state) : resultCode);
    }

    private static ProviderCommandOutcome failed(String code) {
        return new ProviderCommandOutcome(OutcomeState.FAILED, null, code);
    }

    private static ProviderCommandOutcome unknown(ProviderCommand command, String code) {
        return new ProviderCommandOutcome(
                OutcomeState.RESULT_UNKNOWN, "command:" + command.commandId(), code);
    }

    private static boolean definitiveRejection(HttpStatusCode status) {
        return status.is4xxClientError()
                && status.value() != 408 && status.value() != 425 && status.value() != 429;
    }

    private static String defaultCode(OutcomeState state) {
        return switch (state) {
            case SUCCEEDED -> "PROVIDER_COMMAND_SUCCEEDED";
            case FAILED -> "PROVIDER_COMMAND_FAILED";
            case RESULT_UNKNOWN -> "PROVIDER_RESULT_UNKNOWN";
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static String boundedRequired(JsonNode node, String field, int maximum) {
        String value = bounded(node, field, maximum);
        if (value == null) throw new IllegalStateException("Provider field is missing.");
        return value;
    }

    private static String bounded(JsonNode node, String field, int maximum) {
        String value = text(node, field);
        if (value.isBlank()) return null;
        if (value.length() > maximum) throw new IllegalStateException("Provider field is too long.");
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Provider enum is invalid.", invalid);
        }
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
                        "Workplace device provider credential references are invalid.");
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private record ProviderConfiguration(String code, long version) { }
}
