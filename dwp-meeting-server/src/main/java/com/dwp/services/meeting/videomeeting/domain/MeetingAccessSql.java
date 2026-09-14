package com.dwp.services.meeting.videomeeting.domain;

/** Common actor-access predicate; each caller independently binds its tenant and actor. */
final class MeetingAccessSql {

    static final String ACCESS_PREDICATE = """
            (meeting.organizer_user_id = :userId
             OR EXISTS (
                 SELECT 1 FROM vm_meeting_participants access
                  WHERE access.tenant_id = meeting.tenant_id
                    AND access.meeting_id = meeting.meeting_id
                    AND access.user_id = :userId
                    AND access.attendance_state <> 'DENIED'))
            """;

    private MeetingAccessSql() {
    }
}
