package com.dwp.services.platform.home.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
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
        service = new HomePreferenceService(repository, objectMapper, auditService);
    }

    @Test
    void returnsGovernedWorkspaceDefaultsWithoutCreatingData() {
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME))
                .thenReturn(Optional.empty());

        HomePreferenceDtos.HomePreferenceResponse result = service.get(
                7L, 11L, HomePreferenceService.WORKSPACE_HOME);

        assertThat(result.customized()).isFalse();
        assertThat(result.schemaVersion()).isEqualTo(2);
        assertThat(result.surfaceKey()).isEqualTo(HomePreferenceService.WORKSPACE_HOME);
        assertThat(result.layout().presentation()).isEqualTo("balanced");
        assertThat(result.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .containsExactly("announcements", "daily-brief", "focus", "schedule", "activity");
        assertThat(result.layout().widgets().getFirst().size()).isEqualTo("full");
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
    void rejectsHiddenGovernedAnnouncements() {
        HomePreferenceDtos.HomeLayoutPayload layout = workspaceLayout(
                widgets(false),
                null,
                "balanced");

        assertThatThrownBy(() -> service.update(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                null,
                new HomePreferenceDtos.UpdateHomePreferenceRequest(layout, 0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
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
                        workspaceLayout(widgets(true), appLayout, "expressive"),
                        0L));

        assertThat(result.customized()).isTrue();
        assertThat(result.layout().presentation()).isEqualTo("expressive");
        assertThat(result.layout().appLayout().path("hiddenAppIds").get(0).asText())
                .isEqualTo("dwp-ask");
        assertThat(result.layout().widgets()).hasSize(5);
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
        widgets(true).forEach(widget -> legacyWidgets.addObject()
                .put("widgetKey", widget.widgetKey())
                .put("visible", widget.visible()));
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

        assertThat(result.schemaVersion()).isEqualTo(2);
        assertThat(result.layout().presentation()).isEqualTo("balanced");
        assertThat(result.layout().widgets())
                .allSatisfy(widget -> assertThat(widget.size()).isNotBlank());
    }

    @Test
    void rejectsUnknownSurfaceAndDisallowedWidgetSize() {
        assertThatThrownBy(() -> service.get(7L, 11L, "unregistered-home"))
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
    void rejectsHiddenAppsThatRemainInTheVisibleLayout() {
        ObjectNode appLayout = validAppLayout();
        appLayout.withObject("groups").withArray("work").add("dwp-ask");

        assertThatThrownBy(() -> service.update(
                7L,
                11L,
                HomePreferenceService.WORKSPACE_HOME,
                null,
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        workspaceLayout(widgets(true), appLayout, "balanced"),
                        0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
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

    private List<HomePreferenceDtos.WidgetPreference> widgets(boolean announcementsVisible) {
        return List.of(
                widget("announcements", announcementsVisible, "full"),
                widget("daily-brief", true, "full"),
                widget("focus", true, "medium"),
                widget("schedule", true, "compact"),
                widget("activity", true, "compact"));
    }

    private HomePreferenceDtos.WidgetPreference widget(
            String widgetKey,
            boolean visible,
            String size) {
        return new HomePreferenceDtos.WidgetPreference(widgetKey, visible, size);
    }
}
