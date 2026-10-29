package com.dwp.services.platform.calendar;

import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventsV2OpenApiContractTest {

    @Test
    void eventsV2UsesCanonicalPathAndGloballyUniqueOperationId() throws Exception {
        RequestMapping root = CalendarEventsV2Controller.class.getAnnotation(RequestMapping.class);
        Method method = CalendarEventsV2Controller.class.getDeclaredMethod(
                "events", Long.class, Long.class, java.util.UUID.class,
                String.class, String.class, java.time.OffsetDateTime.class,
                java.time.OffsetDateTime.class, String.class, int.class, String.class);

        assertThat(root.value()).containsExactly("/v2/calendar");
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/events");
        assertThat(method.getAnnotation(Operation.class).operationId())
                .isEqualTo("listCalendarEventsV2");
    }
}
