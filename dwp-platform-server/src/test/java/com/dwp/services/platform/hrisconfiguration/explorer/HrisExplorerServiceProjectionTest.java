package com.dwp.services.platform.hrisconfiguration.explorer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerRepository.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrisExplorerServiceProjectionTest {

    private HrisExplorerProviderCoordinator coordinator;

    @AfterEach
    void closeCoordinator() {
        if (coordinator != null) coordinator.close();
    }

    @Test
    void emitsOnlyExactCurrentlyAuthorizedCatalogSubsetAndRefiltersStoredState() {
        String teamTime = "hris.explorer.team-time";
        String governance = HrisExplorerV43RouteAdapter.OPERATIONAL_DWP_CONTROL_ROUTE_KEY;
        String denied = "hris.explorer.my-hr-home";
        Map<String, ExplorerAuthorization> decisions = new LinkedHashMap<>();
        decisions.put(teamTime, HrisExplorerV43RouteAdapter.authorization(teamTime));
        decisions.put(governance, HrisExplorerV43RouteAdapter.authorization(governance));
        decisions.put(denied, new ExplorerAuthorization(
                "route.hcm.personal.home.page", CapabilityMode.SINGLE,
                List.of("hcm.personal.profile.view")));
        HrisExplorerAuthoritySource.AuthoritySnapshot authority = snapshot(decisions);
        HrisExplorerRepository repository = mock(HrisExplorerRepository.class);
        when(repository.readAndPurge(
                42, SUBJECT, Set.of(teamTime, governance), NOW))
                .thenReturn(new ExplorerState(
                        5,
                        List.of(new FavoriteRow(denied, 1),
                                new FavoriteRow(teamTime, 2)),
                        List.of(new RecentRow(
                                        governance, NOW.minusMinutes(2), NOW.plusDays(30)),
                                new RecentRow(
                                        denied, NOW.minusMinutes(3), NOW.plusDays(30)))));
        coordinator = new HrisExplorerProviderCoordinator(
                List.of(), new HrisExplorerRuntimeControls(
                Duration.ofMillis(100), Duration.ofMinutes(5), 5));
        HrisExplorerService service = new HrisExplorerService(
                repository, coordinator,
                Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"), ZoneOffset.UTC));

        HrisExplorerService.ExplorerResult result = service.explorer(
                authority, NOW, Freshness.ALLOW_STALE, null, null, 100);

        assertThat(result.response().routes())
                .extracting(ExplorerRoute::routeKey)
                .containsExactly(governance, teamTime);
        assertThat(result.response().favorites())
                .extracting(ExplorerFavorite::routeKey).containsExactly(teamTime);
        assertThat(result.response().favorites())
                .extracting(ExplorerFavorite::position).containsExactly(1);
        assertThat(result.response().recents())
                .extracting(ExplorerRecent::routeKey).containsExactly(governance);
        assertThat(result.response().projectionStatus().state())
                .isEqualTo(ProjectionState.PARTIAL);
        verify(repository).readAndPurge(
                42, SUBJECT, Set.of(teamTime, governance), NOW);
    }
}
