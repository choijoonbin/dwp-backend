package com.dwp.services.platform.calendar;

public final class CalendarTypes {

    private CalendarTypes() {
    }

    public enum CalendarType { PERSONAL, TEAM, RESOURCE, SYSTEM }

    public enum EventType { MEETING, FOCUS, TASK, OUT_OF_OFFICE, REMINDER }

    public enum EventStatus { CONFIRMED, TENTATIVE, CANCELLED }

    public enum EventVisibility { DEFAULT, PUBLIC, PRIVATE, CONFIDENTIAL }

    public enum RecurrencePattern { NONE, DAILY, WEEKLY, MONTHLY }

    public enum AttendeeType { REQUIRED, OPTIONAL, RESOURCE }

    public enum ResponseStatus { NEEDS_ACTION, ACCEPTED, TENTATIVE, DECLINED }

    public enum ResourceType { ROOM, DESK, EQUIPMENT }

    public enum ResourceState { AVAILABLE, MAINTENANCE, RETIRED }
}
