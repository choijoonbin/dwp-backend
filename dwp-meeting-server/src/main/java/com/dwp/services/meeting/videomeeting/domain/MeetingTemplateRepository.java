package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.AgendaItem;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.TemplateFilter;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.TemplateInput;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.TemplateScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeetingTemplateRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final RowMapper<Template> TEMPLATE = (row, index) -> new Template(
            row.getObject("template_id", UUID.class), row.getLong("tenant_id"),
            row.getLong("owner_user_id"), TemplateScope.valueOf(row.getString("template_scope")),
            row.getString("name"), row.getString("purpose"), row.getString("category"),
            row.getInt("duration_minutes"), row.getLong("version"),
            row.getObject("updated_at", OffsetDateTime.class));

    public MeetingTemplateRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Template> find(long tenant, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM vm_meeting_templates WHERE tenant_id = ? AND template_id = ? AND deleted_at IS NULL"
                + (lock ? " FOR UPDATE" : ""), TEMPLATE, tenant, id).stream().findFirst();
    }

    public List<Template> list(long tenant, long actor, TemplateFilter filter,
                               String search, String category, boolean favoritesOnly,
                               int page, int size, boolean admin) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_templates
                 WHERE tenant_id = ? AND deleted_at IS NULL
                   AND (template_scope = 'ORGANIZATION' OR owner_user_id = ?)
                   AND (? = 'ALL' OR template_scope = ?)
                   AND (? = FALSE OR template_scope = 'ORGANIZATION')
                   AND position(lower(?) in lower(name || ' ' || purpose || ' ' || category)) > 0
                   AND (? = '' OR category = ?)
                   AND (? = FALSE OR EXISTS (SELECT 1 FROM vm_meeting_template_favorites f
                        WHERE f.tenant_id = vm_meeting_templates.tenant_id
                          AND f.template_id = vm_meeting_templates.template_id AND f.user_id = ?))
                 ORDER BY updated_at DESC, template_id
                 LIMIT ? OFFSET ?
                """, TEMPLATE, tenant, actor, filter.name(), filter.name(), admin,
                search, category, category, favoritesOnly, actor, size, (long) page * size);
    }

    public long count(long tenant, long actor, TemplateFilter filter, String search,
                      String category, boolean favoritesOnly, boolean admin) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_templates
                 WHERE tenant_id = ? AND deleted_at IS NULL
                   AND (template_scope = 'ORGANIZATION' OR owner_user_id = ?)
                   AND (? = 'ALL' OR template_scope = ?)
                   AND (? = FALSE OR template_scope = 'ORGANIZATION')
                   AND position(lower(?) in lower(name || ' ' || purpose || ' ' || category)) > 0
                   AND (? = '' OR category = ?)
                   AND (? = FALSE OR EXISTS (SELECT 1 FROM vm_meeting_template_favorites f
                        WHERE f.tenant_id = vm_meeting_templates.tenant_id
                          AND f.template_id = vm_meeting_templates.template_id AND f.user_id = ?))
                """, Long.class, tenant, actor, filter.name(), filter.name(), admin, search,
                category, category, favoritesOnly, actor);
    }

    public Template create(long tenant, long actor, TemplateScope scope, TemplateInput input) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_templates (
                    template_id, tenant_id, owner_user_id, template_scope,
                    name, purpose, category, duration_minutes, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenant, actor, scope.name(), input.name().trim(), input.purpose().trim(),
                input.category().trim(), input.durationMinutes(), actor);
        replaceAgenda(tenant, id, input.agendaItems());
        revision(tenant, id, 0, actor, input);
        return find(tenant, id, false).orElseThrow();
    }

    public Template update(Template current, long actor, TemplateInput input) {
        int changed = jdbc.update("""
                UPDATE vm_meeting_templates
                   SET name = ?, purpose = ?, category = ?, duration_minutes = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND template_id = ? AND version = ?
                """, input.name().trim(), input.purpose().trim(), input.category().trim(),
                input.durationMinutes(), actor, current.tenantId(), current.id(), current.version());
        if (changed != 1) throw MeetingWorkspacePolicy.missing();
        replaceAgenda(current.tenantId(), current.id(), input.agendaItems());
        revision(current.tenantId(), current.id(), current.version() + 1, actor, input);
        return find(current.tenantId(), current.id(), false).orElseThrow();
    }

    public void delete(Template current) {
        jdbc.update("UPDATE vm_meeting_templates SET deleted_at = CURRENT_TIMESTAMP, version = version + 1 WHERE tenant_id = ? AND template_id = ? AND version = ?",
                current.tenantId(), current.id(), current.version());
        jdbc.update("DELETE FROM vm_meeting_template_favorites WHERE tenant_id = ? AND template_id = ?",
                current.tenantId(), current.id());
    }

    public List<AgendaItem> agenda(long tenant, UUID id) {
        return jdbc.query("""
                SELECT title, description, presenter_role, duration_minutes
                  FROM vm_meeting_template_agenda_items
                 WHERE tenant_id = ? AND template_id = ? ORDER BY position
                """, (row, index) -> new AgendaItem(row.getString("title"),
                        row.getString("description"), row.getString("presenter_role"),
                        row.getInt("duration_minutes")), tenant, id);
    }

    public boolean favorite(long tenant, long actor, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM vm_meeting_template_favorites
                               WHERE tenant_id = ? AND user_id = ? AND template_id = ?)
                """, Boolean.class, tenant, actor, id));
    }

    public void favorite(long tenant, long actor, UUID id, boolean enabled) {
        if (enabled) {
            jdbc.update("""
                    INSERT INTO vm_meeting_template_favorites (tenant_id, user_id, template_id)
                    VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                    """, tenant, actor, id);
        } else {
            jdbc.update("DELETE FROM vm_meeting_template_favorites WHERE tenant_id = ? AND user_id = ? AND template_id = ?",
                    tenant, actor, id);
        }
    }

    private void replaceAgenda(long tenant, UUID id, List<AgendaItem> items) {
        jdbc.update("DELETE FROM vm_meeting_template_agenda_items WHERE tenant_id = ? AND template_id = ?", tenant, id);
        for (int index = 0; index < items.size(); index++) {
            AgendaItem item = items.get(index);
            jdbc.update("""
                    INSERT INTO vm_meeting_template_agenda_items (
                        tenant_id, template_id, position, title, description, presenter_role, duration_minutes)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, tenant, id, index, item.title().trim(), item.description().trim(),
                    item.role().trim(), item.durationMinutes());
        }
    }

    public boolean revisionAccessible(long tenant, long actor, UUID id, long revision) {
        return !jdbc.query("""
                SELECT t.template_id FROM vm_meeting_template_revisions r
                    JOIN vm_meeting_templates t ON t.tenant_id = r.tenant_id AND t.template_id = r.template_id
                    WHERE r.tenant_id = ? AND r.template_id = ? AND r.revision = ?
                      AND t.deleted_at IS NULL
                      AND (t.template_scope = 'ORGANIZATION' OR t.owner_user_id = ?)
                    FOR SHARE OF t
                """, (row, index) -> row.getObject("template_id", UUID.class),
                tenant, id, revision, actor).isEmpty();
    }

    private void revision(long tenant, UUID id, long version, long actor, TemplateInput input) {
        try {
            jdbc.update("""
                    INSERT INTO vm_meeting_template_revisions
                        (tenant_id, template_id, revision, snapshot, created_by)
                    VALUES (?, ?, ?, ?::jsonb, ?)
                    """, tenant, id, version, mapper.writeValueAsString(input), actor);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Template revision cannot be serialized.");
        }
    }

    public record Template(UUID id, long tenantId, long ownerId, TemplateScope scope,
                           String name, String purpose, String category, int durationMinutes,
                           long version, OffsetDateTime updatedAt) { }
}
