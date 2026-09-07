package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingPreparationMaterialAccessService;
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

class MeetingPreparationMaterialAccessControllerContractTest {

    @Test
    void exposesPublicVersionBoundMaterialAccessRoute() throws Exception {
        RequestMapping root = MeetingPreparationMaterialAccessController.class
                .getAnnotation(RequestMapping.class);
        var method = MeetingPreparationMaterialAccessController.class.getDeclaredMethod(
                "issueAccessTicket", UUID.class, UUID.class,
                VideoMeetingPreparationDtos.MaterialAccessRequest.class, String.class);

        assertThat(root.value()).containsExactly("/v1/meetings/{meetingId}/materials");
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/{materialId}/access-ticket");
        assertThat(MeetingPreparationMaterialAccessController.class
                .isAnnotationPresent(io.swagger.v3.oas.annotations.Hidden.class)).isFalse();
    }

    @Test
    void ticketResponseIsNeverStoredOrReferred() {
        UUID meetingId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        var request = new VideoMeetingPreparationDtos.MaterialAccessRequest(3L);
        var ticket = new VideoMeetingPreparationDtos.MaterialAccessTicketResponse(
                meetingId, materialId, 3L,
                "https://files.example.test/meeting-materials/open?ticket=short-ticket-001",
                OffsetDateTime.parse("2099-09-01T01:01:00Z"),
                "application/pdf", "Release brief.pdf");
        MeetingPreparationMaterialAccessService service =
                mock(MeetingPreparationMaterialAccessService.class);
        when(service.issueAccessTicket(meetingId, materialId, request, "correlation-1"))
                .thenReturn(ticket);

        var response = new MeetingPreparationMaterialAccessController(service)
                .issueAccessTicket(meetingId, materialId, request, "correlation-1");

        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isEqualTo(ticket);
    }
}
