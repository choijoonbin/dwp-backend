package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

@Component
public class ProviderResultValidator {

    private static final Set<String> FORBIDDEN_FIELD_FRAGMENTS = Set.of(
            "credential", "password", "secret", "token", "authorization", "cookie", "html", "script");
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 2_000;
    private static final int MAX_STRING = 4_096;
    private static final int MAX_REDACTIONS = 32;
    private static final Pattern EXTERNAL_URI = Pattern.compile(
            "^(?:[A-Za-z][A-Za-z0-9+.-]*:|//).*");

    private final ObjectMapper objectMapper;
    private final HomeRuntimeProperties properties;

    public ProviderResultValidator(ObjectMapper objectMapper, HomeRuntimeProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public HomeWidgetProviderContract.BatchResponse validate(
            HomeWidgetProviderContract.BatchResponse response,
            HomeRuntimeContext context,
            List<WidgetProviderPort.Request> requests) {
        if (response == null
                || response.schemaVersion() != HomeWidgetProviderContract.SCHEMA_VERSION
                || response.tenantId() != context.tenantId()
                || response.userId() != context.userId()
                || !context.authorityDecisionRevision().equals(response.authorityDecisionRevision())) {
            malformed("Provider response identity binding does not match the request.");
        }
        if (response.results().size() != requests.size()) {
            malformed("Provider response cardinality does not match the request.");
        }
        Map<UUID, WidgetProviderPort.Request> expected = new HashMap<>();
        requests.forEach(request -> expected.put(request.instanceId(), request));
        Set<UUID> observed = new HashSet<>();
        for (HomeWidgetProviderContract.WidgetResult result : response.results()) {
            WidgetProviderPort.Request request = result == null ? null : expected.get(result.instanceId());
            if (request == null || !observed.add(result.instanceId())
                    || !request.definition().definitionKey().equals(result.definitionKey())
                    || !request.definition().manifestHash().equals(
                            result.definitionManifestHash())
                    || !request.definition().rendererBindingRevision().equals(
                            result.rendererBindingRevision())) {
                malformed("Provider response contains an unknown or duplicate widget result.");
            }
            validateResult(result);
        }
        try {
            if (objectMapper.writeValueAsBytes(response).length
                    > properties.maximumProviderPayloadBytes()) {
                malformed("Provider response exceeds the payload budget.");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw malformed("Provider response cannot be serialized.", exception);
        }
        return response;
    }

    public static String internalRoute(String value) {
        if (value == null || value.isBlank() || value.length() > 512
                || !value.startsWith("/") || value.startsWith("//")
                || value.contains("\\") || value.contains("..")
                || value.contains("\r") || value.contains("\n")
                || !value.matches("/[A-Za-z0-9/_?=&.%-]*")) {
            throw new WidgetProviderException(
                    WidgetProviderException.Kind.MALFORMED,
                    "INVALID_SOURCE_ROUTE",
                    "Provider returned an invalid internal route.");
        }
        return value;
    }

    private void validateResult(HomeWidgetProviderContract.WidgetResult result) {
        if (result.state() == null || result.source() == null
                || result.source().sourceKey() == null
                || !result.source().sourceKey().matches("[A-Z][A-Z0-9_.-]{1,99}")) {
            malformed("Provider source state is incomplete.");
        }
        HomeWidgetProviderContract.SourceState source = result.source();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime latestGeneratedAt = now.plusSeconds(30);
        if (source.generatedAt() == null || source.expiresAt() == null
                || !source.expiresAt().isAfter(source.generatedAt())
                || source.generatedAt().isAfter(latestGeneratedAt)
                || source.lastSuccessAt() != null && source.lastSuccessAt().isAfter(latestGeneratedAt)
                || source.lastSuccessAt() != null && source.lastSuccessAt().isAfter(source.generatedAt())
                || source.reasonCode() != null
                && !source.reasonCode().matches("[A-Z][A-Z0-9_.-]{1,79}")) {
            malformed("Provider freshness metadata is invalid.");
        }
        boolean noData = result.state() == HomeWidgetProviderContract.State.FORBIDDEN
                || result.state() == HomeWidgetProviderContract.State.UNAVAILABLE;
        if (noData && (!result.payload().isEmpty() || !result.actions().isEmpty())) {
            malformed("Forbidden or unavailable provider results cannot expose data or actions.");
        }
        if (result.state() == HomeWidgetProviderContract.State.EMPTY
                && !result.payload().isEmpty()) {
            malformed("An EMPTY result cannot contain a payload.");
        }
        if (result.state() == HomeWidgetProviderContract.State.STALE
                && source.lastSuccessAt() == null) {
            malformed("A STALE result requires the last successful refresh time.");
        }
        if (result.state() == HomeWidgetProviderContract.State.FORBIDDEN
                && (source.reasonCode() == null
                || !source.reasonCode().startsWith("AUTHORIZATION_"))) {
            malformed("A FORBIDDEN result requires an authorization reason code.");
        }
        if (result.state() == HomeWidgetProviderContract.State.FORBIDDEN && source.retryable()) {
            malformed("A FORBIDDEN result cannot be marked retryable.");
        }
        if ((result.state() == HomeWidgetProviderContract.State.AVAILABLE
                || result.state() == HomeWidgetProviderContract.State.EMPTY)
                && (source.retryable() || source.reasonCode() != null)) {
            malformed("Successful provider results cannot carry an error reason.");
        }
        if ((result.state() == HomeWidgetProviderContract.State.PARTIAL
                || result.state() == HomeWidgetProviderContract.State.STALE
                || result.state() == HomeWidgetProviderContract.State.UNAVAILABLE)
                && source.reasonCode() == null) {
            malformed("A degraded provider result requires a reason code.");
        }
        if (result.state() == HomeWidgetProviderContract.State.PARTIAL
                && result.redactions().isEmpty()) {
            malformed("A PARTIAL result must disclose its redactions.");
        }
        if ((result.state() == HomeWidgetProviderContract.State.AVAILABLE
                || result.state() == HomeWidgetProviderContract.State.EMPTY
                || result.state() == HomeWidgetProviderContract.State.PARTIAL)
                && !source.expiresAt().isAfter(now)) {
            malformed("A successful provider result cannot already be expired.");
        }
        if ((result.state() == HomeWidgetProviderContract.State.AVAILABLE
                || result.state() == HomeWidgetProviderContract.State.EMPTY
                || result.state() == HomeWidgetProviderContract.State.PARTIAL)
                && (source.resultVersion() == null
                || !source.resultVersion().matches("[A-Za-z0-9._:@+-]{1,160}"))) {
            malformed("A data-bearing provider result requires a bounded result version.");
        }
        if (result.redactions().size() > MAX_REDACTIONS
                || result.redactions().stream().anyMatch(value -> value == null
                || !value.matches("[A-Z][A-Z0-9_.-]{1,79}"))) {
            malformed("Provider redactions are invalid.");
        }
        if (result.actions().size() > HomeWidgetProviderContract.MAX_ACTIONS_PER_WIDGET) {
            malformed("Provider action budget exceeded.");
        }
        Set<String> actionIds = new HashSet<>();
        for (HomeWidgetProviderContract.Action action : result.actions()) {
            if (action == null || action.actionId() == null || action.labelKey() == null
                    || action.kind() == null
                    || !action.actionId().matches("[a-z][a-z0-9.-]{1,79}")
                    || !action.labelKey().matches("[a-zA-Z][a-zA-Z0-9._-]{1,119}")
                    || !actionIds.add(action.actionId())) {
                malformed("Provider action is incomplete.");
            }
            if (action.kind() == HomeWidgetProviderContract.ActionKind.SOURCE_ROUTE) {
                internalRoute(action.sourceRoute());
                if (action.commandKey() != null || action.expectedResultVersion() != null) {
                    malformed("Source route action contains command metadata.");
                }
            } else {
                if (action.sourceRoute() != null || action.commandKey() == null
                        || !action.commandKey().matches("[a-z][a-z0-9.-]{2,119}")
                        || action.expectedResultVersion() == null
                        || !action.expectedResultVersion().equals(source.resultVersion())) {
                    malformed("Command action contract is invalid.");
                }
            }
        }
        JsonNode payload = objectMapper.valueToTree(result.payload());
        inspect(payload, 0, new int[]{0});
    }

    private void inspect(JsonNode node, int depth, int[] count) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_NODES) malformed("Provider payload complexity exceeded.");
        if (node.isTextual()) {
            String text = node.asText();
            String lower = text.toLowerCase(Locale.ROOT);
            if (text.length() > MAX_STRING || lower.contains("<script")
                    || lower.startsWith("javascript:") || EXTERNAL_URI.matcher(text).matches()) {
                malformed("Provider payload contains disallowed text.");
            }
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (FORBIDDEN_FIELD_FRAGMENTS.stream().anyMatch(key::contains)) {
                    malformed("Provider payload contains a forbidden field.");
                }
                inspect(entry.getValue(), depth + 1, count);
            });
        } else if (node.isArray()) {
            node.forEach(child -> inspect(child, depth + 1, count));
        }
    }

    private void malformed(String message) {
        throw malformed(message, null);
    }

    private WidgetProviderException malformed(String message, Throwable cause) {
        throw new WidgetProviderException(
                WidgetProviderException.Kind.MALFORMED,
                "PROVIDER_CONTRACT_INVALID",
                message,
                cause);
    }
}
