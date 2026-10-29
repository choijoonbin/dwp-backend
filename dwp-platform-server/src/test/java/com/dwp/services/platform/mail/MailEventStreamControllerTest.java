package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailEventStreamControllerTest {

    @Test
    void reconnectUsesHighestExplicitOrLastEventCursor() {
        MailEventStreamService service = mock(MailEventStreamService.class);
        SseEmitter emitter = new SseEmitter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2);
        request.setAttribute(MailProductSurfacePepFilter.TRUSTED_AUTHORITY_ATTRIBUTE,
                new MailProductSurfacePepFilter.TrustedAuthority(
                        "route.mail.work.events.data", "psr-" + "a".repeat(64), expiry));
        when(service.connect(7, 91, 42, expiry)).thenReturn(emitter);
        MailEventStreamController controller = new MailEventStreamController(service);

        assertThat(controller.stream(7, 91, "42", 12, request)).isSameAs(emitter);
        verify(service).connect(7, 91, 42, expiry);
    }

    @Test
    void pollingFallbackReturnsSameMinimalCatchupEnvelope() {
        MailEventStreamService service = mock(MailEventStreamService.class);
        MailEventStreamService.CatchUp catchUp =
                new MailEventStreamService.CatchUp(List.of(), 17, false);
        when(service.catchUp(7, 91, 17)).thenReturn(catchUp);
        MailEventStreamController controller = new MailEventStreamController(service);

        controller.catchUp(7, 91, 17);

        verify(service).catchUp(7, 91, 17);
    }
}
