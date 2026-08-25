package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.HomeCompositionPolicyReader;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.home.preference.HomePreferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class HomeViewServiceTest {
    @Mock private HomeViewRepository viewRepository;
    @Mock private HomeViewRevisionRepository revisionRepository;
    @Mock private HomeDeviceLayoutRepository deviceLayouts;
    @Mock private HomeWidgetConfigurationRepository widgetConfigurations;
    @Mock private HomePreferenceService preferenceService;
    @Mock private HomeCompositionPolicyReader compositionPolicy;
    @Mock private HomePersonalizationAccess access;
    @Mock private PlatformAuditService audit;
    @Mock private HomeViewCompatibilityBridge compatibilityBridge;
    @Mock private HomePersonalizationScopeLock scopeLock;
    @Mock private HomeCommandReceiptService commandReceipts;

    private ObjectMapper objectMapper;
    private HomeViewService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        HomeWidgetConfigurationPolicy widgetPolicy =
                new HomeWidgetConfigurationPolicy(objectMapper);
        service = new HomeViewService(
                viewRepository, revisionRepository, deviceLayouts, widgetConfigurations,
                preferenceService, compositionPolicy, access, audit,
                objectMapper, compatibilityBridge, scopeLock,
                new HomeViewSnapshotCodec(objectMapper, widgetPolicy),
                widgetPolicy, new HomeCanonicalJson(objectMapper), commandReceipts);
        lenient().when(compositionPolicy.personalCustomizationEnabled(7L)).thenReturn(true);
        lenient().when(compositionPolicy.flowPersonalizationEnabled(7L)).thenReturn(true);
    }

    @Test
    void templateApplicationPreservesTheClassicCommandRailSnapshotAndMirrorsTheDefaultView() {
        UUID viewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomePreferenceDtos.WidgetPreference classicSnapshot =
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", false, "large", "standard");
        HomePreferenceDtos.HomeLayoutPayload current = layout(List.of(
                classicSnapshot,
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall")));
        HomePreferenceDtos.HomeLayoutPayload requested = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "large", "tall"),
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).customized(false).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(current)).version(0L).build();
        when(revisionRepository.findByTenantIdAndUserIdAndCommandId(7L, 11L, commandId))
                .thenReturn(Optional.empty());
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(preferenceService.normalizeForSurface("workspace-home", requested))
                .thenReturn(requested);
        when(viewRepository.saveAndFlush(view)).thenReturn(view);
        when(revisionRepository.findTopByViewIdOrderByRevisionNumberDesc(viewId))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any(HomeViewRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                viewId, 7L, 11L)).thenReturn(List.of());

        HomeViewDtos.HomeViewResponse result = service.applyExternalLayout(
                7L, 11L, viewId, 0L, requested, "TEMPLATE", "Applied",
                commandId, "a".repeat(64), 11L, "corr");

        assertThat(result.layout().widgets().getFirst()).isEqualTo(classicSnapshot);
        assertThat(result.layout().widgets().get(1).widgetKey()).isEqualTo("focus");
        assertThat(result.layout().widgets().get(1).size()).isEqualTo("large");
        assertThat(result.customized()).isTrue();
        verify(compatibilityBridge).mirrorDefaultView(view);
        verify(audit).success(eq(7L), eq(11L), eq("home-view.layout-applied"),
                eq("HOME_VIEW"), eq(viewId.toString()), eq("corr"), any(), any());
    }

    @Test
    void widgetConfigurationAcceptsOnlyTheRegisteredSourceFieldsFilterAndTypes()
            throws Exception {
        UUID viewId = UUID.randomUUID();
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).customized(false).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(layout(List.of(
                        new HomePreferenceDtos.WidgetPreference(
                                "focus", true, "medium", "tall")))))
                .version(0L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdAndWidgetKey(
                viewId, 7L, 11L, "focus")).thenReturn(Optional.empty());
        when(widgetConfigurations.saveAndFlush(any(HomeWidgetConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(viewRepository.saveAndFlush(view)).thenReturn(view);
        when(revisionRepository.findTopByViewIdOrderByRevisionNumberDesc(viewId))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any(HomeViewRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                viewId, 7L, 11L)).thenReturn(List.of());

        var valid = objectMapper.readValue("""
                {"sourceKey":"WORK","fieldKeys":["title","dueAt"],
                 "filterPreset":"DUE_SOON","itemLimit":8}
                """, HomeViewDtos.WidgetConfigurationPayload.class);
        service.putWidgetConfiguration(
                7L, 11L, viewId, "focus", UUID.randomUUID(), "corr",
                new HomeViewDtos.UpdateWidgetConfigurationRequest(valid, 0L));

        verify(widgetConfigurations).saveAndFlush(any(HomeWidgetConfiguration.class));
        assertThat(view.isCustomized()).isTrue();
        verify(compatibilityBridge).mirrorDefaultView(view);
    }

    @Test
    void widgetConfigurationRejectsUnregisteredValuesAndNonIntegralLimits() throws Exception {
        UUID viewId = UUID.randomUUID();
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(layout(List.of(
                        new HomePreferenceDtos.WidgetPreference(
                                "focus", true, "medium", "tall")))))
                .version(0L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        List<HomeViewDtos.WidgetConfigurationPayload> invalid = List.of(
                new HomeViewDtos.WidgetConfigurationPayload(
                        "UNTRUSTED", List.of("title"), "DUE_SOON", null),
                new HomeViewDtos.WidgetConfigurationPayload(
                        "WORK", List.of("privateBody"), "DUE_SOON", null),
                new HomeViewDtos.WidgetConfigurationPayload(
                        "WORK", List.of("title"), "ALL_TENANT_DATA", null),
                new HomeViewDtos.WidgetConfigurationPayload(
                        "WORK", List.of("title"), "DUE_SOON", 21));

        for (HomeViewDtos.WidgetConfigurationPayload configuration : invalid) {
            assertThatThrownBy(() -> service.putWidgetConfiguration(
                    7L, 11L, viewId, "focus", UUID.randomUUID(), "corr",
                    new HomeViewDtos.UpdateWidgetConfigurationRequest(configuration, 0L)))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(
                                    ErrorCode.INVALID_INPUT_VALUE));
        }
        verify(widgetConfigurations, never()).saveAndFlush(any());
    }

    @Test
    void widgetConfigurationJsonRejectsFractionalIntegerCoercion() {
        assertThatThrownBy(() -> objectMapper.readValue("""
                {"sourceKey":"WORK","fieldKeys":["title"],
                 "filterPreset":"DUE_SOON","itemLimit":1.5}
                """, HomeViewDtos.WidgetConfigurationPayload.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    @Test
    void tenantPolicyAllowsExistingViewReadsButBlocksMutations() {
        UUID viewId = UUID.randomUUID();
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(layout(List.of())))
                .version(0L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(compositionPolicy.flowPersonalizationEnabled(7L)).thenReturn(false);
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                viewId, 7L, 11L)).thenReturn(List.of());
        when(viewRepository.findByTenantIdAndUserIdAndSurfaceKeyOrderByUpdatedAtDesc(
                7L, 11L, "workspace-home")).thenReturn(List.of(view));
        when(revisionRepository
                .findTop50ByViewIdAndTenantIdAndUserIdAndRestorableTrueOrderByRevisionNumberDesc(
                viewId, 7L, 11L)).thenReturn(List.of());
        when(deviceLayouts.findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                viewId, 7L, 11L)).thenReturn(List.of());

        assertThat(service.get(7L, 11L, viewId).viewId()).isEqualTo(viewId);
        assertThat(service.list(7L, 11L, "workspace-home")).hasSize(1);
        assertThat(service.revisions(7L, 11L, viewId)).isEmpty();
        assertThat(service.deviceLayouts(7L, 11L, viewId)).isEmpty();
        assertThatThrownBy(() -> service.update(
                7L, 11L, viewId, UUID.randomUUID(), "corr",
                new HomeViewDtos.UpdateHomeViewRequest(
                        "Changed", layout(List.of()), 0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.reset(
                7L, 11L, viewId, UUID.randomUUID(), "corr", 0L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(viewRepository, never()).saveAndFlush(any(HomeView.class));
    }

    @Test
    void emptyViewListIsSideEffectFreeForNewUsers() throws Exception {
        when(viewRepository.findByTenantIdAndUserIdAndSurfaceKeyOrderByUpdatedAtDesc(
                7L, 11L, "workspace-home")).thenReturn(List.of());

        assertThat(service.list(7L, 11L, "workspace-home")).isEmpty();
        assertThat(HomeViewService.class
                .getMethod("list", Long.class, Long.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)
                .readOnly()).isTrue();
        verify(scopeLock, never()).lock(any(), any(), any());
        verify(viewRepository, never()).saveAndFlush(any());
        verify(compatibilityBridge, never()).mirrorDefaultView(any());
    }

    @Test
    void revisionHistoryAppliesRestorableFilterBeforeTheLatestFiftyLimit() {
        UUID viewId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload storedLayout = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(storedLayout)).version(0L).build();
        HomeViewDtos.HomeViewSnapshot storedSnapshot = new HomeViewDtos.HomeViewSnapshot(
                1, false,
                new HomeViewDtos.HomeViewSnapshotView(
                        "My home", true, 5, storedLayout),
                Map.of(), Map.of());
        HomeViewRevision revision = HomeViewRevision.builder()
                .revisionId(revisionId).viewId(viewId).tenantId(7L).userId(11L)
                .revisionNumber(51L).schemaVersion(5)
                .snapshot(objectMapper.valueToTree(storedSnapshot))
                .source("USER").restorable(true).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(revisionRepository
                .findTop50ByViewIdAndTenantIdAndUserIdAndRestorableTrueOrderByRevisionNumberDesc(
                        viewId, 7L, 11L)).thenReturn(List.of(revision));

        assertThat(service.revisions(7L, 11L, viewId))
                .extracting(HomeViewDtos.HomeViewRevisionResponse::revisionId)
                .containsExactly(revisionId);
        verify(revisionRepository)
                .findTop50ByViewIdAndTenantIdAndUserIdAndRestorableTrueOrderByRevisionNumberDesc(
                        viewId, 7L, 11L);
    }

    @Test
    void regularUpdateRejectsClassicCommandRailValueOrPositionTampering() {
        UUID viewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload current = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short"),
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall")));
        HomePreferenceDtos.HomeLayoutPayload tampered = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall"),
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", false, "full", "standard")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(current)).version(0L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(revisionRepository.findByTenantIdAndUserIdAndCommandId(
                7L, 11L, commandId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                7L, 11L, viewId, commandId, "corr",
                new HomeViewDtos.UpdateHomeViewRequest("Changed", tampered, 0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
        verify(viewRepository, never()).saveAndFlush(any(HomeView.class));
    }

    @Test
    void deviceOverlayCannotReorderTheSharedSemanticDomOrder() {
        UUID viewId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload current = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short"),
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall"),
                new HomePreferenceDtos.WidgetPreference(
                        "schedule", true, "quarter", "standard")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(current)).version(0L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));

        var overlay = new HomeViewDtos.DeviceLayoutOverlay(
                List.of("schedule", "focus"), Map.of(), "compact");
        assertThatThrownBy(() -> service.putDeviceLayout(
                7L, 11L, viewId, "MOBILE", UUID.randomUUID(), "corr",
                new HomeViewDtos.UpdateDeviceLayoutRequest(overlay, 0L, null)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
        verify(deviceLayouts, never()).saveAndFlush(any());
    }

    @Test
    void deviceOverlayRejectsASizeOutsideTheWidgetsRegistryContract() {
        UUID viewId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload current = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short"),
                new HomePreferenceDtos.WidgetPreference(
                        "schedule", true, "quarter", "standard")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(current)).version(0L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(preferenceService.isWidgetSizeAllowed(
                "workspace-home", "schedule", "full")).thenReturn(false);

        var overlay = new HomeViewDtos.DeviceLayoutOverlay(
                List.of("schedule"), Map.of("schedule", "full"), "compact");
        assertThatThrownBy(() -> service.putDeviceLayout(
                7L, 11L, viewId, "MOBILE", UUID.randomUUID(), "corr",
                new HomeViewDtos.UpdateDeviceLayoutRequest(overlay, 0L, null)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
        verify(deviceLayouts, never()).saveAndFlush(any());
    }

    @Test
    void deviceOverlayRejectsAnUnregisteredDensityEvenOutsideControllerValidation() {
        UUID viewId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload current = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short"),
                new HomePreferenceDtos.WidgetPreference(
                        "schedule", true, "quarter", "standard")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(current)).version(0L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));

        var overlay = new HomeViewDtos.DeviceLayoutOverlay(
                List.of("schedule"), Map.of(), "relaxed");
        assertThatThrownBy(() -> service.putDeviceLayout(
                7L, 11L, viewId, "MOBILE", UUID.randomUUID(), "corr",
                new HomeViewDtos.UpdateDeviceLayoutRequest(overlay, 0L, null)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
        verify(deviceLayouts, never()).saveAndFlush(any());
    }

    @Test
    void deviceOverlayMarksAResetViewCustomizedAndMirrorsClassicMetadata() {
        UUID viewId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload current = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short"),
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall"),
                new HomePreferenceDtos.WidgetPreference(
                        "schedule", true, "quarter", "standard")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).customized(false).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(current)).version(0L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(preferenceService.isWidgetSizeAllowed(
                "workspace-home", "focus", "medium")).thenReturn(true);
        when(preferenceService.isWidgetSizeAllowed(
                "workspace-home", "schedule", "quarter")).thenReturn(true);
        when(deviceLayouts.findByViewIdAndTenantIdAndUserIdAndDeviceClass(
                viewId, 7L, 11L, "MOBILE")).thenReturn(Optional.empty());
        when(deviceLayouts.saveAndFlush(any(HomeDeviceLayout.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(viewRepository.saveAndFlush(view)).thenReturn(view);
        when(revisionRepository.findTopByViewIdOrderByRevisionNumberDesc(viewId))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any(HomeViewRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                viewId, 7L, 11L)).thenReturn(List.of());
        when(deviceLayouts.findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                viewId, 7L, 11L)).thenReturn(List.of());

        var overlay = new HomeViewDtos.DeviceLayoutOverlay(
                List.of("focus", "schedule"),
                Map.of("focus", "medium", "schedule", "quarter"),
                "compact");
        var result = service.putDeviceLayout(
                7L, 11L, viewId, "mobile", UUID.randomUUID(), "corr",
                new HomeViewDtos.UpdateDeviceLayoutRequest(overlay, 0L, null));

        assertThat(result.overlay()).isEqualTo(overlay);
        assertThat(view.isCustomized()).isTrue();
        verify(compatibilityBridge).mirrorDefaultView(view);
    }

    @Test
    void aCorruptBackfilledViewCanBeRepairedWithTheDisplayedRecoveryLayout() {
        UUID viewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload recovery = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short"),
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(1)
                .layoutPayload(objectMapper.createArrayNode().add("corrupt"))
                .version(3L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(preferenceService.defaultLayoutForSurface("workspace-home"))
                .thenReturn(recovery);
        when(preferenceService.normalizeForSurface("workspace-home", recovery))
                .thenReturn(recovery);
        when(revisionRepository.findByTenantIdAndUserIdAndCommandId(
                7L, 11L, commandId)).thenReturn(Optional.empty());
        when(viewRepository.saveAndFlush(view)).thenReturn(view);
        when(revisionRepository.findTopByViewIdOrderByRevisionNumberDesc(viewId))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any(HomeViewRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                viewId, 7L, 11L)).thenReturn(List.of());
        when(deviceLayouts.findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                viewId, 7L, 11L)).thenReturn(List.of());

        var result = service.update(
                7L, 11L, viewId, commandId, "corr",
                new HomeViewDtos.UpdateHomeViewRequest("Recovered", recovery, 3L));

        assertThat(result.layout()).isEqualTo(recovery);
        assertThat(view.getSchemaVersion()).isEqualTo(5);
        verify(compatibilityBridge).mirrorDefaultView(view);
    }

    @Test
    void restoreReinstatesTheRevisionCustomizationState() {
        UUID viewId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload revisionLayout = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short"),
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("Changed")
                .defaultView(true).customized(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(revisionLayout)).version(0L).build();
        HomeViewDtos.HomeViewSnapshot storedSnapshot = new HomeViewDtos.HomeViewSnapshot(
                1, false,
                new HomeViewDtos.HomeViewSnapshotView(
                        "Reset home", false, 5, revisionLayout),
                Map.of(), Map.of());
        HomeViewRevision source = HomeViewRevision.builder()
                .revisionId(revisionId).viewId(viewId).tenantId(7L).userId(11L)
                .revisionNumber(1L).schemaVersion(5)
                .snapshot(objectMapper.valueToTree(storedSnapshot))
                .source("USER").restorable(true).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(revisionRepository.findByTenantIdAndUserIdAndCommandId(
                7L, 11L, commandId)).thenReturn(Optional.empty());
        when(revisionRepository.findByRevisionIdAndViewIdAndTenantIdAndUserId(
                revisionId, viewId, 7L, 11L)).thenReturn(Optional.of(source));
        when(preferenceService.normalizeForSurface("workspace-home", revisionLayout))
                .thenReturn(revisionLayout);
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                viewId, 7L, 11L)).thenReturn(List.of());
        when(deviceLayouts.findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                viewId, 7L, 11L)).thenReturn(List.of());
        when(viewRepository.saveAndFlush(view)).thenReturn(view);
        when(revisionRepository.findTopByViewIdOrderByRevisionNumberDesc(viewId))
                .thenReturn(Optional.of(source));
        when(revisionRepository.saveAndFlush(any(HomeViewRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        HomeViewDtos.HomeViewResponse restored = service.restore(
                7L, 11L, viewId, revisionId, commandId, "corr", 0L);

        assertThat(restored.customized()).isFalse();
        assertThat(restored.name()).isEqualTo("Reset home");
        verify(compatibilityBridge).mirrorDefaultView(view);
    }

    @Test
    void resetRestoresGovernedDefaultsClearsChildOverridesAndMirrorsClassicState() {
        UUID viewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload customized = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short"),
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "large", "expanded")));
        HomePreferenceDtos.HomeLayoutPayload defaults = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short"),
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).customized(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(customized)).version(4L).build();
        HomeWidgetConfiguration configuration = HomeWidgetConfiguration.builder()
                .widgetConfigurationId(UUID.randomUUID()).viewId(viewId)
                .tenantId(7L).userId(11L).widgetKey("focus")
                .configurationPayload(objectMapper.createObjectNode()
                        .put("sourceKey", "WORK"))
                .build();
        HomeDeviceLayout device = HomeDeviceLayout.builder()
                .deviceLayoutId(UUID.randomUUID()).viewId(viewId)
                .tenantId(7L).userId(11L).deviceClass("MOBILE")
                .overlayPayload(objectMapper.createObjectNode()
                        .put("density", "compact"))
                .build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(preferenceService.defaultLayoutForSurface("workspace-home"))
                .thenReturn(defaults);
        when(preferenceService.normalizeForSurface("workspace-home", defaults))
                .thenReturn(defaults);
        java.util.concurrent.atomic.AtomicInteger configurationReads =
                new java.util.concurrent.atomic.AtomicInteger();
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                viewId, 7L, 11L)).thenAnswer(ignored ->
                        configurationReads.getAndIncrement() < 2
                                ? List.of(configuration) : List.of());
        java.util.concurrent.atomic.AtomicInteger deviceReads =
                new java.util.concurrent.atomic.AtomicInteger();
        when(deviceLayouts.findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                viewId, 7L, 11L)).thenAnswer(ignored ->
                        deviceReads.getAndIncrement() < 2
                                ? List.of(device) : List.of());
        when(viewRepository.saveAndFlush(view)).thenReturn(view);
        when(revisionRepository.findTopByViewIdOrderByRevisionNumberDesc(viewId))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any(HomeViewRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        HomeViewDtos.HomeViewResponse result = service.reset(
                7L, 11L, viewId, commandId, "corr", 4L);

        assertThat(result.layout()).isEqualTo(defaults);
        assertThat(result.customized()).isFalse();
        assertThat(result.widgetConfigurations()).isEmpty();
        verify(widgetConfigurations).deleteAll(List.of(configuration));
        verify(deviceLayouts).deleteAll(List.of(device));
        verify(compatibilityBridge).mirrorDefaultView(view);
        verify(revisionRepository).saveAndFlush(any(HomeViewRevision.class));
        verify(audit).success(eq(7L), eq(11L), eq("home-view.reset"),
                eq("HOME_VIEW"), eq(viewId.toString()), eq("corr"), any(), any());
    }

    @Test
    void resetRetryReplaysItsExactResponseBeforeADeletedTargetLookup() {
        UUID viewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomeViewDtos.HomeViewResponse original = new HomeViewDtos.HomeViewResponse(
                viewId, "default", "workspace-home", "My home", true, false, 5,
                layout(List.of()), 5L, null, null, Map.of());
        when(commandReceipts.replay(
                eq(7L), eq(11L), eq(commandId), eq("RESET_VIEW"),
                eq(viewId.toString()), any(String.class),
                eq(HomeViewDtos.HomeViewResponse.class))).thenReturn(original);

        assertThat(service.reset(7L, 11L, viewId, commandId, "retry", 4L))
                .isEqualTo(original);

        verify(viewRepository, never())
                .findByViewIdAndTenantIdAndUserId(any(), any(), any());
        verify(viewRepository, never()).findOwnedForUpdate(any(), any(), any());
        verify(scopeLock, never()).lock(any(), any(), any());
    }

    @Test
    void dualWriteFailureAbortsTheTransactionalViewMutationBeforeRevisionAndAudit() {
        UUID viewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload current = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall")));
        HomePreferenceDtos.HomeLayoutPayload requested = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "large", "tall")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(current)).version(0L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(revisionRepository.findByTenantIdAndUserIdAndCommandId(
                7L, 11L, commandId)).thenReturn(Optional.empty());
        when(preferenceService.normalizeForSurface("workspace-home", requested))
                .thenReturn(requested);
        when(viewRepository.saveAndFlush(view)).thenReturn(view);
        doThrow(new IllegalStateException("dual write unavailable"))
                .when(compatibilityBridge).mirrorDefaultView(view);

        assertThatThrownBy(() -> service.applyExternalLayout(
                7L, 11L, viewId, 0L, requested, "USER", "Applied",
                commandId, "a".repeat(64), 11L, "corr"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dual write unavailable");

        verify(revisionRepository, never()).saveAndFlush(any());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateRetryReplaysItsExactResponseBeforeADeletedTargetLookup() {
        UUID viewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload requestLayout = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall")));
        HomeViewDtos.HomeViewResponse original = new HomeViewDtos.HomeViewResponse(
                viewId, "default", "workspace-home", "Saved", true, true, 5,
                requestLayout, 4L, null, null, Map.of());
        when(commandReceipts.replay(
                eq(7L), eq(11L), eq(commandId), eq("UPDATE_VIEW"),
                eq(viewId.toString()), any(String.class),
                eq(HomeViewDtos.HomeViewResponse.class))).thenReturn(original);

        assertThat(service.update(
                7L, 11L, viewId, commandId, "retry",
                new HomeViewDtos.UpdateHomeViewRequest("Saved", requestLayout, 3L)))
                .isEqualTo(original);

        verify(viewRepository, never())
                .findByViewIdAndTenantIdAndUserId(any(), any(), any());
        verify(viewRepository, never()).findOwnedForUpdate(any(), any(), any());
        verify(scopeLock, never()).lock(any(), any(), any());
    }

    @Test
    void deleteRetryReplaysItsExactResponseBeforeADeletedTargetLookup() {
        UUID viewId = UUID.randomUUID();
        UUID activeViewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        HomeViewDtos.DeleteHomeViewResponse original =
                new HomeViewDtos.DeleteHomeViewResponse(viewId, activeViewId);
        when(commandReceipts.replay(
                eq(7L), eq(11L), eq(commandId), eq("DELETE_VIEW"),
                eq(viewId.toString()), any(String.class),
                eq(HomeViewDtos.DeleteHomeViewResponse.class))).thenReturn(original);

        assertThat(service.delete(
                7L, 11L, viewId, commandId, 3L, "retry"))
                .isEqualTo(original);

        verify(viewRepository, never()).findOwnedForUpdate(any(), any(), any());
        verify(scopeLock, never()).lock(any(), any(), any());
    }

    private HomePreferenceDtos.HomeLayoutPayload layout(
            List<HomePreferenceDtos.WidgetPreference> widgets) {
        return new HomePreferenceDtos.HomeLayoutPayload(
                new HomePreferenceDtos.AppLayoutPayloadV1(
                        1, Map.of("work", List.of("dwp-work")), Map.of(), List.of()),
                "balanced", widgets);
    }
}
