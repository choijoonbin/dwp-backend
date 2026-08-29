package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.MeetingMediaWebhookService;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

@Hidden
@RestController
@RequestMapping(MeetingMediaWebhookController.PATH)
@ConditionalOnProperty(prefix = "dwp.meeting", name = "provider", havingValue = "livekit")
public class MeetingMediaWebhookController {

    public static final String PATH = "/internal/v1/media/livekit/webhook";
    static final int MAXIMUM_BODY_BYTES = 128 * 1024;

    private final MeetingMediaWebhook webhook;
    private final MeetingMediaWebhookService service;

    public MeetingMediaWebhookController(
            MeetingMediaWebhook webhook, MeetingMediaWebhookService service) {
        this.webhook = webhook;
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        String authorization = exactAuthorization(request);
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAXIMUM_BODY_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "The webhook payload is too large.");
        }
        byte[] body = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
        if (body.length == 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The webhook payload is required.");
        }
        if (body.length > MAXIMUM_BODY_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "The webhook payload is too large.");
        }
        service.accept(webhook.verify(
                new String(body, StandardCharsets.UTF_8), authorization));
        return ResponseEntity.noContent().build();
    }

    private String exactAuthorization(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("Authorization");
        if (values == null || !values.hasMoreElements()) throw unauthorized();
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || value.length() > 4096 || !value.startsWith("Bearer ")) {
            throw unauthorized();
        }
        return value;
    }

    private BaseException unauthorized() {
        return new BaseException(
                ErrorCode.UNAUTHORIZED, "A signed LiveKit webhook is required.");
    }
}
