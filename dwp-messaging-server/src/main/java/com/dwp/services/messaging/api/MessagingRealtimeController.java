package com.dwp.services.messaging.api;

import com.dwp.services.messaging.realtime.MessagingStreamService;
import com.dwp.services.messaging.realtime.MessagingTypingService;
import com.dwp.services.messaging.security.MessagingRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class MessagingRealtimeController {

    private final MessagingStreamService streams;
    private final MessagingTypingService typing;

    public MessagingRealtimeController(
            MessagingStreamService streams,
            MessagingTypingService typing) {
        this.streams = streams;
        this.typing = typing;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) String after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        String cursor = after == null || after.isBlank() ? lastEventId : after;
        return streams.open(MessagingRequestContext.get(), cursor);
    }

    @PostMapping("/conversations/{conversationId}/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void typing(
            @PathVariable UUID conversationId,
            @Valid @RequestBody TypingRequest request) {
        typing.change(conversationId, request.started());
    }

    public record TypingRequest(@NotNull Boolean started) {
    }
}
