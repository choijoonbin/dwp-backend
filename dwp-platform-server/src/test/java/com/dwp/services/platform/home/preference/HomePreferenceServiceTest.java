package com.dwp.services.platform.home.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.HomeCompositionPolicyReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomePreferenceServiceTest {

    @Mock
    private HomePreferenceRepository repository;
    @Mock
    private PlatformAuditService auditService;

    private ObjectMapper objectMapper;
    private HomePreferenceService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new HomePreferenceService(repository, objectMapper, auditService, tenantId -> true);
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
                .containsExactly("command-rail", "activity", "focus", "schedule", "daily-brief");
        assertThat(result.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::size)
                .containsExactly("large", "quarter", "medium", "quarter", "full");
        assertThat(result.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::height)
                .containsExactly("short", "tall", "tall", "standard", "standard");
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
        assertThat(result.layout().appLayout()).isEqualTo(appLayout);
        assertThat(result.layout().widgets()).hasSize(5);
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
        assertThat(result.layout().appLayout()).isEqualTo(validAppLayout());
        assertThat(result.layout().presentation()).isEqualTo("balanced");
        assertThat(result.layout().widgets())
                .allSatisfy(widget -> assertThat(widget.size()).isNotBlank());
        assertThat(result.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .containsExactly("command-rail", "activity", "focus", "schedule", "daily-brief");
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
                                validAppLayout(), "balanced", widgets),
                        0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void rejectsWorkspaceUpdatesWhenTenantDisablesPersonalCustomization() {
        HomeCompositionPolicyReader disabled = tenantId -> false;
        HomePreferenceService governedService = new HomePreferenceService(
                repository, objectMapper, auditService, disabled);

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

    private HomePreferenceDtos.HomeLayoutPayload workspaceLayout(
            List<HomePreferenceDtos.WidgetPreference> widgets,
            ObjectNode appLayout,
            String presentation) {
        return new HomePreferenceDtos.HomeLayoutPayload(appLayout, presentation, widgets);
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
                widget("daily-brief", true, "full"));
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
