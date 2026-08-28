package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.NoticeState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.PlanState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoMeetingContentAdmissionGuardTest {

    private static final long TENANT_ID = 77L;
    private static final UUID MEETING_ID = UUID.randomUUID();
    private static final UUID PARTICIPANT_ID = UUID.randomUUID();
    private static final UUID NOTICE_ID = UUID.randomUUID();
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 28, 9, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private VideoMeetingContentRepository content;

    @Test
    void meetingWithoutAProcessingPlanDoesNotRequireAcknowledgement() {
        when(content.plan(TENANT_ID, MEETING_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> guard().requireCurrentNoticeAcknowledgement(
                TENANT_ID, MEETING_ID, PARTICIPANT_ID)).doesNotThrowAnyException();
    }

    @Test
    void currentNoticeMustBeAcknowledgedBeforeMediaAccess() {
        when(content.plan(TENANT_ID, MEETING_ID)).thenReturn(Optional.of(plan(NOTICE_ID)));
        when(content.currentNotice(TENANT_ID, MEETING_ID)).thenReturn(Optional.of(notice()));
        when(content.acknowledgedBy(TENANT_ID, MEETING_ID, NOTICE_ID, PARTICIPANT_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> guard().requireCurrentNoticeAcknowledgement(
                TENANT_ID, MEETING_ID, PARTICIPANT_ID))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void missingPublishedNoticeFailsClosed() {
        when(content.plan(TENANT_ID, MEETING_ID)).thenReturn(Optional.of(plan(NOTICE_ID)));
        when(content.currentNotice(TENANT_ID, MEETING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard().requireCurrentNoticeAcknowledgement(
                TENANT_ID, MEETING_ID, PARTICIPANT_ID))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void acknowledgementForTheCurrentRevisionAllowsMediaAccess() {
        when(content.plan(TENANT_ID, MEETING_ID)).thenReturn(Optional.of(plan(NOTICE_ID)));
        when(content.currentNotice(TENANT_ID, MEETING_ID)).thenReturn(Optional.of(notice()));
        when(content.acknowledgedBy(TENANT_ID, MEETING_ID, NOTICE_ID, PARTICIPANT_ID))
                .thenReturn(true);

        assertThatCode(() -> guard().requireCurrentNoticeAcknowledgement(
                TENANT_ID, MEETING_ID, PARTICIPANT_ID)).doesNotThrowAnyException();
    }

    private VideoMeetingContentAdmissionGuard guard() {
        return new VideoMeetingContentAdmissionGuard(content);
    }

    private ContentPlan plan(UUID noticeId) {
        return new ContentPlan(
                UUID.randomUUID(), TENANT_ID, MEETING_ID,
                true, true, true, false, PlanState.READY,
                noticeId, 2, 3, NOW);
    }

    private ContentNotice notice() {
        return new ContentNotice(
                NOTICE_ID, TENANT_ID, MEETING_ID, 2, NoticeState.PUBLISHED,
                "RECORDING_TRANSCRIPT_AI", true, true, true, NOW);
    }
}
