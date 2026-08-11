package com.dwp.services.platform.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkspaceRepository {

    private final JdbcTemplate jdbc;

    public WorkspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WorkRow> workItems(Long tenantId, Long actorId, boolean korean) {
        return jdbc.query("""
                SELECT work_item_id, work_key, title_ko, title_en, summary_ko, summary_en,
                       work_type, priority, lifecycle_state, owner_name, due_at,
                       source_system, source_reference, source_route,
                       reason_ko, reason_en, recommended_next_ko, recommended_next_en,
                       latest_activity_ko, latest_activity_en, version, updated_at
                  FROM wrk_items
                 WHERE tenant_id = ?
                   AND (assignee_user_id IS NULL OR assignee_user_id = ?)
                 ORDER BY CASE priority WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                          CASE lifecycle_state
                              WHEN 'DUE_SOON' THEN 1 WHEN 'IN_PROGRESS' THEN 2
                              WHEN 'WAITING' THEN 3 ELSE 4 END,
                          due_at NULLS LAST, work_key
                """, (result, ignored) -> workRow(result, korean), tenantId, actorId);
    }

    public Optional<WorkRow> workItem(
            Long tenantId,
            Long actorId,
            UUID workItemId,
            boolean korean) {
        return jdbc.query("""
                SELECT work_item_id, work_key, title_ko, title_en, summary_ko, summary_en,
                       work_type, priority, lifecycle_state, owner_name, due_at,
                       source_system, source_reference, source_route,
                       reason_ko, reason_en, recommended_next_ko, recommended_next_en,
                       latest_activity_ko, latest_activity_en, version, updated_at
                  FROM wrk_items
                 WHERE tenant_id = ? AND work_item_id = ?
                   AND (assignee_user_id IS NULL OR assignee_user_id = ?)
                """, (result, ignored) -> workRow(result, korean), tenantId, workItemId, actorId)
                .stream().findFirst();
    }

    public boolean updateWorkStatus(
            Long tenantId,
            Long actorId,
            UUID workItemId,
            String status,
            long version,
            String latestActivityKo,
            String latestActivityEn) {
        return jdbc.update("""
                UPDATE wrk_items
                   SET lifecycle_state = ?,
                       latest_activity_ko = ?,
                       latest_activity_en = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND work_item_id = ? AND version = ?
                   AND (assignee_user_id IS NULL OR assignee_user_id = ?)
                """, status, latestActivityKo, latestActivityEn, actorId,
                tenantId, workItemId, version, actorId) == 1;
    }

    public List<ActivityRow> activity(Long tenantId, Long actorId, boolean korean) {
        return jdbc.query("""
                SELECT activity_event_id, occurred_at, actor_kind, actor_name, event_state,
                       title_ko, title_en, summary_ko, summary_en, object_type,
                       object_label_ko, object_label_en, source_system, tool_name,
                       audit_reference, progress, source_route
                  FROM wrk_activity_events
                 WHERE tenant_id = ?
                   AND (visible_to_user_id IS NULL OR visible_to_user_id = ?)
                 ORDER BY occurred_at DESC
                 LIMIT 200
                """, (result, ignored) -> activityRow(result, korean), tenantId, actorId);
    }

    public void addWorkActivity(
            Long tenantId,
            Long actorId,
            WorkRow item,
            String state,
            String titleKo,
            String titleEn,
            String summaryKo,
            String summaryEn,
            String auditReference) {
        jdbc.update("""
                INSERT INTO wrk_activity_events (
                    activity_event_id, tenant_id, visible_to_user_id, actor_kind,
                    actor_name, event_state, title_ko, title_en, summary_ko, summary_en,
                    object_type, object_label_ko, object_label_en, source_system,
                    audit_reference, source_route, occurred_at)
                VALUES (?, ?, ?, 'PERSON', ?, ?, ?, ?, ?, ?, 'WORK_ITEM', ?, ?, ?, ?, ?,
                        CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), tenantId, actorId, "User " + actorId, state,
                titleKo, titleEn, summaryKo, summaryEn, item.title(), item.title(),
                item.sourceSystem(), auditReference, item.sourceRoute());
    }

    public List<AppRow> apps(Long tenantId, Long actorId, boolean korean) {
        return jdbc.query("""
                SELECT app.app_key, app.name_ko, app.name_en,
                       app.description_ko, app.description_en, app.owner_name,
                       app.category, app.launch_mode, app.launch_target, app.icon_key,
                       app.resource_key, app.health_state,
                       COALESCE(preference.pinned, FALSE) AS pinned,
                       preference.last_used_at, COALESCE(preference.launch_count, 0) AS launch_count,
                       COALESCE(preference.version, 0) AS preference_version
                  FROM adm_workspace_apps app
                  LEFT JOIN usr_workspace_app_preferences preference
                    ON preference.tenant_id = app.tenant_id
                   AND preference.app_key = app.app_key
                   AND preference.user_id = ?
                 WHERE app.tenant_id = ? AND app.lifecycle_state = 'ACTIVE'
                 ORDER BY app.sort_order, app.app_key
                """, (result, ignored) -> appRow(result, korean), actorId, tenantId);
    }

    public Optional<AppRow> app(Long tenantId, Long actorId, String appKey, boolean korean) {
        return apps(tenantId, actorId, korean).stream()
                .filter(app -> app.id().equals(appKey))
                .findFirst();
    }

    public boolean setPinned(
            Long tenantId,
            Long actorId,
            String appKey,
            boolean pinned,
            long version) {
        return jdbc.update("""
                INSERT INTO usr_workspace_app_preferences (
                    tenant_id, user_id, app_key, pinned, version)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT (tenant_id, user_id, app_key) DO UPDATE
                   SET pinned = EXCLUDED.pinned,
                       version = usr_workspace_app_preferences.version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE usr_workspace_app_preferences.version = ?
                """, tenantId, actorId, appKey, pinned, version) == 1;
    }

    public void recordLaunch(Long tenantId, Long actorId, String appKey) {
        jdbc.update("""
                INSERT INTO usr_workspace_app_preferences (
                    tenant_id, user_id, app_key, pinned, last_used_at, launch_count, version)
                VALUES (?, ?, ?, FALSE, CURRENT_TIMESTAMP, 1, 1)
                ON CONFLICT (tenant_id, user_id, app_key) DO UPDATE
                   SET last_used_at = CURRENT_TIMESTAMP,
                       launch_count = usr_workspace_app_preferences.launch_count + 1,
                       version = usr_workspace_app_preferences.version + 1,
                       updated_at = CURRENT_TIMESTAMP
                """, tenantId, actorId, appKey);
    }

    public void addAppActivity(
            Long tenantId,
            Long actorId,
            AppRow app,
            String titleKo,
            String titleEn,
            String summaryKo,
            String summaryEn,
            String auditReference) {
        jdbc.update("""
                INSERT INTO wrk_activity_events (
                    activity_event_id, tenant_id, visible_to_user_id, actor_kind,
                    actor_name, event_state, title_ko, title_en, summary_ko, summary_en,
                    object_type, object_label_ko, object_label_en, source_system,
                    audit_reference, source_route, occurred_at)
                VALUES (?, ?, ?, 'PERSON', ?, 'COMPLETED', ?, ?, ?, ?, 'WORKSPACE_APP',
                        ?, ?, 'DWP Apps', ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), tenantId, actorId, "User " + actorId,
                titleKo, titleEn, summaryKo, summaryEn, app.name(), app.name(), auditReference,
                "/apps?app=" + app.id());
    }

    private WorkRow workRow(ResultSet result, boolean korean) throws SQLException {
        return new WorkRow(
                result.getObject("work_item_id", UUID.class),
                result.getString("work_key"),
                localized(result, "title", korean),
                localized(result, "summary", korean),
                result.getString("work_type"),
                result.getString("priority"),
                result.getString("lifecycle_state"),
                result.getString("owner_name"),
                result.getObject("due_at", OffsetDateTime.class),
                result.getString("source_system"),
                result.getString("source_reference"),
                result.getString("source_route"),
                localized(result, "reason", korean),
                localized(result, "recommended_next", korean),
                localized(result, "latest_activity", korean),
                result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private ActivityRow activityRow(ResultSet result, boolean korean) throws SQLException {
        return new ActivityRow(
                result.getObject("activity_event_id", UUID.class),
                result.getObject("occurred_at", OffsetDateTime.class),
                result.getString("actor_kind"),
                result.getString("actor_name"),
                result.getString("event_state"),
                localized(result, "title", korean),
                localized(result, "summary", korean),
                result.getString("object_type"),
                localized(result, "object_label", korean),
                result.getString("source_system"),
                result.getString("tool_name"),
                result.getString("audit_reference"),
                (Integer) result.getObject("progress"),
                result.getString("source_route"));
    }

    private AppRow appRow(ResultSet result, boolean korean) throws SQLException {
        return new AppRow(
                result.getString("app_key"),
                localized(result, "name", korean),
                localized(result, "description", korean),
                result.getString("owner_name"),
                result.getString("category"),
                result.getString("launch_mode"),
                result.getString("launch_target"),
                result.getString("icon_key"),
                result.getString("resource_key"),
                result.getString("health_state"),
                result.getBoolean("pinned"),
                result.getObject("last_used_at", OffsetDateTime.class),
                result.getLong("launch_count"),
                result.getLong("preference_version"));
    }

    private String localized(ResultSet result, String field, boolean korean) throws SQLException {
        String preferred = result.getString(field + (korean ? "_ko" : "_en"));
        return preferred != null ? preferred : result.getString(field + (korean ? "_en" : "_ko"));
    }

    public record WorkRow(
            UUID workItemId,
            String id,
            String title,
            String summary,
            String type,
            String priority,
            String status,
            String owner,
            OffsetDateTime dueAt,
            String sourceSystem,
            String sourceReference,
            String sourceRoute,
            String reason,
            String recommendedNext,
            String latestActivity,
            long version,
            OffsetDateTime updatedAt) {
    }

    public record ActivityRow(
            UUID id,
            OffsetDateTime occurredAt,
            String actor,
            String actorName,
            String state,
            String title,
            String summary,
            String objectType,
            String objectLabel,
            String source,
            String tool,
            String auditId,
            Integer progress,
            String sourceRoute) {
    }

    public record AppRow(
            String id,
            String name,
            String description,
            String owner,
            String category,
            String launchMode,
            String launchTarget,
            String iconKey,
            String resourceKey,
            String health,
            boolean pinned,
            OffsetDateTime lastUsedAt,
            long launchCount,
            long version) {
    }
}
