package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarBookingDtos.BookingOwnerType.PERSONAL;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.BookingOwnerType.TEAM;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.MeetingMode.IN_PERSON;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.MeetingMode.PHONE;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.MeetingMode.VIDEO;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.QuestionType.SHORT_TEXT;
import static com.dwp.services.platform.calendar.CalendarBookingDtos.QuestionType.SINGLE_SELECT;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarBookingContractTest {

    @Test
    void acceptsACompletePersonalPageContract() {
        assertThatCode(() -> CalendarBookingContract.validate(valid()))
                .doesNotThrowAnyException();
    }

    @Test
    void teamOwnershipRequiresAnExactGroupReference() {
        CalendarBookingDtos.SaveBookingPageRequest personalWithGroup = copy(
                valid(), PERSONAL, UUID.randomUUID(), valid().availabilityRules(), valid().questions());
        CalendarBookingDtos.SaveBookingPageRequest teamWithoutGroup = copy(
                valid(), TEAM, null, valid().availabilityRules(), valid().questions());

        assertThatThrownBy(() -> CalendarBookingContract.validate(personalWithGroup))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> CalendarBookingContract.validate(teamWithoutGroup))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsOverlappingAvailabilityBeforeAnyHoldCanBeCreated() {
        List<CalendarBookingDtos.AvailabilityRule> overlapping = List.of(
                new CalendarBookingDtos.AvailabilityRule(
                        DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0)),
                new CalendarBookingDtos.AvailabilityRule(
                        DayOfWeek.MONDAY, LocalTime.of(11, 30), LocalTime.of(13, 0)));

        assertThatThrownBy(() -> CalendarBookingContract.validate(copy(
                valid(), PERSONAL, null, overlapping, valid().questions())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("cannot overlap");
    }

    @Test
    void questionKeysAndSelectOptionsAreDeterministic() {
        List<CalendarBookingDtos.BookingQuestion> duplicateKeys = List.of(
                new CalendarBookingDtos.BookingQuestion(
                        "purpose", "Purpose", SHORT_TEXT, true, List.of()),
                new CalendarBookingDtos.BookingQuestion(
                        "purpose", "Purpose again", SHORT_TEXT, false, List.of()));
        assertThatThrownBy(() -> CalendarBookingContract.validate(copy(
                valid(), PERSONAL, null, valid().availabilityRules(), duplicateKeys)))
                .isInstanceOf(BaseException.class);

        List<CalendarBookingDtos.BookingQuestion> duplicateOptions = List.of(
                new CalendarBookingDtos.BookingQuestion(
                        "format", "Format", SINGLE_SELECT, true,
                        List.of("Remote", " remote ")));
        assertThatThrownBy(() -> CalendarBookingContract.validate(copy(
                valid(), PERSONAL, null, valid().availabilityRules(), duplicateOptions)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void inPersonModeRequiresAConcreteLocation() {
        CalendarBookingDtos.SaveBookingPageRequest value = valid();
        CalendarBookingDtos.SaveBookingPageRequest inPerson = new CalendarBookingDtos.SaveBookingPageRequest(
                value.ownerType(), value.ownerGroupRef(), value.calendarId(), value.title(), value.description(),
                value.timeZone(), value.durationMinutes(), value.bufferBeforeMinutes(),
                value.bufferAfterMinutes(), value.dailyLimit(), value.minimumNoticeMinutes(),
                value.maximumAdvanceDays(), IN_PERSON, " ", null, null,
                value.availabilityRules(), value.questions(), value.version());
        assertThatThrownBy(() -> CalendarBookingContract.validate(inPerson))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("requires a location");
    }

    @Test
    void remoteModesRequireExplicitApprovedConnectionDetails() {
        CalendarBookingDtos.SaveBookingPageRequest value = valid();
        CalendarBookingDtos.SaveBookingPageRequest videoWithoutUrl =
                new CalendarBookingDtos.SaveBookingPageRequest(
                        value.ownerType(), value.ownerGroupRef(), value.calendarId(),
                        value.title(), value.description(), value.timeZone(),
                        value.durationMinutes(), value.bufferBeforeMinutes(),
                        value.bufferAfterMinutes(), value.dailyLimit(),
                        value.minimumNoticeMinutes(), value.maximumAdvanceDays(),
                        VIDEO, null, null, null, value.availabilityRules(),
                        value.questions(), value.version());
        CalendarBookingDtos.SaveBookingPageRequest unapprovedVideo =
                new CalendarBookingDtos.SaveBookingPageRequest(
                        value.ownerType(), value.ownerGroupRef(), value.calendarId(),
                        value.title(), value.description(), value.timeZone(),
                        value.durationMinutes(), value.bufferBeforeMinutes(),
                        value.bufferAfterMinutes(), value.dailyLimit(),
                        value.minimumNoticeMinutes(), value.maximumAdvanceDays(),
                        VIDEO, null, "https://example.com/meeting", null,
                        value.availabilityRules(), value.questions(), value.version());
        CalendarBookingDtos.SaveBookingPageRequest phoneWithoutDialIn =
                new CalendarBookingDtos.SaveBookingPageRequest(
                        value.ownerType(), value.ownerGroupRef(), value.calendarId(),
                        value.title(), value.description(), value.timeZone(),
                        value.durationMinutes(), value.bufferBeforeMinutes(),
                        value.bufferAfterMinutes(), value.dailyLimit(),
                        value.minimumNoticeMinutes(), value.maximumAdvanceDays(),
                        PHONE, null, null, null, value.availabilityRules(),
                        value.questions(), value.version());

        assertThatThrownBy(() -> CalendarBookingContract.validate(videoWithoutUrl))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("conference URL");
        assertThatThrownBy(() -> CalendarBookingContract.validate(unapprovedVideo))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("approved HTTPS");
        assertThatThrownBy(() -> CalendarBookingContract.validate(phoneWithoutDialIn))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("dial-in");
    }

    private CalendarBookingDtos.SaveBookingPageRequest valid() {
        return new CalendarBookingDtos.SaveBookingPageRequest(
                PERSONAL, null, UUID.randomUUID(), "Design review", "Discuss the launch", "Asia/Seoul",
                30, 10, 10, 8, 120, 60, VIDEO, null,
                "https://meet.google.com/abc-defg-hij", null,
                List.of(
                        new CalendarBookingDtos.AvailabilityRule(
                                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0)),
                        new CalendarBookingDtos.AvailabilityRule(
                                DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(18, 0))),
                List.of(new CalendarBookingDtos.BookingQuestion(
                        "purpose", "Purpose", SHORT_TEXT, true, List.of())),
                0L);
    }

    private CalendarBookingDtos.SaveBookingPageRequest copy(
            CalendarBookingDtos.SaveBookingPageRequest value,
            CalendarBookingDtos.BookingOwnerType ownerType,
            UUID ownerGroupRef,
            List<CalendarBookingDtos.AvailabilityRule> rules,
            List<CalendarBookingDtos.BookingQuestion> questions) {
        return new CalendarBookingDtos.SaveBookingPageRequest(
                ownerType, ownerGroupRef, value.calendarId(), value.title(), value.description(), value.timeZone(),
                value.durationMinutes(), value.bufferBeforeMinutes(), value.bufferAfterMinutes(),
                value.dailyLimit(), value.minimumNoticeMinutes(), value.maximumAdvanceDays(),
                value.meetingMode(), value.location(), value.conferenceUrl(),
                value.dialInInstructions(), rules, questions, value.version());
    }
}
