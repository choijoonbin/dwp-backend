package com.dwp.services.platform.workplace;

import java.time.OffsetDateTime;

import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;

final class WorkplaceBookingViewMapper {

    private WorkplaceBookingViewMapper() {
    }

    static WorkplaceDtos.Booking toBooking(
            WorkplaceBookingRepository.BookingRow value,
            OffsetDateTime now) {
        OffsetDateTime checkInOpensAt = value.startsAt()
                .minusMinutes(value.checkInLeadMinutes());
        OffsetDateTime checkInClosesAt = value.startsAt()
                .plusMinutes(value.autoReleaseMinutes());
        boolean active = value.status() == BookingStatus.RESERVED
                || value.status() == BookingStatus.CHECKED_IN;
        boolean canCheckIn = value.requireCheckIn()
                && value.status() == BookingStatus.RESERVED
                && !now.isBefore(checkInOpensAt)
                && !now.isAfter(checkInClosesAt)
                && now.isBefore(value.endsAt());
        boolean canCancel = value.status() == BookingStatus.RESERVED
                && now.isBefore(value.startsAt());
        boolean canRelease = active
                && !now.isBefore(value.startsAt())
                && now.isBefore(value.endsAt());
        return new WorkplaceDtos.Booking(
                value.bookingId(), value.resourceId(), value.resourceName(), value.resourceType(),
                value.siteName(), value.floorName(), value.purpose(), value.startsAt(), value.endsAt(),
                value.status(), value.visibleToColleagues(), value.checkedInAt(),
                value.releasedAt(), canCheckIn, canCancel, canRelease,
                checkInOpensAt, checkInClosesAt, value.version());
    }
}
