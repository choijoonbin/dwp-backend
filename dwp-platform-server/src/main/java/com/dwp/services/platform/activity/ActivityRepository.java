package com.dwp.services.platform.activity;

import com.dwp.services.platform.workspace.WorkspaceDtos;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ActivityRepository {
    private static final String SELECT_EVENT = """
            SELECT e.*, (SELECT w.work_key FROM wrk_items w
              WHERE w.tenant_id=e.tenant_id AND w.work_item_id::text=e.object_id) AS source_work_key
            FROM wrk_activity_events e WHERE
            """;
    // Audiences are not grants. An item must still exist and the viewer must have
    // its current domain permission AND current object ownership/catalog grant.
    static final String AUTHORIZED = """
             e.tenant_id = :tenant AND e.visible_to_user_id = :user
             AND ((e.data_provenance IN ('LIVE','LEGACY')
                 AND e.correlation_id IS DISTINCT FROM 'activity-local-joonbin'
                 AND (e.source_event_id IS NULL OR e.source_event_id NOT LIKE 'activity-local:%'))
               OR (:localFixtures AND e.data_provenance = 'SAMPLE'
                 AND e.visible_to_user_id = 900018
                 AND e.correlation_id = 'activity-local-joonbin'
                 AND e.source_event_id LIKE 'activity-local:%'))
             AND ((e.object_type = 'WORK_ITEM' AND :workView
                   AND e.source_system IN ('WORKSPACE','DWP_WORKSPACE')
                   AND EXISTS (SELECT 1 FROM wrk_items w WHERE w.tenant_id = e.tenant_id
                     AND w.work_item_id::text = e.object_id AND w.assignee_user_id = :user
                     AND w.work_type = 'TASK' AND w.source_system IN ('WORKSPACE','DWP_WORKSPACE')))
               OR (e.object_type = 'WORKSPACE_APP' AND e.source_system = 'DWP Apps' AND :appsView
                   AND EXISTS (SELECT 1 FROM adm_workspace_apps app
                     WHERE app.tenant_id = e.tenant_id AND app.app_key = e.object_id
                       AND app.lifecycle_state = 'ACTIVE'
                       AND upper(app.resource_key) || ':VIEW' IN (:permissions))))
            """;
    private final NamedParameterJdbcTemplate jdbc;
    public ActivityRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<WorkspaceDtos.ActivityEvent> list(
            Long tenant, Long user, Set<String> permissions, boolean korean,
            ActivityQuery query, ActivityCursor.Position position, boolean localFixtures) {
        MapSqlParameterSource params = access(tenant, user, permissions, localFixtures)
                .addValue("snapshot", position.snapshotAt()).addValue("limit", query.limit() + 1);
        StringBuilder sql = new StringBuilder(SELECT_EVENT)
                .append(AUTHORIZED).append(" AND e.created_at <= :snapshot");
        filter(sql, params, "actor_kind", "actor", query.actor());
        filter(sql, params, "event_state", "state", query.state());
        filter(sql, params, "source_system", "source", query.source());
        filter(sql, params, "object_type", "objectType", query.objectType());
        filter(sql, params, "object_id", "objectId", query.objectId());
        filter(sql, params, "execution_id", "executionId", query.executionId());
        if (!query.includeUsage()) sql.append(" AND e.event_kind <> 'USAGE'");
        if (query.from() != null) {
            sql.append(" AND e.occurred_at >= :from"); params.addValue("from", query.from());
        }
        if (query.to() != null) {
            sql.append(" AND e.occurred_at < :to"); params.addValue("to", query.to());
        }
        if (query.query() != null) {
            sql.append(" AND (e.title_ko ILIKE :query ESCAPE '!' OR e.title_en ILIKE :query ESCAPE '!'"
                    + " OR e.summary_ko ILIKE :query ESCAPE '!' OR e.summary_en ILIKE :query ESCAPE '!'")
                    .append(" OR e.object_label_ko ILIKE :query ESCAPE '!' OR e.object_label_en ILIKE :query ESCAPE '!')");
            params.addValue("query", "%" + query.query().replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%");
        }
        if (position.id() != null) {
            sql.append(" AND (e.occurred_at,e.activity_event_id) < (:lastTime,:lastId)");
            params.addValue("lastTime", position.occurredAt()).addValue("lastId", position.id());
        }
        sql.append(" ORDER BY e.occurred_at DESC,e.activity_event_id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, (rs, n) -> event(rs, korean));
    }

    public Optional<WorkspaceDtos.ActivityEvent> detail(
            Long tenant, Long user, Set<String> permissions, boolean korean, UUID id,
            boolean localFixtures) {
        return jdbc.query(SELECT_EVENT + AUTHORIZED
                        + " AND e.activity_event_id = :id",
                access(tenant, user, permissions, localFixtures).addValue("id", id),
                (rs, n) -> event(rs, korean)).stream().findFirst();
    }

    public long[] executionCounts(Long tenant, Long user, Set<String> permissions) {
        return jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE e.event_state='RUNNING') AS running,
                       count(*) FILTER (WHERE e.event_state='NEEDS_INPUT') AS needs_input,
                       count(*) FILTER (WHERE e.event_state='POLICY_BLOCKED') AS blocked,
                       count(*) FILTER (WHERE e.event_state='COMPLETED') AS completed,
                       count(*) FILTER (WHERE e.event_state='FAILED') AS failed,
                       count(*) FILTER (WHERE e.event_state='CANCELLED') AS cancelled
                  FROM wrk_activity_execution_current e WHERE
                """ + AUTHORIZED, access(tenant, user, permissions, false),
                (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)});
    }

    private MapSqlParameterSource access(
            Long tenant, Long user, Set<String> permissions, boolean localFixtures) {
        return new MapSqlParameterSource().addValue("tenant", tenant).addValue("user", user)
                .addValue("localFixtures", localFixtures)
                .addValue("workView", permissions.contains("APP.WORK:VIEW"))
                .addValue("appsView", permissions.contains("APP.APPS:VIEW"))
                .addValue("permissions", permissions.isEmpty() ? Set.of("__NONE__") : permissions);
    }

    private void filter(StringBuilder sql, MapSqlParameterSource params, String column, String key, String value) {
        if (value == null) return;
        sql.append(" AND e.").append(column).append(" = :").append(key);
        params.addValue(key, value);
    }

    private WorkspaceDtos.ActivityEvent event(ResultSet rs, boolean korean) throws SQLException {
        UUID auditId = rs.getObject("audit_record_id", UUID.class);
        String objectId = rs.getString("object_id");
        String route = "WORK_ITEM".equals(rs.getString("object_type"))
                ? "/work?item=" + java.net.URLEncoder.encode(rs.getString("source_work_key"),
                    java.nio.charset.StandardCharsets.UTF_8)
                : "/apps?app=" + java.net.URLEncoder.encode(objectId, java.nio.charset.StandardCharsets.UTF_8);
        return new WorkspaceDtos.ActivityEvent(rs.getObject("activity_event_id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("actor_kind"),
                rs.getString("actor_name"), rs.getString("event_state"), localized(rs, "title", korean),
                localized(rs, "summary", korean), rs.getString("object_type"), localized(rs, "object_label", korean),
                rs.getString("source_system"), rs.getString("tool_name"), auditId == null ? null : auditId.toString(),
                (Integer) rs.getObject("progress"), route, rs.getString("event_kind"), rs.getString("source_event_id"),
                objectId, rs.getString("execution_id"), (Long) rs.getObject("execution_version"),
                (Integer) rs.getObject("attempt"), rs.getString("work_status"), rs.getString("correlation_id"),
                auditId, rs.getString("data_provenance"), "AVAILABLE", "RESTRICTED",
                auditId == null ? "LEGACY_UNLINKED" : "VERIFIED", null);
    }

    private String localized(ResultSet rs, String field, boolean korean) throws SQLException {
        String text = rs.getString(field + (korean ? "_ko" : "_en"));
        return text == null ? rs.getString(field + (korean ? "_en" : "_ko")) : text;
    }
}
