package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.ResponseStatus;

final class CalendarInvitationResponseRepository {

    private final JdbcTemplate jdbc;

    CalendarInvitationResponseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lockCommand(Long tenantId, Long actorId, UUID idempotencyKey) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(
                        1, "calendar-response:" + tenantId + ":" + actorId + ":" + idempotencyKey),
                result -> null);
    }

    Optional<ResponseReceipt> receipt(
            Long tenantId, Long actorId, UUID idempotencyKey) {
        return jdbc.query(
                CalendarInvitationResponseSql.RECEIPT,
                (result, ignored) -> new ResponseReceipt(
                        result.getObject("actor_person_public_id", UUID.class),
                        result.getObject("event_id", UUID.class),
                        result.getString("request_fingerprint"),
                        result.getLong("expected_event_version"),
                        result.getLong("result_event_version"),
                        ResponseStatus.valueOf(result.getString("result_response_status")),
                        result.getLong("result_attendee_response_version")),
                tenantId, actorId, idempotencyKey).stream().findFirst();
    }

    Optional<LockedResponseState> lockState(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            UUID eventId) {
        return jdbc.query(
                CalendarInvitationResponseSql.LOCK_EVENT_AND_ATTENDEE,
                (result, ignored) -> new LockedResponseState(
                        result.getObject("attendee_id", UUID.class),
                        result.getLong("event_version"),
                        ResponseStatus.valueOf(result.getString("response_status")),
                        result.getLong("response_version")),
                tenantId, eventId, actorPersonPublicId, actorId,
                actorPersonPublicId).stream().findFirst();
    }

    long updateAttendee(
            Long tenantId,
            UUID attendeeId,
            long expectedResponseVersion,
            ResponseStatus response) {
        return jdbc.query(
                CalendarInvitationResponseSql.UPDATE_ATTENDEE,
                (result, ignored) -> result.getLong("response_version"),
                response.name(), tenantId, attendeeId, expectedResponseVersion).stream()
                .findFirst()
                .orElse(-1L);
    }

    long updateEventVersion(
            Long tenantId,
            Long actorId,
            UUID eventId,
            long expectedEventVersion) {
        return jdbc.query(
                CalendarInvitationResponseSql.UPDATE_EVENT_VERSION,
                (result, ignored) -> result.getLong("version"),
                actorId, tenantId, eventId, expectedEventVersion).stream()
                .findFirst()
                .orElse(-1L);
    }

    int insertReceipt(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            UUID idempotencyKey,
            UUID eventId,
            String requestFingerprint,
            long expectedEventVersion,
            long resultEventVersion,
            ResponseStatus resultResponse,
            long resultAttendeeResponseVersion) {
        return jdbc.update(
                CalendarInvitationResponseSql.INSERT_RECEIPT,
                tenantId, actorId, actorPersonPublicId, idempotencyKey,
                eventId, requestFingerprint, expectedEventVersion,
                resultEventVersion, resultResponse.name(), resultAttendeeResponseVersion);
    }

    record LockedResponseState(
            UUID attendeeId,
            long eventVersion,
            ResponseStatus response,
            long responseVersion) {
    }

    record ResponseReceipt(
            UUID actorPersonPublicId,
            UUID eventId,
            String requestFingerprint,
            long expectedEventVersion,
            long resultEventVersion,
            ResponseStatus resultResponse,
            long resultAttendeeResponseVersion) {
    }
}
