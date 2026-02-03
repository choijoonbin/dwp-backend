package com.dwp.services.synapsex.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

/**
 * Drill-down 공통 Query Param: range / from / to 규칙
 * range가 있으면 from/to 무시. range 없으면 from/to 사용 (보정 적용).
 */
public final class DrillDownParamUtil {

    private DrillDownParamUtil() {}

    /** range enum: 1h|6h|24h|7d|30d|90d */
    public static final List<String> RANGE_VALUES = List.of("1h", "6h", "24h", "7d", "30d", "90d");

    /**
     * range 또는 from/to로 (fromInstant, toInstant) 계산.
     * range가 있으면 now 기준 계산. 없으면 from/to 사용 (to 없으면 now, from 없으면 to-24h).
     */
    public static TimeRange resolve(String range, Instant from, Instant to) {
        Instant now = Instant.now();
        if (range != null && !range.isBlank()) {
            Instant fromResolved = parseRangeToFrom(range);
            return new TimeRange(fromResolved, now);
        }
        if (to == null) to = now;
        if (from == null) from = to.minus(24, ChronoUnit.HOURS);
        return new TimeRange(from, to);
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
}
