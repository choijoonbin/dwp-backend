package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.MeetingMediaWebhookService;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.EventType;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.ProviderEvent;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.RoomBinding;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingMediaWebhookControllerTest {

    @Test
    void boundedSignedBodyIsVerifiedAndAppliedWithoutGatewayHeaders() throws Exception {
        MeetingMediaWebhook webhook = mock(MeetingMediaWebhook.class);
        MeetingMediaWebhookService service = mock(MeetingMediaWebhookService.class);
        MeetingMediaWebhookController controller =
                new MeetingMediaWebhookController(webhook, service);
        MockHttpServletRequest request = request("{\"event\":\"room_started\"}");
        ProviderEvent event = event();
        when(webhook.verify(
                "{\"event\":\"room_started\"}", "Bearer signed-webhook"))
                .thenReturn(event);

        var response = controller.receive(request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).accept(event);
    }

    @Test
    void duplicateAuthorizationAndOversizedBodiesFailBeforeVerification() {
        MeetingMediaWebhook webhook = mock(MeetingMediaWebhook.class);
        MeetingMediaWebhookController controller = new MeetingMediaWebhookController(
                webhook, mock(MeetingMediaWebhookService.class));
        MockHttpServletRequest duplicate = request("{}");
        duplicate.addHeader("Authorization", "Bearer second");

        assertThatThrownBy(() -> controller.receive(duplicate))
                .isInstanceOf(BaseException.class);

        MockHttpServletRequest oversized = request("{}");
        oversized.setContent(new byte[MeetingMediaWebhookController.MAXIMUM_BODY_BYTES + 1]);
        assertThatThrownBy(() -> controller.receive(oversized))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode().value())
                        .isEqualTo(413));
    }

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", MeetingMediaWebhookController.PATH);
        request.addHeader("Authorization", "Bearer signed-webhook");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private ProviderEvent event() {
        UUID meetingId = UUID.randomUUID();
        UUID incarnation = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 29, 1, 0, 0, 0, ZoneOffset.UTC);
        return new ProviderEvent(
                "LIVEKIT", "EV_room", EventType.ROOM_STARTED, now,
                new RoomBinding(
                        "RM_room", "room", 77L, meetingId, incarnation, now),
                null);
    }
}
