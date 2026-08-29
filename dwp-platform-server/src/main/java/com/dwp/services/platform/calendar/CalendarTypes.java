package com.dwp.services.platform.calendar;

public final class CalendarTypes {

    private CalendarTypes() {
    }

    public enum CalendarType { PERSONAL, TEAM, RESOURCE, SYSTEM }

    public enum CalendarAccessLevel {
        OWNER, MANAGE, EDIT, VIEW_DETAILS, VIEW_FREE_BUSY, EVENT_ATTENDEE, NONE
    }

    public enum CalendarSourceKind { OWNED, COMPANY, SHARED, TEAM, RESOURCE }

    public enum CalendarSubscriptionPolicy { REQUIRED, DEFAULT_ON, OPTIONAL }

    public enum EventType { MEETING, FOCUS, TASK, OUT_OF_OFFICE, REMINDER }

    public enum EventStatus { CONFIRMED, TENTATIVE, CANCELLED }

    public enum EventVisibility { DEFAULT, PUBLIC, PRIVATE, CONFIDENTIAL }

    public enum EventImportance { LOW, NORMAL, HIGH }

    public enum EventDetailLevel { FULL, FREE_BUSY }

    public enum RecurrencePattern { NONE, DAILY, WEEKLY, MONTHLY }

    public enum AttendeeType { REQUIRED, OPTIONAL, RESOURCE }

    public enum ResponseStatus { NEEDS_ACTION, ACCEPTED, TENTATIVE, DECLINED }

    public enum ResourceType { ROOM, DESK, EQUIPMENT }

    public enum ResourceState { AVAILABLE, MAINTENANCE, RETIRED }

    public enum RoomBookingEligibilityReason {
        ELIGIBLE, RESOURCE_UNAVAILABLE, POLICY_BLOCKED, RESOURCE_CONFLICT
    }
}
