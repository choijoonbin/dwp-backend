package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Converts patch values using the governed target column schema. */
final class EmployeeChangePatchValues {

    static Object value(EmployeeChangeDtos.PatchOperation operation, Column column) {
        if ("remove".equals(operation.op())) {
            if (!column.nullable()) throw invalid(operation.path() + " cannot be removed.");
            return null;
        }
        Object raw = operation.value();
        try {
            return switch (column.kind()) {
                case TEXT -> text(raw, 1, 200, null);
                case TITLE -> text(raw, 1, 240, null);
                case KEY -> text(raw, 1, 100, "[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
                case CODE -> codeText(raw, 80);
                case LOCALE -> text(raw, 2, 35, "[A-Za-z0-9_-]{2,35}");
                case TIME_ZONE -> java.time.ZoneId.of(text(raw, 1, 80, null)).getId();
                case DATE -> LocalDate.parse(text(raw, 10, 10, "\\d{4}-\\d{2}-\\d{2}"));
                case BOOLEAN -> {
                    if (!(raw instanceof Boolean value)) throw new IllegalArgumentException();
                    yield value;
                }
                case HOURS -> decimal(raw, new BigDecimal("0.01"), new BigDecimal("168"));
                case FTE -> decimal(raw, BigDecimal.ZERO, BigDecimal.ONE);
                case WORKER_TYPE -> enumText(raw,
                        List.of("EMPLOYEE", "CONTINGENT", "NONWORKER", "PENDING"));
                case WORKER_STATUS -> enumText(raw,
                        List.of("ACTIVE", "LEAVE", "TERMINATED", "PENDING"));
                case ASSIGNMENT_STATUS -> enumText(raw,
                        List.of("ACTIVE", "SUSPENDED", "ENDED", "PENDING"));
            };
        } catch (RuntimeException exception) {
            throw invalid("The value for " + operation.path() + " is invalid.");
        }
    }

    static String text(Object raw, int minimum, int maximum, String pattern) {
        if (!(raw instanceof String value) || !value.equals(value.trim())
                || value.length() < minimum || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)
                || (pattern != null && !value.matches(pattern))) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    static String enumText(Object raw, List<String> allowed) {
        String value = text(raw, 1, 40, "[A-Za-z][A-Za-z0-9_-]{0,39}")
                .toUpperCase(Locale.ROOT);
        if (!allowed.isEmpty() && !allowed.contains(value)) throw new IllegalArgumentException();
        return value;
    }

    static String codeText(Object raw, int maximum) {
        String value = text(raw, 1, maximum, "[A-Za-z][A-Za-z0-9._-]{0,79}");
        return value.toUpperCase(Locale.ROOT);
    }

    static BigDecimal decimal(Object raw, BigDecimal minimum, BigDecimal maximum) {
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            throw new IllegalArgumentException();
        }
        BigDecimal value = new BigDecimal(raw.toString());
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException();
        }
        return value.stripTrailingZeros();
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    record Column(String name, boolean nullable, ValueKind kind) {
    }

    enum ValueKind {
        TEXT, TITLE, KEY, CODE, LOCALE, TIME_ZONE, DATE, BOOLEAN,
        HOURS, FTE, WORKER_TYPE, WORKER_STATUS, ASSIGNMENT_STATUS
    }
}
