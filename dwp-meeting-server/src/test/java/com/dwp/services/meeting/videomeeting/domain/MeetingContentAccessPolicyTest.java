package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.Audience;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingContentAccessPolicyTest {

    private final MeetingContentAccessPolicy policy = new MeetingContentAccessPolicy();

    @Test
    void hostCanViewPrivateDraft() {
        assertThat(policy.canView(
                participant(ParticipantRole.ORGANIZER, AttendanceState.LEFT),
                report(ReportState.DRAFT), false)).isTrue();
    }

    @Test
    void attendeeCannotViewPrivateDraftWithoutGrant() {
        assertThat(policy.canView(
                participant(ParticipantRole.ATTENDEE, AttendanceState.LEFT),
                report(ReportState.DRAFT), false)).isFalse();
    }

    @Test
    void explicitGrantAllowsPrivateDraftView() {
        assertThat(policy.canView(
                participant(ParticipantRole.ATTENDEE, AttendanceState.LEFT),
                report(ReportState.DRAFT), true)).isTrue();
    }

    @Test
    void admittedMemberCanViewPublishedReport() {
        assertThat(policy.canView(
                participant(ParticipantRole.ATTENDEE, AttendanceState.LEFT),
                report(ReportState.PUBLISHED), false)).isTrue();
    }

    @Test
    void invitedMemberCannotViewPublishedReportUntilAdmitted() {
        assertThat(policy.canView(
                participant(ParticipantRole.ATTENDEE, AttendanceState.INVITED),
                report(ReportState.PUBLISHED), false)).isFalse();
    }

    @Test
    void deniedMemberCannotViewEvenWithExplicitGrant() {
        assertThat(policy.canView(
                participant(ParticipantRole.ATTENDEE, AttendanceState.DENIED),
                report(ReportState.PUBLISHED), true)).isFalse();
    }

    @Test
    void deletedReportCannotBeViewedByHost() {
        assertThat(policy.canView(
                participant(ParticipantRole.ORGANIZER, AttendanceState.LEFT),
                report(ReportState.DELETED), true)).isFalse();
    }

    @Test
    void hostCanReview() {
        assertThat(policy.canReview(
                participant(ParticipantRole.CO_HOST, AttendanceState.LEFT), false)).isTrue();
    }

    @Test
    void attendeeNeedsExplicitReviewGrant() {
        Participant attendee = participant(ParticipantRole.ATTENDEE, AttendanceState.LEFT);

        assertThat(policy.canReview(attendee, false)).isFalse();
        assertThat(policy.canReview(attendee, true)).isTrue();
    }

    @Test
    void presenterNeedsExplicitManageGrant() {
        Participant presenter = participant(ParticipantRole.PRESENTER, AttendanceState.LEFT);

        assertThat(policy.canManage(presenter, false)).isFalse();
        assertThat(policy.canManage(presenter, true)).isTrue();
    }

    @Test
    void onlyHostCanRequestAnalysis() {
        assertThat(policy.canRequest(
                participant(ParticipantRole.ORGANIZER, AttendanceState.LEFT))).isTrue();
        assertThat(policy.canRequest(
                participant(ParticipantRole.ATTENDEE, AttendanceState.LEFT))).isFalse();
    }

    @Test
    void tenantRoleWithoutMeetingParticipantNeverGrantsAccess() {
        assertThat(policy.canView(null, report(ReportState.PUBLISHED), true)).isFalse();
        assertThat(policy.canReview(null, true)).isFalse();
        assertThat(policy.canManage(null, true)).isFalse();
    }

    private Participant participant(ParticipantRole role, AttendanceState attendance) {
        return new Participant(
                UUID.randomUUID(), 77, UUID.randomUUID(), 101L, UUID.randomUUID(),
                "member@example.test", "Meeting member", null, null, role, attendance,
                true, null, null, null, null, null, null, null, 0);
    }

    private IntelligenceReport report(ReportState state) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T00:00:00Z");
        return new IntelligenceReport(
                UUID.randomUUID(), 77, UUID.randomUUID(), UUID.randomUUID(), state,
                state == ReportState.PUBLISHED
                        ? Audience.MEETING_PARTICIPANTS : Audience.PRIVATE_REVIEWERS,
                state == ReportState.DELETED ? null : "protected",
                state == ReportState.DELETED ? null : "a".repeat(64),
                "b".repeat(64), VideoMeetingIntelligenceModels.SCHEMA_VERSION,
                now.plusDays(30), false,
                state == ReportState.APPROVED || state == ReportState.PUBLISHED ? now : null,
                state == ReportState.APPROVED || state == ReportState.PUBLISHED ? 202L : null,
                state == ReportState.PUBLISHED ? now : null,
                state == ReportState.PUBLISHED ? 101L : null,
                state == ReportState.DELETED ? now : null,
                state == ReportState.DELETED ? 101L : null,
                0, 101L);
    }
}
