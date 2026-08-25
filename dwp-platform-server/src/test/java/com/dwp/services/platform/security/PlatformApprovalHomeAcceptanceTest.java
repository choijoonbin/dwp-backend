package com.dwp.services.platform.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.preference.HomePreference;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.home.preference.HomePreferenceRepository;
import com.dwp.services.platform.home.preference.HomePreferenceService;
import com.dwp.services.platform.home.personalization.HomePersonalizationScopeLock;
import com.dwp.services.platform.support.PilotAuthorizationFixtureAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformApprovalHomeAcceptanceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PlatformApprovalsPepRegistry registry =
            new PlatformApprovalsPepRegistry(objectMapper);
    private final HomePreferenceRepository repository = mock(HomePreferenceRepository.class);
    private final HomePreferenceService service = new HomePreferenceService(
            repository,
            objectMapper,
            mock(PlatformAuditService.class),
            tenantId -> true,
            mock(HomePersonalizationScopeLock.class));

    @AfterEach
    void clearContext() {
        PlatformApprovalsAuthorizationContext.clear();
    }

    @Test
    void psA018UpdatesOnlyTheActorsFixedApprovalHomeAtTheExpectedVersion() {
        PilotAuthorizationFixtureAdapter.PlatformPepFixture fixture =
                new PilotAuthorizationFixtureAdapter().project("PS-A018");
        assertThat(fixture.expectedOutcome()).isEqualTo("OWN_STATE_BOUND_ACTIONS_ONLY");
        assertThat(fixture.composition())
                .extracting(PilotAuthorizationFixtureAdapter.SourceRecord::reference)
                .contains("APPROVAL_HOME_PREF_1", "PAYLOAD_APPROVAL_HOME_PREF_UPDATE_1");

        PlatformApprovalsPepRegistry.Decision decision = registry.authorize(evidence(
                "PUT", "/v1/home-preferences/surfaces/approval-home",
                Set.of("APP.APPROVALS:VIEW"),
                "route.approvals.work.home-preference-update.action"));
        assertThat(decision.allowed()).isTrue();
        PlatformApprovalsAuthorizationContext.set(7L, 11L, decision.routeContractKeys());

        HomePreference stored = preference(8L);
        when(repository.findForUpdate(7L, 11L, HomePreferenceService.APPROVAL_HOME))
                .thenReturn(Optional.of(stored));
        when(repository.saveAndFlush(stored)).thenReturn(stored);

        HomePreferenceDtos.HomePreferenceResponse response = service.update(
                7L,
                11L,
                HomePreferenceService.APPROVAL_HOME,
                "ps-a018",
                new HomePreferenceDtos.UpdateHomePreferenceRequest(
                        layout("focused"),
                        8L));

        assertThat(response.surfaceKey()).isEqualTo("approval-home");
        assertThat(response.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .doesNotContain("admin-health");
        verify(repository).findForUpdate(7L, 11L, "approval-home");
        verify(repository).saveAndFlush(stored);

        assertThatThrownBy(() -> service.update(
                7L, 12L, "approval-home", "wrong-owner",
                new HomePreferenceDtos.UpdateHomePreferenceRequest(layout("balanced"), 8L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));
        assertThatThrownBy(() -> service.update(
                7L, 11L, "approval-home", "stale-version",
                new HomePreferenceDtos.UpdateHomePreferenceRequest(layout("balanced"), 7L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(repository, times(1)).saveAndFlush(any(HomePreference.class));
        verify(repository, never()).findForUpdate(7L, 12L, "approval-home");
    }

    @Test
    void fixedSurfaceAndServerResolvedRouteKeyRemainFailClosed() {
        assertThat(registry.authorize(evidence(
                "PUT", "/v1/home-preferences/surfaces/hcm-home",
                Set.of("APP.APPROVALS:VIEW"),
                "route.approvals.work.home-preference-update.action")).allowed()).isFalse();
        assertThat(registry.authorize(evidence(
                "PUT", "/v1/home-preferences/surfaces/approval-home",
                Set.of("APP.APPROVALS:VIEW"),
                "route.approvals.work.home-preference.data")).denialCode())
                .isEqualTo("EXACT_ROUTE_AUTHORITY_REQUIRED");
    }

    @Test
    void governedReadStripsLegacyAdministrativeWidgetFromWorkSurface() {
        PlatformApprovalsPepRegistry.Decision decision = registry.authorize(evidence(
                "GET", "/v1/home-preferences/surfaces/approval-home",
                Set.of("APP.APPROVALS:VIEW"),
                "route.approvals.work.home-preference.data"));
        PlatformApprovalsAuthorizationContext.set(7L, 11L, decision.routeContractKeys());
        HomePreference stored = preference(8L);
        when(repository.findByTenantIdAndUserIdAndSurfaceKey(7L, 11L, "approval-home"))
                .thenReturn(Optional.of(stored));

        HomePreferenceDtos.HomePreferenceResponse response = service.get(
                7L, 11L, "approval-home");

        assertThat(response.layout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .doesNotContain("admin-health");
    }

    private HomePreference preference(long version) {
        return HomePreference.builder()
                .homePreferenceId(31L)
                .tenantId(7L)
                .userId(11L)
                .surfaceKey("approval-home")
                .schemaVersion(HomePreferenceDtos.SCHEMA_VERSION)
                .layoutPayload(objectMapper.valueToTree(new HomePreferenceDtos.HomeLayoutPayload(
                        null,
                        "balanced",
                        List.of(
                                widget("decision-pulse", true, "full", "short"),
                                widget("focus-queue", true, "large", "tall"),
                                widget("admin-health", true, "full", "tall")))))
                .version(version)
                .build();
    }

    private HomePreferenceDtos.HomeLayoutPayload layout(String presentation) {
        return new HomePreferenceDtos.HomeLayoutPayload(
                null,
                presentation,
                List.of(
                        widget("decision-pulse", true, "full", "short"),
                        widget("focus-queue", true, "large", "tall")));
    }

    private HomePreferenceDtos.WidgetPreference widget(
            String key, boolean visible, String size, String height) {
        return new HomePreferenceDtos.WidgetPreference(key, visible, size, height);
    }

    private PlatformApprovalsPepRegistry.RequestEvidence evidence(
            String method, String path, Set<String> permissions, String clientRouteKey) {
        return new PlatformApprovalsPepRegistry.RequestEvidence(
                method, path, permissions, clientRouteKey);
    }
}
