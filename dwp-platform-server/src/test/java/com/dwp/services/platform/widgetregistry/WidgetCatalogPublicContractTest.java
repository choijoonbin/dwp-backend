package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WidgetCatalogPublicContractTest {
    @Test
    void publicDecisionAndReasonVocabularyIsExact() {
        assertThat(Arrays.stream(WidgetRegistryDtos.EffectiveCatalogState.values())
                .map(Enum::name)).containsExactly(
                "AVAILABLE", "ALREADY_ADDED", "DEPRECATED", "DENY");
        assertThat(Arrays.stream(WidgetRegistryDtos.EffectiveCatalogReason.values())
                .map(Enum::name)).containsExactly(
                "NOT_AVAILABLE", "DISABLED_BY_ORGANIZATION", "APP_ACCESS_REQUIRED",
                "INCOMPATIBLE", "TEMPORARILY_UNAVAILABLE", "DEPRECATED", "AVAILABLE",
                "ALREADY_ADDED");
    }

    @Test
    void serializedCatalogCannotLeakInternalDecisionCauses() throws Exception {
        var item = new WidgetRegistryDtos.EffectiveItem(
                null, "core.work.focus", "focus", null, null,
                WidgetRegistryDtos.EffectiveCatalogState.DENY,
                List.of(
                        WidgetRegistryDtos.EffectiveCatalogReason.NOT_AVAILABLE,
                        WidgetRegistryDtos.EffectiveCatalogReason.DISABLED_BY_ORGANIZATION,
                        WidgetRegistryDtos.EffectiveCatalogReason.INCOMPATIBLE,
                        WidgetRegistryDtos.EffectiveCatalogReason.TEMPORARILY_UNAVAILABLE),
                new WidgetRegistryDtos.PlacementCapabilities(false, false, false, false), 0);

        String payload = new ObjectMapper().writeValueAsString(item);
        assertThat(payload).contains(
                "\"effectiveState\":\"DENY\"",
                "\"NOT_AVAILABLE\"",
                "\"DISABLED_BY_ORGANIZATION\"",
                "\"INCOMPATIBLE\"",
                "\"TEMPORARILY_UNAVAILABLE\"");
        assertThat(payload).doesNotContain(
                "POLICY_MISSING_DENY", "POLICY_DISABLED", "VERSION_UNRESOLVED",
                "VERSION_NOT_PUBLISHED", "VERSION_BLOCKED", "CERTIFICATION_INCOMPLETE",
                "RENDERER_NOT_ALLOWLISTED", "KILL_CONTROL_ACTIVE", "SURFACE_UNSUPPORTED");
    }
}
