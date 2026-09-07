package com.dwp.services.platform.workhub.assignment;

import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.Priority;
import java.time.OffsetDateTime;
import java.util.UUID;

final class MeetingFollowupProtocol {
    private MeetingFollowupProtocol() { }
    static final String PATH = "/internal/v1/meeting-followups/resolve";
    static final String ASSERTION_HEADER = "X-DWP-Work-Assertion";
    static final String ISSUER = "dwp-platform-work";
    static final String AUDIENCE = "dwp-meeting-followup-source";
    enum Operation { READ, CREATE, REASSIGN }
    enum OriginalAccess { AVAILABLE, FORBIDDEN, DELETED, UNAVAILABLE }
    record Source(UUID meetingId, UUID reportId, UUID candidateId) { }
    record Request(long tenantId, long actorUserId, Source source, Operation action,
                   Long targetAssigneeUserId, Long expectedSourceVersion) { }
    record ApprovedTask(long assigneeUserId, String title, String description, Priority priority,
                        OffsetDateTime dueAt) { }
    record Response(Long tenantId, Long actorUserId, Source source, Operation action,
                    Boolean allowed, String denialCode, Long sourceVersion,
                    OriginalAccess originalAccess, Boolean canAssign, ApprovedTask approvedTask) { }
}
