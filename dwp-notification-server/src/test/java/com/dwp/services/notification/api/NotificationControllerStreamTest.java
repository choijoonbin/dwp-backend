package com.dwp.services.notification.api;

import com.dwp.services.notification.domain.NotificationModels.SyncResponse;
import com.dwp.services.notification.domain.NotificationService;
import com.dwp.services.notification.realtime.NotificationStreamService;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationControllerStreamTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(1, 9L, Set.of(), Set.of(), false, null);

    @AfterEach
    void clearContext() {
        NotificationRequestContext.clear();
    }

    @Test
    void resumesFromLastEventIdWhenExplicitCursorIsAbsent() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationStreamService streamService = mock(NotificationStreamService.class);
        NotificationController controller = new NotificationController(
                notificationService, streamService);
        SseEmitter emitter = new SseEmitter();
        when(streamService.open(eq(ACTOR), eq("41"), any())).thenReturn(emitter);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.stream(null, "41")).isSameAs(emitter);

        verify(notificationService).validateSyncCursor(ACTOR, "41");
        verify(streamService).open(eq(ACTOR), eq("41"), any());
    }

    @Test
    void explicitAfterCursorWinsAndCatchUpDelegatesBoundedPages() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationStreamService streamService = mock(NotificationStreamService.class);
        NotificationController controller = new NotificationController(
                notificationService, streamService);
        SseEmitter emitter = new SseEmitter();
        NotificationStreamService.CatchUpSource[] source = new NotificationStreamService.CatchUpSource[1];
        when(streamService.open(eq(ACTOR), eq("42"), any())).thenAnswer(invocation -> {
            source[0] = invocation.getArgument(2);
            return emitter;
        });
        SyncResponse expected = mock(SyncResponse.class);
        when(notificationService.sync(ACTOR, "42", 100)).thenReturn(expected);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.stream("42", "41")).isSameAs(emitter);
        assertThat(source[0].next("42", 100)).isSameAs(expected);

        verify(notificationService).validateSyncCursor(ACTOR, "42");
        verify(notificationService).sync(ACTOR, "42", 100);
    }
}
