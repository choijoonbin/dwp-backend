package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarReminderDtos.ReminderChannel;
import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;

@Repository
class CalendarReminderRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    CalendarReminderRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean identityMatches(long tenantId, long userId, UUID personPublicId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM cal_identity_links
                     WHERE tenant_id = ? AND user_id = ? AND person_public_id = ?)
                """, Boolean.class, tenantId, userId, personPublicId));
    }

    Optional<ReminderSetRow> reminderSet(
            long tenantId,
            UUID eventId,
            long ownerUserId,
            UUID ownerPersonPublicId) {
        return jdbc.query("""
                SELECT event_version, version, reminder_state, updated_at
                  FROM cal_event_reminder_sets
                 WHERE tenant_id = ? AND event_id = ?
                   AND owner_user_id = ? AND owner_person_public_id = ?
                """, (result, ignored) -> new ReminderSetRow(
                        result.getLong("event_version"),
                        result.getLong("version"),
                        result.getString("reminder_state"),
                        result.getObject("updated_at", OffsetDateTime.class)),
                tenantId, eventId, ownerUserId, ownerPersonPublicId).stream().findFirst();
    }

    List<DefinitionRow> definitions(
            long tenantId,
            UUID eventId,
            long ownerUserId,
            UUID ownerPersonPublicId,
            long setVersion) {
        return jdbc.query("""
                SELECT reminder_id, offset_minutes, channel,
                       next_occurrence_starts_at, next_due_at
                  FROM cal_event_reminder_definitions
                 WHERE tenant_id = ? AND event_id = ?
                   AND owner_user_id = ? AND owner_person_public_id = ?
                   AND set_version = ?
                 ORDER BY offset_minutes, channel, reminder_id
                """, (result, ignored) -> new DefinitionRow(
                        result.getObject("reminder_id", UUID.class),
                        result.getInt("offset_minutes"),
                        ReminderChannel.valueOf(result.getString("channel")),
                        result.getObject("next_occurrence_starts_at", OffsetDateTime.class),
                        result.getObject("next_due_at", OffsetDateTime.class)),
                tenantId, eventId, ownerUserId, ownerPersonPublicId, setVersion);
    }

    Optional<EventLock> lockEvent(long tenantId, UUID eventId) {
        return jdbc.query("""
                SELECT version, status
                  FROM cal_events
                 WHERE tenant_id = ? AND event_id = ? AND deleted_at IS NULL
                 FOR UPDATE
                """, (result, ignored) -> new EventLock(
                        result.getLong("version"), result.getString("status")),
                tenantId, eventId).stream().findFirst();
    }

    Optional<Long> lockSetVersion(
            long tenantId,
            UUID eventId,
            long ownerUserId,
            UUID ownerPersonPublicId) {
        return jdbc.query("""
                SELECT version
                  FROM cal_event_reminder_sets
                 WHERE tenant_id = ? AND event_id = ?
                   AND owner_user_id = ? AND owner_person_public_id = ?
                 FOR UPDATE
                """, (result, ignored) -> result.getLong("version"),
                tenantId, eventId, ownerUserId, ownerPersonPublicId).stream().findFirst();
    }

    void insertSet(
            long tenantId,
            UUID eventId,
            long ownerUserId,
            UUID ownerPersonPublicId,
            long eventVersion,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO cal_event_reminder_sets (
                    tenant_id, event_id, owner_user_id, owner_person_public_id,
                    event_version, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                """, tenantId, eventId, ownerUserId, ownerPersonPublicId,
                eventVersion, now, now);
    }

    void updateSet(
            long tenantId,
            UUID eventId,
            long ownerUserId,
            UUID ownerPersonPublicId,
            long eventVersion,
            long expectedVersion,
            OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE cal_event_reminder_sets
                   SET event_version = ?, version = version + 1,
                       reminder_state = 'ACTIVE', invalidated_at = NULL,
                       invalidation_reason = NULL, updated_at = ?
                 WHERE tenant_id = ? AND event_id = ?
                   AND owner_user_id = ? AND owner_person_public_id = ?
                   AND version = ?
                """, eventVersion, now, tenantId, eventId,
                ownerUserId, ownerPersonPublicId, expectedVersion);
        if (updated != 1) throw new IllegalStateException("The Calendar reminder set changed.");
    }

    void deleteDefinitions(
            long tenantId,
            UUID eventId,
            long ownerUserId,
            UUID ownerPersonPublicId) {
        jdbc.update("""
                DELETE FROM cal_event_reminder_definitions
                 WHERE tenant_id = ? AND event_id = ?
                   AND owner_user_id = ? AND owner_person_public_id = ?
                """, tenantId, eventId, ownerUserId, ownerPersonPublicId);
    }

    void insertDefinition(
            UUID reminderId,
            long tenantId,
            UUID eventId,
            long ownerUserId,
            UUID ownerPersonPublicId,
            long setVersion,
            int offsetMinutes,
            ReminderChannel channel,
            CalendarReminderSchedule.Occurrence next,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO cal_event_reminder_definitions (
                    reminder_id, tenant_id, event_id,
                    owner_user_id, owner_person_public_id,
                    set_version, offset_minutes, channel,
                    next_occurrence_starts_at, next_due_at,
                    delivery_sequence, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, reminderId, tenantId, eventId, ownerUserId, ownerPersonPublicId,
                setVersion, offsetMinutes, channel.name(),
                next == null ? null : next.startsAt(),
                next == null ? null : next.dueAt(), now, now);
    }

    void auditReplacement(
            CalendarReminderAccess.Actor actor,
            UUID eventId,
            String correlationId,
            long beforeVersion,
            long afterVersion,
            int beforeCount,
            int afterCount) {
        jdbc.update("""
                INSERT INTO cal_audit_events (
                    audit_event_id, tenant_id, event_id, action,
                    actor_user_id, correlation_id,
                    before_snapshot, after_snapshot, occurred_at)
                VALUES (?, ?, ?, 'calendar.event.reminders.replaced',
                        ?, ?, ?::jsonb, ?::jsonb, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), actor.tenantId(), eventId, actor.userId(), correlationId,
                encode(Map.of("version", beforeVersion, "count", beforeCount)),
                encode(Map.of("version", afterVersion, "count", afterCount)));
    }

    List<DueReminder> claimDue(OffsetDateTime now, int limit) {
        return jdbc.query("""
                SELECT definition.reminder_id,
                       definition.tenant_id,
                       definition.event_id,
                       definition.owner_user_id,
                       definition.channel,
                       definition.offset_minutes,
                       definition.set_version,
                       definition.delivery_sequence,
                       definition.next_occurrence_starts_at,
                       definition.next_due_at,
                       event.version AS event_version,
                       event.title,
                       event.starts_at,
                       event.time_zone,
                       event.recurrence_pattern,
                       event.recurrence_interval,
                       event.recurrence_until,
                       event.recurrence_days,
                       event.recurrence_count
                  FROM cal_event_reminder_definitions definition
                  JOIN cal_event_reminder_sets reminder_set
                    ON reminder_set.tenant_id = definition.tenant_id
                   AND reminder_set.event_id = definition.event_id
                   AND reminder_set.owner_user_id = definition.owner_user_id
                   AND reminder_set.owner_person_public_id = definition.owner_person_public_id
                   AND reminder_set.version = definition.set_version
                   AND reminder_set.reminder_state = 'ACTIVE'
                  JOIN cal_events event
                    ON event.tenant_id = definition.tenant_id
                   AND event.event_id = definition.event_id
                   AND event.version = reminder_set.event_version
                 WHERE definition.next_due_at IS NOT NULL
                   AND definition.next_due_at <= ?
                   AND event.status <> 'CANCELLED'
                   AND event.deleted_at IS NULL
                 ORDER BY definition.next_due_at,
                          definition.tenant_id, definition.reminder_id
                 LIMIT ?
                 FOR UPDATE OF definition, event SKIP LOCKED
                """, (result, ignored) -> new DueReminder(
                        result.getObject("reminder_id", UUID.class),
                        result.getLong("tenant_id"),
                        result.getObject("event_id", UUID.class),
                        result.getLong("owner_user_id"),
                        ReminderChannel.valueOf(result.getString("channel")),
                        result.getInt("offset_minutes"),
                        result.getLong("set_version"),
                        result.getLong("event_version"),
                        result.getLong("delivery_sequence"),
                        result.getObject("next_occurrence_starts_at", OffsetDateTime.class),
                        result.getObject("next_due_at", OffsetDateTime.class),
                        result.getString("title"),
                        new CalendarReminderSchedule.EventSchedule(
                                result.getObject("starts_at", OffsetDateTime.class),
                                result.getString("time_zone"),
                                RecurrencePattern.valueOf(
                                        result.getString("recurrence_pattern")),
                                result.getInt("recurrence_interval"),
                                result.getObject("recurrence_until", LocalDate.class),
                                recurrenceDays(result.getArray("recurrence_days")),
                                nullableInteger(result, "recurrence_count"))),
                now, limit);
    }

    private static List<DayOfWeek> recurrenceDays(java.sql.Array value)
            throws java.sql.SQLException {
        if (value == null) return List.of();
        return java.util.Arrays.stream((Object[]) value.getArray())
                .map(item -> DayOfWeek.of(((Number) item).intValue()))
                .distinct().sorted().toList();
    }

    private static Integer nullableInteger(
            java.sql.ResultSet result, String column) throws java.sql.SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    boolean beginDelivery(DueReminder reminder, OffsetDateTime claimedAt) {
        return jdbc.update("""
                INSERT INTO cal_event_reminder_delivery_ledger (
                    ledger_id, tenant_id, event_id, owner_user_id,
                    reminder_id, set_version, event_version,
                    delivery_sequence, occurrence_starts_at, due_at,
                    channel, decision, claimed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLAIMED', ?)
                ON CONFLICT (
                    tenant_id, reminder_id, set_version, occurrence_starts_at)
                DO NOTHING
                """, UUID.randomUUID(), reminder.tenantId(), reminder.eventId(),
                reminder.ownerUserId(), reminder.reminderId(), reminder.setVersion(),
                reminder.eventVersion(), Math.addExact(reminder.deliverySequence(), 1L),
                reminder.occurrenceStartsAt(), reminder.dueAt(), reminder.channel().name(),
                claimedAt) == 1;
    }

    void completeDelivery(DueReminder reminder, UUID domainEventId, OffsetDateTime completedAt) {
        int updated = jdbc.update("""
                UPDATE cal_event_reminder_delivery_ledger
                   SET decision = 'EMITTED', domain_event_id = ?, completed_at = ?
                 WHERE tenant_id = ? AND reminder_id = ? AND set_version = ?
                   AND occurrence_starts_at = ?
                   AND decision = 'CLAIMED' AND completed_at IS NULL
                """, domainEventId, completedAt, reminder.tenantId(), reminder.reminderId(),
                reminder.setVersion(), reminder.occurrenceStartsAt());
        if (updated != 1) {
            throw new IllegalStateException("The Calendar reminder delivery claim was lost.");
        }
    }

    void advance(
            DueReminder reminder,
            CalendarReminderSchedule.Occurrence next,
            OffsetDateTime updatedAt) {
        int updated = jdbc.update("""
                UPDATE cal_event_reminder_definitions definition
                   SET next_occurrence_starts_at = ?, next_due_at = ?,
                       delivery_sequence = delivery_sequence + 1,
                       updated_at = ?
                  FROM cal_event_reminder_sets reminder_set,
                       cal_events event
                 WHERE definition.reminder_id = ?
                   AND definition.tenant_id = ?
                   AND definition.event_id = ?
                   AND definition.owner_user_id = ?
                   AND definition.set_version = ?
                   AND definition.delivery_sequence = ?
                   AND definition.next_occurrence_starts_at = ?
                   AND reminder_set.tenant_id = definition.tenant_id
                   AND reminder_set.event_id = definition.event_id
                   AND reminder_set.owner_user_id = definition.owner_user_id
                   AND reminder_set.owner_person_public_id = definition.owner_person_public_id
                   AND reminder_set.version = definition.set_version
                   AND reminder_set.reminder_state = 'ACTIVE'
                   AND reminder_set.event_version = ?
                   AND event.tenant_id = definition.tenant_id
                   AND event.event_id = definition.event_id
                   AND event.version = reminder_set.event_version
                   AND event.status <> 'CANCELLED'
                   AND event.deleted_at IS NULL
                """, next == null ? null : next.startsAt(),
                next == null ? null : next.dueAt(), updatedAt,
                reminder.reminderId(), reminder.tenantId(), reminder.eventId(),
                reminder.ownerUserId(), reminder.setVersion(),
                reminder.deliverySequence(), reminder.occurrenceStartsAt(),
                reminder.eventVersion());
        if (updated != 1) {
            throw new IllegalStateException("The Calendar reminder schedule changed while claimed.");
        }
    }

    int purgeDeliveryLedger(OffsetDateTime cutoff, int limit) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT ledger.ledger_id
                      FROM cal_event_reminder_delivery_ledger ledger
                     WHERE ledger.completed_at IS NOT NULL
                       AND ledger.completed_at < ?
                     ORDER BY ledger.completed_at, ledger.ledger_id
                     LIMIT ?
                     FOR UPDATE OF ledger SKIP LOCKED
                )
                DELETE FROM cal_event_reminder_delivery_ledger ledger
                 USING expired
                 WHERE ledger.ledger_id = expired.ledger_id
                """, cutoff, limit);
    }

    int purgeInvalidSets(OffsetDateTime cutoff, int limit) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT reminder_set.tenant_id, reminder_set.event_id,
                           reminder_set.owner_user_id,
                           reminder_set.owner_person_public_id
                      FROM cal_event_reminder_sets reminder_set
                     WHERE reminder_set.updated_at < ?
                       AND reminder_set.reminder_state = 'INVALIDATED'
                       AND reminder_set.invalidated_at < ?
                     ORDER BY reminder_set.invalidated_at,
                              reminder_set.tenant_id, reminder_set.event_id
                     LIMIT ?
                     FOR UPDATE OF reminder_set SKIP LOCKED
                )
                DELETE FROM cal_event_reminder_sets reminder_set
                 USING expired
                 WHERE reminder_set.tenant_id = expired.tenant_id
                   AND reminder_set.event_id = expired.event_id
                   AND reminder_set.owner_user_id = expired.owner_user_id
                   AND reminder_set.owner_person_public_id = expired.owner_person_public_id
                """, cutoff, cutoff, limit);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Calendar reminder audit data is invalid.", exception);
        }
    }

    record ReminderSetRow(
            long eventVersion,
            long version,
            String state,
            OffsetDateTime updatedAt) {
    }

    record DefinitionRow(
            UUID reminderId,
            int offsetMinutes,
            ReminderChannel channel,
            OffsetDateTime nextOccurrenceStartsAt,
            OffsetDateTime nextDueAt) {
    }

    record EventLock(long version, String status) {
    }

    record DueReminder(
            UUID reminderId,
            long tenantId,
            UUID eventId,
            long ownerUserId,
            ReminderChannel channel,
            int offsetMinutes,
            long setVersion,
            long eventVersion,
            long deliverySequence,
            OffsetDateTime occurrenceStartsAt,
            OffsetDateTime dueAt,
            String eventTitle,
            CalendarReminderSchedule.EventSchedule schedule) {
    }
}
