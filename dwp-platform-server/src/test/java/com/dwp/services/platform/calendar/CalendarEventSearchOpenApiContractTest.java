package com.dwp.services.platform.calendar;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventSearchOpenApiContractTest {

    @Test
    void searchHasCanonicalReadOnlyV2PathAndUniqueOperationId() throws Exception {
        RequestMapping root = CalendarEventSearchController.class
                .getAnnotation(RequestMapping.class);
        Method method = CalendarEventSearchController.class.getDeclaredMethod(
                "search", HttpServletRequest.class, HttpServletResponse.class,
                String.class, OffsetDateTime.class, OffsetDateTime.class,
                String.class, int.class, String.class);

        assertThat(root.value()).containsExactly("/v2/calendar/events/search");
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method.getAnnotation(Operation.class).operationId())
                .isEqualTo("searchCalendarEventsV2");
        assertThat(method.isAnnotationPresent(PostMapping.class)).isFalse();
        assertThat(method.isAnnotationPresent(PutMapping.class)).isFalse();
        assertThat(method.isAnnotationPresent(PatchMapping.class)).isFalse();
        assertThat(method.isAnnotationPresent(DeleteMapping.class)).isFalse();
    }
}
