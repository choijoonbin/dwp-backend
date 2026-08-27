package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMeetingServiceWiringContractTest {

    @Test
    void productionConstructorsAreExplicitWhenServicesExposeTestClockConstructors() {
        List.of(
                        VideoMeetingService.class,
                        VideoMeetingCollaborationService.class,
                        VideoMeetingContentService.class)
                .forEach(this::assertExplicitProductionConstructor);
    }

    private void assertExplicitProductionConstructor(Class<?> serviceType) {
        Constructor<?>[] publicConstructors = serviceType.getConstructors();
        assertThat(publicConstructors)
                .as("public production constructor for %s", serviceType.getSimpleName())
                .hasSize(1);
        assertThat(publicConstructors[0].isAnnotationPresent(Autowired.class))
                .as("explicit Spring injection constructor for %s", serviceType.getSimpleName())
                .isTrue();
    }
}
