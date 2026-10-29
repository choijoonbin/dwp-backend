package com.dwp.services.platform.calendar;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.util.List;

import static com.dwp.services.platform.calendar.CalendarTypes.EventImportance;

/** Normalizes values shared by calendar persistence operations. */
final class CalendarPersistenceValues {

    private CalendarPersistenceValues() { }

    static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    static EventImportance importance(EventImportance value) {
        return value == null ? EventImportance.NORMAL : value;
    }

    static Integer[] recurrenceDays(List<DayOfWeek> values) {
        if (values == null || values.isEmpty()) return null;
        return values.stream().distinct().sorted()
                .map(DayOfWeek::getValue).toArray(Integer[]::new);
    }
}
