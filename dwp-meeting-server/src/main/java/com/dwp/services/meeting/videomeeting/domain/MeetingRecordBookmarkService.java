package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingRecordBookmarkDtos.BookmarkInput;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordBookmarkDtos.BookmarkPage;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordBookmarkDtos.BookmarkState;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MeetingRecordBookmarkService {
    private final MeetingRecordBookmarkRepository bookmarks;
    private final VideoMeetingRepository meetings;
    private final MeetingWorkspaceCommands commands;
    private final VideoMeetingAuditRecorder audit;

    public MeetingRecordBookmarkService(MeetingRecordBookmarkRepository bookmarks,
            VideoMeetingRepository meetings, MeetingWorkspaceCommands commands,
            VideoMeetingAuditRecorder audit) {
        this.bookmarks = bookmarks;
        this.meetings = meetings;
        this.commands = commands;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public BookmarkPage read(List<UUID> meetingIds) {
        var actor = MeetingWorkspacePolicy.require("APP.MEETINGS", "VIEW");
        if (meetingIds == null || meetingIds.isEmpty() || meetingIds.size() > 100
                || meetingIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(meetingIds).size() != meetingIds.size()) {
            throw MeetingWorkspacePolicy.invalid();
        }
        var states = bookmarks.accessiblePage(actor.tenantId(), actor.userId(), meetingIds);
        if (states.size() != meetingIds.size()) throw MeetingWorkspacePolicy.missing();
        var byId = states.stream().collect(Collectors.toMap(BookmarkState::meetingId, Function.identity()));
        return new BookmarkPage(meetingIds.stream().map(byId::get).toList());
    }

    @Transactional
    public BookmarkState update(UUID meetingId, BookmarkInput input, String key, String correlation) {
        var actor = MeetingWorkspacePolicy.require("APP.MEETINGS", "VIEW");
        if (meetingId == null || input == null || input.favorite() == null
                || input.expectedVersion() == null || input.expectedVersion() < 0) {
            throw MeetingWorkspacePolicy.invalid();
        }
        if (bookmarks.accessiblePage(actor.tenantId(), actor.userId(), List.of(meetingId)).isEmpty()) {
            throw MeetingWorkspacePolicy.missing();
        }
        var attempt = commands.begin("RECORD_BOOKMARK_UPDATE", key,
                new BookmarkCommand(meetingId, input.favorite(), input.expectedVersion()));
        // Take the owner row lock before a fresh authorization statement. A waiter
        // must not reuse the access snapshot from before a concurrent revocation.
        meetings.lockMeeting(actor.tenantId(), meetingId);
        if (bookmarks.accessiblePage(actor.tenantId(), actor.userId(), List.of(meetingId)).isEmpty()) {
            throw MeetingWorkspacePolicy.missing();
        }
        var current = bookmarks.lock(actor.tenantId(), actor.userId(), meetingId);
        // A replay never re-applies an old boolean after a newer command.
        if (attempt.replay() != null) return current;
        MeetingWorkspacePolicy.version(current.version(), input.expectedVersion());
        var updated = bookmarks.update(actor.tenantId(), actor.userId(), meetingId,
                input.favorite(), input.expectedVersion());
        audit.workspaceChanged(actor, "meeting.record-bookmark.updated", "MEETING_RECORD_BOOKMARK",
                meetingId.toString(), correlation,
                Map.of("favorite", updated.favorite(), "version", updated.version()));
        commands.complete(attempt, meetingId, updated.version());
        return updated;
    }

    private record BookmarkCommand(UUID meetingId, boolean favorite, long expectedVersion) { }
}
