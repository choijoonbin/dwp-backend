package com.dwp.services.platform.catalog;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CatalogService {

    private static final int DEFAULT_GRAPH_NODE_LIMIT = 8;
    private static final int DEFAULT_GRAPH_ANCHOR_LIMIT = 4;
    private static final int FOCUSED_GRAPH_NODE_LIMIT = 160;
    private static final Set<String> DEPENDENCY_DIRECTION_SOURCE_TO_TARGET = Set.of(
            "DEPENDS_ON", "CONSUMES", "REQUIRES_PERMISSION", "SYNCHRONIZES");

    private final CatalogRepository repository;
    private final PlatformAuditService auditService;

    public CatalogService(CatalogRepository repository, PlatformAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public CatalogDtos.Overview overview(
            Long tenantId, String query, String kind, String lifecycle) {
        Snapshot snapshot = snapshot(tenantId);
        String normalizedQuery = lower(query);
        String normalizedKind = upper(kind);
        String normalizedLifecycle = upper(lifecycle);
        List<CatalogDtos.Entity> visible = snapshot.entities().stream()
                .filter(entity -> normalizedQuery == null
                        || lower(entity.ref()).contains(normalizedQuery)
                        || lower(entity.name()).contains(normalizedQuery)
                        || lower(entity.ownerRef()).contains(normalizedQuery))
                .filter(entity -> normalizedKind == null || "ALL".equals(normalizedKind)
                        || normalizedKind.equals(entity.kind()))
                .filter(entity -> normalizedLifecycle == null || "ALL".equals(normalizedLifecycle)
                        || normalizedLifecycle.equals(entity.lifecycleState()))
                .sorted(entityComparator())
                .toList();

        Map<String, Long> byKind = snapshot.entities().stream().collect(Collectors.groupingBy(
                CatalogDtos.Entity::kind, java.util.TreeMap::new, Collectors.counting()));
        Map<String, Long> byLifecycle = snapshot.entities().stream().collect(Collectors.groupingBy(
                CatalogDtos.Entity::lifecycleState, java.util.TreeMap::new, Collectors.counting()));
        Set<String> connected = new HashSet<>();
        snapshot.relations().forEach(relation -> {
            connected.add(relation.sourceRef());
            connected.add(relation.targetRef());
        });
        long orphanCount = snapshot.entities().stream()
                .filter(this::isGovernedAsset)
                .filter(entity -> !connected.contains(entity.ref()))
                .count();
        return new CatalogDtos.Overview(
                snapshot.entities().size(), snapshot.relations().size(),
                snapshot.relations().stream().filter(relation -> relation.relationId() != null).count(),
                orphanCount,
                snapshot.relations().stream()
                        .filter(relation -> "CRITICAL".equals(relation.criticality())).count(),
                Map.copyOf(byKind), Map.copyOf(byLifecycle), visible, OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public CatalogDtos.Graph graph(Long tenantId, String rawFocusRef, Integer requestedDepth) {
        Snapshot snapshot = snapshot(tenantId);
        String focusRef = normalizeOptionalRef(rawFocusRef);
        int depth = requestedDepth == null ? 2 : Math.max(1, Math.min(4, requestedDepth));
        Set<String> selectedRefs;
        boolean truncated;
        if (focusRef == null) {
            selectedRefs = overviewGraphRefs(snapshot);
            truncated = snapshot.entities().size() > selectedRefs.size();
        } else {
            requireEntity(snapshot, focusRef);
            selectedRefs = neighborhood(snapshot, focusRef, depth);
            truncated = selectedRefs.size() > FOCUSED_GRAPH_NODE_LIMIT;
        }
        if (selectedRefs.size() > FOCUSED_GRAPH_NODE_LIMIT) {
            selectedRefs = selectedRefs.stream().limit(FOCUSED_GRAPH_NODE_LIMIT)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        Set<String> finalRefs = selectedRefs;
        List<CatalogDtos.Relation> relations = snapshot.relations().stream()
                .filter(relation -> finalRefs.contains(relation.sourceRef())
                        && finalRefs.contains(relation.targetRef()))
                .toList();
        Map<String, Long> incoming = snapshot.relations().stream().collect(Collectors.groupingBy(
                CatalogDtos.Relation::targetRef, Collectors.counting()));
        Map<String, Long> outgoing = snapshot.relations().stream().collect(Collectors.groupingBy(
                CatalogDtos.Relation::sourceRef, Collectors.counting()));
        List<CatalogDtos.GraphNode> nodes = snapshot.entities().stream()
                .filter(entity -> finalRefs.contains(entity.ref()))
                .sorted(entityComparator())
                .map(entity -> new CatalogDtos.GraphNode(
                        entity,
                        incoming.getOrDefault(entity.ref(), 0L),
                        outgoing.getOrDefault(entity.ref(), 0L),
                        incoming.getOrDefault(entity.ref(), 0L) == 0
                                && outgoing.getOrDefault(entity.ref(), 0L) == 0))
                .toList();
        return new CatalogDtos.Graph(focusRef, nodes, relations, truncated, OffsetDateTime.now());
    }

    private Set<String> overviewGraphRefs(Snapshot snapshot) {
        if (snapshot.relations().isEmpty()) {
            return snapshot.entities().stream()
                    .sorted(entityComparator())
                    .limit(DEFAULT_GRAPH_NODE_LIMIT)
                    .map(CatalogDtos.Entity::ref)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Map<String, Integer> degree = new HashMap<>();
        snapshot.relations().forEach(relation -> {
            degree.merge(relation.sourceRef(), 1, Integer::sum);
            degree.merge(relation.targetRef(), 1, Integer::sum);
        });
        Comparator<CatalogDtos.Relation> relationComparator = Comparator
                .comparingInt((CatalogDtos.Relation relation) ->
                        degree.getOrDefault(relation.sourceRef(), 0)
                                + degree.getOrDefault(relation.targetRef(), 0))
                .reversed()
                .thenComparing(Comparator.comparingInt(
                        (CatalogDtos.Relation relation) -> weight(relation.criticality()))
                        .reversed())
                .thenComparing(CatalogDtos.Relation::relationType)
                .thenComparing(CatalogDtos.Relation::sourceRef)
                .thenComparing(CatalogDtos.Relation::targetRef);
        List<CatalogDtos.Relation> rankedRelations = snapshot.relations().stream()
                .sorted(relationComparator)
                .toList();
        Map<String, List<CatalogDtos.Relation>> incident = new HashMap<>();
        rankedRelations.forEach(relation -> {
            incident.computeIfAbsent(relation.sourceRef(), ignored -> new ArrayList<>()).add(relation);
            incident.computeIfAbsent(relation.targetRef(), ignored -> new ArrayList<>()).add(relation);
        });

        Set<String> selected = snapshot.entities().stream()
                .filter(entity -> degree.containsKey(entity.ref()))
                .sorted(Comparator
                        .comparingInt((CatalogDtos.Entity entity) ->
                                degree.getOrDefault(entity.ref(), 0))
                        .reversed()
                        .thenComparing(entityComparator()))
                .limit(DEFAULT_GRAPH_ANCHOR_LIMIT)
                .map(CatalogDtos.Entity::ref)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> anchors = List.copyOf(selected);
        for (String anchor : anchors) {
            for (CatalogDtos.Relation relation : incident.getOrDefault(anchor, List.of())) {
                String neighbor = anchor.equals(relation.sourceRef())
                        ? relation.targetRef() : relation.sourceRef();
                if (selected.add(neighbor) || selected.size() == DEFAULT_GRAPH_NODE_LIMIT) break;
            }
            if (selected.size() == DEFAULT_GRAPH_NODE_LIMIT) return selected;
        }
        for (CatalogDtos.Relation relation : rankedRelations) {
            boolean sourceSelected = selected.contains(relation.sourceRef());
            boolean targetSelected = selected.contains(relation.targetRef());
            if (sourceSelected == targetSelected) continue;
            selected.add(sourceSelected ? relation.targetRef() : relation.sourceRef());
            if (selected.size() == DEFAULT_GRAPH_NODE_LIMIT) return selected;
        }
        for (CatalogDtos.Relation relation : rankedRelations) {
            int newNodeCount = (selected.contains(relation.sourceRef()) ? 0 : 1)
                    + (selected.contains(relation.targetRef()) ? 0 : 1);
            if (selected.size() + newNodeCount > DEFAULT_GRAPH_NODE_LIMIT) continue;
            selected.add(relation.sourceRef());
            selected.add(relation.targetRef());
            if (selected.size() == DEFAULT_GRAPH_NODE_LIMIT) break;
        }
        return selected;
    }

    @Transactional(readOnly = true)
    public CatalogDtos.ImpactAnalysis impact(Long tenantId, String rawRef, String rawOperation) {
        Snapshot snapshot = snapshot(tenantId);
        String ref = normalizeRef(rawRef);
        CatalogDtos.Entity target = requireEntity(snapshot, ref);
        String operation = upper(rawOperation);
        if (operation == null) operation = "CHANGE";
        if (!Set.of("CHANGE", "RETIRE", "OUTAGE").contains(operation)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported catalog impact operation.");
        }

        Map<String, List<DependencyEdge>> dependents = dependencyEdges(snapshot.relations());
        Map<String, ImpactAccumulator> impacted = new LinkedHashMap<>();
        Queue<Traversal> queue = new ArrayDeque<>();
        queue.add(new Traversal(ref, 0));
        Set<String> visited = new HashSet<>(Set.of(ref));
        while (!queue.isEmpty()) {
            Traversal current = queue.remove();
            for (DependencyEdge edge : dependents.getOrDefault(current.ref(), List.of())) {
                int distance = current.distance() + 1;
                impacted.computeIfAbsent(edge.dependentRef(), ignored -> new ImpactAccumulator(distance))
                        .merge(distance, edge.relation());
                if (visited.add(edge.dependentRef()) && distance < 8) {
                    queue.add(new Traversal(edge.dependentRef(), distance));
                }
            }
        }
        List<CatalogDtos.ImpactItem> items = impacted.entrySet().stream()
                .filter(entry -> snapshot.byRef().containsKey(entry.getKey()))
                .map(entry -> entry.getValue().item(snapshot.byRef().get(entry.getKey())))
                .sorted(Comparator.comparingInt(CatalogDtos.ImpactItem::distance)
                        .thenComparing(item -> item.entity().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        long direct = items.stream().filter(item -> item.distance() == 1).count();
        int riskScore = items.stream().mapToInt(item ->
                weight(item.highestCriticality()) * Math.max(1, 5 - item.distance())).sum();
        boolean hasCriticalDirect = items.stream().anyMatch(item ->
                item.distance() == 1 && "CRITICAL".equals(item.highestCriticality()));
        boolean blocked = hasCriticalDirect || ("RETIRE".equals(operation) && direct > 0);
        List<String> findings = new ArrayList<>();
        if (direct == 0) findings.add("NO_DIRECT_DEPENDENTS");
        if (direct > 0) findings.add("DIRECT_DEPENDENTS_REQUIRE_REVIEW");
        if (items.stream().anyMatch(item -> item.distance() > 1)) findings.add("TRANSITIVE_IMPACT_DETECTED");
        if (hasCriticalDirect) findings.add("CRITICAL_CONSUMER_BLOCKS_CHANGE");
        if (target.ownerRef() == null || target.ownerRef().isBlank()) findings.add("OWNER_MISSING");
        return new CatalogDtos.ImpactAnalysis(
                target, operation, riskScore, blocked, direct,
                items.stream().filter(item -> item.distance() > 1).count(),
                items, List.copyOf(findings), OffsetDateTime.now());
    }

    @Transactional
    public CatalogDtos.Relation declare(
            Long tenantId,
            Long actorId,
            String correlationId,
            CatalogDtos.DeclareRelationRequest request) {
        Snapshot snapshot = snapshot(tenantId);
        String sourceRef = normalizeRef(request.sourceRef());
        String targetRef = normalizeRef(request.targetRef());
        if (sourceRef.equals(targetRef)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A catalog relation cannot reference itself.");
        }
        requireEntity(snapshot, sourceRef);
        requireEntity(snapshot, targetRef);
        CatalogDtos.Relation relation = repository.saveRelation(
                tenantId, actorId, request, sourceRef, targetRef);
        auditService.success(
                tenantId, actorId, "catalog.relation.declared", "CATALOG_RELATION",
                relation.relationId().toString(), correlationId, null, relationSnapshot(relation));
        return relation;
    }

    @Transactional
    public CatalogDtos.Relation retire(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID relationId,
            long version) {
        CatalogDtos.Relation relation = repository.retireRelation(
                tenantId, actorId, relationId, version);
        auditService.success(
                tenantId, actorId, "catalog.relation.retired", "CATALOG_RELATION",
                relationId.toString(), correlationId, null, relationSnapshot(relation));
        return relation;
    }

    private Snapshot snapshot(Long tenantId) {
        List<CatalogDtos.Entity> entities = repository.inventory(tenantId);
        Map<String, CatalogDtos.Entity> byRef = entities.stream().collect(Collectors.toMap(
                CatalogDtos.Entity::ref, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<CatalogDtos.Relation> relations = repository.relations(tenantId).stream()
                .filter(relation -> byRef.containsKey(relation.sourceRef())
                        && byRef.containsKey(relation.targetRef()))
                .toList();
        return new Snapshot(entities, byRef, relations);
    }

    private Set<String> neighborhood(Snapshot snapshot, String focusRef, int maximumDepth) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        snapshot.relations().forEach(relation -> {
            adjacency.computeIfAbsent(relation.sourceRef(), ignored -> new LinkedHashSet<>())
                    .add(relation.targetRef());
            adjacency.computeIfAbsent(relation.targetRef(), ignored -> new LinkedHashSet<>())
                    .add(relation.sourceRef());
        });
        Set<String> result = new LinkedHashSet<>(Set.of(focusRef));
        Queue<Traversal> queue = new ArrayDeque<>();
        queue.add(new Traversal(focusRef, 0));
        while (!queue.isEmpty() && result.size() <= FOCUSED_GRAPH_NODE_LIMIT) {
            Traversal current = queue.remove();
            if (current.distance() >= maximumDepth) continue;
            for (String next : adjacency.getOrDefault(current.ref(), Set.of())) {
                if (result.add(next)) queue.add(new Traversal(next, current.distance() + 1));
            }
        }
        return result;
    }

    private Map<String, List<DependencyEdge>> dependencyEdges(List<CatalogDtos.Relation> relations) {
        Map<String, List<DependencyEdge>> result = new HashMap<>();
        relations.forEach(relation -> {
            boolean sourceDependsOnTarget = DEPENDENCY_DIRECTION_SOURCE_TO_TARGET
                    .contains(relation.relationType());
            String dependency = sourceDependsOnTarget ? relation.targetRef() : relation.sourceRef();
            String dependent = sourceDependsOnTarget ? relation.sourceRef() : relation.targetRef();
            result.computeIfAbsent(dependency, ignored -> new ArrayList<>())
                    .add(new DependencyEdge(dependent, relation));
            if ("SYNCHRONIZES".equals(relation.relationType())) {
                result.computeIfAbsent(dependent, ignored -> new ArrayList<>())
                        .add(new DependencyEdge(dependency, relation));
            }
        });
        return result;
    }

    private CatalogDtos.Entity requireEntity(Snapshot snapshot, String ref) {
        CatalogDtos.Entity entity = snapshot.byRef().get(ref);
        if (entity == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Catalog entity was not found.");
        }
        return entity;
    }

    private boolean isGovernedAsset(CatalogDtos.Entity entity) {
        return !Set.of("SERVICE", "PERMISSION", "NAVIGATION").contains(entity.kind());
    }

    private Comparator<CatalogDtos.Entity> entityComparator() {
        return Comparator.comparing(CatalogDtos.Entity::kind)
                .thenComparing(CatalogDtos.Entity::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CatalogDtos.Entity::ref);
    }

    private Map<String, Object> relationSnapshot(CatalogDtos.Relation relation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("relationId", relation.relationId());
        result.put("sourceRef", relation.sourceRef());
        result.put("targetRef", relation.targetRef());
        result.put("relationType", relation.relationType());
        result.put("criticality", relation.criticality());
        result.put("lifecycleState", relation.lifecycleState());
        result.put("version", relation.version());
        return result;
    }

    private int weight(String criticality) {
        return switch (criticality) {
            case "CRITICAL" -> 4;
            case "OPERATIONAL" -> 2;
            default -> 1;
        };
    }

    private String normalizeRef(String value) {
        if (value == null || value.isBlank()) throw new BaseException(ErrorCode.INVALID_FORMAT);
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]*:[A-Z0-9_.:/-]+")) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Catalog reference is invalid.");
        }
        return normalized;
    }

    private String normalizeOptionalRef(String value) {
        return value == null || value.isBlank() ? null : normalizeRef(value);
    }

    private String lower(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private record Snapshot(
            List<CatalogDtos.Entity> entities,
            Map<String, CatalogDtos.Entity> byRef,
            List<CatalogDtos.Relation> relations) {
    }

    private record Traversal(String ref, int distance) {
    }

    private record DependencyEdge(String dependentRef, CatalogDtos.Relation relation) {
    }

    private static final class ImpactAccumulator {
        private int distance;
        private final Set<String> relationTypes = new LinkedHashSet<>();
        private String highestCriticality = "INFORMATIONAL";

        private ImpactAccumulator(int distance) {
            this.distance = distance;
        }

        private void merge(int candidateDistance, CatalogDtos.Relation relation) {
            distance = Math.min(distance, candidateDistance);
            relationTypes.add(relation.relationType());
            if (criticalityWeight(relation.criticality()) > criticalityWeight(highestCriticality)) {
                highestCriticality = relation.criticality();
            }
        }

        private CatalogDtos.ImpactItem item(CatalogDtos.Entity entity) {
            return new CatalogDtos.ImpactItem(
                    entity, distance, List.copyOf(relationTypes), highestCriticality);
        }

        private static int criticalityWeight(String criticality) {
            return switch (criticality) {
                case "CRITICAL" -> 4;
                case "OPERATIONAL" -> 2;
                default -> 1;
            };
        }
    }
}
