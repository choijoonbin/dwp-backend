package com.dwp.services.synapsex.util;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

/**
 * Drill-down 공통 Query Param: range / from / to 규칙
 * 계약: from 또는 to가 있으면 range 무시. range와 from/to 동시 제공 시 400 반환.
 */
public final class DrillDownParamUtil {

    private DrillDownParamUtil() {}

    /** range enum: 1h|6h|24h|7d|30d|90d */
    public static final List<String> RANGE_VALUES = List.of("1h", "6h", "24h", "7d", "30d", "90d");

    /**
     * range와 from/to 동시 제공 시 400. 계약: 둘 중 하나만 사용.
     */
    public static void validateRangeExclusive(String range, Instant from, Instant to) {
        boolean hasRange = range != null && !range.isBlank();
        boolean hasFromTo = from != null || to != null;
        if (hasRange && hasFromTo) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "range와 from/to를 동시에 지정할 수 없습니다. 둘 중 하나만 사용하세요.");
        }
    }

    /**
     * from/to 우선, 없으면 range 사용.
     * 계약: from 또는 to가 있으면 range 무시. 둘 다 없으면 range로 계산.
     * validateRangeExclusive 호출 후 사용.
     */
    public static TimeRange resolve(String range, Instant from, Instant to) {
        Instant now = Instant.now();
        if (from != null || to != null) {
            if (to == null) to = now;
            if (from == null) from = to.minus(24, ChronoUnit.HOURS);
            return new TimeRange(from, to);
        }
        if (range != null && !range.isBlank()) {
            Instant fromResolved = parseRangeToFrom(range);
            return new TimeRange(fromResolved, now);
        }
        return new TimeRange(now.minus(24, ChronoUnit.HOURS), now);
    }

    public static Instant parseRangeToFrom(String range) {
        if (range == null || range.isBlank()) return Instant.now().minus(24, ChronoUnit.HOURS);
        return switch (range.toLowerCase()) {
            case "1h" -> Instant.now().minus(1, ChronoUnit.HOURS);
            case "6h" -> Instant.now().minus(6, ChronoUnit.HOURS);
            case "24h" -> Instant.now().minus(24, ChronoUnit.HOURS);
            case "7d" -> Instant.now().minus(7, ChronoUnit.DAYS);
            case "30d" -> Instant.now().minus(30, ChronoUnit.DAYS);
            case "90d" -> Instant.now().minus(90, ChronoUnit.DAYS);
            default -> Instant.now().minus(24, ChronoUnit.HOURS);
        };
    }

    /** comma-separated string → List (trim, non-empty) */
    public static List<String> parseMulti(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Stream.of(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** comma-separated string → List<Long> (ids). "case-1", "1", "AC-2026-0321" 등 숫자 추출 */
    public static List<Long> parseIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Stream.of(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    String num = s.replaceAll("\\D+", "");
                    if (num.isEmpty()) return null;
                    try { return Long.parseLong(num); } catch (NumberFormatException e) { return null; }
                })
                .filter(id -> id != null)
                .toList();
    }

    public record TimeRange(Instant from, Instant to) {}

    /**
     * sort=field,dir 형식 또는 sort+order 분리 형식 파싱.
     * sort에 쉼표가 있으면 field,dir로 분리; 없으면 sort=field, order=dir.
     */
    public static String[] parseSortAndOrder(String sort, String order, String defaultField, String defaultDir) {
        if (sort != null && !sort.isBlank() && sort.contains(",")) {
            String[] parts = sort.split(",", 2);
            return new String[]{parts[0].trim(), parts.length >= 2 && !parts[1].isBlank() ? parts[1].trim() : defaultDir};
        }
        String f = sort != null && !sort.isBlank() ? sort.trim() : defaultField;
        String d = order != null && !order.isBlank() ? order.trim() : defaultDir;
        return new String[]{f, d};
    }
}
