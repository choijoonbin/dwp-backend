package com.dwp.services.platform.workhub.assignment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.Priority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workhub.assignment.WorkAssignmentDtos.*;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentSourceAuthority.ConfirmedTask;

@Repository
public class WorkAssignmentRepository {
    private final JdbcTemplate jdbc;

    public WorkAssignmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Every mutation takes the actor/command lock before a candidate or assignment lock. */
    public void lockCommand(AccessContext context, UUID commandId) {
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        lock("command:" + context.tenantId() + ":" + context.userId() + ":" + commandId);
    }

    public void lockCandidate(AccessContext context, SourceIdentity source) {
        lock("candidate:" + context.tenantId() + ":" + source.sourceSystem() + ":" + source.candidateId());
    }

    private void lock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 726226))",
                result -> null, "work-assignment:" + key);
    }

    public Optional<AssignmentRow> find(AccessContext context, UUID assignmentId, boolean forUpdate) {
        return jdbc.query("""
                SELECT * FROM work_assignments WHERE tenant_id = ? AND assignment_id = ?
                AND (created_by_user_id = ? OR assignee_user_id = ?)
                """ + (forUpdate ? " FOR UPDATE" : ""), this::assignment,
                context.tenantId(), assignmentId, context.userId(), context.userId()).stream().findFirst();
    }

    public Optional<AssignmentRow> findBySource(AccessContext context, SourceIdentity source) {
        return jdbc.query("""
                SELECT * FROM work_assignments WHERE tenant_id = ? AND source_system = ?
                AND meeting_id = ? AND report_id = ? AND candidate_id = ?
                AND (created_by_user_id = ? OR assignee_user_id = ?)
                """, this::assignment, context.tenantId(), source.sourceSystem().name(),
                source.meetingId(), source.reportId(), source.candidateId(), context.userId(), context.userId())
                .stream().findFirst();
    }

    public boolean candidateExists(AccessContext context, SourceIdentity source) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM work_assignments
                WHERE tenant_id = ? AND source_system = ? AND candidate_id = ?)
                """, Boolean.class, context.tenantId(), source.sourceSystem().name(), source.candidateId()));
    }

    public List<AssignmentRow> list(AccessContext context, Scope scope, int page, int size) {
        return jdbc.query("SELECT * FROM work_assignments WHERE tenant_id = ? AND "
                        + scopeColumn(scope) + " = ? ORDER BY updated_at DESC, assignment_id LIMIT ? OFFSET ?",
                this::assignment, context.tenantId(), context.userId(), size, (long) page * size);
    }

    public long count(AccessContext context, Scope scope) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM work_assignments WHERE tenant_id = ? AND "
                + scopeColumn(scope) + " = ?", Long.class, context.tenantId(), context.userId());
        return count == null ? 0 : count;
    }

    private String scopeColumn(Scope scope) {
        return switch (scope) {
            case ASSIGNED_TO_ME -> "assignee_user_id";
            case ASSIGNED_BY_ME -> "created_by_user_id";
        };
    }

    public AssignmentRow insert(AccessContext context, UUID assignmentId, ConfirmedTask task) {
        SourceIdentity source = task.source();
        jdbc.update("""
                INSERT INTO work_assignments
                (assignment_id, tenant_id, created_by_user_id, assigned_by_user_id, assignee_user_id,
                 source_system, meeting_id, report_id, candidate_id, confirmed_source_version,
                 title, description, priority, due_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, assignmentId, context.tenantId(), context.userId(), context.userId(), task.assigneeUserId(),
                source.sourceSystem().name(), source.meetingId(), source.reportId(), source.candidateId(),
                task.sourceVersion(), task.title().trim(), task.description(), task.priority().name(), task.dueAt());
        return find(context, assignmentId, false).orElseThrow();
    }

    public AssignmentRow transition(AccessContext context, AssignmentRow before,
                                    AssignmentState assignmentState, WorkState workState) {
        requireChanged(jdbc.update("""
                UPDATE work_assignments SET assignment_state = ?, work_state = ?,
                accepted_at = CASE WHEN ? <> 'ACCEPTED' THEN NULL
                    ELSE COALESCE(accepted_at, CURRENT_TIMESTAMP) END,
                completed_at = CASE WHEN ? = 'COMPLETED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND assignment_id = ? AND version = ? AND assignment_revision = ?
                AND (created_by_user_id = ? OR assignee_user_id = ?)
                """, assignmentState.name(), workState.name(), assignmentState.name(), workState.name(),
                context.tenantId(), before.assignmentId(), before.version(), before.assignmentRevision(),
                context.userId(), context.userId()));
        return find(context, before.assignmentId(), false).orElseThrow();
    }

    public AssignmentRow reassign(AccessContext context, AssignmentRow before, long assigneeUserId) {
        requireChanged(jdbc.update("""
                UPDATE work_assignments SET assigned_by_user_id = ?, assignee_user_id = ?,
                assignment_state = 'PENDING', work_state = 'OPEN', assignment_revision = assignment_revision + 1,
                version = version + 1, accepted_at = NULL, completed_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND assignment_id = ? AND created_by_user_id = ?
                AND version = ? AND assignment_revision = ?
                """, context.userId(), assigneeUserId, context.tenantId(), before.assignmentId(),
                context.userId(), before.version(), before.assignmentRevision()));
        return find(context, before.assignmentId(), false).orElseThrow();
    }

    public void appendEvent(AccessContext context, AssignmentRow row, String action, String reasonCode, UUID auditId) {
        jdbc.update("""
                INSERT INTO work_assignment_events
                (event_id, tenant_id, assignment_id, action, actor_user_id, assignee_user_id,
                 assignment_state, work_state, assignment_revision, version, reason_code, audit_record_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), context.tenantId(), row.assignmentId(), action, context.userId(),
                row.assigneeUserId(), row.assignmentState().name(), row.workState().name(),
                row.assignmentRevision(), row.version(), reasonCode, auditId);
    }

    public List<Event> events(AccessContext context, UUID assignmentId, long afterVersion, int limit) {
        return jdbc.query("""
                SELECT e.* FROM work_assignment_events e JOIN work_assignments a
                ON a.tenant_id = e.tenant_id AND a.assignment_id = e.assignment_id
                WHERE e.tenant_id = ? AND e.assignment_id = ? AND e.version > ?
                AND (a.created_by_user_id = ? OR a.assignee_user_id = ?)
                ORDER BY e.version ASC LIMIT ?
                """, (rs, index) -> new Event(rs.getObject("event_id", UUID.class),
                        rs.getObject("assignment_id", UUID.class), rs.getString("action"),
                        rs.getLong("actor_user_id"), rs.getLong("assignee_user_id"),
                        AssignmentState.valueOf(rs.getString("assignment_state")),
                        WorkState.valueOf(rs.getString("work_state")), rs.getLong("assignment_revision"),
                        rs.getLong("version"), rs.getString("reason_code"),
                        rs.getObject("occurred_at", OffsetDateTime.class), rs.getObject("audit_record_id", UUID.class)),
                context.tenantId(), assignmentId, afterVersion, context.userId(), context.userId(), limit);
    }

    public Optional<ReceiptRow> receipt(AccessContext context, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM work_assignment_command_receipts
                WHERE tenant_id = ? AND actor_user_id = ? AND command_id = ?
                """, this::receiptRow, context.tenantId(), context.userId(), commandId).stream().findFirst();
    }

    public ReceiptRow recordReceipt(AccessContext context, UUID commandId, String operation, String target,
                                    String fingerprint, AssignmentRow row) {
        jdbc.update("""
                INSERT INTO work_assignment_command_receipts
                (tenant_id, actor_user_id, command_id, assignment_id, operation, target_key,
                 request_fingerprint, applied_version, applied_assignment_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, context.tenantId(), context.userId(), commandId, row.assignmentId(), operation, target,
                fingerprint, row.version(), row.assignmentRevision());
        return receipt(context, commandId).orElseThrow();
    }

    private AssignmentRow assignment(ResultSet rs, int index) throws SQLException {
        return new AssignmentRow(rs.getObject("assignment_id", UUID.class), rs.getLong("created_by_user_id"),
                rs.getLong("assigned_by_user_id"), rs.getLong("assignee_user_id"),
                new SourceIdentity(SourceSystem.valueOf(rs.getString("source_system")),
                        rs.getObject("meeting_id", UUID.class), rs.getObject("report_id", UUID.class),
                        rs.getObject("candidate_id", UUID.class)),
                rs.getLong("confirmed_source_version"), rs.getString("title"), rs.getString("description"),
                Priority.valueOf(rs.getString("priority")), rs.getObject("due_at", OffsetDateTime.class),
                AssignmentState.valueOf(rs.getString("assignment_state")), WorkState.valueOf(rs.getString("work_state")),
                rs.getLong("assignment_revision"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("accepted_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class));
    }

    private ReceiptRow receiptRow(ResultSet rs, int index) throws SQLException {
        return new ReceiptRow(rs.getObject("command_id", UUID.class), rs.getObject("assignment_id", UUID.class),
                rs.getString("operation"), rs.getString("target_key"), rs.getString("request_fingerprint"),
                rs.getLong("applied_version"), rs.getLong("applied_assignment_revision"),
                rs.getObject("applied_at", OffsetDateTime.class));
    }

    private void requireChanged(int changed) {
        if (changed != 1) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }

    public record AssignmentRow(UUID assignmentId, long createdByUserId, long assignedByUserId,
                                long assigneeUserId, SourceIdentity source, long confirmedSourceVersion,
                                String title, String description, Priority priority, OffsetDateTime dueAt,
                                AssignmentState assignmentState, WorkState workState,
                                long assignmentRevision, long version, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt, OffsetDateTime acceptedAt, OffsetDateTime completedAt) { }
    public record ReceiptRow(UUID commandId, UUID assignmentId, String operation, String target,
                             String fingerprint, long appliedVersion, long appliedAssignmentRevision,
                             OffsetDateTime appliedAt) { }
}
