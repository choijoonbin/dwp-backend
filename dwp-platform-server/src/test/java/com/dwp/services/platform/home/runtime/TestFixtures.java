package com.dwp.services.platform.home.runtime;

import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TestFixtures {

    private TestFixtures() {
    }

    static HomeRuntimeContext context() {
        return HomeRuntimeContext.create(
                71L, 82L, UUID.randomUUID(),
                "APP.WORK:VIEW,APP.CALENDAR:VIEW", "MEMBER", "team-a",
                "decision-17", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString(),
                "ko-KR", "Asia/Seoul");
    }

    static WidgetProviderPort.Request request(String definitionKey) {
        WidgetCatalogService.RuntimeDefinition definition = new WidgetCatalogService.RuntimeDefinition(
                UUID.randomUUID(), definitionKey, "focus", UUID.randomUUID(), "1.0.0",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "binding-1",
                "home.focus", "core.work", "APP.WORK", List.of("APP.WORK:VIEW"),
                "INTERNAL", "NONE", 30,
                WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE, List.of());
        return new WidgetProviderPort.Request(UUID.randomUUID(), definition, Map.of(), 10);
    }
}
