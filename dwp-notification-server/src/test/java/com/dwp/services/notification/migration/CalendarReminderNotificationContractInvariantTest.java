package com.dwp.services.notification.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarReminderNotificationContractInvariantTest {

    @Test
    void registersLocalizedInAppAndEmailContractsWithoutMailSourceImpersonation()
            throws IOException {
        String migration = resource(
                "db/migration/V39__register_calendar_event_reminder_contracts.sql");

        assertThat(migration)
                .contains(
                        "CALENDAR.EVENT_REMINDER.IN_APP",
                        "CALENDAR.EVENT_REMINDER.EMAIL",
                        "calendar.event-reminder.due.v1",
                        "'IN_APP', 'ko-KR'",
                        "'IN_APP', 'en-US'",
                        "'EMAIL', 'ko-KR'",
                        "'EMAIL', 'en-US'",
                        "calendar-event-reminder-in-app-ko-v1",
                        "calendar-event-reminder-in-app-en-v1",
                        "calendar-event-reminder-email-ko-v1",
                        "calendar-event-reminder-email-en-v1",
                        "email-calendar-reminder",
                        "'EMAIL', FALSE, 'MUTED', FALSE",
                        "externalAdapterRequired",
                        "SOURCE_EVENT_RECIPIENT",
                        "TITLE_ONLY")
                .doesNotContain(
                        "urn:dwp:platform:mail",
                        "MAIL.",
                        "email-receipt",
                        "INSERT INTO ntf_delivery_jobs");
    }

    @Test
    void runtimeOnboardingBindsCalendarSourceProducerOwnershipAndEntitlementTogether()
            throws IOException {
        String application = resource("application.yml");

        assertThat(application)
                .contains(
                        "dwp-platform-server=platform|workplace|mail|calendar",
                        "calendar=APP.CALENDAR:VIEW",
                        "urn:dwp:platform:calendar=dwp-platform-server:calendar.event-reminder.due.v1")
                .doesNotContain(
                        "urn:dwp:platform:calendar=dwp-platform-server,");
    }

    private String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
