package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingCard;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoMeetingHistoryProjectionTest {
    @ParameterizedTest
    @EnumSource(ParticipantRole.class)
    void preservesAuthorizedOrganizerAndExactViewerRoleWithoutGrantingMediaAccess(ParticipantRole role) {
        Meeting meeting = meeting();
        var response = VideoMeetingDtos.history(new MeetingCard(meeting, 6, role));
        assertThat(response.organizerUserId()).isEqualTo(42);
        assertThat(response.organizerName()).isEqualTo("Kim Mina");
        assertThat(response.participantRole()).isEqualTo(role.name());
        assertThat(response.canHost()).isEqualTo(role.canHost());
        assertThat(response.actualDurationMinutes()).isEqualTo(45);
        assertThat(response.recordingAvailable()).isFalse();
        assertThat(response.transcriptAvailable()).isFalse();
        assertThat(response.publicationState()).isEqualTo("NONE");
        assertThat(response.retentionState()).isEqualTo("UNCONFIGURED");
        assertThat(response.retentionUntil()).isNull();
    }

    @Test
    void missingViewerRoleRemainsNonPrivileged() {
        var response = VideoMeetingDtos.history(new MeetingCard(meeting(), 6, null));
        assertThat(response.participantRole()).isEqualTo("ATTENDEE");
        assertThat(response.canHost()).isFalse();
    }

    @Test
    void carriesOnlyTheAuthorizedEvidenceProjectionSuppliedByTheRepository() {
        OffsetDateTime retention = OffsetDateTime.parse("2026-10-07T01:45:00Z");
        var response = VideoMeetingDtos.history(
                new MeetingCard(meeting(), 6, ParticipantRole.ORGANIZER),
                "PUBLISHED", "ACTIVE", retention);
        assertThat(response.publicationState()).isEqualTo("PUBLISHED");
        assertThat(response.retentionState()).isEqualTo("ACTIVE");
        assertThat(response.retentionUntil()).isEqualTo(retention);
    }

    private Meeting meeting() {
        Meeting meeting = mock(Meeting.class);
        when(meeting.meetingId()).thenReturn(UUID.fromString("81000000-0000-0000-0000-000000000301"));
        when(meeting.title()).thenReturn("Release decision");
        when(meeting.organizerUserId()).thenReturn(42L);
        when(meeting.organizerName()).thenReturn("Kim Mina");
        when(meeting.startedAt()).thenReturn(OffsetDateTime.parse("2026-09-07T01:00:00Z"));
        when(meeting.endedAt()).thenReturn(OffsetDateTime.parse("2026-09-07T01:45:00Z"));
        return meeting;
    }
}
