package com.dwp.services.platform.calendar;

/** Read-only boundary through which Calendar consumes provider projections. */
public interface CalendarExternalProjectionPort {

    CalendarExternalDtos.Projection project(CalendarExternalDtos.Query query);
}
