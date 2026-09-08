package com.dwp.services.platform.workhub.personal;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;

@Repository
public class PersonalWorkRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public PersonalWorkRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** One transaction lock also serializes duplicate commands before receipt lookup. */
    public void lock(AccessContext context) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 717271))",
                result -> null, "personal-work:" + context.tenantId() + ":" + context.userId());
    }

    public Optional<TaskRow> find(AccessContext context, UUID taskId) {
        return jdbc.query("""
                SELECT * FROM personal_work_tasks
                WHERE tenant_id = ? AND owner_user_id = ? AND task_id = ? AND deleted_at IS NULL
                """, this::task, context.tenantId(), context.userId(), taskId).stream().findFirst();
    }

    public List<TaskRow> list(AccessContext context, Status status, int page, int size) {
        return jdbc.query("""
                SELECT * FROM personal_work_tasks
                WHERE tenant_id = ? AND owner_user_id = ?
                AND deleted_at IS NULL
                AND ((?::text IS NULL AND status <> 'ARCHIVED') OR status = ?)
                ORDER BY updated_at DESC, task_id LIMIT ? OFFSET ?
                """, this::task, context.tenantId(), context.userId(),
                status == null ? null : status.name(), status == null ? null : status.name(),
                size, (long) page * size);
    }

    public long count(AccessContext context, Status status) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM personal_work_tasks
                WHERE tenant_id = ? AND owner_user_id = ?
                AND deleted_at IS NULL
                AND ((?::text IS NULL AND status <> 'ARCHIVED') OR status = ?)
                """, Long.class, context.tenantId(), context.userId(),
                status == null ? null : status.name(), status == null ? null : status.name());
        return count == null ? 0 : count;
    }

    public TaskRow insert(AccessContext context, UUID taskId, CreateTaskRequest request) {
        SourceReference source = request.sourceReference();
        jdbc.update("""
                INSERT INTO personal_work_tasks
                (task_id, tenant_id, owner_user_id, title, description, status, priority, due_at,
                 source_system, source_reference, obligation_key, checklist, source_references)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, taskId, context.tenantId(), context.userId(), request.title().trim(),
                request.description(), request.priority().name(), request.dueAt(),
                source == null ? null : source.sourceSystem(),
                source == null ? null : source.sourceReference(),
                source == null ? null : source.obligationKey(),
                json(request.checklist()), json(request.sourceReferences()));
        return find(context, taskId).orElseThrow();
    }

    public TaskRow update(AccessContext context, UUID taskId, UpdateTaskRequest request) {
        SourceReference source = request.sourceReference();
        int changed = jdbc.update("""
                UPDATE personal_work_tasks SET title = ?, description = ?, priority = ?, due_at = ?,
                source_system = ?, source_reference = ?, obligation_key = ?,
                checklist = ?::jsonb, source_references = ?::jsonb,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND owner_user_id = ? AND task_id = ? AND version = ? AND deleted_at IS NULL
                """, request.title().trim(), request.description(), request.priority().name(), request.dueAt(),
                source == null ? null : source.sourceSystem(),
                source == null ? null : source.sourceReference(),
                source == null ? null : source.obligationKey(),
                json(request.checklist()), json(request.sourceReferences()),
                context.tenantId(), context.userId(), taskId, request.version());
        requireChanged(changed);
        return find(context, taskId).orElseThrow();
    }

    public TaskRow transition(AccessContext context, UUID taskId, Status status, long version) {
        int changed = jdbc.update("""
                UPDATE personal_work_tasks SET status = ?, version = version + 1,
                completed_at = CASE WHEN ? = 'COMPLETED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND owner_user_id = ? AND task_id = ? AND version = ? AND deleted_at IS NULL
                """, status.name(), status.name(), context.tenantId(), context.userId(), taskId, version);
        requireChanged(changed);
        return find(context, taskId).orElseThrow();
    }

    public TaskRow softDelete(AccessContext context, UUID taskId, long version) {
        requireChanged(jdbc.update("""
                UPDATE personal_work_tasks SET deleted_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND owner_user_id = ? AND task_id = ?
                   AND version = ? AND deleted_at IS NULL
                """, context.tenantId(), context.userId(), taskId, version));
        return jdbc.query("""
                SELECT * FROM personal_work_tasks
                 WHERE tenant_id = ? AND owner_user_id = ? AND task_id = ?
                """, this::task, context.tenantId(), context.userId(), taskId).getFirst();
    }

    public boolean isDeleted(AccessContext context, UUID taskId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM personal_work_tasks
                 WHERE tenant_id = ? AND owner_user_id = ? AND task_id = ? AND deleted_at IS NOT NULL)
                """, Boolean.class, context.tenantId(), context.userId(), taskId));
    }

    /** Rebuild each affected plan so its order and optimistic version remain valid. */
    public void removeTaskFromPlans(AccessContext context, UUID taskId) {
        List<LocalDate> dates = jdbc.query("""
                SELECT DISTINCT plan_date FROM personal_work_day_plan_items
                 WHERE tenant_id = ? AND owner_user_id = ?
                   AND source_system = 'PERSONAL_TASK' AND source_reference = ?
                """, (rs, ignored) -> rs.getObject("plan_date", LocalDate.class),
                context.tenantId(), context.userId(), taskId.toString());
        for (LocalDate date : dates) {
            PlanRow before = plan(context, date);
            replacePlan(context, date, before.version(), before.items().stream()
                    .filter(item -> !("PERSONAL_TASK".equals(item.sourceSystem())
                            && taskId.toString().equals(item.sourceReference()))).toList());
        }
    }

    public void appendTimeline(AccessContext context, TaskRow task, String action, UUID auditId) {
        jdbc.update("""
                INSERT INTO personal_work_timeline
                (event_id, tenant_id, owner_user_id, task_id, action, status, version, audit_record_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), context.tenantId(), context.userId(), task.taskId(),
                action, task.status().name(), task.version(), auditId);
    }

    public List<TimelineEvent> timeline(AccessContext context, UUID taskId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM personal_work_timeline
                WHERE tenant_id = ? AND owner_user_id = ? AND task_id = ?
                ORDER BY occurred_at DESC, version DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new TimelineEvent(rs.getObject("event_id", UUID.class),
                rs.getString("action"), Status.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getObject("audit_record_id", UUID.class)),
                context.tenantId(), context.userId(), taskId, size, (long) page * size);
    }

    public long timelineCount(AccessContext context, UUID taskId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM personal_work_timeline
                WHERE tenant_id = ? AND owner_user_id = ? AND task_id = ?
                """, Long.class, context.tenantId(), context.userId(), taskId);
        return count == null ? 0 : count;
    }

    public PlanRow plan(AccessContext context, LocalDate date) {
        List<PlanRow> plans = jdbc.query("""
                SELECT version, updated_at FROM personal_work_day_plans
                WHERE tenant_id = ? AND owner_user_id = ? AND plan_date = ?
                """, (rs, row) -> new PlanRow(date, rs.getLong("version"), List.of(),
                rs.getObject("updated_at", OffsetDateTime.class)), context.tenantId(), context.userId(), date);
        if (plans.isEmpty()) return new PlanRow(date, 0, List.of(), null);
        PlanRow plan = plans.getFirst();
        List<SourceReference> items = jdbc.query("""
                SELECT source_system, source_reference, obligation_key FROM personal_work_day_plan_items
                WHERE tenant_id = ? AND owner_user_id = ? AND plan_date = ? ORDER BY position
                """, (rs, row) -> source(rs), context.tenantId(), context.userId(), date);
        return new PlanRow(date, plan.version(), items, plan.updatedAt());
    }

    public PlanRow replacePlan(AccessContext context, LocalDate date, long version,
                               List<SourceReference> items) {
        jdbc.update("""
                INSERT INTO personal_work_day_plans (tenant_id, owner_user_id, plan_date)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, context.tenantId(), context.userId(), date);
        requireChanged(jdbc.update("""
                UPDATE personal_work_day_plans SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND owner_user_id = ? AND plan_date = ? AND version = ?
                """, context.tenantId(), context.userId(), date, version));
        jdbc.update("""
                DELETE FROM personal_work_day_plan_items
                WHERE tenant_id = ? AND owner_user_id = ? AND plan_date = ?
                """, context.tenantId(), context.userId(), date);
        for (int position = 0; position < items.size(); position++) {
            SourceReference item = items.get(position);
            jdbc.update("""
                    INSERT INTO personal_work_day_plan_items
                    (tenant_id, owner_user_id, plan_date, position, source_system, source_reference, obligation_key)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, context.tenantId(), context.userId(), date, position, item.sourceSystem(),
                    item.sourceReference(), item.obligationKey() == null ? "" : item.obligationKey());
        }
        return plan(context, date);
    }

    public Optional<Receipt> receipt(AccessContext context, UUID commandId) {
        return jdbc.query("""
                SELECT operation, target_key, request_fingerprint, response_payload::text FROM personal_work_command_receipts
                WHERE tenant_id = ? AND owner_user_id = ? AND command_id = ?
                """, (rs, row) -> new Receipt(rs.getString("operation"), rs.getString("target_key"),
                rs.getString("request_fingerprint"), rs.getString("response_payload")),
                context.tenantId(), context.userId(), commandId).stream().findFirst();
    }

    public void recordReceipt(AccessContext context, UUID commandId, String operation,
                              String target, String fingerprint, String payload) {
        jdbc.update("""
                INSERT INTO personal_work_command_receipts
                (tenant_id, owner_user_id, command_id, operation, target_key, request_fingerprint, response_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, context.tenantId(), context.userId(), commandId, operation, target, fingerprint, payload);
    }

    private TaskRow task(ResultSet rs, int row) throws SQLException {
        return new TaskRow(rs.getObject("task_id", UUID.class), rs.getString("title"),
                rs.getString("description"), Status.valueOf(rs.getString("status")),
                Priority.valueOf(rs.getString("priority")), rs.getObject("due_at", OffsetDateTime.class),
                source(rs), rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                readList(rs.getString("checklist"), new TypeReference<List<ChecklistItem>>() { }),
                readList(rs.getString("source_references"), new TypeReference<List<SourceReference>>() { }),
                rs.getObject("deleted_at", OffsetDateTime.class));
    }

    private SourceReference source(ResultSet rs) throws SQLException {
        String system = rs.getString("source_system");
        if (system == null) return null;
        String obligation = rs.getString("obligation_key");
        return new SourceReference(system, rs.getString("source_reference"),
                obligation == null || obligation.isEmpty() ? null : obligation);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? List.of() : value); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    private <T> List<T> readList(String value, TypeReference<List<T>> type) {
        if (value == null) return List.of();
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    private void requireChanged(int changed) {
        if (changed != 1) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }

    public record TaskRow(UUID taskId, String title, String description, Status status,
                          Priority priority, OffsetDateTime dueAt, SourceReference sourceReference,
                          long version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          OffsetDateTime completedAt, List<ChecklistItem> checklist,
                          List<SourceReference> sourceReferences, OffsetDateTime deletedAt) {
        public TaskRow {
            checklist = checklist == null ? List.of() : List.copyOf(checklist);
            sourceReferences = sourceReferences == null
                    ? sourceReference == null ? List.of() : List.of(sourceReference)
                    : List.copyOf(sourceReferences);
        }
        public TaskRow(UUID taskId, String title, String description, Status status,
                       Priority priority, OffsetDateTime dueAt, SourceReference sourceReference,
                       long version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       OffsetDateTime completedAt) {
            this(taskId, title, description, status, priority, dueAt, sourceReference,
                    version, createdAt, updatedAt, completedAt, null, null, null);
        }
    }
    public record PlanRow(LocalDate date, long version, List<SourceReference> items,
                          OffsetDateTime updatedAt) { }
    public record Receipt(String operation, String target, String fingerprint, String payload) { }
}
