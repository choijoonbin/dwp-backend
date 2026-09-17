package com.dwp.services.platform.workplace.workplacenavigation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkplaceNavigationServiceTest {
    private static final Instant FIXED = Instant.parse("2026-09-16T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);

    private final WorkplaceNavigationRepository repository = mock(WorkplaceNavigationRepository.class);
    private final WorkplaceNavigationService service = new WorkplaceNavigationService(
            repository, Clock.fixed(FIXED, ZoneOffset.UTC));
    private final UUID siteId = UUID.randomUUID();
    private final UUID graphId = UUID.randomUUID();
    private final UUID floorId = UUID.randomUUID();
    private final UUID originNode = UUID.randomUUID();
    private final UUID elevatorNode = UUID.randomUUID();
    private final UUID destinationNode = UUID.randomUUID();
    private final UUID originPoi = UUID.randomUUID();
    private final UUID destinationPoi = UUID.randomUUID();

    @BeforeEach
    void graph() {
        when(repository.publishedGraph(42, siteId)).thenReturn(Optional.of(graph(NOW.minusDays(1))));
        when(repository.pois(42, graphId)).thenReturn(List.of(
                poi(originPoi, originNode, PoiCategory.ENTRY),
                poi(destinationPoi, destinationNode, PoiCategory.ROOM)));
        when(repository.fallback(eq(42L), eq(siteId), any())).thenReturn(
                new LocationFallback(siteId, floorId, null, "Campus", "12F", "Room",
                        "/api/platform/v1/workplace/floors/map", List.of()));
        when(repository.nodes(42, graphId)).thenReturn(List.of(
                node(originNode, NodeKind.ENTRY, true, null),
                node(elevatorNode, NodeKind.ELEVATOR, true, null),
                node(destinationNode, NodeKind.POI, true, null)));
    }

    @Test
    void accessibleAvoidStairsUsesPublishedElevatorPath() {
        when(repository.edges(42, graphId)).thenReturn(List.of(
                edge(originNode, destinationNode, 10, TravelMode.STAIR, false, null),
                edge(originNode, elevatorNode, 20, TravelMode.WALK, true, null),
                edge(elevatorNode, destinationNode, 30, TravelMode.ELEVATOR, true, null)));

        RouteProjection route = service.route(42, siteId, originPoi, destinationPoi,
                true, true, Set.of("APP.WORKPLACE:VIEW"));

        assertThat(route.outcome()).isEqualTo(RouteOutcome.GUIDED);
        assertThat(route.totalTravelSeconds()).isEqualTo(50);
        assertThat(route.steps()).extracting(RouteStep::travelMode)
                .containsExactly(TravelMode.WALK, TravelMode.ELEVATOR);
        assertThat(route.graphRevisionId()).isEqualTo(graphId);
    }

    @Test
    void restrictedOnlyPathIsDeniedUntilExactPermissionIsPresent() {
        String permission = "APP.WORKPLACE.ZONE.SECURE:ACCESS";
        when(repository.edges(42, graphId)).thenReturn(List.of(
                edge(originNode, destinationNode, 10, TravelMode.WALK, true, permission)));

        RouteProjection denied = service.route(42, siteId, originPoi, destinationPoi,
                false, false, Set.of("APP.WORKPLACE:VIEW"));
        RouteProjection allowed = service.route(42, siteId, originPoi, destinationPoi,
                false, false, Set.of(permission));

        assertThat(denied.outcome()).isEqualTo(RouteOutcome.ACCESS_DENIED);
        assertThat(denied.steps()).isEmpty();
        assertThat(allowed.outcome()).isEqualTo(RouteOutcome.GUIDED);
    }

    @Test
    void staleOrMissingGraphReturnsExplicitFallbackAndNeverInventsSteps() {
        when(repository.publishedGraph(42, siteId))
                .thenReturn(Optional.of(graph(NOW.minusDays(31))))
                .thenReturn(Optional.empty());

        RouteProjection stale = service.route(42, siteId, originPoi, destinationPoi,
                false, false, Set.of());
        RouteProjection missing = service.route(42, siteId, originPoi, destinationPoi,
                false, false, Set.of());

        assertThat(stale.outcome()).isEqualTo(RouteOutcome.GRAPH_STALE);
        assertThat(stale.steps()).isEmpty();
        assertThat(missing.outcome()).isEqualTo(RouteOutcome.GRAPH_MISSING);
        assertThat(missing.steps()).isEmpty();
        verify(repository, never()).edges(42, graphId);
    }

    private GraphRow graph(OffsetDateTime publishedAt) {
        return new GraphRow(graphId, 42, siteId, 7, GraphState.PUBLISHED,
                "a".repeat(64), "Published graph", 3,
                publishedAt.minusHours(1), publishedAt, publishedAt);
    }

    private PoiView poi(UUID poiId, UUID nodeId, PoiCategory category) {
        return new PoiView(poiId, nodeId, siteId, floorId, null, category,
                category.name(), category.name(), null, null);
    }

    private NodeRow node(UUID nodeId, NodeKind kind, boolean accessible, UUID restrictedZoneId) {
        return new NodeRow(nodeId, floorId, kind, accessible, restrictedZoneId);
    }

    private EdgeRow edge(
            UUID from,
            UUID to,
            int seconds,
            TravelMode mode,
            boolean accessible,
            String permission) {
        return new EdgeRow(UUID.randomUUID(), from, to, seconds, mode,
                false, accessible, permission);
    }
}
