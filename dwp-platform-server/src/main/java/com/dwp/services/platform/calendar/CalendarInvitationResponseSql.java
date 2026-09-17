package com.dwp.services.platform.calendar;

final class CalendarInvitationResponseSql {

    private CalendarInvitationResponseSql() {
    }

    static final String RECEIPT = """
        SELECT actor_person_public_id, event_id, request_fingerprint,
               expected_event_version, result_event_version,
               result_response_status, result_attendee_response_version
          FROM cal_invitation_response_commands
         WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
        """;

    static final String LOCK_EVENT_AND_ATTENDEE = """
        SELECT attendee.attendee_id, event.version AS event_version,
               attendee.response_status, attendee.response_version
          FROM cal_events event
          JOIN cal_event_attendees attendee
            ON attendee.tenant_id = event.tenant_id
           AND attendee.event_id = event.event_id
         WHERE event.tenant_id = ? AND event.event_id = ?
           AND event.status <> 'CANCELLED' AND event.deleted_at IS NULL
           AND (
               attendee.attendee_person_public_id = ?
               OR (attendee.attendee_person_public_id IS NULL
                   AND attendee.attendee_user_id = ?)
           )
         ORDER BY CASE WHEN attendee.attendee_person_public_id = ? THEN 0 ELSE 1 END,
                  attendee.updated_at DESC
         LIMIT 1
         FOR UPDATE OF event, attendee
        """;

    static final String UPDATE_ATTENDEE = """
        UPDATE cal_event_attendees
           SET response_status = ?, responded_at = CURRENT_TIMESTAMP,
               response_version = response_version + 1,
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = ? AND attendee_id = ? AND response_version = ?
         RETURNING response_version
        """;

    static final String UPDATE_EVENT_VERSION = """
        UPDATE cal_events
           SET version = version + 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = ? AND event_id = ? AND version = ?
         RETURNING version
        """;

    static final String INSERT_RECEIPT = """
        INSERT INTO cal_invitation_response_commands (
            tenant_id, actor_user_id, actor_person_public_id, idempotency_key,
            event_id, request_fingerprint, expected_event_version,
            result_event_version, result_response_status,
            result_attendee_response_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
}
