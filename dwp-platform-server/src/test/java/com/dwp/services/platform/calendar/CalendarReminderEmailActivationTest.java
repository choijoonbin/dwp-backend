package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import static com.dwp.services.platform.calendar.CalendarReminderDtos.ReminderChannel.EMAIL;
import static com.dwp.services.platform.calendar.CalendarReminderDtos.ReminderChannel.IN_APP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CalendarReminderEmailActivationTest {

    @Test
    void emailDefinitionsFailClosedUntilTheExternalDeliveryGateIsExplicitlyEnabled() {
        CalendarReminderDtos.ReplaceRemindersRequest request =
                new CalendarReminderDtos.ReplaceRemindersRequest(
                        0L,
                        List.of(new CalendarReminderDtos.ReminderInput(15, EMAIL)));

        CalendarReminderService disabled = service(false);
        assertThatThrownBy(() -> disabled.validate(request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("email reminders are not enabled");

        CalendarReminderService enabled = service(true);
        assertThat(enabled.validate(request))
                .extracting(CalendarReminderDtos.ReminderInput::channel)
                .containsExactly(EMAIL);
        assertThat(enabled.validate(new CalendarReminderDtos.ReplaceRemindersRequest(
                0L,
                List.of(new CalendarReminderDtos.ReminderInput(5, IN_APP)))))
                .hasSize(1);
    }

    @Test
    void runtimeConfigurationKeepsEmailDeliveryDarkByDefault() throws IOException {
        String application = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(application).contains(
                "email-enabled: ${DWP_PLATFORM_CALENDAR_REMINDER_EMAIL_ENABLED:false}");
    }

    private CalendarReminderService service(boolean emailEnabled) {
        return new CalendarReminderService(
                mock(CalendarReminderRepository.class),
                mock(CalendarRepository.class),
                emailEnabled,
                Clock.systemUTC());
    }
}
