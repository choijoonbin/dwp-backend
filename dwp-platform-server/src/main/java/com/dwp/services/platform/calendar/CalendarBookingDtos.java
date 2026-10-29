package com.dwp.services.platform.calendar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

final class CalendarBookingDtos {

    private CalendarBookingDtos() {
    }

    enum BookingOwnerType { PERSONAL, TEAM }

    enum BookingPageState { DRAFT, PUBLISHED, PAUSED, ARCHIVED }

    enum BookingHoldState { HELD, CONSUMED, EXPIRED, CANCELLED }

    enum AppointmentState { CONFIRMED, RESCHEDULED, CANCELLED }

    enum MeetingMode { IN_PERSON, VIDEO, PHONE }

    enum QuestionType { SHORT_TEXT, LONG_TEXT, SINGLE_SELECT, CHECKBOX }

    record AvailabilityRule(
            @NotNull DayOfWeek dayOfWeek,
            @NotNull LocalTime startsAt,
            @NotNull LocalTime endsAt) {
    }

    record BookingQuestion(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,63}") String key,
            @NotBlank @Size(max = 160) String label,
            @NotNull QuestionType type,
            boolean required,
            @Size(max = 20) List<@NotBlank @Size(max = 120) String> options) {
    }

    record SaveBookingPageRequest(
            @NotNull BookingOwnerType ownerType,
            UUID ownerGroupRef,
            @NotNull UUID calendarId,
            @NotBlank @Size(max = 160) String title,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 80) String timeZone,
            @Min(5) @Max(480) int durationMinutes,
            @Min(0) @Max(180) int bufferBeforeMinutes,
            @Min(0) @Max(180) int bufferAfterMinutes,
            @Min(1) @Max(100) int dailyLimit,
            @Min(0) @Max(10080) int minimumNoticeMinutes,
            @Min(1) @Max(365) int maximumAdvanceDays,
            @NotNull MeetingMode meetingMode,
            @Size(max = 240) String location,
            @Size(max = 1000) String conferenceUrl,
            @Size(max = 500) String dialInInstructions,
            @NotEmpty @Size(max = 28) List<@Valid AvailabilityRule> availabilityRules,
            @NotNull @Size(max = 20) List<@Valid BookingQuestion> questions,
            @NotNull @Min(0) Long version) {
    }

    record BookingPage(
            UUID pageId,
            BookingOwnerType ownerType,
            UUID ownerPersonPublicId,
            UUID ownerGroupRef,
            UUID calendarId,
            BookingPageState state,
            String title,
            String description,
            String timeZone,
            int durationMinutes,
            int bufferBeforeMinutes,
            int bufferAfterMinutes,
            int dailyLimit,
            int minimumNoticeMinutes,
            int maximumAdvanceDays,
            MeetingMode meetingMode,
            String location,
            String conferenceUrl,
            String dialInInstructions,
            List<AvailabilityRule> availabilityRules,
            List<BookingQuestion> questions,
            boolean publicLinkConfigured,
            long publicTokenVersion,
            long version,
            OffsetDateTime updatedAt) {
    }

    record ChangeBookingPageStateRequest(
            @NotNull BookingPageState state,
            @NotNull @Min(0) Long version) {
    }

    /** The raw token is returned only on publish/rotate and is never persisted. */
    record BookingPagePublishReceipt(
            BookingPage page,
            String publicToken) {
    }

    record CreateBookingHoldRequest(
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long pageVersion) {
    }

    record CancelBookingHoldRequest(
            @NotNull @Min(1) Long fencingToken) {
    }

    record BookingHold(
            UUID holdId,
            UUID pageId,
            UUID bookerPersonPublicId,
            BookingHoldState state,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime expiresAt,
            long fencingToken,
            long version) {
    }

    record BookingAnswer(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,63}") String key,
            @NotBlank @Size(max = 2000) String value) {
    }

    record ConfirmBookingRequest(
            @NotNull UUID holdId,
            @NotNull @Min(1) Long fencingToken,
            @NotNull UUID idempotencyKey,
            @NotNull @Size(max = 20) List<@Valid BookingAnswer> answers) {
    }

    record Appointment(
            UUID appointmentId,
            UUID pageId,
            UUID eventId,
            UUID bookerPersonPublicId,
            AppointmentState state,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID supersedesAppointmentId,
            long version,
            OffsetDateTime updatedAt) {
    }

    /** The management token is one-time display material; storage is hash-only. */
    record AppointmentReceipt(
            Appointment appointment,
            String managementToken) {
    }

    record RescheduleAppointmentRequest(
            @NotNull UUID replacementHoldId,
            @NotNull @Min(1) Long fencingToken,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long version) {
    }

    record CancelAppointmentRequest(
            @NotNull @Min(0) Long version,
            @Size(max = 500) String reason) {
    }

    record RotateBookingTokenRequest(
            @NotNull @Min(0) Long version) {
    }

    record BookingSecurityCapabilities(
            boolean authenticatedBookingEnabled,
            boolean externalPublicBookingEnabled,
            String externalPublicBookingReason,
            int holdTtlSeconds) {
    }

    record AuthenticatedBookablePage(
            UUID pageId,
            long pageVersion,
            String title,
            String description,
            String timeZone,
            int durationMinutes,
            MeetingMode meetingMode,
            String location,
            String conferenceUrl,
            String dialInInstructions,
            List<BookingQuestion> questions) {
    }

    record AvailabilitySlot(
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
    }

    record BookingAvailability(
            UUID pageId,
            long pageVersion,
            OffsetDateTime from,
            OffsetDateTime to,
            List<AvailabilitySlot> slots,
            OffsetDateTime generatedAt) {
    }

    record PublicBookingPage(
            String title,
            String description,
            String timeZone,
            int durationMinutes,
            MeetingMode meetingMode,
            String location,
            String conferenceUrl,
            String dialInInstructions,
            List<BookingQuestion> questions) {
    }

    record ExternalBooker(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 200) String verificationProof) {
    }
}
