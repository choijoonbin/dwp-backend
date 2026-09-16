package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface WidgetProviderPort {

    String providerKey();

    HomeWidgetProviderContract.BatchResponse readBatch(
            HomeRuntimeContext context,
            List<Request> requests,
            OffsetDateTime deadline);

    default HomeWidgetProviderContract.CommandResponse executeCommand(
            HomeRuntimeContext context,
            HomeWidgetProviderContract.CommandRequest request,
            OffsetDateTime deadline) {
        throw new WidgetProviderException(
                WidgetProviderException.Kind.MALFORMED,
                "COMMAND_NOT_SUPPORTED",
                "This Home provider does not support commands.");
    }

    record Request(
            UUID instanceId,
            WidgetCatalogService.RuntimeDefinition definition,
            Map<String, Object> configuration,
            int itemLimit) {

        public Request {
            configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        }

        HomeWidgetProviderContract.WidgetRequest contract() {
            return new HomeWidgetProviderContract.WidgetRequest(
                    instanceId,
                    definition.definitionKey(),
                    definition.semanticVersion(),
                    definition.manifestHash(),
                    definition.rendererBindingRevision(),
                    configuration,
                    itemLimit);
        }
    }
}
