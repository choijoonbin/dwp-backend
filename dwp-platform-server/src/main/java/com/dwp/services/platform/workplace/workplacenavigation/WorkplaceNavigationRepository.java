package com.dwp.services.platform.workplace.workplacenavigation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;

@Repository
public class WorkplaceNavigationRepository {
    private final JdbcTemplate jdbc;

    public WorkplaceNavigationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GraphRow> graph(long tenantId, UUID graphId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_graph_revisions
                 WHERE tenant_id=? AND graph_revision_id=?
                """, this::graphRow, tenantId, graphId).stream().findFirst();
    }

    public Optional<GraphRow> publishedGraph(long tenantId, UUID siteId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_graph_revisions
                 WHERE tenant_id=? AND site_id=? AND lifecycle_state='PUBLISHED'
                """, this::graphRow, tenantId, siteId).stream().findFirst();
    }

    public List<GraphRow> graphs(long tenantId, UUID siteId) {
        return jdbc.query("""
                SELECT * FROM wp_navigation_graph_revisions
                 WHERE tenant_id=? AND (?::uuid IS NULL OR site_id=?::uuid)
                 ORDER BY site_id, revision_number DESC
                """, this::graphRow, tenantId, siteId, siteId);
    }

    public Optional<GraphCommandRow> graphCommand(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT graph_revision_id, command_type, request_fingerprint
                  FROM wp_navigation_graph_commands
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key=?
                """, (rs, row) -> new GraphCommandRow(
                rs.getObject("graph_revision_id", UUID.class),
                rs.getString("command_type"), rs.getString("request_fingerprint")),
                tenantId, actorId, idempotencyKey).stream().findFirst();
    }

    public void insertGraph(
            GraphRow graph,
            CreateGraphRequest request,
            long actorId,
            String idempotencyKey,
            String fingerprint,
            String correlationId,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_navigation_graph_revisions (
                    graph_revision_id, tenant_id, site_id, revision_number,
                    lifecycle_state, content_hash, change_summary, version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, 1, ?, ?, ?, ?)
                """, graph.graphRevisionId(), graph.tenantId(), graph.siteId(),
                graph.revisionNumber(), graph.contentHash(), graph.changeSummary(),
                now, actorId, now, actorId);
        for (NavigationNodeInput node : request.nodes()) {
            jdbc.update("""
                    INSERT INTO wp_navigation_nodes (
                        node_id, tenant_id, graph_revision_id, floor_id, node_code,
                        node_kind, position_x, position_y, accessible, restricted_zone_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, node.nodeId(), graph.tenantId(), graph.graphRevisionId(), node.floorId(),
                    node.nodeCode().trim(), node.nodeKind().name(), node.positionX(), node.positionY(),
                    node.accessible(), node.restrictedZoneId());
        }
        for (NavigationEdgeInput edge : request.edges()) {
            jdbc.update("""
                    INSERT INTO wp_navigation_edges (
                        edge_id, tenant_id, graph_revision_id, from_node_id, to_node_id,
                        travel_seconds, travel_mode, bidirectional, accessible, required_permission)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, edge.edgeId(), graph.tenantId(), graph.graphRevisionId(),
                    edge.fromNodeId(), edge.toNodeId(), edge.travelSeconds(), edge.travelMode().name(),
                    edge.bidirectional(), edge.accessible(), normalized(edge.requiredPermission()));
        }
        for (PoiInput poi : request.pois()) {
            jdbc.update("""
                    INSERT INTO wp_navigation_pois (
                        poi_id, tenant_id, graph_revision_id, node_id, site_id, floor_id,
                        resource_id, category, name_ko, name_en,
                        direction_hint_ko, direction_hint_en)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, poi.poiId(), graph.tenantId(), graph.graphRevisionId(), poi.nodeId(),
                    graph.siteId(), poi.floorId(), poi.resourceId(), poi.category().name(),
                    poi.nameKo().trim(), poi.nameEn().trim(), normalized(poi.directionHintKo()),
                    normalized(poi.directionHintEn()));
        }
        jdbc.update("""
                INSERT INTO wp_navigation_graph_commands (
                    graph_command_id, tenant_id, actor_user_id, graph_revision_id,
                    command_type, idempotency_key, request_fingerprint,
                    correlation_id, created_at)
                VALUES (?, ?, ?, ?, 'CREATE', ?, ?, ?, ?)
                """, UUID.randomUUID(), graph.tenantId(), actorId, graph.graphRevisionId(),
                idempotencyKey, fingerprint, correlationId, now);
        audit(graph.tenantId(), actorId, "navigation.graph.created", "GRAPH",
                graph.graphRevisionId(), correlationId, now);
    }

    public boolean publish(
            long tenantId,
            long actorId,
            GraphRow graph,
            long expectedVersion,
            String idempotencyKey,
            String fingerprint,
            String correlationId,
            OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_navigation_graph_revisions
                   SET lifecycle_state='ARCHIVED', version=version+1,
                       updated_at=?, updated_by=?
                 WHERE tenant_id=? AND site_id=? AND lifecycle_state='PUBLISHED'
                   AND graph_revision_id<>?
                """, now, actorId, tenantId, graph.siteId(), graph.graphRevisionId());
        int changed = jdbc.update("""
                UPDATE wp_navigation_graph_revisions
                   SET lifecycle_state='PUBLISHED', submitted_at=COALESCE(submitted_at, ?),
                       submitted_by=COALESCE(submitted_by, ?), published_at=?, published_by=?,
                       version=version+1, updated_at=?, updated_by=?
                 WHERE tenant_id=? AND graph_revision_id=? AND version=?
                   AND lifecycle_state='REVIEW'
                """, now, actorId, now, actorId, now, actorId, tenantId,
                graph.graphRevisionId(), expectedVersion);
        if (changed == 0) return false;
        jdbc.update("""
                INSERT INTO wp_navigation_graph_commands (
                    graph_command_id, tenant_id, actor_user_id, graph_revision_id,
                    command_type, idempotency_key, request_fingerprint,
                    correlation_id, created_at)
                VALUES (?, ?, ?, ?, 'PUBLISH', ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, actorId, graph.graphRevisionId(),
                idempotencyKey, fingerprint, correlationId, now);
        audit(tenantId, actorId, "navigation.graph.published", "GRAPH",
                graph.graphRevisionId(), correlationId, now);
        return true;
    }

    public boolean submitForReview(
            long tenantId,
            long actorId,
            UUID graphId,
            long expectedVersion,
            String idempotencyKey,
            String fingerprint,
            String correlationId,
            OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE wp_navigation_graph_revisions
                   SET lifecycle_state='REVIEW', submitted_at=?, submitted_by=?,
                       version=version+1, updated_at=?, updated_by=?
                 WHERE tenant_id=? AND graph_revision_id=? AND version=?
                   AND lifecycle_state='DRAFT'
                """, now, actorId, now, actorId, tenantId, graphId, expectedVersion);
        if (changed == 0) return false;
        insertGraphCommand(tenantId, actorId, graphId, "REVIEW", idempotencyKey,
                fingerprint, correlationId, now);
        audit(tenantId, actorId, "navigation.graph.review.submitted", "GRAPH",
                graphId, correlationId, now);
        return true;
    }

    public boolean archive(
            long tenantId,
            long actorId,
            UUID graphId,
            long expectedVersion,
            String idempotencyKey,
            String fingerprint,
            String correlationId,
            OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE wp_navigation_graph_revisions
                   SET lifecycle_state='ARCHIVED', version=version+1,
                       updated_at=?, updated_by=?
                 WHERE tenant_id=? AND graph_revision_id=? AND version=?
                   AND lifecycle_state IN ('DRAFT','REVIEW','PUBLISHED')
                """, now, actorId, tenantId, graphId, expectedVersion);
        if (changed == 0) return false;
        insertGraphCommand(tenantId, actorId, graphId, "ARCHIVE", idempotencyKey,
                fingerprint, correlationId, now);
        audit(tenantId, actorId, "navigation.graph.archived", "GRAPH",
                graphId, correlationId, now);
        return true;
    }

    public List<NodeRow> nodes(long tenantId, UUID graphId) {
        return jdbc.query("""
                SELECT node_id, floor_id, node_kind, accessible, restricted_zone_id
                  FROM wp_navigation_nodes
                 WHERE tenant_id=? AND graph_revision_id=?
                """, (rs, row) -> new NodeRow(rs.getObject("node_id", UUID.class),
                rs.getObject("floor_id", UUID.class), NodeKind.valueOf(rs.getString("node_kind")),
                rs.getBoolean("accessible"), rs.getObject("restricted_zone_id", UUID.class)),
                tenantId, graphId);
    }

    public List<EdgeRow> edges(long tenantId, UUID graphId) {
        return jdbc.query("""
                SELECT edge_id, from_node_id, to_node_id, travel_seconds,
                       travel_mode, bidirectional, accessible, required_permission
                  FROM wp_navigation_edges
                 WHERE tenant_id=? AND graph_revision_id=?
                """, (rs, row) -> new EdgeRow(rs.getObject("edge_id", UUID.class),
                rs.getObject("from_node_id", UUID.class), rs.getObject("to_node_id", UUID.class),
                rs.getInt("travel_seconds"), TravelMode.valueOf(rs.getString("travel_mode")),
                rs.getBoolean("bidirectional"), rs.getBoolean("accessible"),
                rs.getString("required_permission")), tenantId, graphId);
    }

    public List<PoiView> pois(long tenantId, UUID graphId) {
        return jdbc.query("""
                SELECT poi_id, node_id, site_id, floor_id, resource_id, category,
                       name_ko, name_en, direction_hint_ko, direction_hint_en
                  FROM wp_navigation_pois
                 WHERE tenant_id=? AND graph_revision_id=? AND active
                 ORDER BY floor_id, category, name_en
                """, this::poi, tenantId, graphId);
    }

    public boolean siteExists(long tenantId, UUID siteId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_sites
                 WHERE tenant_id=? AND site_id=? AND lifecycle_state<>'CLOSED')
                """, Boolean.class, tenantId, siteId));
    }

    public boolean floorBelongsToSite(long tenantId, UUID siteId, UUID floorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_floors
                 WHERE tenant_id=? AND site_id=? AND floor_id=? AND lifecycle_state<>'CLOSED')
                """, Boolean.class, tenantId, siteId, floorId));
    }

    public boolean resourceBelongsToFloor(long tenantId, UUID floorId, UUID resourceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_resources
                 WHERE tenant_id=? AND floor_id=? AND resource_id=? AND lifecycle_state<>'RETIRED')
                """, Boolean.class, tenantId, floorId, resourceId));
    }

    public boolean zoneBelongsToFloor(long tenantId, UUID floorId, UUID zoneId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_zones
                 WHERE tenant_id=? AND floor_id=? AND zone_id=?)
                """, Boolean.class, tenantId, floorId, zoneId));
    }

    public LocationFallback fallback(long tenantId, UUID siteId, PoiView destination) {
        UUID floorId = destination == null ? null : destination.floorId();
        UUID resourceId = destination == null ? null : destination.resourceId();
        List<LocationFallback> values = jdbc.query("""
                SELECT s.site_id, f.floor_id, r.resource_id,
                       s.name_ko site_name, f.name_ko floor_name, r.name_ko resource_name,
                       f.background_asset_path
                  FROM wp_sites s
                LEFT JOIN wp_floors f ON f.tenant_id=s.tenant_id AND f.site_id=s.site_id
                                     AND (?::uuid IS NULL OR f.floor_id=?::uuid)
                LEFT JOIN wp_resources r ON r.tenant_id=f.tenant_id AND r.floor_id=f.floor_id
                                        AND ?::uuid IS NOT NULL AND r.resource_id=?::uuid
                 WHERE s.tenant_id=? AND s.site_id=?
                 ORDER BY f.floor_number NULLS LAST LIMIT 1
                """, (rs, row) -> new LocationFallback(
                rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getObject("resource_id", UUID.class), rs.getString("site_name"),
                rs.getString("floor_name"), rs.getString("resource_name"),
                rs.getString("background_asset_path"), List.of()),
                floorId, floorId, resourceId, resourceId, tenantId, siteId);
        LocationFallback base = values.isEmpty()
                ? new LocationFallback(siteId, floorId, resourceId, null, null, null, null, List.of())
                : values.getFirst();
        List<PoiView> desks = destination == null ? List.of() : jdbc.query("""
                SELECT poi_id, node_id, site_id, floor_id, resource_id, category,
                       name_ko, name_en, direction_hint_ko, direction_hint_en
                 FROM wp_navigation_pois
                 WHERE tenant_id=? AND site_id=? AND floor_id=?
                   AND category='HELP_DESK' AND active
                   AND graph_revision_id=(
                       SELECT graph_revision_id FROM wp_navigation_graph_revisions
                        WHERE tenant_id=? AND site_id=? AND lifecycle_state='PUBLISHED')
                 ORDER BY name_en
                """, this::poi, tenantId, siteId, destination.floorId(), tenantId, siteId);
        return new LocationFallback(base.siteId(), base.floorId(), base.resourceId(),
                base.siteName(), base.floorName(), base.resourceName(), base.floorMapPath(), desks);
    }

    public GraphRevisionView view(long tenantId, GraphRow graph) {
        Integer nodeCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_navigation_nodes
                 WHERE tenant_id=? AND graph_revision_id=?
                """, Integer.class, tenantId, graph.graphRevisionId());
        Integer edgeCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_navigation_edges
                 WHERE tenant_id=? AND graph_revision_id=?
                """, Integer.class, tenantId, graph.graphRevisionId());
        Integer poiCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_navigation_pois
                 WHERE tenant_id=? AND graph_revision_id=?
                """, Integer.class, tenantId, graph.graphRevisionId());
        return new GraphRevisionView(graph.graphRevisionId(), graph.siteId(), graph.revisionNumber(),
                graph.state(), graph.contentHash(), graph.changeSummary(), graph.version(),
                graph.submittedAt(), graph.publishedAt(), nodeCount == null ? 0 : nodeCount,
                edgeCount == null ? 0 : edgeCount, poiCount == null ? 0 : poiCount,
                graph.updatedAt());
    }

    private void audit(long tenantId, long actorId, String action, String type,
                       UUID resourceId, String correlationId, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_navigation_audit_events (
                    audit_event_id, tenant_id, actor_user_id, action, resource_type,
                    resource_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?)
                """, UUID.randomUUID(), tenantId, actorId, action, type,
                resourceId, correlationId, now);
    }

    private void insertGraphCommand(
            long tenantId,
            long actorId,
            UUID graphId,
            String commandType,
            String idempotencyKey,
            String fingerprint,
            String correlationId,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_navigation_graph_commands (
                    graph_command_id, tenant_id, actor_user_id, graph_revision_id,
                    command_type, idempotency_key, request_fingerprint,
                    correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, actorId, graphId, commandType,
                idempotencyKey, fingerprint, correlationId, now);
    }

    private GraphRow graphRow(ResultSet rs, int row) throws SQLException {
        return new GraphRow(rs.getObject("graph_revision_id", UUID.class),
                rs.getLong("tenant_id"), rs.getObject("site_id", UUID.class),
                rs.getLong("revision_number"), GraphState.valueOf(rs.getString("lifecycle_state")),
                rs.getString("content_hash"), rs.getString("change_summary"), rs.getLong("version"),
                rs.getObject("submitted_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private PoiView poi(ResultSet rs, int row) throws SQLException {
        return new PoiView(rs.getObject("poi_id", UUID.class),
                rs.getObject("node_id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getObject("resource_id", UUID.class),
                PoiCategory.valueOf(rs.getString("category")), rs.getString("name_ko"),
                rs.getString("name_en"), rs.getString("direction_hint_ko"),
                rs.getString("direction_hint_en"));
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record GraphCommandRow(UUID graphId, String commandType, String requestFingerprint) { }
}
