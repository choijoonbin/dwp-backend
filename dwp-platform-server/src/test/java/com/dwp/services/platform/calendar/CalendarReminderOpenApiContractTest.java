package com.dwp.services.platform.calendar;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarReminderOpenApiContractTest {

    @Test
    void exposesStableGetAndPutOperationsOnTheExactEventReminderPath() throws Exception {
        RequestMapping root = CalendarReminderController.class
                .getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly(
                "/v1/calendar/events/{eventId}/reminders");

        Method get = CalendarReminderController.class.getMethod(
                "reminders", HttpServletRequest.class,
                HttpServletResponse.class, UUID.class);
        Method put = CalendarReminderController.class.getMethod(
                "replace", HttpServletRequest.class, HttpServletResponse.class,
                UUID.class, CalendarReminderDtos.ReplaceRemindersRequest.class);
        assertThat(get.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(put.getAnnotation(PutMapping.class)).isNotNull();
        assertThat(get.getAnnotation(Operation.class).operationId())
                .isEqualTo("getCalendarEventReminders");
        assertThat(put.getAnnotation(Operation.class).operationId())
                .isEqualTo("replaceCalendarEventReminders");
        assertThat(List.of(
                get.getAnnotation(Operation.class).operationId(),
                put.getAnnotation(Operation.class).operationId()))
                .doesNotHaveDuplicates();
    }

    @Test
    void responseAndReplacementRecordsKeepThePublishedJsonFieldOrder() {
        assertThat(names(CalendarReminderDtos.ReminderSet.class))
                .containsExactly(
                        "eventId", "eventVersion", "version", "state",
                        "reminders", "updatedAt");
        assertThat(names(CalendarReminderDtos.ReminderDefinition.class))
                .containsExactly(
                        "reminderId", "offsetMinutes", "channel",
                        "nextOccurrenceStartsAt", "nextDueAt");
        assertThat(names(CalendarReminderDtos.ReplaceRemindersRequest.class))
                .containsExactly("version", "reminders");
    }

    private List<String> names(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName).toList();
    }
}
