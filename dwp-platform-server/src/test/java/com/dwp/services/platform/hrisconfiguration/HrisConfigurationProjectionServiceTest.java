package com.dwp.services.platform.hrisconfiguration;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.personalization.HomeTemplateDtos;
import com.dwp.services.platform.home.personalization.HomeTemplateService;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.navigation.NavigationDtos;
import com.dwp.services.platform.navigation.NavigationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HrisConfigurationProjectionServiceTest {

    @Mock private NavigationService navigation;
    @Mock private HomeTemplateService homeTemplates;
    private HrisConfigurationProjectionService service;

    @BeforeEach
    void setUp() {
        service = new HrisConfigurationProjectionService(navigation, homeTemplates);
    }

    @Test
    void rejectsMissingApplicationEntitlementBeforeReadingTenantSources() {
        assertThatThrownBy(() -> service.project(
                7L, 11L, "APP.CALENDAR:VIEW", "WORKSPACE_MEMBER", "en"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void returnsOnlyAuthorizedHrisMenusAndPublishedVisibleHrisWidgets() {
        when(navigation.runtimeTree(7L, "ko")).thenReturn(List.of(
                group("people", List.of(
                        node("hr.people", "/hr/people", "APP.HCM", "VIEW"),
                        node("hr.admin", "/hr/admin", "APP.HCM", "MANAGE"),
                        node("calendar", "/calendar", "APP.CALENDAR", "VIEW")))));
        when(homeTemplates.list(7L, "APP.HCM:VIEW", "CUSTOM_PEOPLE_LEAD"))
                .thenReturn(List.of(
                        template("PUBLISHED", "hris-team", true),
                        template("REVOKED", "hris-revoked", true),
                        template("PUBLISHED", "calendar-agenda", true),
                        template("PUBLISHED", "hris-hidden", false)));

        var result = service.project(7L, 11L, "APP.HCM:VIEW", "CUSTOM_PEOPLE_LEAD", "ko");

        assertThat(result.state())
                .isEqualTo(HrisConfigurationProjectionDtos.ProjectionState.COMPLETE);
        assertThat(result.menus()).singleElement().satisfies(group ->
                assertThat(group.children()).extracting(
                        HrisConfigurationProjectionDtos.MenuItem::navigationKey)
                        .containsExactly("hr.people"));
        assertThat(result.widgets()).extracting(
                        HrisConfigurationProjectionDtos.WidgetItem::widgetKey)
                .containsExactly("hris-team");
        verify(navigation).runtimeTree(7L, "ko");
        verify(homeTemplates).list(7L, "APP.HCM:VIEW", "CUSTOM_PEOPLE_LEAD");
    }

    @Test
    void preservesMenuProjectionWhenTheWidgetSourceIsUnavailable() {
        when(navigation.runtimeTree(7L, "en")).thenReturn(List.of(
                node("hr.self", "/hr/me", "APP.HCM", "VIEW")));
        when(homeTemplates.list(7L, "APP.HCM:VIEW", "WORKSPACE_MEMBER"))
                .thenThrow(new IllegalStateException("feature disabled"));

        var result = service.project(
                7L, 11L, "APP.HCM:VIEW", "WORKSPACE_MEMBER", "en");

        assertThat(result.state())
                .isEqualTo(HrisConfigurationProjectionDtos.ProjectionState.PARTIAL);
        assertThat(result.menus()).hasSize(1);
        assertThat(result.widgets()).isEmpty();
        assertThat(result.sources()).anySatisfy(source -> {
            assertThat(source.source()).isEqualTo("HOME_TEMPLATE");
            assertThat(source.reasonCode()).isEqualTo("HOME_TEMPLATE_SOURCE_UNAVAILABLE");
        });
    }

    @Test
    void keepsAuditorProjectionReadOnlyAndFailsClosedOnSodConflict() {
        when(navigation.runtimeTree(7L, "en")).thenReturn(List.of());
        String auditPermissions = "APP.HCM:VIEW,HCM.CONFIGURATION_WORKBENCH:AUDIT";
        when(homeTemplates.list(7L, auditPermissions, "CUSTOM_AUDITOR"))
                .thenReturn(List.of());

        var audit = service.project(
                7L, 11L, auditPermissions, "CUSTOM_AUDITOR", "en");

        assertThat(audit.readOnly()).isTrue();
        assertThat(audit.allowedActions()).containsExactly("VIEW_PROJECTION", "AUDIT_READ");
        assertThat(audit.commandAuthorizationReusable()).isFalse();
        assertThatThrownBy(() -> service.project(
                7L, 11L,
                "APP.HCM:VIEW,HCM.CONFIGURATION_WORKBENCH:UPDATE,"
                        + "HCM.CONFIGURATION_WORKBENCH:AUDIT",
                "CUSTOM_CONFIGURATION_OWNER", "en"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void missingPublishGrantNeverAppearsAsAnAllowedAction() {
        String permissions = "APP.HCM:VIEW,HCM.CONFIGURATION_WORKBENCH:UPDATE";
        when(navigation.runtimeTree(7L, "en")).thenReturn(List.of());
        when(homeTemplates.list(7L, permissions, "CUSTOM_CONFIGURATION_OWNER"))
                .thenReturn(List.of());

        var result = service.project(
                7L, 11L, permissions, "CUSTOM_CONFIGURATION_OWNER", "en");

        assertThat(result.allowedActions())
                .contains("UPDATE_CONFIGURATION")
                .doesNotContain("PUBLISH_CONFIGURATION");
    }

    @Test
    void staleWidgetTemplatesFailClosedAndReportTheirSourceState() {
        when(navigation.runtimeTree(7L, "en")).thenReturn(List.of(
                node("hr.self", "/hr/me", "APP.HCM", "VIEW")));
        when(homeTemplates.list(7L, "APP.HCM:VIEW", "WORKSPACE_MEMBER"))
                .thenReturn(List.of(template("PUBLISHED", "hris-stale", true, 4)));

        var result = service.project(
                7L, 11L, "APP.HCM:VIEW", "WORKSPACE_MEMBER", "en");

        assertThat(result.widgets()).isEmpty();
        assertThat(result.state())
                .isEqualTo(HrisConfigurationProjectionDtos.ProjectionState.PARTIAL);
        assertThat(result.sources()).anySatisfy(source -> {
            assertThat(source.source()).isEqualTo("HOME_TEMPLATE");
            assertThat(source.state())
                    .isEqualTo(HrisConfigurationProjectionDtos.SourceState.STALE);
        });
    }

    @Test
    void revokedWidgetTemplatesFailClosedInsteadOfBeingReplayed() {
        when(navigation.runtimeTree(7L, "en")).thenReturn(List.of(
                node("hr.self", "/hr/me", "APP.HCM", "VIEW")));
        when(homeTemplates.list(7L, "APP.HCM:VIEW", "WORKSPACE_MEMBER"))
                .thenReturn(List.of(template("REVOKED", "hris-former", true)));

        var result = service.project(
                7L, 11L, "APP.HCM:VIEW", "WORKSPACE_MEMBER", "en");

        assertThat(result.widgets()).isEmpty();
        assertThat(result.sources()).anySatisfy(source -> {
            assertThat(source.source()).isEqualTo("HOME_TEMPLATE");
            assertThat(source.state())
                    .isEqualTo(HrisConfigurationProjectionDtos.SourceState.REVOKED);
        });
    }

    private NavigationDtos.RuntimeNode group(
            String key,
            List<NavigationDtos.RuntimeNode> children) {
        return new NavigationDtos.RuntimeNode(
                key, "GROUP", key, null, null, null, null, null, null, children);
    }

    private NavigationDtos.RuntimeNode node(
            String key,
            String route,
            String resource,
            String permission) {
        return new NavigationDtos.RuntimeNode(
                key, "APP", key, null, key, route, null, resource, permission, List.of());
    }

    private HomeTemplateDtos.HomeTemplateResponse template(
            String lifecycle,
            String widgetKey,
            boolean visible) {
        return template(lifecycle, widgetKey, visible, 5);
    }

    private HomeTemplateDtos.HomeTemplateResponse template(
            String lifecycle,
            String widgetKey,
            boolean visible,
            int schemaVersion) {
        UUID id = UUID.nameUUIDFromBytes((lifecycle + widgetKey).getBytes());
        var layout = new HomePreferenceDtos.HomeLayoutPayload(
                null, "balanced", List.of(new HomePreferenceDtos.WidgetPreference(
                        widgetKey, visible, "medium", "standard")));
        return new HomeTemplateDtos.HomeTemplateResponse(
                id, "template-" + widgetKey, "Template " + widgetKey,
                new HomeTemplateDtos.TemplateAudience("ALL", List.of()),
                lifecycle, schemaVersion, layout, 3L, null, null, null);
    }
}
