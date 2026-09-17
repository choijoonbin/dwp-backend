package com.dwp.services.platform.workplace.connectorops;

import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderHttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;

/** Production HTTP relay adapter. It has no local/fake success path. */
@Component
@ConditionalOnProperty(
        name = "dwp.workplace.provider-integration.http.enabled",
        havingValue = "true")
public class WorkplaceConnectorHttpReplayAdapter implements WorkplaceConnectorReplayAdapter {
    private final WorkplaceProviderHttpTransport transport;

    public WorkplaceConnectorHttpReplayAdapter(WorkplaceProviderHttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public boolean supports(String provider, ConnectorKind kind) {
        return provider != null && kind != null;
    }

    @Override
    public boolean ready(ProviderContext context) {
        return context != null && supports(context.provider(), context.kind())
                && transport.ready(context.provider(), context.credentialReference());
    }

    @Override
    public PreviewEstimate preview(
            ProviderContext context,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean failedOnly,
            int maximumRecords) {
        String requestIdentity = sha256(String.join("\n",
                Long.toString(context.tenantId()), context.provider(), context.kind().name(),
                from.toInstant().toString(), to.toInstant().toString(),
                Boolean.toString(failedOnly), Integer.toString(maximumRecords)));
        JsonNode response = requireResponse(transport.post(context.provider(),
                context.credentialReference(), path(context, "/replays:preview"),
                context.tenantId(), "preview:" + requestIdentity,
                Map.of("from", from, "to", to, "failedOnly", failedOnly,
                        "maximumRecords", maximumRecords)));
        return new PreviewEstimate(nonNegativeLong(response, "estimatedRecords"),
                textList(response, "limitations"));
    }

    @Override
    public DispatchResult dispatch(
            ProviderContext context,
            UUID jobId,
            UUID previewId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean failedOnly,
            int maximumRecords) {
        JsonNode response = requireResponse(transport.post(context.provider(),
                context.credentialReference(), path(context, "/replays"), context.tenantId(),
                jobId.toString(), Map.of("jobId", jobId, "previewId", previewId,
                        "from", from, "to", to, "failedOnly", failedOnly,
                        "maximumRecords", maximumRecords)));
        return new DispatchResult(state(response), nullableText(response, "providerOperationReference"),
                nullableText(response, "resultCode"));
    }

    @Override
    public LookupResult lookup(
            ProviderContext context,
            UUID jobId,
            String providerOperationReference) {
        JsonNode response = requireResponse(transport.get(context.provider(),
                context.credentialReference(), path(context, "/replays/" + jobId),
                context.tenantId()));
        return new LookupResult(state(response), nullableText(response, "providerOperationReference"),
                nullableText(response, "resultCode"));
    }

    @Override
    public RuntimeObservation observe(ProviderContext context) {
        JsonNode response = requireResponse(transport.get(context.provider(),
                context.credentialReference(), path(context, "/runtime"), context.tenantId()));
        List<Capability> capabilities = textList(response, "capabilities").stream()
                .map(value -> enumValue(Capability.class, value)).toList();
        return new RuntimeObservation(enumValue(ProviderReportedState.class,
                requiredText(response, "reportedState")), capabilities,
                OffsetDateTime.parse(requiredText(response, "sourceObservedAt")),
                nullableDateTime(response, "lastSuccessAt"), nullableLong(response, "lagSeconds"),
                nullableText(response, "checkpointReference"),
                nullableLong(response, "retryQueueDepth"),
                nullableLong(response, "deadLetterQueueDepth"),
                nullableText(response, "errorCode"), requiredText(response, "adapterId"),
                requiredText(response, "adapterVersion"),
                requiredText(response, "payloadFingerprint"));
    }

    private static String path(ProviderContext context, String suffix) {
        return "/v1/workplace-connectors/" + context.provider() + "/"
                + context.kind().name().toLowerCase(Locale.ROOT).replace('_', '-') + suffix;
    }

    private static ReplayState state(JsonNode node) {
        return enumValue(ReplayState.class, requiredText(node, "state"));
    }

    private static JsonNode requireResponse(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("The provider returned an invalid JSON response.");
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null) throw new IllegalStateException("Provider response field is missing: " + field);
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Provider response field is invalid: " + field);
        }
        return value.textValue();
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        Long value = nullableLong(node, field);
        if (value == null) throw new IllegalStateException("Provider response field is missing: " + field);
        return value;
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalStateException("Provider response field is invalid: " + field);
        }
        return value.longValue();
    }

    private static OffsetDateTime nullableDateTime(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null ? null : OffsetDateTime.parse(value);
    }

    private static List<String> textList(JsonNode node, String field) {
        JsonNode values = node.get(field);
        if (values == null || !values.isArray()) {
            throw new IllegalStateException("Provider response field is invalid: " + field);
        }
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalStateException("Provider response array is invalid: " + field);
            }
            result.add(value.textValue());
        });
        return List.copyOf(result);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Provider response enum is invalid.", invalid);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
