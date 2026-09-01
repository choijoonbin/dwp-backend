package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingRecordingAccessControllerContractTest {

    @Test
    void exposesPublicVersionBoundAccessTicketRoute() throws Exception {
        RequestMapping root = MeetingRecordingAccessController.class
                .getAnnotation(RequestMapping.class);
        var method = MeetingRecordingAccessController.class.getDeclaredMethod(
                "issueAccessTicket", UUID.class, UUID.class,
                MeetingRecordingAccessDtos.AccessTicketCommand.class, String.class);

        assertThat(root.value()).containsExactly("/v1/meetings/{meetingId}/artifacts");
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/{artifactId}/access-ticket");
        assertThat(MeetingRecordingAccessController.class
                .isAnnotationPresent(io.swagger.v3.oas.annotations.Hidden.class)).isFalse();
    }

    @Test
    void accessTicketResponseIsNeverStoredOrReferred() {
        UUID meetingId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        var request = new MeetingRecordingAccessDtos.AccessTicketCommand(3L);
        var ticket = new MeetingRecordingAccessDtos.AccessTicketResponse(
                artifactId, 3L, "https://media.example.test/playback/token",
                OffsetDateTime.parse("2026-09-01T01:01:00Z"), "video/mp4");
        MeetingRecordingAccessService service = mock(MeetingRecordingAccessService.class);
        when(service.issueAccessTicket(meetingId, artifactId, request, "correlation-1"))
                .thenReturn(ticket);

        var response = new MeetingRecordingAccessController(service).issueAccessTicket(
                meetingId, artifactId, request, "correlation-1");

        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isEqualTo(ticket);
    }
}
