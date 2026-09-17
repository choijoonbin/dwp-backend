package com.dwp.services.platform.home.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeLayoutPolicyRegistryPlacementTest {
    private HomeLayoutPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new HomeLayoutPolicy(new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void availablePersonalDefinitionAcceptsItsExactDottedKeyAndManifestFootprint() {
        String key = "partner.insights.weekly";
        var layout = layout(widget(key, true, "large", "tall"));

        var result = policy.normalizeForSurface(
                "workspace-home", layout,
                Map.of(key, contract(true, "medium", Set.of("medium", "large"),
                        "standard", Set.of("standard", "tall"), null)));

        assertThat(result.widgets()).contains(widget(key, true, "large", "tall"));
    }

    @Test
    void newUnknownDefinitionAndManifestFootprintExpansionFailClosed() {
        String key = "partner.insights.weekly";
        assertInvalid(() -> policy.normalizeForSurface(
                "workspace-home", layout(widget(key, true, "medium", "standard")), Map.of()));

        assertInvalid(() -> policy.normalizeForSurface(
                "workspace-home", layout(widget(key, true, "full", "standard")),
                Map.of(key, contract(true, "medium", Set.of("medium"),
                        "standard", Set.of("standard"), null))));
        assertInvalid(() -> policy.normalizeForSurface(
                "workspace-home", layout(widget(key, true, "medium", "expanded")),
                Map.of(key, contract(true, "medium", Set.of("medium"),
                        "standard", Set.of("standard"), null))));
    }

    @Test
    void unavailableStoredDefinitionIsPreservedAcrossAnUnrelatedUpdate() {
        var unavailable = widget(
                "partner.retired.summary", false, "medium", "standard");
        var stored = layout(
                unavailable,
                widget("focus", true, "medium", "tall"));
        var requested = layout(widget("focus", true, "large", "tall"));

        var result = policy.normalizeForSurface(
                "workspace-home", requested, Map.of(), stored);

        assertThat(result.widgets().getFirst()).isEqualTo(unavailable);
        assertThat(result.widgets()).contains(widget("focus", true, "large", "tall"));
    }

    @Test
    void unavailableStoredDefinitionCannotBeChangedWhileAvailableOneCan() {
        String key = "partner.retired.summary";
        var stored = layout(widget(key, false, "medium", "standard"));
        var changed = layout(widget(key, true, "large", "tall"));

        assertInvalid(() -> policy.normalizeForSurface(
                "workspace-home", changed, Map.of(), stored));

        var result = policy.normalizeForSurface(
                "workspace-home", changed,
                Map.of(key, contract(true, "medium", Set.of("medium", "large"),
                        "standard", Set.of("standard", "tall"), null)),
                stored);
        assertThat(result.widgets()).contains(widget(key, true, "large", "tall"));
    }

    @Test
    void readReconciliationPreservesAPreviouslyValidatedDottedDefinition() {
        var storedWidget = widget(
                "partner.retired.summary", false, "medium", "standard");

        var result = policy.reconcileStoredForSurface(
                "workspace-home", layout(storedWidget));

        assertThat(result.widgets().getFirst()).isEqualTo(storedWidget);
    }

    @Test
    void thirtyInstanceCeilingIncludesRequiredStaticWidgets() {
        Map<String, HomeLayoutPolicy.RegistryWidgetContract> contracts = new LinkedHashMap<>();
        List<HomePreferenceDtos.WidgetPreference> requested = IntStream.range(0, 24)
                .mapToObj(index -> {
                    String key = "partner.widget-" + index;
                    contracts.put(key, contract(true, "medium", Set.of("medium"),
                            "standard", Set.of("standard"), null));
                    return widget(key, true, "medium", "standard");
                }).toList();

        assertInvalid(() -> policy.normalizeForSurface(
                "workspace-home",
                new HomePreferenceDtos.HomeLayoutPayload(null, "balanced", requested),
                contracts));
    }

    private HomePreferenceDtos.HomeLayoutPayload layout(
            HomePreferenceDtos.WidgetPreference... widgets) {
        return new HomePreferenceDtos.HomeLayoutPayload(
                null, "balanced", List.of(widgets));
    }

    private HomePreferenceDtos.WidgetPreference widget(
            String key, boolean visible, String size, String height) {
        return new HomePreferenceDtos.WidgetPreference(key, visible, size, height);
    }

    private HomeLayoutPolicy.RegistryWidgetContract contract(
            boolean canHide,
            String defaultSize,
            Set<String> allowedSizes,
            String defaultHeight,
            Set<String> allowedHeights,
            Boolean fixedVisibility) {
        return new HomeLayoutPolicy.RegistryWidgetContract(
                canHide, defaultSize, allowedSizes,
                defaultHeight, allowedHeights, fixedVisibility);
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
    }
}
