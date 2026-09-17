package com.dwp.services.platform.home.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.HomeCompositionPolicyReader;
import com.dwp.services.platform.home.personalization.HomePersonalizationScopeLock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomePreferenceServiceTest {

    @Mock
    private HomePreferenceRepository repository;
    @Mock
    private PlatformAuditService auditService;
    @Mock
    private HomePersonalizationScopeLock scopeLock;

    private ObjectMapper objectMapper;
    private HomePreferenceService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new HomePreferenceService(
                repository, objectMapper, auditService, tenantId -> true, scopeLock);
    }

    @Test
    void returnsGovernedWorkspaceDefaultsWithoutCreatingData() {
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME))
                .thenReturn(Optional.empty());

        HomePreferenceDtos.HomePreferenceResponse result = service.get(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME);

        assertThat(result.customized()).isFalse();
        assertThat(result.schemaVersion()).isEqualTo(5);
        assertThat(result.surfaceKey()).isEqualTo(HomePreferenceService.WORKSPACE_HOME);
        assertThat(result.layout().presentation()).isEqualTo("balanced");
        assertThat(result.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .containsExactly(
                        "command-rail", "activity", "focus", "schedule", "daily-brief",
                        "focus-balance", "meeting-load");
        assertThat(result.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::size)
                .containsExactly(
                        "large", "quarter", "medium", "quarter", "full", "medium", "medium");
        assertThat(result.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::height)
                .containsExactly(
                        "short", "tall", "tall", "standard", "standard", "short", "short");
    }

    @Test
    void returnsRoleAwareHcmSurfaceDefaults() {
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.HCM_HOME))
                .thenReturn(Optional.empty());

        HomePreferenceDtos.HomePreferenceResponse result = service.get(
                7L, 11L, HomePreferenceService.HCM_HOME);

        assertThat(result.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .containsExactly(
                        "quick-actions", "people-signals", "attention", "profile", "team", "operations");
        assertThat(result.layout().appLayout()).isNull();
    }

    @Test
    void normalizesLegacyHrisSurfaceRequestsToCanonicalHcm() {
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.HCM_HOME))
                .thenReturn(Optional.empty());

        HomePreferenceDtos.HomePreferenceResponse result = service.get(
                7L, 11L, HomePreferenceService.LEGACY_HRIS_HOME);

        assertThat(result.surfaceKey()).isEqualTo(HomePreferenceService.HCM_HOME);
        verify(repository).findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.HCM_HOME);
    }

    @Test
    void storesAValidatedWorkspaceLayoutForTheCurrentUser() {
        ObjectNode appLayout = validAppLayout();
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(HomePreference.class))).thenAnswer(invocation -> {
            HomePreference saved = invocation.getArgument(0);
            saved.setHomePreferenceId(31L);
            saved.setVersion(0L);
            return saved;
        });

        HomePreferenceDtos.HomePreferenceResponse result = service.update(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                "corr-home",
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        workspaceLayout(workspaceWidgets(), appLayout, "expressive"),
                        0L));

        assertThat(result.customized()).isTrue();
        assertThat(result.layout().presentation()).isEqualTo("expressive");
        JsonNode actualAppLayout = objectMapper.valueToTree(result.layout().appLayout());
        assertThat(actualAppLayout).isEqualTo(appLayout);
        assertThat(result.layout().widgets()).hasSize(7);
        assertThat(result.layout().widgets().getFirst().size()).isEqualTo("large");
        assertThat(result.layout().widgets().getFirst().height()).isEqualTo("short");
        verify(auditService).success(
                eq(7L),
                eq(11L),
                eq("home-preference.updated"),
                eq("HOME_PREFERENCE"),
                eq("11:workspace-home"),
                eq("corr-home"),
                anyMap(),
                anyMap());
    }

    @Test
    void persistsPersonalCurrentModeSeparatelyFromTenantAllowedAndDefaultModes() {
        HomeCompositionPolicyReader modes = new HomeCompositionPolicyReader() {
            @Override public boolean personalCustomizationEnabled(Long tenantId) { return true; }
            @Override public Set<String> allowedModes(Long tenantId) {
                return Set.of("CLASSIC", "FLOW_V1", "MZ_V1");
            }
            @Override public String defaultMode(Long tenantId) { return "FLOW_V1"; }
            @Override public boolean modeEnabled(String mode) { return true; }
        };
        HomePreferenceService modeService = new HomePreferenceService(
                repository, objectMapper, auditService, modes, scopeLock);
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(HomePreference.class))).thenAnswer(invocation -> {
            HomePreference saved = invocation.getArgument(0);
            saved.setVersion(0L);
            return saved;
        });

        HomePreferenceDtos.HomePreferenceResponse result = modeService.update(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME, "mode-change",
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        workspaceLayout(workspaceWidgets(), null, "balanced"),
                        "MZ_V1", 0L));

        assertThat(result.allowedModes()).containsExactly("CLASSIC", "FLOW_V1", "MZ_V1");
        assertThat(result.defaultMode()).isEqualTo("FLOW_V1");
        assertThat(result.currentMode()).isEqualTo("MZ_V1");
        verify(repository).saveAndFlush(argThat(preference ->
                "MZ_V1".equals(preference.getCurrentMode())));
    }

    @Test
    void changesCurrentModeWithoutLayoutCustomizationPermissionOrMutation() {
        HomeCompositionPolicyReader modes = new HomeCompositionPolicyReader() {
            @Override public boolean personalCustomizationEnabled(Long tenantId) { return false; }
            @Override public Set<String> allowedModes(Long tenantId) {
                return Set.of("CLASSIC", "FLOW_V1", "MZ_V1");
            }
            @Override public String defaultMode(Long tenantId) { return "CLASSIC"; }
            @Override public boolean modeEnabled(String mode) { return true; }
        };
        HomePreferenceService modeService = new HomePreferenceService(
                repository, objectMapper, auditService, modes, scopeLock);
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(HomePreference.class))).thenAnswer(invocation -> {
            HomePreference saved = invocation.getArgument(0);
            saved.setVersion(0L);
            return saved;
        });

        HomePreferenceDtos.HomePreferenceResponse result = modeService.updateCurrentMode(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                "mode-only",
                new HomePreferenceDtos.UpdateHomeCurrentModeRequest("MZ_V1", 0L));

        assertThat(result.currentMode()).isEqualTo("MZ_V1");
        assertThat(result.customized()).isFalse();
        assertThat(result.layout()).isEqualTo(
                modeService.defaultLayoutForSurface(HomePreferenceService.WORKSPACE_HOME));
        verify(repository).saveAndFlush(argThat(preference ->
                "MZ_V1".equals(preference.getCurrentMode())
                        && !preference.isCustomized()));
        verify(auditService).success(
                eq(7L), eq(11L), eq("home-preference.current-mode.updated"),
                eq("HOME_PREFERENCE"), eq("11:workspace-home"), eq("mode-only"),
                anyMap(), anyMap());
    }

    @Test
    void exposesTenantAllowlistSeparatelyFromRuntimeModeAvailability() {
        HomeCompositionPolicyReader modes = new HomeCompositionPolicyReader() {
            @Override public boolean personalCustomizationEnabled(Long tenantId) { return true; }
            @Override public Set<String> allowedModes(Long tenantId) {
                return Set.of("CLASSIC", "FLOW_V1", "MZ_V1");
            }
            @Override public String defaultMode(Long tenantId) { return "CLASSIC"; }
            @Override public boolean modeEnabled(String mode) { return !"MZ_V1".equals(mode); }
        };
        HomePreferenceService modeService = new HomePreferenceService(
                repository, objectMapper, auditService, modes, scopeLock);
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME)).thenReturn(Optional.empty());

        HomePreferenceDtos.HomePreferenceResponse result = modeService.get(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME);

        assertThat(result.allowedModes()).containsExactly("CLASSIC", "FLOW_V1", "MZ_V1");
        assertThat(result.enabledModes()).containsExactly("CLASSIC", "FLOW_V1");
        assertThat(result.disabledModeReasons()).containsEntry(
                "MZ_V1", "ROLLOUT_OR_KILL_SWITCH_DISABLED");
    }

    @Test
    void reconcilesAStoredModeAfterTenantPolicyRevocation() {
        HomeCompositionPolicyReader modes = new HomeCompositionPolicyReader() {
            @Override public boolean personalCustomizationEnabled(Long tenantId) { return true; }
            @Override public Set<String> allowedModes(Long tenantId) {
                return Set.of("CLASSIC");
            }
            @Override public String defaultMode(Long tenantId) { return "CLASSIC"; }
        };
        HomePreferenceService modeService = new HomePreferenceService(
                repository, objectMapper, auditService, modes, scopeLock);
        HomePreference preference = HomePreference.builder()
                .tenantId(7L).userId(11L).surfaceKey(HomePreferenceService.WORKSPACE_HOME)
                .schemaVersion(HomePreferenceDtos.SCHEMA_VERSION)
                .layoutPayload(objectMapper.valueToTree(
                        workspaceLayout(workspaceWidgets(), null, "balanced")))
                .currentMode("MZ_V1").version(4L).customized(true).build();
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME))
                .thenReturn(Optional.of(preference));

        HomePreferenceDtos.HomePreferenceResponse result = modeService.get(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME);

        assertThat(result.currentMode()).isEqualTo("CLASSIC");
        assertThat(result.integrityStatus())
                .isEqualTo(HomePreferenceDtos.HomePreferenceIntegrityStatus.RECONCILED);
        assertThat(result.warnings()).contains("CURRENT_MODE_RECONCILED");
    }

    @Test
    void acceptsTheCompactResponseHubFootprintUsedByFlowHome() {
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(HomePreference.class))).thenAnswer(invocation -> {
            HomePreference saved = invocation.getArgument(0);
            saved.setHomePreferenceId(32L);
            saved.setVersion(0L);
            return saved;
        });
        List<HomePreferenceDtos.WidgetPreference> flowWidgets = workspaceWidgets().stream()
                .map(widget -> "daily-brief".equals(widget.widgetKey())
                        ? widget("daily-brief", true, "compact", "short")
                        : widget)
                .toList();

        HomePreferenceDtos.HomePreferenceResponse result = service.update(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                "corr-flow-home",
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        workspaceLayout(flowWidgets, null, "balanced"),
                        0L));

        assertThat(result.layout().widgets())
                .filteredOn(widget -> "daily-brief".equals(widget.widgetKey()))
                .singleElement()
                .satisfies(widget -> {
                    assertThat(widget.size()).isEqualTo("compact");
                    assertThat(widget.height()).isEqualTo("short");
                });
    }

    @Test
    void acceptsBoundedCalendarInsightGeometryAndRejectsExpansion() {
        List<HomePreferenceDtos.WidgetPreference> bounded = workspaceWidgets().stream()
                .map(widget -> switch (widget.widgetKey()) {
                    case "focus-balance" -> widget(
                            "focus-balance", true, "quarter", "standard");
                    case "meeting-load" -> widget(
                            "meeting-load", false, "compact", "short");
                    default -> widget;
                })
                .toList();

        HomePreferenceDtos.HomeLayoutPayload normalized = service.normalizeForSurface(
                HomePreferenceService.WORKSPACE_HOME,
                workspaceLayout(bounded, null, "balanced"));

        assertThat(normalized.widgets())
                .filteredOn(widget -> widget.widgetKey().equals("focus-balance"))
                .singleElement()
                .isEqualTo(widget("focus-balance", true, "quarter", "standard"));
        assertThat(normalized.widgets())
                .filteredOn(widget -> widget.widgetKey().equals("meeting-load"))
                .singleElement()
                .isEqualTo(widget("meeting-load", false, "compact", "short"));
        assertThat(service.isWidgetSizeAllowed(
                HomePreferenceService.WORKSPACE_HOME, "focus-balance", "compact")).isTrue();
        assertThat(service.isWidgetSizeAllowed(
                HomePreferenceService.WORKSPACE_HOME, "meeting-load", "full")).isFalse();

        List<HomePreferenceDtos.WidgetPreference> oversized = workspaceWidgets().stream()
                .map(widget -> widget.widgetKey().equals("focus-balance")
                        ? widget("focus-balance", true, "full", "short")
                        : widget)
                .toList();
        assertThatThrownBy(() -> service.normalizeForSurface(
                HomePreferenceService.WORKSPACE_HOME,
                workspaceLayout(oversized, null, "balanced")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));

        List<HomePreferenceDtos.WidgetPreference> tooTall = workspaceWidgets().stream()
                .map(widget -> widget.widgetKey().equals("meeting-load")
                        ? widget("meeting-load", true, "medium", "tall")
                        : widget)
                .toList();
        assertThatThrownBy(() -> service.normalizeForSurface(
                HomePreferenceService.WORKSPACE_HOME,
                workspaceLayout(tooTall, null, "balanced")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void updateAuditPreservesFalseCustomizedBeforeImage() {
        HomePreference preference = HomePreference.builder()
                .homePreferenceId(31L)
                .tenantId(7L)
                .userId(11L)
                .surfaceKey(HomePreferenceService.WORKSPACE_HOME)
                .schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(
                        workspaceLayout(workspaceWidgets(), validAppLayout(), "balanced")))
                .version(4L)
                .customized(false)
                .build();
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME))
                .thenReturn(Optional.of(preference));
        when(repository.saveAndFlush(preference)).thenReturn(preference);

        service.update(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                "corr-update-from-reset",
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        workspaceLayout(workspaceWidgets(), validAppLayout(), "expressive"),
                        4L));

        verify(auditService).success(
                eq(7L),
                eq(11L),
                eq("home-preference.updated"),
                eq("HOME_PREFERENCE"),
                eq("11:workspace-home"),
                eq("corr-update-from-reset"),
                argThat(before -> Boolean.FALSE.equals(
                        ((Map<?, ?>) before).get("customized"))),
                argThat(after -> Boolean.TRUE.equals(
                        ((Map<?, ?>) after).get("customized"))));
    }

    @Test
    void resetAuditPreservesFalseCustomizedBeforeImage() {
        HomePreference preference = HomePreference.builder()
                .homePreferenceId(31L)
                .tenantId(7L)
                .userId(11L)
                .surfaceKey(HomePreferenceService.WORKSPACE_HOME)
                .schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(
                        workspaceLayout(workspaceWidgets(), validAppLayout(), "balanced")))
                .version(4L)
                .customized(false)
                .build();
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME))
                .thenReturn(Optional.of(preference));
        when(repository.saveAndFlush(preference)).thenReturn(preference);

        service.reset(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                "corr-reset-again",
                4L);

        verify(auditService).success(
                eq(7L),
                eq(11L),
                eq("home-preference.reset"),
                eq("HOME_PREFERENCE"),
                eq("11:workspace-home"),
                eq("corr-reset-again"),
                argThat(before -> Boolean.FALSE.equals(
                        ((Map<?, ?>) before).get("customized"))),
                argThat(after -> Boolean.FALSE.equals(
                        ((Map<?, ?>) after).get("customized"))));
    }

    @Test
    void storesHcmCompositionIndependentlyFromWorkspaceHome() {
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.HCM_HOME))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(HomePreference.class))).thenAnswer(invocation -> {
            HomePreference saved = invocation.getArgument(0);
            saved.setHomePreferenceId(32L);
            saved.setVersion(0L);
            return saved;
        });

        List<HomePreferenceDtos.WidgetPreference> widgets = List.of(
                widget("attention", true, "full"),
                widget("quick-actions", true, "medium"),
                widget("profile", false, "compact"),
                widget("people-signals", true, "large"),
                widget("team", true, "large"),
                widget("operations", false, "full"));
        HomePreferenceDtos.HomePreferenceResponse result = service.update(
                7L,
                11L,
                HomePreferenceService.HCM_HOME,
                "corr-hcm",
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        new HomePreferenceDtos.HomeLayoutPayload(null, "focused", widgets),
                        0L));

        assertThat(result.surfaceKey()).isEqualTo(HomePreferenceService.HCM_HOME);
        assertThat(result.layout().presentation()).isEqualTo("focused");
        assertThat(result.layout().widgets().getFirst().widgetKey()).isEqualTo("attention");
        assertThat(result.layout().widgets().getFirst().size()).isEqualTo("full");
    }

    @Test
    void upgradesLegacyWorkspaceDocumentsAtReadTime() {
        ObjectNode legacyLayout = objectMapper.createObjectNode();
        legacyLayout.set("appLayout", validAppLayout());
        var legacyWidgets = legacyLayout.putArray("widgets");
        legacyWorkspaceWidgets().forEach(widget -> legacyWidgets.addObject()
                .put("widgetKey", widget.widgetKey())
                .put("visible", widget.visible())
                .put("size", widget.size()));
        HomePreference legacy = HomePreference.builder()
                .homePreferenceId(31L)
                .tenantId(7L)
                .userId(11L)
                .surfaceKey(HomePreferenceService.WORKSPACE_HOME)
                .schemaVersion(1)
                .layoutPayload(legacyLayout)
                .version(4L)
                .build();
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME))
                .thenReturn(Optional.of(legacy));

        HomePreferenceDtos.HomePreferenceResponse result = service.get(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME);

        assertThat(result.schemaVersion()).isEqualTo(5);
        JsonNode actualAppLayout = objectMapper.valueToTree(result.layout().appLayout());
        assertThat(actualAppLayout).isEqualTo(validAppLayout());
        assertThat(result.layout().presentation()).isEqualTo("balanced");
        assertThat(result.layout().widgets())
                .allSatisfy(widget -> assertThat(widget.size()).isNotBlank());
        assertThat(result.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .containsExactly(
                        "command-rail", "activity", "focus", "schedule", "daily-brief",
                        "focus-balance", "meeting-load");
        assertThat(result.integrityStatus())
                .isEqualTo(HomePreferenceDtos.HomePreferenceIntegrityStatus.RECONCILED);
        assertThat(result.layout().widgets().subList(5, 7))
                .containsExactly(
                        widget("focus-balance", true, "medium", "short"),
                        widget("meeting-load", true, "medium", "short"));
    }

    @Test
    void rejectsUnknownSurfaceAndDisallowedWidgetSize() {
        assertThatThrownBy(() -> service.get(7L, 11L, "unregistered-home"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        List<HomePreferenceDtos.WidgetPreference> invalidWorkspaceFootprint = List.of(
                widget("activity", true, "quarter"),
                widget("focus", true, "fifth"),
                widget("schedule", true, "quarter"),
                widget("daily-brief", true, "full"));
        assertThatThrownBy(() -> service.update(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                null,
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        workspaceLayout(invalidWorkspaceFootprint, null, "balanced"),
                        0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        List<HomePreferenceDtos.WidgetPreference> invalidWorkspaceHeight = List.of(
                widget("activity", true, "quarter"),
                widget("focus", true, "medium"),
                widget("schedule", true, "quarter", "expanded"),
                widget("daily-brief", true, "full"));
        assertThatThrownBy(() -> service.update(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                null,
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        workspaceLayout(invalidWorkspaceHeight, null, "balanced"),
                        0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        List<HomePreferenceDtos.WidgetPreference> invalid = List.of(
                widget("quick-actions", true, "compact"));
        assertThatThrownBy(() -> service.update(
                7L,
                11L,
                HomePreferenceService.HCM_HOME,
                null,
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        new HomePreferenceDtos.HomeLayoutPayload(null, "balanced", invalid),
                        0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void rejectsAppLayoutsOnSurfacesWithoutAPersonalApplicationCatalog() {
        List<HomePreferenceDtos.WidgetPreference> widgets = List.of(
                widget("quick-actions", true, "full"),
                widget("people-signals", true, "full"),
                widget("attention", true, "large"),
                widget("profile", true, "compact"),
                widget("team", true, "full"),
                widget("operations", true, "full"));

        assertThatThrownBy(() -> service.update(
                7L,
                11L,
                HomePreferenceService.HCM_HOME,
                null,
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        new HomePreferenceDtos.HomeLayoutPayload(
                                typedAppLayout(validAppLayout()), "balanced", widgets),
                        0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void rejectsWorkspaceUpdatesWhenTenantDisablesPersonalCustomization() {
        HomeCompositionPolicyReader disabled = tenantId -> false;
        HomePreferenceService governedService = new HomePreferenceService(
                repository, objectMapper, auditService, disabled, scopeLock);

        assertThatThrownBy(() -> governedService.update(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                null,
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        workspaceLayout(workspaceWidgets(), null, "balanced"),
                        0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> governedService.reset(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                null,
                0L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void rejectsDuplicateAppPlacementAcrossGroupsAndFolders() {
        HomePreferenceDtos.AppLayoutPayloadV1 duplicate =
                new HomePreferenceDtos.AppLayoutPayloadV1(
                        1,
                        Map.of("work", List.of("dwp-work"),
                                "connect", List.of("dwp-work")),
                        Map.of(),
                        List.of());

        assertThatThrownBy(() -> service.update(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                null,
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        new HomePreferenceDtos.HomeLayoutPayload(
                                duplicate, "balanced", workspaceWidgets()),
                        0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void returnsRecoverableDefaultsForCorruptPersistedLayouts() throws Exception {
        HomePreference corrupt = HomePreference.builder()
                .homePreferenceId(31L)
                .tenantId(7L)
                .userId(11L)
                .surfaceKey(HomePreferenceService.WORKSPACE_HOME)
                .schemaVersion(5)
                .layoutPayload(objectMapper.readTree("""
                        {
                          "presentation":"balanced",
                          "widgets":[],
                          "fixedZones":["now"]
                        }
                        """))
                .version(8L)
                .build();
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME))
                .thenReturn(Optional.of(corrupt));

        HomePreferenceDtos.HomePreferenceResponse result = service.get(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME);

        assertThat(result.customized()).isTrue();
        assertThat(result.integrityStatus())
                .isEqualTo(HomePreferenceDtos.HomePreferenceIntegrityStatus.RECOVERED);
        assertThat(result.version()).isEqualTo(8L);
        assertThat(result.warnings()).containsExactly("INVALID_STORED_LAYOUT");
        assertThat(result.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .contains("command-rail", "focus", "schedule");
    }

    @Test
    void strictLayoutDecoderRejectsFlowFixedZoneOverrides() {
        assertThatThrownBy(() -> objectMapper.readValue("""
                        {
                          "appLayout":null,
                          "presentation":"balanced",
                          "widgets":[{"widgetKey":"focus","visible":true}],
                          "now":{"visible":false}
                        }
                        """, HomePreferenceDtos.HomeLayoutPayload.class))
                .hasMessageContaining("Unknown home layout field: now");
    }

    private HomePreferenceDtos.HomeLayoutPayload workspaceLayout(
            List<HomePreferenceDtos.WidgetPreference> widgets,
            ObjectNode appLayout,
            String presentation) {
        return new HomePreferenceDtos.HomeLayoutPayload(
                appLayout == null ? null : typedAppLayout(appLayout), presentation, widgets);
    }

    private HomePreferenceDtos.AppLayoutPayloadV1 typedAppLayout(ObjectNode value) {
        return objectMapper.convertValue(value, HomePreferenceDtos.AppLayoutPayloadV1.class);
    }

    private ObjectNode validAppLayout() {
        ObjectNode appLayout = objectMapper.createObjectNode();
        appLayout.put("version", 1);
        appLayout.putObject("groups").putArray("work").add("dwp-work");
        appLayout.putObject("folders");
        appLayout.putArray("hiddenAppIds").add("dwp-ask");
        return appLayout;
    }

    private List<HomePreferenceDtos.WidgetPreference> workspaceWidgets() {
        return List.of(
                widget("command-rail", true, "large"),
                widget("activity", true, "fifth"),
                widget("focus", true, "medium"),
                widget("schedule", true, "quarter"),
                widget("daily-brief", true, "full"),
                widget("focus-balance", true, "medium"),
                widget("meeting-load", true, "medium"));
    }

    private List<HomePreferenceDtos.WidgetPreference> legacyWorkspaceWidgets() {
        return List.of(
                widget("announcements", false, "full"),
                widget("activity", true, "compact"),
                widget("focus", true, "medium"),
                widget("schedule", true, "compact"),
                widget("daily-brief", true, "full"));
    }

    private HomePreferenceDtos.WidgetPreference widget(
            String widgetKey,
            boolean visible,
            String size) {
        return new HomePreferenceDtos.WidgetPreference(widgetKey, visible, size, null);
    }

    private HomePreferenceDtos.WidgetPreference widget(
            String widgetKey,
            boolean visible,
            String size,
            String height) {
        return new HomePreferenceDtos.WidgetPreference(widgetKey, visible, size, height);
    }
}
