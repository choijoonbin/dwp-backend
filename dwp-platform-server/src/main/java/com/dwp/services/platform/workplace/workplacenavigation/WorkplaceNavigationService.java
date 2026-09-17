package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;

@Service
public class WorkplaceNavigationService {
    static final Duration GRAPH_MAX_AGE = Duration.ofDays(30);

    private final WorkplaceNavigationRepository repository;
    private final Clock clock;

    @Autowired
    public WorkplaceNavigationService(WorkplaceNavigationRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WorkplaceNavigationService(WorkplaceNavigationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PoiView> pois(long tenantId, UUID siteId) {
        requireTenant(tenantId);
        return repository.publishedGraph(tenantId, siteId)
                .map(graph -> repository.pois(tenantId, graph.graphRevisionId()))
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public RouteProjection route(
            long tenantId,
            UUID siteId,
            UUID originPoiId,
            UUID destinationPoiId,
            boolean accessible,
            boolean avoidStairs,
            Set<String> permissions) {
        requireTenant(tenantId);
        OffsetDateTime now = now();
        GraphRow graph = repository.publishedGraph(tenantId, siteId).orElse(null);
        if (graph == null) {
            return fallback(RouteOutcome.GRAPH_MISSING, null, null, null,
                    repository.fallback(tenantId, siteId, null),
                    List.of("No published navigation graph is available."), now);
        }
        List<PoiView> pois = repository.pois(tenantId, graph.graphRevisionId());
        PoiView origin = poi(pois, originPoiId);
        PoiView destination = poi(pois, destinationPoiId);
        LocationFallback location = repository.fallback(tenantId, siteId, destination);
        if (origin == null || destination == null) {
            return fallback(RouteOutcome.NO_ROUTE, graph, origin, destination, location,
                    List.of("The selected origin or destination is not in the published graph."), now);
        }
        if (graph.publishedAt() == null
                || graph.publishedAt().isBefore(now.minus(GRAPH_MAX_AGE))) {
            return fallback(RouteOutcome.GRAPH_STALE, graph, origin, destination, location,
                    List.of("The published graph is stale; use the floor map or help desk."), now);
        }

        Map<UUID, NodeRow> nodes = new HashMap<>();
        for (NodeRow node : repository.nodes(tenantId, graph.graphRevisionId())) {
            nodes.put(node.nodeId(), node);
        }
        Set<String> normalizedPermissions = normalizePermissions(permissions);
        Path path = shortestPath(nodes, repository.edges(tenantId, graph.graphRevisionId()),
                origin.nodeId(), destination.nodeId(), accessible, avoidStairs,
                normalizedPermissions, true);
        if (path == null) {
            Path unrestricted = shortestPath(nodes,
                    repository.edges(tenantId, graph.graphRevisionId()), origin.nodeId(),
                    destination.nodeId(), accessible, avoidStairs, normalizedPermissions, false);
            RouteOutcome outcome = unrestricted == null
                    ? RouteOutcome.NO_ROUTE : RouteOutcome.ACCESS_DENIED;
            String message = outcome == RouteOutcome.ACCESS_DENIED
                    ? "The available route crosses a restricted zone you cannot access."
                    : "No route satisfies the accessibility and stair preferences.";
            return fallback(outcome, graph, origin, destination, location, List.of(message), now);
        }
        return new RouteProjection(RouteOutcome.GUIDED, graph.graphRevisionId(),
                graph.revisionNumber(), origin, destination, steps(path, nodes), path.cost(),
                location, List.of(), graph.publishedAt(), now);
    }

    @Transactional(readOnly = true)
    public List<GraphRevisionView> graphs(long tenantId, UUID siteId) {
        requireTenant(tenantId);
        return repository.graphs(tenantId, siteId).stream()
                .map(graph -> repository.view(tenantId, graph)).toList();
    }

    @Transactional
    public GraphRevisionView createGraph(
            long tenantId,
            long actorId,
            String idempotencyKey,
            String correlationId,
            CreateGraphRequest request) {
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        String key = requireKey(idempotencyKey);
        String fingerprint = fingerprint("CREATE", request.siteId(), request.revisionNumber(),
                request.contentHash(), request.changeSummary().trim(), request.nodes(),
                request.edges(), request.pois(), request.reason().trim());
        var replay = repository.graphCommand(tenantId, actorId, key);
        if (replay.isPresent()) {
            if (!replay.get().commandType().equals("CREATE")
                    || !replay.get().requestFingerprint().equals(fingerprint)) {
                throw conflict("The idempotency key was already used for another graph command.");
            }
            GraphRow existing = repository.graph(tenantId, replay.get().graphId())
                    .orElseThrow(() -> notFound("The prior graph command no longer resolves."));
            return repository.view(tenantId, existing);
        }
        validateGraph(tenantId, request);
        OffsetDateTime now = now();
        GraphRow graph = new GraphRow(UUID.randomUUID(), tenantId, request.siteId(),
                request.revisionNumber(), GraphState.DRAFT, request.contentHash(),
                request.changeSummary().trim(), 1, null, null, now);
        repository.insertGraph(graph, request, actorId, key, fingerprint, correlationId, now);
        return repository.view(tenantId, graph);
    }

    @Transactional
    public GraphRevisionView publishGraph(
            long tenantId,
            long actorId,
            UUID graphId,
            String idempotencyKey,
            String correlationId,
            PublishGraphRequest request) {
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        String key = requireKey(idempotencyKey);
        GraphRow graph = repository.graph(tenantId, graphId)
                .orElseThrow(() -> notFound("The navigation graph was not found."));
        String fingerprint = fingerprint("PUBLISH", graphId, request.expectedVersion(),
                request.reason().trim());
        var replay = repository.graphCommand(tenantId, actorId, key);
        if (replay.isPresent()) {
            if (!replay.get().commandType().equals("PUBLISH")
                    || !replay.get().graphId().equals(graphId)
                    || !replay.get().requestFingerprint().equals(fingerprint)) {
                throw conflict("The idempotency key was already used for another graph command.");
            }
            return repository.view(tenantId, repository.graph(tenantId, graphId)
                    .orElseThrow(() -> notFound("The published graph was not found.")));
        }
        if (!repository.publish(tenantId, actorId, graph, request.expectedVersion(),
                key, fingerprint, correlationId, now())) {
            throw conflict("The graph state or version changed; refresh before publishing.");
        }
        return repository.view(tenantId, repository.graph(tenantId, graphId)
                .orElseThrow(() -> notFound("The published graph was not found.")));
    }

    @Transactional
    public GraphRevisionView submitForReview(
            long tenantId,
            long actorId,
            UUID graphId,
            String idempotencyKey,
            String correlationId,
            GraphTransitionRequest request) {
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        String key = requireKey(idempotencyKey);
        GraphRow graph = repository.graph(tenantId, graphId)
                .orElseThrow(() -> notFound("The navigation graph was not found."));
        String fingerprint = fingerprint("REVIEW", graphId, request.expectedVersion(),
                request.reason().trim());
        GraphRevisionView replay = transitionReplay(
                tenantId, actorId, key, graphId, "REVIEW", fingerprint);
        if (replay != null) return replay;
        if (!repository.submitForReview(tenantId, actorId, graph.graphRevisionId(),
                request.expectedVersion(), key, fingerprint, correlationId, now())) {
            throw conflict("The graph state or version changed; refresh before review submission.");
        }
        return repository.view(tenantId, repository.graph(tenantId, graphId)
                .orElseThrow(() -> notFound("The reviewed graph was not found.")));
    }

    @Transactional
    public GraphRevisionView archiveGraph(
            long tenantId,
            long actorId,
            UUID graphId,
            String idempotencyKey,
            String correlationId,
            GraphTransitionRequest request) {
        requireActor(tenantId, actorId);
        requireConfirmation(request.explicitConfirmation());
        String key = requireKey(idempotencyKey);
        GraphRow graph = repository.graph(tenantId, graphId)
                .orElseThrow(() -> notFound("The navigation graph was not found."));
        String fingerprint = fingerprint("ARCHIVE", graphId, request.expectedVersion(),
                request.reason().trim());
        GraphRevisionView replay = transitionReplay(
                tenantId, actorId, key, graphId, "ARCHIVE", fingerprint);
        if (replay != null) return replay;
        if (!repository.archive(tenantId, actorId, graph.graphRevisionId(),
                request.expectedVersion(), key, fingerprint, correlationId, now())) {
            throw conflict("The graph state or version changed; refresh before archiving.");
        }
        return repository.view(tenantId, repository.graph(tenantId, graphId)
                .orElseThrow(() -> notFound("The archived graph was not found.")));
    }

    private GraphRevisionView transitionReplay(
            long tenantId,
            long actorId,
            String idempotencyKey,
            UUID graphId,
            String commandType,
            String fingerprint) {
        var replay = repository.graphCommand(tenantId, actorId, idempotencyKey);
        if (replay.isEmpty()) return null;
        if (!replay.get().commandType().equals(commandType)
                || !replay.get().graphId().equals(graphId)
                || !replay.get().requestFingerprint().equals(fingerprint)) {
            throw conflict("The idempotency key was already used for another graph command.");
        }
        return repository.view(tenantId, repository.graph(tenantId, graphId)
                .orElseThrow(() -> notFound("The prior graph command no longer resolves.")));
    }

    private void validateGraph(long tenantId, CreateGraphRequest request) {
        if (!repository.siteExists(tenantId, request.siteId())) {
            throw notFound("The navigation site was not found.");
        }
        Map<UUID, NavigationNodeInput> nodes = new HashMap<>();
        for (NavigationNodeInput node : request.nodes()) {
            if (nodes.put(node.nodeId(), node) != null) {
                throw invalid("Navigation node identifiers must be unique.");
            }
            if (!repository.floorBelongsToSite(tenantId, request.siteId(), node.floorId())) {
                throw invalid("Every navigation node floor must belong to the graph site.");
            }
            if (node.restrictedZoneId() != null
                    && !repository.zoneBelongsToFloor(
                    tenantId, node.floorId(), node.restrictedZoneId())) {
                throw invalid("A restricted navigation node zone must belong to its floor.");
            }
            if (node.positionX().signum() < 0 || node.positionX().compareTo(new java.math.BigDecimal("100")) > 0
                    || node.positionY().signum() < 0
                    || node.positionY().compareTo(new java.math.BigDecimal("100")) > 0) {
                throw invalid("Navigation node positions must be percentages from 0 through 100.");
            }
        }
        Set<UUID> edges = new HashSet<>();
        for (NavigationEdgeInput edge : request.edges()) {
            if (!edges.add(edge.edgeId()) || !nodes.containsKey(edge.fromNodeId())
                    || !nodes.containsKey(edge.toNodeId())
                    || edge.fromNodeId().equals(edge.toNodeId())) {
                throw invalid("Navigation edges must be unique and connect two graph nodes.");
            }
        }
        Set<UUID> poiIds = new HashSet<>();
        for (PoiInput poi : request.pois()) {
            NavigationNodeInput node = nodes.get(poi.nodeId());
            if (!poiIds.add(poi.poiId()) || node == null || !node.floorId().equals(poi.floorId())) {
                throw invalid("POIs must be unique and match their graph node floor.");
            }
            if (poi.resourceId() != null
                    && !repository.resourceBelongsToFloor(tenantId, poi.floorId(), poi.resourceId())) {
                throw invalid("A POI resource must belong to the selected floor and tenant.");
            }
        }
    }

    private Path shortestPath(
            Map<UUID, NodeRow> nodes,
            List<EdgeRow> edges,
            UUID origin,
            UUID destination,
            boolean accessible,
            boolean avoidStairs,
            Set<String> permissions,
            boolean enforceAuthorization) {
        if (!nodes.containsKey(origin) || !nodes.containsKey(destination)) return null;
        if (enforceAuthorization && !nodeAuthorized(nodes.get(origin), permissions)) return null;
        Map<UUID, List<Arc>> graph = new HashMap<>();
        for (EdgeRow edge : edges) {
            addArc(graph, edge.fromNodeId(), edge.toNodeId(), edge);
            if (edge.bidirectional()) addArc(graph, edge.toNodeId(), edge.fromNodeId(), edge);
        }
        Map<UUID, Integer> cost = new HashMap<>();
        Map<UUID, Arc> previous = new HashMap<>();
        PriorityQueue<Hop> queue = new PriorityQueue<>(Comparator.comparingInt(Hop::cost));
        cost.put(origin, 0);
        queue.add(new Hop(origin, 0));
        while (!queue.isEmpty()) {
            Hop hop = queue.remove();
            if (hop.cost() != cost.getOrDefault(hop.nodeId(), Integer.MAX_VALUE)) continue;
            if (hop.nodeId().equals(destination)) break;
            for (Arc arc : graph.getOrDefault(hop.nodeId(), List.of())) {
                NodeRow target = nodes.get(arc.to());
                if (target == null || (accessible && (!arc.edge().accessible() || !target.accessible()))) continue;
                if (avoidStairs && arc.edge().travelMode() == TravelMode.STAIR) continue;
                if (enforceAuthorization && !authorized(target, arc.edge(), permissions)) continue;
                int candidate = hop.cost() + arc.edge().travelSeconds();
                if (candidate < cost.getOrDefault(arc.to(), Integer.MAX_VALUE)) {
                    cost.put(arc.to(), candidate);
                    previous.put(arc.to(), arc);
                    queue.add(new Hop(arc.to(), candidate));
                }
            }
        }
        if (!cost.containsKey(destination)) return null;
        ArrayDeque<Arc> result = new ArrayDeque<>();
        UUID cursor = destination;
        while (!cursor.equals(origin)) {
            Arc arc = previous.get(cursor);
            if (arc == null) return null;
            result.addFirst(arc);
            cursor = arc.from();
        }
        return new Path(List.copyOf(result), cost.get(destination));
    }

    private boolean authorized(NodeRow node, EdgeRow edge, Set<String> permissions) {
        if (edge.requiredPermission() != null
                && !permissions.contains(edge.requiredPermission().toUpperCase(Locale.ROOT))) return false;
        return nodeAuthorized(node, permissions);
    }

    private boolean nodeAuthorized(NodeRow node, Set<String> permissions) {
        if (node.restrictedZoneId() == null) return true;
        return permissions.contains(("APP.WORKPLACE.ZONE." + node.restrictedZoneId() + ":ACCESS")
                .toUpperCase(Locale.ROOT));
    }

    private List<RouteStep> steps(Path path, Map<UUID, NodeRow> nodes) {
        List<RouteStep> result = new ArrayList<>();
        for (Arc arc : path.arcs()) {
            TravelMode mode = arc.edge().travelMode();
            String ko = switch (mode) {
                case ELEVATOR -> "엘리베이터를 이용하세요.";
                case STAIR -> "계단을 이용하세요.";
                case RAMP -> "경사로를 이용하세요.";
                case WALK -> "표시된 통로를 따라 이동하세요.";
            };
            String en = switch (mode) {
                case ELEVATOR -> "Take the elevator.";
                case STAIR -> "Take the stairs.";
                case RAMP -> "Use the ramp.";
                case WALK -> "Follow the published corridor.";
            };
            result.add(new RouteStep(arc.from(), arc.to(), nodes.get(arc.to()).floorId(),
                    mode, arc.edge().travelSeconds(), ko, en));
        }
        return List.copyOf(result);
    }

    private RouteProjection fallback(
            RouteOutcome outcome,
            GraphRow graph,
            PoiView origin,
            PoiView destination,
            LocationFallback location,
            List<String> limitations,
            OffsetDateTime now) {
        return new RouteProjection(outcome, graph == null ? null : graph.graphRevisionId(),
                graph == null ? 0 : graph.revisionNumber(), origin, destination, List.of(), 0,
                location, limitations, graph == null ? null : graph.publishedAt(), now);
    }

    private static PoiView poi(List<PoiView> pois, UUID id) {
        if (id == null) return null;
        return pois.stream().filter(value -> id.equals(value.poiId())).findFirst().orElse(null);
    }

    private static void addArc(Map<UUID, List<Arc>> graph, UUID from, UUID to, EdgeRow edge) {
        graph.computeIfAbsent(from, ignored -> new ArrayList<>()).add(new Arc(from, to, edge));
    }

    private static Set<String> normalizePermissions(Set<String> values) {
        if (values == null) return Set.of();
        Set<String> result = new HashSet<>();
        values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).forEach(result::add);
        return Set.copyOf(result);
    }

    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw invalid("A bounded Idempotency-Key is required.");
        }
        return value.trim();
    }

    private static void requireActor(long tenantId, long actorId) {
        requireTenant(tenantId);
        if (actorId <= 0) throw new BaseException(ErrorCode.UNAUTHORIZED,
                "A positive actor identifier is required.");
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw invalid("A positive tenant identifier is required.");
    }

    private static void requireConfirmation(boolean confirmed) {
        if (!confirmed) throw invalid("Explicit confirmation is required.");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    private record Hop(UUID nodeId, int cost) { }
    private record Arc(UUID from, UUID to, EdgeRow edge) { }
    private record Path(List<Arc> arcs, int cost) { }
}
