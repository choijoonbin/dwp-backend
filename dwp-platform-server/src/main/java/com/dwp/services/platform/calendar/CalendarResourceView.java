package com.dwp.services.platform.calendar;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.ResourceState;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceType;

/** Persistence-neutral resource view used by calendar row mapping. */
interface CalendarResourceView {
    UUID resourceId();
    String code();
    String name();
    String nameKo();
    String nameEn();
    ResourceType type();
    String site();
    String floor();
    int capacity();
    List<String> features();
    String timeZone();
    boolean approvalRequired();
    ResourceState state();
    boolean available();
    long version();
}
