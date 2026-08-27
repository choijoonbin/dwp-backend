package com.dwp.services.platform.calendar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Service
class CalendarSchedulingHorizon {

    private final Clock clock;

    @Autowired
    CalendarSchedulingHorizon() {
        this(Clock.systemUTC());
    }

    CalendarSchedulingHorizon(Clock clock) {
        this.clock = clock;
    }

    Horizon evaluate(ZoneId zone, int maximumAdvanceDays) {
        Instant now = clock.instant();
        LocalDate latestDate = now.atZone(zone).toLocalDate().plusDays(maximumAdvanceDays);
        return new Horizon(now, latestDate);
    }

    record Horizon(Instant now, LocalDate latestDate) {

        boolean contains(OffsetDateTime value, ZoneId zone) {
            return !value.atZoneSameInstant(zone).toLocalDate().isAfter(latestDate);
        }

        boolean contains(LocalDate value) {
            return value == null || !value.isAfter(latestDate);
        }

        boolean isPast(OffsetDateTime value) {
            return value.toInstant().isBefore(now);
        }
    }
}
