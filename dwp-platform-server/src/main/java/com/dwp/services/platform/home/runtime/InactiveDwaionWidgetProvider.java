package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Explicit fail-closed marker for the DWAI.ON projection. No DWAI.ON widget definition is part of
 * the authoritative runtime catalog in Wave 4, so its effective instance count remains zero.
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
        List<HomeWidgetProviderContract.WidgetResult> results = requests.stream()
                .map(request -> new HomeWidgetProviderContract.WidgetResult(
                        request.instanceId(), request.definition().definitionKey(),
                        request.definition().manifestHash(),
                        request.definition().rendererBindingRevision(),
                        HomeWidgetProviderContract.State.UNAVAILABLE,
                        new HomeWidgetProviderContract.SourceState(
                                "DWAION_HOME", now, now.plusSeconds(1), null,
                                "PROVIDER_INACTIVE_WAVE4", false, null),
                        Map.of(), List.of(), List.of()))
                .toList();
        return new HomeWidgetProviderContract.BatchResponse(
                HomeWidgetProviderContract.SCHEMA_VERSION,
                context.tenantId(), context.userId(),
                context.authorityDecisionRevision(), results);
    }
}
