package com.dwp.services.platform.widgetregistry.internal.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetRegistryInternalRoutesTest {

    private static final String UUID_A = "abcdef12-3456-4abc-8def-000000000001";
    private static final String UUID_B = "20000000-0000-4000-8000-000000000002";

    @Test
    void resolvesOnlyTheTwelveDocumentedTemplatesAndRegistersHeadForEveryRead() {
        Map<WidgetRegistryInternalRoutes.Route, String> paths = new LinkedHashMap<>();
        paths.put(WidgetRegistryInternalRoutes.Route.LIST_DEFINITIONS, "/definitions");
        paths.put(WidgetRegistryInternalRoutes.Route.GET_DEFINITION, "/definitions/" + UUID_A);
        paths.put(
                WidgetRegistryInternalRoutes.Route.LIST_DEFINITION_VERSIONS,
                "/definitions/" + UUID_A + "/versions");
        paths.put(WidgetRegistryInternalRoutes.Route.GET_VERSION, "/versions/" + UUID_A);
        paths.put(
                WidgetRegistryInternalRoutes.Route.GET_RETIREMENT_IMPACT,
                "/definitions/" + UUID_A + "/retirement-impact");
        paths.put(
                WidgetRegistryInternalRoutes.Route.GET_RELEASE_CHANNEL,
                "/definitions/" + UUID_A + "/channels/STABLE");
        paths.put(
                WidgetRegistryInternalRoutes.Route.LIST_EVIDENCE,
                "/versions/" + UUID_A + "/evidence");
        paths.put(
                WidgetRegistryInternalRoutes.Route.GET_EVIDENCE,
                "/versions/" + UUID_A + "/evidence/" + UUID_B);
        paths.put(WidgetRegistryInternalRoutes.Route.LIST_RUNTIME_CONTROLS, "/runtime-controls");
        paths.put(
                WidgetRegistryInternalRoutes.Route.GET_COMMAND_COMPLETION,
                "/command-completions/" + UUID_A);
        paths.put(
                WidgetRegistryInternalRoutes.Route.SEAL_COMMAND_NOT_EXECUTED,
                "/command-completions/" + UUID_A + "/seal-not-executed");
        paths.put(WidgetRegistryInternalRoutes.Route.EXECUTE_COMMAND, "/commands");

        assertThat(paths).hasSize(12);
        paths.forEach((route, suffix) -> {
            String path = WidgetRegistryInternalRoutes.PREFIX + suffix;
            assertThat(WidgetRegistryInternalRoutes.resolve(path, route.method()).match().route())
                    .isEqualTo(route);
            if ("GET".equals(route.method())) {
                WidgetRegistryInternalRoutes.Resolution head =
                        WidgetRegistryInternalRoutes.resolve(path, "HEAD");
                assertThat(head.status())
                        .isEqualTo(WidgetRegistryInternalRoutes.ResolutionStatus.MATCHED);
                assertThat(head.match().method()).isEqualTo("HEAD");
            }
        });
    }

    @Test
    void rejectsPrefixLookalikesUppercaseIdsUnknownRoutesAndWrongMethods() {
        assertThat(WidgetRegistryInternalRoutes.isPlanePath(
                WidgetRegistryInternalRoutes.PREFIX + "-lookalike/definitions")).isFalse();
        assertThat(WidgetRegistryInternalRoutes.resolve(
                WidgetRegistryInternalRoutes.PREFIX + "/definitions/"
                        + UUID_A.toUpperCase(), "GET").status())
                .isEqualTo(WidgetRegistryInternalRoutes.ResolutionStatus.NOT_FOUND);
        assertThat(WidgetRegistryInternalRoutes.resolve(
                WidgetRegistryInternalRoutes.PREFIX + "/not-registered", "GET").status())
                .isEqualTo(WidgetRegistryInternalRoutes.ResolutionStatus.NOT_FOUND);
        assertThat(WidgetRegistryInternalRoutes.resolve(
                WidgetRegistryInternalRoutes.PREFIX + "/definitions", "DELETE").status())
                .isEqualTo(WidgetRegistryInternalRoutes.ResolutionStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void commandPolicyIsClosedOverAllTwentyOneDocumentedOperationTypePairs() {
        assertThat(WidgetRegistryCommandTrustPolicy.ruleCount()).isEqualTo(21);
        assertThat(WidgetRegistryCommandTrustPolicy.resolve(
                "createWidgetDefinition", "CREATE_DEFINITION"))
                .isEqualTo(new WidgetRegistryCommandTrustPolicy.Requirement(
                        "widget-registry.write", "WIDGET_DEFINITION_WRITE"));
        assertThat(WidgetRegistryCommandTrustPolicy.resolve(
                "createWidgetDefinition", "PUBLISH")).isNull();
        assertThat(WidgetRegistryCommandTrustPolicy.resolve(
                "unknownOperation", "CREATE_DEFINITION")).isNull();
    }
}
