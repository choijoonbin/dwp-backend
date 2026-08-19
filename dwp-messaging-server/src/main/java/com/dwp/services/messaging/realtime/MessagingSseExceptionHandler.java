package com.dwp.services.messaging.realtime;

import com.dwp.services.messaging.api.MessagingRealtimeController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/** Suppresses the expected async dispatch raised after an SSE client disconnects. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MessagingRealtimeController.class)
public class MessagingSseExceptionHandler {

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnectedClient() {
        // The response is already committed; there is no error body left to write.
    }
}
