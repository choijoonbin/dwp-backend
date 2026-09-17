package com.dwp.platform.contract.home;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Bounded declarative result factories shared by application-owner providers. */
public final class HomeWidgetProviderResponses {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private HomeWidgetProviderResponses() {
    }

    public static HomeWidgetProviderContract.WidgetResult available(
            HomeWidgetProviderContract.WidgetRequest request,
            String sourceKey,
            Map<String, Object> payload,
            String sourceRoute) {
        if (payload == null || payload.isEmpty()) return empty(request, sourceKey, sourceRoute);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String version = resultVersion(request, payload);
        return result(request, HomeWidgetProviderContract.State.AVAILABLE,
                source(sourceKey, now, null, false, version, now), payload,
                List.of(sourceRoute(sourceRoute)), List.of());
    }

    public static HomeWidgetProviderContract.WidgetResult partial(
            HomeWidgetProviderContract.WidgetRequest request,
            String sourceKey,
            Map<String, Object> payload,
            String sourceRoute,
            String reasonCode,
            List<String> redactions) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String version = resultVersion(request, payload);
        return result(request, HomeWidgetProviderContract.State.PARTIAL,
                source(sourceKey, now, reasonCode, true, version, now), payload,
                List.of(sourceRoute(sourceRoute)), redactions);
    }

    public static HomeWidgetProviderContract.WidgetResult empty(
            HomeWidgetProviderContract.WidgetRequest request,
            String sourceKey,
            String sourceRoute) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return result(request, HomeWidgetProviderContract.State.EMPTY,
                source(sourceKey, now, null, false,
                        resultVersion(request, Map.of()), now), Map.of(),
                List.of(sourceRoute(sourceRoute)), List.of());
    }

    public static HomeWidgetProviderContract.WidgetResult forbidden(
            HomeWidgetProviderContract.WidgetRequest request,
            String sourceKey,
            String reasonCode) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return result(request, HomeWidgetProviderContract.State.FORBIDDEN,
                source(sourceKey, now, reasonCode, false, null, null),
                Map.of(), List.of(), List.of());
    }

    public static HomeWidgetProviderContract.WidgetResult unavailable(
            HomeWidgetProviderContract.WidgetRequest request,
            String sourceKey,
            String reasonCode) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return result(request, HomeWidgetProviderContract.State.UNAVAILABLE,
                source(sourceKey, now, reasonCode, true, null, null),
                Map.of(), List.of(), List.of());
    }

    private static HomeWidgetProviderContract.WidgetResult result(
            HomeWidgetProviderContract.WidgetRequest request,
            HomeWidgetProviderContract.State state,
            HomeWidgetProviderContract.SourceState source,
            Map<String, Object> payload,
            List<HomeWidgetProviderContract.Action> actions,
            List<String> redactions) {
        return new HomeWidgetProviderContract.WidgetResult(
                request.instanceId(), request.definitionKey(),
                request.definitionManifestHash(), request.rendererBindingRevision(),
                state, source, payload, actions, redactions);
    }

    private static HomeWidgetProviderContract.SourceState source(
            String sourceKey,
            OffsetDateTime generatedAt,
            String reasonCode,
            boolean retryable,
            String resultVersion,
            OffsetDateTime lastSuccessAt) {
        return new HomeWidgetProviderContract.SourceState(
                sourceKey,
                generatedAt,
                generatedAt.plusSeconds(30),
                lastSuccessAt,
                reasonCode,
                retryable,
                resultVersion);
    }

    private static HomeWidgetProviderContract.Action sourceRoute(String route) {
        return new HomeWidgetProviderContract.Action(
                "open-source", "home.action.openSource",
                HomeWidgetProviderContract.ActionKind.SOURCE_ROUTE,
                route, null, null, false);
    }

    private static String resultVersion(
            HomeWidgetProviderContract.WidgetRequest request,
            Map<String, Object> payload) {
        try {
            byte[] canonical = CANONICAL.writeValueAsBytes(payload);
            byte[] prefix = (request.definitionManifestHash() + "\n"
                    + request.rendererBindingRevision() + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] material = new byte[prefix.length + canonical.length];
            System.arraycopy(prefix, 0, material, 0, prefix.length);
            System.arraycopy(canonical, 0, material, prefix.length, canonical.length);
            return "v1:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(material), 0, 16);
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
