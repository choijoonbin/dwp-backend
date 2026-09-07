package com.dwp.services.meeting.videomeeting.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class MeetingFollowupSourceDtos {

    private MeetingFollowupSourceDtos() {
    }

    public enum Operation {
        READ, CREATE, REASSIGN
    }

    public enum OriginalAccess {
        AVAILABLE, FORBIDDEN, DELETED, UNAVAILABLE
    }

    public record Source(UUID meetingId, UUID reportId, UUID candidateId) {
    }

    public record Request(
            long tenantId,
            long actorUserId,
            Source source,
            Operation action,
            Long targetAssigneeUserId,
            Long expectedSourceVersion) {
    }

    public record ApprovedTask(
            long assigneeUserId,
            String title,
            String description,
            String priority,
            OffsetDateTime dueAt) {
    }

    public record Response(
            Long tenantId,
            Long actorUserId,
            Source source,
            Operation action,
            Boolean allowed,
            String denialCode,
            Long sourceVersion,
            OriginalAccess originalAccess,
            Boolean canAssign,
            ApprovedTask approvedTask) {

        public static Response denied(
                Request request,
                String denialCode,
                Long sourceVersion,
                OriginalAccess access) {
            return new Response(
                    request == null ? null : request.tenantId(),
                    request == null ? null : request.actorUserId(),
                    request == null ? null : request.source(),
                    request == null ? null : request.action(),
                    false, denialCode, sourceVersion, access, false, null);
        }
    }
}
