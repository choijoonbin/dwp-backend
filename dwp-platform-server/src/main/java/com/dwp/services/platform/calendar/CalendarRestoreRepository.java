package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class CalendarRestoreRepository {

    private final JdbcTemplate jdbc;

    CalendarRestoreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void cancelResourceBookingsForTrash(Long tenantId, Long actorId, UUID eventId) {
        jdbc.update(
                CalendarRestoreSql.TRASH_RESOURCE_BOOKINGS,
                actorId,
                tenantId,
                eventId);
    }

    List<RestoreBookingRow> restorableResourceBookings(Long tenantId, UUID eventId) {
        return jdbc.query(
                CalendarRestoreSql.RESTORABLE_RESOURCE_BOOKINGS
                        + " ORDER BY booking.resource_id",
                (result, ignored) -> restoreBooking(result),
                tenantId,
                eventId);
    }

    Optional<RestoreBookingRow> restorableResourceBookingForUpdate(
            Long tenantId, UUID eventId, UUID resourceId) {
        return jdbc.query(
                        CalendarRestoreSql.RESTORABLE_RESOURCE_BOOKING_FOR_UPDATE,
                        (result, ignored) -> restoreBooking(result),
                        tenantId,
                        eventId,
                        resourceId)
                .stream()
                .findFirst();
    }

    Optional<Long> rebookResource(
            Long tenantId,
            Long actorId,
            UUID eventId,
            UUID resourceId,
            long bookingVersion,
            boolean approvalRequired) {
        return jdbc.query(
                        CalendarRestoreSql.REBOOK_RESOURCE,
                        (result, ignored) -> result.getLong("version"),
                        approvalRequired ? "PENDING" : "CONFIRMED",
                        actorId,
                        actorId,
                        tenantId,
                        eventId,
                        resourceId,
                        bookingVersion)
                .stream()
                .findFirst();
    }

    void lockResourceRebookCommand(
            Long tenantId, Long actorId, UUID idempotencyKey) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(
                        1,
                        "calendar-resource-restore:" + tenantId + ":" + actorId + ":"
                                + idempotencyKey),
                result -> null);
    }

    Optional<ResourceRebookCommandRow> resourceRebookCommand(
            Long tenantId, Long actorId, UUID idempotencyKey) {
        return jdbc.query(
                        CalendarRestoreSql.RESOURCE_REBOOK_COMMAND,
                        (result, ignored) -> new ResourceRebookCommandRow(
                                result.getObject("event_id", UUID.class),
                                result.getObject("resource_id", UUID.class),
                                result.getString("request_fingerprint"),
                                CalendarRecoveryDtos.RestoreOutcome.valueOf(result.getString("outcome")),
                                CalendarRecoveryDtos.RestoreReason.valueOf(result.getString("reason_code")),
                                result.getLong("event_version"),
                                result.getLong("booking_version")),
                        tenantId,
                        actorId,
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    void saveResourceRebookCommand(
            Long tenantId,
            Long actorId,
            UUID idempotencyKey,
            UUID eventId,
            UUID resourceId,
            String requestFingerprint,
            CalendarRecoveryDtos.RestoreOutcome outcome,
            CalendarRecoveryDtos.RestoreReason reason,
            long eventVersion,
            long bookingVersion) {
        jdbc.update(
                CalendarRestoreSql.INSERT_RESOURCE_REBOOK_COMMAND,
                tenantId,
                actorId,
                idempotencyKey,
                eventId,
                resourceId,
                requestFingerprint,
                outcome.name(),
                reason.name(),
                eventVersion,
                bookingVersion);
    }

    private RestoreBookingRow restoreBooking(ResultSet result) throws SQLException {
        return new RestoreBookingRow(
                result.getObject("resource_id", UUID.class),
                result.getString("booking_status"),
                result.getLong("booking_version"),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getString("time_zone"),
                CalendarTypes.EventType.valueOf(result.getString("event_type")),
                result.getBoolean("all_day"),
                CalendarTypes.RecurrencePattern.valueOf(
                        result.getString("recurrence_pattern")),
                result.getInt("recurrence_interval"),
                result.getObject("recurrence_until", LocalDate.class),
                CalendarTypes.ResourceType.valueOf(result.getString("resource_type")),
                result.getString("resource_time_zone"),
                result.getBoolean("approval_required"),
                CalendarTypes.ResourceState.valueOf(result.getString("lifecycle_state")),
                result.getInt("capacity"),
                result.getLong("attendee_count"));
    }

    record RestoreBookingRow(
            UUID resourceId,
            String bookingStatus,
            long bookingVersion,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            CalendarTypes.EventType eventType,
            boolean allDay,
            CalendarTypes.RecurrencePattern recurrence,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
            CalendarTypes.ResourceType resourceType,
            String resourceTimeZone,
            boolean approvalRequired,
            CalendarTypes.ResourceState resourceState,
            int capacity,
            long attendeeCount) {
    }

    record ResourceRebookCommandRow(
            UUID eventId,
            UUID resourceId,
            String requestFingerprint,
            CalendarRecoveryDtos.RestoreOutcome outcome,
            CalendarRecoveryDtos.RestoreReason reason,
            long eventVersion,
            long bookingVersion) {
    }
}
