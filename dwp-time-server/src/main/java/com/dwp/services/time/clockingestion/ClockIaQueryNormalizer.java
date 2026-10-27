package com.dwp.services.time.clockingestion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Normalizes IAQ-034/042 queries and their stable cursor fingerprint inputs. */
final class ClockIaQueryNormalizer {

    private static final int MAX_QUERY_LIMIT = 100;
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern METADATA_VERSION = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{16,1024}");
    private static final Set<String> CLOCK_SORTS = Set.of(
            "clockEvent:ASC", "clockEvent:DESC",
            "ingestionStatus:ASC", "ingestionStatus:DESC",
            "qualityFinding:ASC", "qualityFinding:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");
    private static final Set<String> EXCEPTION_SORTS = Set.of(
            "exception:ASC", "exception:DESC",
            "ownerDeepLink:ASC", "ownerDeepLink:DESC",
            "recoveryMetadata:ASC", "recoveryMetadata:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");

    private final Clock clock;

    ClockIaQueryNormalizer(Clock clock) {
        this.clock = clock;
    }

    NormalizedClockQuery normalizeClock(ClockIaQueryModels.ClockQueryRequest request) {
        if (request == null || request.filter() == null) {
            throw invalid("A complete IAQ-034 request is required.");
        }
        CommonQuery common = common(
                request.limit(), request.sort(), CLOCK_SORTS, request.locale(),
                request.timeZone(), request.correlationId(), request.asOf(), request.cursor(),
                "IAQ-034");
        ClockIaQueryModels.ClockFilter filter = request.filter();
        ClockIaQueryModels.TemporalFilter temporal = filter.clockEvent();
        Instant startFrom = temporal == null ? null : temporal.startFrom();
        Instant startTo = temporal == null ? null : temporal.startTo();
        Instant endFrom = temporal == null ? null : temporal.endFrom();
        Instant endTo = temporal == null ? null : temporal.endTo();
        if ((startFrom != null && startTo != null && startFrom.isAfter(startTo))
                || (endFrom != null && endTo != null && endFrom.isAfter(endTo))) {
            throw invalid("IAQ-034 temporal bounds are inverted.");
        }
        List<String> ingestionCodes = filter.ingestionStatus() == null
                ? List.of()
                : codes(filter.ingestionStatus().codes(), "IAQ-034 ingestion status");
        Long minimumRevision = filter.ingestionStatus() == null
                ? null
                : positive(filter.ingestionStatus().minimumRevision(),
                        "IAQ-034 ingestion revision");
        List<String> qualityCodes = filter.qualityFinding() == null
                ? List.of()
                : codes(filter.qualityFinding().decisionCodes(), "IAQ-034 quality finding");
        String evidenceDigest = filter.qualityFinding() == null
                ? null
                : filter.qualityFinding().evidenceDigest();
        if (evidenceDigest != null && !evidenceDigest.matches("^[0-9a-f]{64}$")) {
            throw invalid("IAQ-034 evidence digest is invalid.");
        }
        return new NormalizedClockQuery(
                common.limit(), common.sort(), common.locale(), common.zoneId(),
                common.correlationId(), common.asOf(), common.cursor(), startFrom, startTo,
                endFrom, endTo, filter.effectiveOn(), ingestionCodes, minimumRevision,
                qualityCodes, evidenceDigest);
    }

    NormalizedExceptionQuery normalizeException(
            ClockIaQueryModels.ExceptionQueryRequest request) {
        if (request == null || request.filter() == null) {
            throw invalid("A complete IAQ-042 request is required.");
        }
        CommonQuery common = common(
                request.limit(), request.sort(), EXCEPTION_SORTS, request.locale(),
                request.timeZone(), request.correlationId(), request.asOf(), request.cursor(),
                "IAQ-042");
        ClockIaQueryModels.ExceptionFilter filter = request.filter();
        List<String> exceptionCodes = filter.exception() == null
                ? List.of()
                : codes(filter.exception().codes(), "IAQ-042 exception");
        Long minimumRevision = filter.exception() == null
                ? null
                : positive(filter.exception().minimumRevision(),
                        "IAQ-042 exception revision");
        List<String> ownerCodes = filter.ownerDeepLink() == null
                ? List.of()
                : codes(filter.ownerDeepLink().codes(), "IAQ-042 owner code");
        List<String> ownerPublicIds = filter.ownerDeepLink() == null
                ? List.of()
                : values(
                        filter.ownerDeepLink().publicIds(), PUBLIC_ID,
                        128, "IAQ-042 owner public id");
        List<String> metadataKeys = filter.recoveryMetadata() == null
                ? List.of()
                : values(
                        filter.recoveryMetadata().keys(), PUBLIC_ID,
                        128, "IAQ-042 metadata key");
        List<String> metadataVersions = filter.recoveryMetadata() == null
                ? List.of()
                : values(
                        filter.recoveryMetadata().versions(), METADATA_VERSION,
                        64, "IAQ-042 metadata version");
        return new NormalizedExceptionQuery(
                common.limit(), common.sort(), common.locale(), common.zoneId(),
                common.correlationId(), common.asOf(), common.cursor(), filter.effectiveOn(),
                exceptionCodes, minimumRevision, ownerCodes, ownerPublicIds,
                metadataKeys, metadataVersions);
    }

    private CommonQuery common(
            int limit,
            List<String> sort,
            Set<String> allowedSorts,
            String localeValue,
            String zoneValue,
            UUID correlationId,
            Instant asOf,
            String cursor,
            String queryId) {
        if (limit < 1 || limit > MAX_QUERY_LIMIT
                || sort == null || sort.isEmpty() || sort.size() > 3
                || correlationId == null) {
            throw invalid("A complete " + queryId + " request is required.");
        }
        List<String> normalizedSort = List.copyOf(sort);
        if (new HashSet<>(normalizedSort).size() != normalizedSort.size()
                || normalizedSort.stream().anyMatch(value -> !allowedSorts.contains(value))) {
            throw invalid(queryId + " sort values must be unique and allowlisted.");
        }
        if (cursor != null && !CURSOR.matcher(cursor).matches()) {
            throw invalid(queryId + " cursor format is invalid.");
        }
        if (cursor != null && asOf == null) {
            throw invalid(queryId + " continuation requires the original asOf instant.");
        }
        return new CommonQuery(
                limit,
                normalizedSort,
                canonicalLocale(localeValue, queryId),
                canonicalZone(zoneValue, queryId),
                correlationId,
                asOf == null ? clock.instant() : asOf,
                cursor);
    }

    private static List<String> codes(List<String> input, String label) {
        return values(input, CODE, 64, label);
    }

    private static List<String> values(
            List<String> input,
            Pattern pattern,
            int maximumLength,
            String label) {
        if (input == null) return List.of();
        if (input.size() > 50
                || new HashSet<>(input).size() != input.size()
                || input.stream().anyMatch(value -> value == null
                        || value.length() > maximumLength
                        || !pattern.matcher(value).matches())) {
            throw invalid(label + " filter is invalid.");
        }
        return input.stream().sorted().toList();
    }

    private static Long positive(Long value, String label) {
        if (value != null && value < 1) throw invalid(label + " must be positive.");
        return value;
    }

    private static String canonicalLocale(String value, String queryId) {
        if (value == null || value.length() < 2 || value.length() > 35) {
            throw invalid(queryId + " locale is invalid.");
        }
        Locale locale = Locale.forLanguageTag(value.replace('_', '-'));
        if (locale.getLanguage().isBlank() || "und".equals(locale.toLanguageTag())) {
            throw invalid(queryId + " locale is invalid.");
        }
        return locale.toLanguageTag();
    }

    private static ZoneId canonicalZone(String value, String queryId) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw invalid(queryId + " time zone is invalid.");
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw invalid(queryId + " time zone is invalid.");
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record CommonQuery(
            int limit,
            List<String> sort,
            String locale,
            ZoneId zoneId,
            UUID correlationId,
            Instant asOf,
            String cursor) {}

    record NormalizedClockQuery(
            int limit,
            List<String> sort,
            String locale,
            ZoneId zoneId,
            UUID correlationId,
            Instant asOf,
            String cursor,
            Instant startFrom,
            Instant startTo,
            Instant endFrom,
            Instant endTo,
            java.time.LocalDate effectiveOn,
            List<String> ingestionCodes,
            Long minimumIngestionRevision,
            List<String> qualityCodes,
            String evidenceDigest) {

        String fingerprintSource() {
            return String.join("|",
                    Integer.toString(limit), String.join(",", sort), locale, zoneId.getId(),
                    asOf.toString(), String.valueOf(startFrom), String.valueOf(startTo),
                    String.valueOf(endFrom), String.valueOf(endTo), String.valueOf(effectiveOn),
                    String.join(",", ingestionCodes), String.valueOf(minimumIngestionRevision),
                    String.join(",", qualityCodes), String.valueOf(evidenceDigest));
        }
    }

    record NormalizedExceptionQuery(
            int limit,
            List<String> sort,
            String locale,
            ZoneId zoneId,
            UUID correlationId,
            Instant asOf,
            String cursor,
            java.time.LocalDate effectiveOn,
            List<String> exceptionCodes,
            Long minimumRevision,
            List<String> ownerCodes,
            List<String> ownerPublicIds,
            List<String> metadataKeys,
            List<String> metadataVersions) {

        String fingerprintSource() {
            return String.join("|",
                    Integer.toString(limit), String.join(",", sort), locale, zoneId.getId(),
                    asOf.toString(), String.valueOf(effectiveOn),
                    String.join(",", exceptionCodes), String.valueOf(minimumRevision),
                    String.join(",", ownerCodes), String.join(",", ownerPublicIds),
                    String.join(",", metadataKeys), String.join(",", metadataVersions));
        }
    }
}
