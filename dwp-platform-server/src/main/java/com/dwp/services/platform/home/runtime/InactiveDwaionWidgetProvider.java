package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Explicit fail-closed marker for the DWAI.ON projection. The catalog may expose the design slot,
 * but no data or action is released until the owner publishes a recipient-bound provider API.
 */
@Component
public class InactiveDwaionWidgetProvider implements WidgetProviderPort {

    @Override
    public String providerKey() {
        return "dwaion";
    }

    @Override
    public HomeWidgetProviderContract.BatchResponse readBatch(
            HomeRuntimeContext context,
            List<Request> requests,
            OffsetDateTime deadline) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        context.requireAuthorityCurrent();
        if (deadline == null || deadline.isAfter(context.authorityRevalidateAt())
                || !deadline.isAfter(now)) {
            throw new WidgetProviderException(
                    WidgetProviderException.Kind.TIMEOUT,
                    "PROVIDER_DEADLINE_EXCEEDED",
                    "The DWAI.ON Home provider deadline elapsed before execution.");
        }
        List<HomeWidgetProviderContract.WidgetResult> results = requests.stream()
                .map(request -> {
                    if (!"dwaion.artifact".equals(request.definition().definitionKey())
                            || !"APP.DWAION_ARTIFACTS".equals(
                            request.definition().sourceAppResourceKey())) {
                        throw new WidgetProviderException(
                                WidgetProviderException.Kind.MALFORMED,
                                "DEFINITION_NOT_SUPPORTED",
                                "The DWAI.ON provider does not own the requested definition.");
                    }
                    return new HomeWidgetProviderContract.WidgetResult(
                        request.instanceId(), request.definition().definitionKey(),
                        request.definition().manifestHash(),
                        request.definition().rendererBindingRevision(),
                        HomeWidgetProviderContract.State.UNAVAILABLE,
                        new HomeWidgetProviderContract.SourceState(
                                "DWAION_HOME", now, now.plusSeconds(1), null,
                                "OWNER_PROVIDER_NOT_CONFIGURED", false, null),
                        Map.of(), List.of(), List.of());
                })
                .toList();
        HomeWidgetProviderContract.BatchResponse response =
                new HomeWidgetProviderContract.BatchResponse(
                        HomeWidgetProviderContract.SCHEMA_VERSION,
                        context.tenantId(), context.userId(),
                        context.authorityDecisionRevision(), results);
        context.requireAuthorityCurrent();
        if (!deadline.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new WidgetProviderException(
                    WidgetProviderException.Kind.TIMEOUT,
                    "PROVIDER_DEADLINE_EXCEEDED",
                    "The DWAI.ON Home provider deadline elapsed before disclosure.");
        }
        return response;
    }
}
