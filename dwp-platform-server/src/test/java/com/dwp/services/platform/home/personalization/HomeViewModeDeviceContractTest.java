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
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class HomeViewModeDeviceContractTest {
    private static final HomeViewRegistryPlacementPolicy.Authority AUTHORITY =
            HomeViewRegistryPlacementPolicy.Authority.NONE;
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
    @Mock private HomeViewRegistryPlacementPolicy registryPlacements;

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
                widgetPolicy, new HomeCanonicalJson(objectMapper), commandReceipts,
                registryPlacements);
        lenient().when(registryPlacements.contracts(any(), any(), any(), any()))
                .thenReturn(Map.of());
        lenient().when(registryPlacements.contracts(any(), any(), any(), any(), any()))
                .thenReturn(Map.of());
        lenient().when(compositionPolicy.personalCustomizationEnabled(7L)).thenReturn(true);
        lenient().when(compositionPolicy.flowPersonalizationEnabled(7L)).thenReturn(true);
        lenient().when(compositionPolicy.mzPersonalizationEnabled(7L)).thenReturn(true);
    }

    @Test
    void tenantPolicyAllowsExistingViewReadsButBlocksMutations() {
        UUID viewId = UUID.randomUUID();
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").modeKey("FLOW_V1")
                .viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(layout(List.of())))
                .version(0L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(compositionPolicy.flowPersonalizationEnabled(7L)).thenReturn(false);
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                viewId, 7L, 11L)).thenReturn(List.of());
        when(viewRepository.findByTenantIdAndUserIdAndSurfaceKeyAndModeKeyOrderByUpdatedAtDesc(
                7L, 11L, "workspace-home", "FLOW_V1")).thenReturn(List.of(view));
        when(revisionRepository
                .findTop50ByViewIdAndTenantIdAndUserIdAndRestorableTrueOrderByRevisionNumberDesc(
                viewId, 7L, 11L)).thenReturn(List.of());
        when(deviceLayouts.findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                viewId, 7L, 11L)).thenReturn(List.of());

        assertThat(service.get(7L, 11L, viewId).viewId()).isEqualTo(viewId);
        assertThat(service.list(7L, 11L, "workspace-home", "FLOW_V1")).hasSize(1);
        assertThat(service.revisions(7L, 11L, viewId)).isEmpty();
        assertThat(service.deviceLayouts(7L, 11L, viewId)).isEmpty();
        assertThatThrownBy(() -> service.update(
                7L, 11L, viewId, UUID.randomUUID(), "corr",
                new HomeViewDtos.UpdateHomeViewRequest(
                        "Changed", layout(List.of()), 0L), AUTHORITY))
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
        when(viewRepository.findByTenantIdAndUserIdAndSurfaceKeyAndModeKeyOrderByUpdatedAtDesc(
                7L, 11L, "workspace-home", "CLASSIC")).thenReturn(List.of());

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
    void classicFlowAndMzListsAreIndependentEvenWhenViewKeysMatch() {
        HomeView classic = HomeView.builder()
                .viewId(UUID.randomUUID()).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").modeKey("CLASSIC")
                .viewKey("default").name("Classic home").defaultView(true)
                .schemaVersion(5).layoutPayload(objectMapper.valueToTree(layout(List.of())))
                .version(0L).build();
        HomeView flow = HomeView.builder()
                .viewId(UUID.randomUUID()).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").modeKey("FLOW_V1")
                .viewKey("default").name("Flow home").defaultView(true)
                .schemaVersion(5).layoutPayload(objectMapper.valueToTree(layout(List.of())))
                .version(0L).build();
        HomeView mz = HomeView.builder()
                .viewId(UUID.randomUUID()).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").modeKey("MZ_V1")
                .viewKey("default").name("MZ home").defaultView(true)
                .schemaVersion(5).layoutPayload(objectMapper.valueToTree(layout(List.of())))
                .version(0L).build();
        when(viewRepository.findByTenantIdAndUserIdAndSurfaceKeyAndModeKeyOrderByUpdatedAtDesc(
                7L, 11L, "workspace-home", "CLASSIC")).thenReturn(List.of(classic));
        when(viewRepository.findByTenantIdAndUserIdAndSurfaceKeyAndModeKeyOrderByUpdatedAtDesc(
                7L, 11L, "workspace-home", "FLOW_V1")).thenReturn(List.of(flow));
        when(viewRepository.findByTenantIdAndUserIdAndSurfaceKeyAndModeKeyOrderByUpdatedAtDesc(
                7L, 11L, "workspace-home", "MZ_V1")).thenReturn(List.of(mz));
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                classic.getViewId(), 7L, 11L)).thenReturn(List.of());
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                flow.getViewId(), 7L, 11L)).thenReturn(List.of());
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                mz.getViewId(), 7L, 11L)).thenReturn(List.of());

        assertThat(service.list(7L, 11L, "workspace-home", "CLASSIC").getFirst().name())
                .isEqualTo("Classic home");
        assertThat(service.list(7L, 11L, "workspace-home", "FLOW_V1").getFirst().name())
                .isEqualTo("Flow home");
        assertThat(service.list(7L, 11L, "workspace-home", "FLOW_V1").getFirst().modeKey())
                .isEqualTo("FLOW_V1");
        assertThat(service.list(7L, 11L, "workspace-home", "MZ_V1").getFirst().name())
                .isEqualTo("MZ home");
        assertThat(service.list(7L, 11L, "workspace-home", "MZ_V1").getFirst().modeKey())
                .isEqualTo("MZ_V1");
    }

    @Test
    void mzCreateEvaluatesRegistryPlacementContractsInTheMzNamespace() {
        UUID commandId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload initial = layout(List.of());
        when(viewRepository.countByTenantIdAndUserIdAndSurfaceKeyAndModeKey(
                7L, 11L, "workspace-home", "MZ_V1")).thenReturn(0L);
        when(preferenceService.normalizeForSurface("workspace-home", initial, Map.of()))
                .thenReturn(initial);
        when(viewRepository.saveAndFlush(any(HomeView.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(revisionRepository.findTopByViewIdOrderByRevisionNumberDesc(any()))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any(HomeViewRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        HomeViewDtos.HomeViewResponse created = service.create(
                7L, 11L, commandId, "mz-create",
                new HomeViewDtos.CreateHomeViewRequest(
                        "personal", "MZ personal", "MZ_V1", true, initial), AUTHORITY);

        assertThat(created.modeKey()).isEqualTo("MZ_V1");
        verify(registryPlacements).contracts(
                7L, "workspace-home", "MZ_V1", AUTHORITY);
        verify(scopeLock).lock(7L, 11L, "workspace-home", "MZ_V1");
    }

    @Test
    void legacyNoModeCreateListAndUpdateStayInTheEffectiveFlowNamespace() {
        UUID createCommand = UUID.randomUUID();
        UUID updateCommand = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload initial = layout(List.of());
        HomePreferenceDtos.HomeLayoutPayload changed = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "focus", true, "medium", "tall")));
        java.util.concurrent.atomic.AtomicReference<HomeView> persisted =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(compositionPolicy.effectiveExperienceVariant(7L)).thenReturn("FLOW_V1");
        when(viewRepository.countByTenantIdAndUserIdAndSurfaceKeyAndModeKey(
                7L, 11L, "workspace-home", "FLOW_V1")).thenReturn(0L);
        when(preferenceService.normalizeForSurface("workspace-home", initial, Map.of()))
                .thenReturn(initial);
        when(preferenceService.normalizeForSurface(
                "workspace-home", changed, Map.of(), initial))
                .thenReturn(changed);
        when(viewRepository.saveAndFlush(any(HomeView.class))).thenAnswer(invocation -> {
            HomeView value = invocation.getArgument(0);
            persisted.set(value);
            return value;
        });
        when(viewRepository.findByViewIdAndTenantIdAndUserId(any(), eq(7L), eq(11L)))
                .thenAnswer(ignored -> Optional.ofNullable(persisted.get()));
        when(viewRepository.findOwnedForUpdate(any(), eq(7L), eq(11L)))
                .thenAnswer(ignored -> Optional.ofNullable(persisted.get()));
        when(viewRepository.findByTenantIdAndUserIdAndSurfaceKeyAndModeKeyOrderByUpdatedAtDesc(
                7L, 11L, "workspace-home", "FLOW_V1"))
                .thenAnswer(ignored -> persisted.get() == null
                        ? List.of() : List.of(persisted.get()));
        when(revisionRepository.findTopByViewIdOrderByRevisionNumberDesc(any()))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any(HomeViewRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        HomeViewDtos.HomeViewResponse created = service.create(
                7L, 11L, createCommand, "legacy-flow-create",
                new HomeViewDtos.CreateHomeViewRequest(
                        "personal", "Flow personal", true, initial), AUTHORITY);
        HomeViewDtos.HomeViewResponse listed =
                service.list(7L, 11L, "workspace-home").getFirst();
        HomeViewDtos.HomeViewResponse updated = service.update(
                7L, 11L, created.viewId(), updateCommand, "legacy-flow-update",
                new HomeViewDtos.UpdateHomeViewRequest(
                        "Changed Flow", changed, 0L), AUTHORITY);

        assertThat(created.modeKey()).isEqualTo("FLOW_V1");
        assertThat(listed.modeKey()).isEqualTo("FLOW_V1");
        assertThat(updated.modeKey()).isEqualTo("FLOW_V1");
        assertThat(updated.name()).isEqualTo("Changed Flow");
        verify(scopeLock, org.mockito.Mockito.times(2))
                .lock(7L, 11L, "workspace-home", "FLOW_V1");
        verify(scopeLock, never()).lock(7L, 11L, "workspace-home", "CLASSIC");
    }

    @Test
    void legacyNoModeCreateReplaysAcrossAnEffectiveModeChange() {
        UUID commandId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload initial = layout(List.of());
        HomeViewDtos.CreateHomeViewRequest request = new HomeViewDtos.CreateHomeViewRequest(
                "personal", "Flow personal", true, initial);
        java.util.concurrent.atomic.AtomicReference<String> effectiveMode =
                new java.util.concurrent.atomic.AtomicReference<>("FLOW_V1");
        when(compositionPolicy.effectiveExperienceVariant(7L))
                .thenAnswer(ignored -> effectiveMode.get());
        when(viewRepository.countByTenantIdAndUserIdAndSurfaceKeyAndModeKey(
                7L, 11L, "workspace-home", "FLOW_V1")).thenReturn(0L);
        when(preferenceService.normalizeForSurface("workspace-home", initial, Map.of()))
                .thenReturn(initial);
        when(viewRepository.saveAndFlush(any(HomeView.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(revisionRepository.findTopByViewIdOrderByRevisionNumberDesc(any()))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any(HomeViewRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        HomeViewDtos.HomeViewResponse created = service.create(
                7L, 11L, commandId, "legacy-flow-create", request, AUTHORITY);

        org.mockito.ArgumentCaptor<String> target =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> fingerprint =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(commandReceipts).record(
                eq(7L), eq(11L), eq(commandId), eq("CREATE_VIEW"),
                target.capture(), fingerprint.capture(), eq(created));
        assertThat(target.getValue()).isEqualTo("workspace-home:EFFECTIVE:personal");
        when(commandReceipts.replay(
                7L, 11L, commandId, "CREATE_VIEW", target.getValue(),
                fingerprint.getValue(), HomeViewDtos.HomeViewResponse.class))
                .thenReturn(created);
        effectiveMode.set("CLASSIC");

        HomeViewDtos.HomeViewResponse replayed = service.create(
                7L, 11L, commandId, "retry-after-mode-change", request, AUTHORITY);

        assertThat(replayed).isEqualTo(created);
        verify(compositionPolicy).effectiveExperienceVariant(7L);
        verify(viewRepository).saveAndFlush(any(HomeView.class));
    }

    @Test
    void legacyNoModeNonWorkspaceSurfaceRemainsInTheClassicNamespace() {
        HomeView hcm = HomeView.builder()
                .viewId(UUID.randomUUID()).tenantId(7L).userId(11L)
                .surfaceKey("hcm-home").modeKey("CLASSIC")
                .viewKey("default").name("HCM home").defaultView(true)
                .schemaVersion(5).layoutPayload(objectMapper.valueToTree(layout(List.of())))
                .version(0L).build();
        when(viewRepository.findByTenantIdAndUserIdAndSurfaceKeyAndModeKeyOrderByUpdatedAtDesc(
                7L, 11L, "hcm-home", "CLASSIC")).thenReturn(List.of(hcm));
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                hcm.getViewId(), 7L, 11L)).thenReturn(List.of());

        assertThat(service.list(7L, 11L, "hcm-home").getFirst().modeKey())
                .isEqualTo("CLASSIC");
        verify(compositionPolicy, never()).effectiveExperienceVariant(7L);
    }

    @Test
    void staleFlowUpdateFailsBeforeMutationAndUsesOnlyTheFlowLock() {
        UUID viewId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload layout = layout(List.of());
        HomeView flow = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").modeKey("FLOW_V1")
                .viewKey("default").name("Flow home").defaultView(true)
                .schemaVersion(5).layoutPayload(objectMapper.valueToTree(layout))
                .version(5L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(flow));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(flow));

        assertThatThrownBy(() -> service.update(
                7L, 11L, viewId, UUID.randomUUID(), "corr",
                new HomeViewDtos.UpdateHomeViewRequest("Changed", layout, 4L), AUTHORITY))
                .isInstanceOfSatisfying(HomeViewConflictException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(
                            ErrorCode.HOME_VIEW_VERSION_CONFLICT);
                    assertThat(exception.conflict().operation()).isEqualTo("UPDATE_VIEW");
                    assertThat(exception.conflict().expectedVersion()).isEqualTo(4L);
                    assertThat(exception.conflict().actualVersion()).isEqualTo(5L);
                    assertThat(exception.conflict().latestView().viewId()).isEqualTo(viewId);
                    assertThat(exception.conflict().changedFields()).contains("name");
                });

        verify(scopeLock).lock(7L, 11L, "workspace-home", "FLOW_V1");
        verify(viewRepository, never()).saveAndFlush(any(HomeView.class));
        verify(compatibilityBridge, never()).mirrorDefaultView(any());
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
                new HomeViewDtos.UpdateDeviceLayoutRequest(
                        overlay, 0L, null), AUTHORITY))
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
                "workspace-home", "schedule", "full", Map.of())).thenReturn(false);

        var overlay = new HomeViewDtos.DeviceLayoutOverlay(
                List.of("schedule"), Map.of("schedule", "full"), "compact");
        assertThatThrownBy(() -> service.putDeviceLayout(
                7L, 11L, viewId, "MOBILE", UUID.randomUUID(), "corr",
                new HomeViewDtos.UpdateDeviceLayoutRequest(
                        overlay, 0L, null), AUTHORITY))
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
                new HomeViewDtos.UpdateDeviceLayoutRequest(
                        overlay, 0L, null), AUTHORITY))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
        verify(deviceLayouts, never()).saveAndFlush(any());
    }

    @Test
    void staleOrMissingDeviceVersionReturnsBothConcurrencyHeadsAndTheLatestOverlay() {
        UUID viewId = UUID.randomUUID();
        HomePreferenceDtos.HomeLayoutPayload current = layout(List.of(
                new HomePreferenceDtos.WidgetPreference(
                        "command-rail", true, "large", "short"),
                new HomePreferenceDtos.WidgetPreference(
                        "schedule", true, "quarter", "standard")));
        HomeView view = HomeView.builder()
                .viewId(viewId).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5).integrityState("VALID")
                .layoutPayload(objectMapper.valueToTree(current)).version(5L).build();
        var submittedOverlay = new HomeViewDtos.DeviceLayoutOverlay(
                List.of("schedule"), Map.of(), "compact");
        var currentOverlay = new HomeViewDtos.DeviceLayoutOverlay(
                List.of("schedule"), Map.of("schedule", "quarter"), "comfortable");
        HomeDeviceLayout stored = HomeDeviceLayout.builder()
                .deviceLayoutId(UUID.randomUUID()).viewId(viewId)
                .tenantId(7L).userId(11L).deviceClass("MOBILE_STANDARD")
                .overlayPayload(objectMapper.valueToTree(currentOverlay)).version(3L).build();
        when(viewRepository.findByViewIdAndTenantIdAndUserId(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(viewRepository.findOwnedForUpdate(viewId, 7L, 11L))
                .thenReturn(Optional.of(view));
        when(deviceLayouts.findByViewIdAndTenantIdAndUserIdAndDeviceClass(
                viewId, 7L, 11L, "MOBILE_STANDARD")).thenReturn(Optional.of(stored));
        when(widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                viewId, 7L, 11L)).thenReturn(List.of());

        for (Long submittedVersion : new Long[]{null, 2L}) {
            assertThatThrownBy(() -> service.putDeviceLayout(
                    7L, 11L, viewId, "MOBILE", UUID.randomUUID(), "corr",
                    new HomeViewDtos.UpdateDeviceLayoutRequest(
                            submittedOverlay, 5L, submittedVersion), AUTHORITY))
                    .isInstanceOfSatisfying(HomeViewConflictException.class, exception -> {
                        HomeViewDtos.HomeViewConflictResponse conflict = exception.conflict();
                        assertThat(conflict.operation()).isEqualTo("UPDATE_DEVICE_LAYOUT");
                        assertThat(conflict.expectedVersion()).isEqualTo(5L);
                        assertThat(conflict.actualVersion()).isEqualTo(5L);
                        assertThat(conflict.expectedDeviceVersion()).isEqualTo(submittedVersion);
                        assertThat(conflict.actualDeviceVersion()).isEqualTo(3L);
                        assertThat(conflict.latestDeviceLayout().overlay())
                                .isEqualTo(currentOverlay);
                        assertThat(conflict.latestDeviceLayout().viewVersion()).isEqualTo(5L);
                        assertThat(conflict.changedFields())
                                .containsExactly("deviceLayouts.MOBILE_STANDARD.version");
                    });
        }

        verify(deviceLayouts, never()).saveAndFlush(any());
        verify(viewRepository, never()).saveAndFlush(any());
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
                "workspace-home", "focus", "medium", Map.of())).thenReturn(true);
        when(preferenceService.isWidgetSizeAllowed(
                "workspace-home", "schedule", "quarter", Map.of())).thenReturn(true);
        when(preferenceService.isWidgetSizeAllowed(
                "workspace-home", "command-rail", "full", Map.of())).thenReturn(true);
        when(deviceLayouts.findByViewIdAndTenantIdAndUserIdAndDeviceClass(
                viewId, 7L, 11L, "MOBILE_STANDARD")).thenReturn(Optional.empty());
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
                Map.of(
                        "command-rail", "full",
                        "focus", "medium",
                        "schedule", "quarter"),
                "compact");
        var result = service.putDeviceLayout(
                7L, 11L, viewId, "mobile", UUID.randomUUID(), "corr",
                new HomeViewDtos.UpdateDeviceLayoutRequest(
                        overlay, 0L, null), AUTHORITY);

        assertThat(result.overlay()).isEqualTo(overlay);
        assertThat(result.deviceClass()).isEqualTo("MOBILE_STANDARD");
        assertThat(view.isCustomized()).isTrue();
        verify(compatibilityBridge).mirrorDefaultView(view);
    }

    private HomePreferenceDtos.HomeLayoutPayload layout(
            List<HomePreferenceDtos.WidgetPreference> widgets) {
        return new HomePreferenceDtos.HomeLayoutPayload(
                new HomePreferenceDtos.AppLayoutPayloadV1(
                        1, Map.of("work", List.of("dwp-work")), Map.of(), List.of()),
                "balanced", widgets);
    }
}
