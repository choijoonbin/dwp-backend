package com.dwp.services.platform.home.personalization;

import com.dwp.services.platform.home.preference.HomePreference;
import com.dwp.services.platform.home.preference.HomeLayoutPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeViewCompatibilityBridgeTest {
    @Mock private JdbcTemplate jdbc;

    private ObjectMapper objectMapper;
    private HomeViewCompatibilityBridge bridge;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        bridge = new HomeViewCompatibilityBridge(
                jdbc, new SimpleMeterRegistry(), objectMapper);
        ReflectionTestUtils.setField(bridge, "dualWriteEnabled", true);
    }

    @Test
    void mirrorsLegacyToDefaultViewAndDefaultViewBackToLegacy() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        HomePreference preference = HomePreference.builder()
                .tenantId(7L).userId(11L).surfaceKey("workspace-home")
                .schemaVersion(5).layoutPayload(objectMapper.readTree("{\"widgets\":[]}"))
                .build();

        bridge.mirrorLegacyPreference(preference);

        HomeView view = HomeView.builder()
                .viewId(UUID.randomUUID()).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.readTree("{\"widgets\":[]}"))
                .build();
        bridge.mirrorDefaultView(view);

        verify(jdbc, org.mockito.Mockito.times(3))
                .update(anyString(), any(Object[].class));
    }

    @Test
    void legacyWriteUpsertsTheDefaultKeyCreatedByTheBackfill() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0, 1);
        HomePreference preference = HomePreference.builder()
                .tenantId(7L).userId(11L).surfaceKey("workspace-home")
                .schemaVersion(5).layoutPayload(objectMapper.readTree("{\"widgets\":[]}"))
                .build();

        bridge.mirrorLegacyPreference(preference);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(3))
                .update(sql.capture(), arguments.capture());
        assertThat(sql.getAllValues().getFirst())
                .contains("WHERE tenant_id = ? AND user_id = ? AND surface_key = ? AND is_default");
        assertThat(sql.getAllValues().get(1))
                .contains("WHERE NOT EXISTS")
                .contains("ON CONFLICT (tenant_id, user_id, surface_key, view_key)")
                .contains("WHERE deleted_at IS NULL")
                .contains("DO UPDATE");
        assertThat(arguments.getAllValues().get(1)).hasSize(11);
        assertThat(arguments.getAllValues().get(1)[8]).isEqualTo(7L);
        assertThat(arguments.getAllValues().get(1)[9]).isEqualTo(11L);
        assertThat(arguments.getAllValues().get(1)[10]).isEqualTo("workspace-home");
        assertThat(sql.getAllValues().get(2))
                .contains("INSERT INTO usr_home_view_revisions")
                .contains("'snapshotVersion', 1")
                .contains("'customized', active.is_customized");
    }

    @Test
    void resetCustomizationStateIsMirroredBackToClassic() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        HomeView reset = HomeView.builder()
                .viewId(UUID.randomUUID()).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).customized(false).schemaVersion(5)
                .layoutPayload(objectMapper.readTree("{\"widgets\":[]}"))
                .build();

        bridge.mirrorDefaultView(reset);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), arguments.capture());
        assertThat(sql.getValue())
                .contains("is_customized = EXCLUDED.is_customized");
        assertThat(arguments.getValue()).endsWith(false);
    }

    @Test
    void classicResetClearsAdvancedChildrenBeforeCapturingItsFullRevision() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        bridge.mirrorLegacyReset(
                7L, 11L, "workspace-home",
                objectMapper.readTree("{\"widgets\":[]}"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(4))
                .update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues().get(0))
                .contains("UPDATE usr_home_views")
                .contains("is_customized = FALSE")
                .contains("deleted_at IS NULL");
        assertThat(sql.getAllValues().get(1))
                .contains("DELETE FROM usr_home_widget_configurations")
                .contains("child.tenant_id = active.tenant_id")
                .contains("active.deleted_at IS NULL");
        assertThat(sql.getAllValues().get(2))
                .contains("DELETE FROM usr_home_view_device_layouts")
                .contains("child.user_id = active.user_id")
                .contains("active.deleted_at IS NULL");
        assertThat(sql.getAllValues().get(3))
                .contains("INSERT INTO usr_home_view_revisions")
                .contains("'widgetConfigurations'")
                .contains("'deviceLayouts'");
    }

    @Test
    void canonicalJsonComparisonIgnoresObjectPropertyOrder() throws Exception {
        assertThat(bridge.sameLayout(
                objectMapper.readTree("{\"b\":2,\"a\":1}"),
                objectMapper.readTree("{\"a\":1,\"b\":2}"))).isTrue();
    }

    @Test
    void normalizedCutoverValidationRejectsSemanticallyUnknownWidgets() throws Exception {
        HomeViewCompatibilityBridge normalizedBridge = new HomeViewCompatibilityBridge(
                jdbc, new SimpleMeterRegistry(), objectMapper,
                new HomeLayoutPolicy(objectMapper));
        var bogus = objectMapper.readTree("""
                {"appLayout":{"version":1,"groups":{},"folders":{},"hiddenAppIds":[]},
                 "presentation":"balanced",
                 "widgets":[{"widgetKey":"bogus","visible":true,
                             "size":"medium","height":"standard"}]}
                """);

        assertThat(normalizedBridge.sameNormalizedLayout(
                "workspace-home", bogus, bogus)).isFalse();
    }

    @Test
    void normalizedCutoverValidationRejectsNullWidgetVisibility() throws Exception {
        HomeViewCompatibilityBridge normalizedBridge = new HomeViewCompatibilityBridge(
                jdbc, new SimpleMeterRegistry(), objectMapper,
                new HomeLayoutPolicy(objectMapper));
        var incomplete = objectMapper.readTree("""
                {"appLayout":{"version":1,"groups":{},"folders":{},"hiddenAppIds":[]},
                 "presentation":"balanced",
                 "widgets":[{"widgetKey":"focus","visible":null,
                             "size":"medium","height":"tall"}]}
                """);

        assertThat(normalizedBridge.sameNormalizedLayout(
                "workspace-home", incomplete, incomplete)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizedReadinessPagesBeyondTenThousandRows() throws Exception {
        HomeViewCompatibilityBridge pagingBridge = new HomeViewCompatibilityBridge(
                jdbc, new SimpleMeterRegistry(), objectMapper,
                new HomeLayoutPolicy(objectMapper));
        String validLayout = """
                {"appLayout":{"version":1,"groups":{},"folders":{},"hiddenAppIds":[]},
                 "presentation":"balanced",
                 "widgets":[{"widgetKey":"focus","visible":true,
                             "size":"medium","height":"tall"}]}
                """;
        List<HomeViewCompatibilityBridge.LayoutCandidate> fullPage = IntStream.range(0, 1_000)
                .mapToObj(index -> new HomeViewCompatibilityBridge.LayoutCandidate(
                        1_000L, "workspace-home", validLayout, validLayout))
                .toList();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1_000))).thenReturn(fullPage);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1_000L),
                org.mockito.ArgumentMatchers.eq(1_000L),
                org.mockito.ArgumentMatchers.eq("workspace-home"),
                org.mockito.ArgumentMatchers.eq(1_000))).thenReturn(
                        fullPage, fullPage, fullPage, fullPage, fullPage,
                        fullPage, fullPage, fullPage, fullPage, fullPage,
                        List.of());

        assertThat(pagingBridge.normalizedRowsMatch(7L)).isTrue();
        verify(jdbc, org.mockito.Mockito.times(11)).query(
                anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1_000L),
                org.mockito.ArgumentMatchers.eq(1_000L),
                org.mockito.ArgumentMatchers.eq("workspace-home"),
                org.mockito.ArgumentMatchers.eq(1_000));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resetMetadataCanRemainCutoverReadyWhenBothStoresMatch() throws Exception {
        HomeViewCompatibilityBridge readinessBridge = new HomeViewCompatibilityBridge(
                jdbc, new SimpleMeterRegistry(), objectMapper,
                new HomeLayoutPolicy(objectMapper));
        ReflectionTestUtils.setField(readinessBridge, "dualWriteEnabled", true);
        ReflectionTestUtils.setField(readinessBridge, "shadowCompareEnabled", true);
        when(jdbc.queryForObject(anyString(),
                org.mockito.ArgumentMatchers.eq(Boolean.class),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(7L))).thenReturn(true);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1_000))).thenReturn(java.util.List.of());

        assertThat(readinessBridge.readCutoverReady(7L)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(),
                org.mockito.ArgumentMatchers.eq(Boolean.class),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(7L));
        assertThat(sql.getValue())
                .contains("active.is_customized IS DISTINCT FROM legacy.is_customized")
                .doesNotContain("active.layout_payload IS DISTINCT FROM legacy.layout_payload");
    }

    @Test
    void bothMirrorCallersRemainInsideServiceTransactions() throws Exception {
        Method legacyUpdate = com.dwp.services.platform.home.preference.HomePreferenceService.class
                .getMethod("update", Long.class, Long.class, String.class, String.class,
                        com.dwp.services.platform.home.preference.HomePreferenceDtos
                                .UpdateHomePreferenceRequest.class);
        Method viewApply = HomeViewService.class.getMethod(
                "applyExternalLayout", Long.class, Long.class, UUID.class, Long.class,
                com.dwp.services.platform.home.preference.HomePreferenceDtos
                        .HomeLayoutPayload.class,
                String.class, String.class, UUID.class, String.class, Long.class, String.class);

        assertThat(legacyUpdate.getAnnotation(Transactional.class)).isNotNull();
        assertThat(viewApply.getAnnotation(Transactional.class)).isNotNull();
    }
}
