package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.BookingDetail;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportRepository.BookingRow;

final class WorkplaceExperienceReportSupport {
    private WorkplaceExperienceReportSupport() { }

    static BookingDetail detail(BookingRow b, OffsetDateTime observedAt) {
        List<String> reasons = new ArrayList<>();
        if ("NO_SHOW".equals(b.status())) reasons.add("NO_SHOW_RECORDED");
        if (Set.of("RESERVED", "CHECKED_IN").contains(b.status()) && !b.endsAt().isAfter(observedAt)) {
            reasons.add("PAST_BOOKING_UNSETTLED");
        }
        if (!"AVAILABLE".equals(b.resourceState()) && Set.of("RESERVED", "CHECKED_IN").contains(b.status())) {
            reasons.add("RESOURCE_UNAVAILABLE");
        }
        if (b.legalHold()) reasons.add("LEGAL_HOLD");
        return new BookingDetail(b.bookingId(), b.resourceId(), b.siteId(), b.floorId(), b.resourceName(),
                b.resourceType(), b.floorName(), b.status(), b.startsAt(), b.endsAt(), b.checkedInAt(),
                b.releasedAt(), b.legalHold(), b.version(), b.updatedAt(),
                "/workplace/admin/operations?siteId=" + b.siteId() + "&bookingId=" + b.bookingId(), reasons);
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
