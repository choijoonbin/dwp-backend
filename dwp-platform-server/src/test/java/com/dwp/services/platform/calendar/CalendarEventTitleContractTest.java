package com.dwp.services.platform.calendar;

import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventTitleContractTest {

    @Test
    void createAndUpdateAcceptTheThreeHundredCharacterWorkHandoffTitleContract() {
        assertThat(titleLimit(CalendarDtos.CreateEventRequest.class)).isEqualTo(300);
        assertThat(titleLimit(CalendarDtos.UpdateEventRequest.class)).isEqualTo(300);
    }

    private int titleLimit(Class<?> requestType) {
        RecordComponent title = Arrays.stream(requestType.getRecordComponents())
                .filter(component -> component.getName().equals("title"))
                .findFirst()
                .orElseThrow();
        return title.getAccessor().getAnnotation(Size.class).max();
    }
}
