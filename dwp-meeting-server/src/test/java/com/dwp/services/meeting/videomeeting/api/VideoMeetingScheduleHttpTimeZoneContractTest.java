package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingScheduleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VideoMeetingScheduleHttpTimeZoneContractTest {

    private static final UUID MEETING_ID = UUID.fromString(
            "88000000-0000-4000-8000-000000000001");

    @Test
    void seriesPreviewHttpBindingPreservesTheSubmittedOffsetForIanaZoneValidation()
            throws Exception {
        VideoMeetingScheduleService service = mock(VideoMeetingScheduleService.class);
        when(service.previewSeries(any())).thenReturn(new VideoMeetingScheduleDtos.SeriesPreviewResponse(
                "a".repeat(64), false, List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new VideoMeetingScheduleController(service)).build();

        mvc.perform(post("/v1/meeting-series/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "meeting": {
                                    "title": "Architecture review",
                                    "startsAt": "2099-09-04T19:30:00+09:00",
                                    "durationMinutes": 45,
                                    "timeZone": "Asia/Seoul",
                                    "accessScope": "INVITED",
                                    "waitingRoomEnabled": true,
                                    "guestAccessEnabled": false,
                                    "allowJoinBeforeHost": false,
                                    "defaultMicrophoneEnabled": false,
                                    "defaultCameraEnabled": false,
                                    "participantUserIds": [],
                                    "guestInvitees": []
                                  },
                                  "recurrence": {
                                    "frequency": "WEEKLY",
                                    "interval": 1,
                                    "occurrenceCount": 3
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<VideoMeetingScheduleDtos.SeriesPreviewRequest> request =
                ArgumentCaptor.forClass(VideoMeetingScheduleDtos.SeriesPreviewRequest.class);
        verify(service).previewSeries(request.capture());
        assertThat(request.getValue().meeting().startsAt())
                .isEqualTo(OffsetDateTime.parse("2099-09-04T19:30:00+09:00"));
        assertThat(request.getValue().meeting().startsAt().getOffset().toString())
                .isEqualTo("+09:00");
    }

    @Test
    void reschedulePreviewHttpBindingPreservesTheExplicitDstOverlapOffset()
            throws Exception {
        VideoMeetingScheduleService service = mock(VideoMeetingScheduleService.class);
        when(service.previewReschedule(any(), any())).thenReturn(
                new VideoMeetingScheduleDtos.SeriesPreviewResponse(
                        "b".repeat(64), true, List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new VideoMeetingScheduleController(service)).build();

        mvc.perform(post("/v1/meetings/{meetingId}/schedule/preview", MEETING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startsAt": "2099-11-01T01:30:00-05:00",
                                  "durationMinutes": 45,
                                  "timeZone": "America/New_York",
                                  "scope": "THIS_ONLY",
                                  "expectedSeriesVersion": null,
                                  "expectedVersion": 3,
                                  "calendarFingerprint": null
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<VideoMeetingScheduleDtos.RescheduleRequest> request =
                ArgumentCaptor.forClass(VideoMeetingScheduleDtos.RescheduleRequest.class);
        verify(service).previewReschedule(any(), request.capture());
        assertThat(request.getValue().startsAt())
                .isEqualTo(OffsetDateTime.parse("2099-11-01T01:30:00-05:00"));
        assertThat(request.getValue().startsAt().getOffset().toString())
                .isEqualTo("-05:00");
    }
}
