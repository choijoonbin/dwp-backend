package com.dwp.services.platform.workplace;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportRepository.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.PolicyScopeType;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportSupport.invalid;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportSupport.detail;

@Component
class WorkplaceExperienceReportPolicyPreview {
    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceRuntimeGovernance governance;

    WorkplaceExperienceReportPolicyPreview(WorkplaceCatalogRepository catalog,
                                          WorkplaceRuntimeGovernance governance) {
        this.catalog = catalog;
        this.governance = governance;
    }

    List<PolicyEffect> effects(Long tenantId, SiteRow site, List<BookingRow> bookings,
                               PolicyChanges changes, OffsetDateTime generatedAt) {
        WorkplaceCatalogRepository.PolicyRow base = catalog.policy(tenantId);
        validate(changes, base);
        Map<UUID, WorkplaceCatalogRepository.PolicyRow> currentByResource = new HashMap<>();
        List<PolicyEffect> effects = new ArrayList<>();
        ZoneId zone = ZoneId.of(site.timeZone());
        for (BookingRow b : bookings) {
            WorkplaceCatalogRepository.PolicyRow current = currentByResource.computeIfAbsent(b.resourceId(),
                    resource -> governance.effectivePolicy(tenantId, PolicyScopeType.RESOURCE, resource, base));
            validate(changes, current);
            List<String> known = knownEffects(b, zone, current, changes);
            if (!known.isEmpty()) effects.add(new PolicyEffect(
                    detail(b, generatedAt), known));
        }
        return effects;
    }

    static List<PolicyDailyImpact> dailyImpact(List<BookingRow> bookings, List<PolicyEffect> effects,
                                               String timeZone, OffsetDateTime from, OffsetDateTime to) {
        ZoneId zone = ZoneId.of(timeZone);
        Set<UUID> affected = effects.stream().map(e -> e.booking().bookingId()).collect(Collectors.toSet());
        Map<LocalDate, long[]> counts = new HashMap<>();
        for (BookingRow row : bookings) {
            OffsetDateTime bucketStart = row.startsAt().isBefore(from) ? from : row.startsAt();
            LocalDate day = bucketStart.atZoneSameInstant(zone).toLocalDate();
            long[] count = counts.computeIfAbsent(day, ignored -> new long[2]);
            count[0]++;
            if (affected.contains(row.bookingId())) count[1]++;
        }
        List<PolicyDailyImpact> result = new ArrayList<>();
        LocalDate last = to.minusNanos(1).atZoneSameInstant(zone).toLocalDate();
        for (LocalDate day = from.atZoneSameInstant(zone).toLocalDate(); !day.isAfter(last); day = day.plusDays(1)) {
            long[] count = counts.getOrDefault(day, new long[2]);
            result.add(new PolicyDailyImpact(day, timeZone, count[0], count[1]));
        }
        return List.copyOf(result);
    }

    static List<String> knownEffects(BookingRow booking, ZoneId zone,
                                     WorkplaceCatalogRepository.PolicyRow current, PolicyChanges changes) {
        List<String> effects = new ArrayList<>();
        double minutes = Duration.between(booking.startsAt(), booking.endsAt()).toSeconds() / 60.0;
        if (changes.minimumBookingMinutes() != null
                && changes.minimumBookingMinutes() != current.minimumBookingMinutes()
                && minutes < changes.minimumBookingMinutes()) effects.add("BELOW_PROPOSED_MINIMUM_DURATION");
        if (changes.maximumBookingMinutes() != null
                && changes.maximumBookingMinutes() != current.maximumBookingMinutes()
                && minutes > changes.maximumBookingMinutes()) effects.add("EXCEEDS_PROPOSED_MAXIMUM_DURATION");
        LocalTime start = changes.workingDayStart() == null ? current.workingDayStart() : changes.workingDayStart();
        LocalTime end = changes.workingDayEnd() == null ? current.workingDayEnd() : changes.workingDayEnd();
        if (!start.equals(current.workingDayStart()) || !end.equals(current.workingDayEnd())) {
            var localStart = booking.startsAt().atZoneSameInstant(zone);
            var localEnd = booking.endsAt().atZoneSameInstant(zone);
            if (!localStart.toLocalDate().equals(localEnd.toLocalDate())
                    || localStart.toLocalTime().isBefore(start) || localEnd.toLocalTime().isAfter(end)) {
                effects.add("OUTSIDE_PROPOSED_WORKING_HOURS");
            }
        }
        boolean requireCheckIn = changes.requireCheckIn() == null
                ? current.requireCheckIn() : changes.requireCheckIn();
        if (changes.requireCheckIn() != null && requireCheckIn != current.requireCheckIn()) {
            effects.add(requireCheckIn ? "CHECK_IN_REQUIREMENT_ADDED" : "CHECK_IN_REQUIREMENT_REMOVED");
        }
        if (changes.autoReleaseMinutes() != null
                && changes.autoReleaseMinutes() != current.autoReleaseMinutes()
                && requireCheckIn && "RESERVED".equals(booking.status())) {
            effects.add("PROPOSED_AUTO_RELEASE_DEADLINE_CHANGED");
        }
        return effects;
    }

    private static void validate(PolicyChanges c, WorkplaceCatalogRepository.PolicyRow current) {
        int min = c.minimumBookingMinutes() == null ? current.minimumBookingMinutes() : c.minimumBookingMinutes();
        int max = c.maximumBookingMinutes() == null ? current.maximumBookingMinutes() : c.maximumBookingMinutes();
        LocalTime start = c.workingDayStart() == null ? current.workingDayStart() : c.workingDayStart();
        LocalTime end = c.workingDayEnd() == null ? current.workingDayEnd() : c.workingDayEnd();
        if (min < 15 || min > 1440 || max < min || max > 10080 || !end.isAfter(start)
                || c.autoReleaseMinutes() != null && (c.autoReleaseMinutes() < 0 || c.autoReleaseMinutes() > 240)) {
            throw invalid("Proposed policy duration, working hours or auto-release bounds are invalid.");
        }
    }
}
