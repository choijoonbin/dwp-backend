package com.dwp.services.notification.api;

import com.dwp.services.notification.domain.NotificationModels.SyncResponse;
import com.dwp.services.notification.domain.NotificationService;
import com.dwp.services.notification.realtime.NotificationStreamService;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.UUID;

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
        when(streamService.open(eq(ACTOR), eq("41"), eq(null), any())).thenReturn(emitter);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.stream(null, "41", null)).isSameAs(emitter);

        verify(notificationService).validateSyncCursor(ACTOR, "41");
        verify(streamService).open(eq(ACTOR), eq("41"), eq(null), any());
    }

    @Test
    void explicitAfterCursorWinsAndCatchUpDelegatesBoundedPages() {
        NotificationService notificationService = mock(NotificationService.class);
        NotificationStreamService streamService = mock(NotificationStreamService.class);
        NotificationController controller = new NotificationController(
                notificationService, streamService);
        SseEmitter emitter = new SseEmitter();
        UUID clientId = UUID.fromString("42000000-0000-0000-0000-000000000001");
        NotificationStreamService.CatchUpSource[] source = new NotificationStreamService.CatchUpSource[1];
        when(streamService.open(eq(ACTOR), eq("42"), eq(clientId), any())).thenAnswer(invocation -> {
            source[0] = invocation.getArgument(3);
            return emitter;
        });
        SyncResponse expected = mock(SyncResponse.class);
        when(notificationService.sync(ACTOR, "42", 100)).thenReturn(expected);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.stream("42", "41", clientId)).isSameAs(emitter);
        assertThat(source[0].next("42", 100)).isSameAs(expected);

        verify(notificationService).validateSyncCursor(ACTOR, "42");
        verify(notificationService).sync(ACTOR, "42", 100);
    }
}
