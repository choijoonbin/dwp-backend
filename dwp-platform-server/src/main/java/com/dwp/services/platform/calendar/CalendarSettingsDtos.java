package com.dwp.services.platform.calendar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class CalendarSettingsDtos {

    private CalendarSettingsDtos() {
    }

    public enum SpeedyMeetingMode {
        STANDARD,
        FIVE_TEN
    }

    public enum DefaultVisibility {
        FREE_BUSY,
        DETAILS,
        PRIVATE
    }

    public enum DelegationScope {
        RESPOND,
        EDIT_SCHEDULE,
        CREATE
    }

    public enum DelegationStatus {
        SCHEDULED,
        ACTIVE,
        EXPIRED,
        REVOKED
    }

    public enum SettingKey {
        WORKING_DAYS,
        WORKING_DAY_START,
        WORKING_DAY_END,
        TIME_ZONE,
        WEEK_START,
        DEFAULT_EVENT_MINUTES,
        SPEEDY_MEETING_MODE,
        DEFAULT_BUFFER_MINUTES,
        DEFAULT_VISIBILITY,
        DEFAULT_REMINDER_MINUTES
    }

    public enum SettingSource {
        USER,
        TENANT_POLICY,
        SYSTEM_DEFAULT
    }

    public record SettingGovernance(
            SettingKey key,
            SettingSource source,
            boolean managed,
            boolean inherited,
            boolean locked) {
    }

    public record Settings(
            List<DayOfWeek> workingDays,
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            String timeZone,
            DayOfWeek weekStart,
            int defaultEventMinutes,
            SpeedyMeetingMode speedyMeetingMode,
            int defaultBufferMinutes,
            DefaultVisibility defaultVisibility,
            int defaultReminderMinutes,
            List<SettingGovernance> governance,
            long version,
            OffsetDateTime updatedAt) {

        public Settings {
            workingDays = List.copyOf(workingDays);
            governance = List.copyOf(governance);
        }
    }

    public record UpdateSettingsRequest(
            @NotEmpty @Size(max = 7) List<@NotNull DayOfWeek> workingDays,
            @NotNull LocalTime workingDayStart,
            @NotNull LocalTime workingDayEnd,
            @NotBlank @Size(max = 80) String timeZone,
            @NotNull DayOfWeek weekStart,
            @Min(5) @Max(1440) int defaultEventMinutes,
            @NotNull SpeedyMeetingMode speedyMeetingMode,
            @Min(0) @Max(120) int defaultBufferMinutes,
            @NotNull DefaultVisibility defaultVisibility,
            @Min(0) @Max(10080) int defaultReminderMinutes,
            @NotNull @PositiveOrZero Long version) {
    }

    public record Delegation(
            UUID delegationId,
            UUID ownerPersonPublicId,
            UUID delegatePersonPublicId,
            List<DelegationScope> scopes,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            DelegationStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        public Delegation {
            scopes = List.copyOf(scopes);
        }
    }

    public record CreateDelegationRequest(
            @NotNull UUID delegatePersonPublicId,
            @NotEmpty @Size(max = 3) List<@NotNull DelegationScope> scopes,
            @NotNull OffsetDateTime validFrom,
            @NotNull OffsetDateTime validUntil) {
    }

    public record RevokeDelegationRequest(
            @NotNull @PositiveOrZero Long version) {
    }

    public record ResetSettingsRequest(
            @NotNull @PositiveOrZero Long version) {
    }
}
