package com.dwp.services.time.close;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseIaQueryModels.AggregateFilter;
import com.dwp.services.time.close.TimeCloseIaQueryModels.EffectiveRangeFilter;
import com.dwp.services.time.close.TimeCloseIaQueryModels.ReceiptFilter;
import com.dwp.services.time.close.TimeCloseIaQueryModels.StatusFilter;
import com.dwp.services.time.close.TimeCloseIaQueryModels.TimeCloseQuery;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Parses the exact IAQ-039/043/044 parameter and filter contracts. */
final class TimeCloseIaQueryParser {

    private TimeCloseIaQueryParser() {
    }

    private static final Set<String> BASE_PARAMETERS = Set.of(
            "limit", "sort", "locale", "timeZone", "correlationId", "asOf", "cursor",
            "filter[effectiveOn]", "filter[timePeriod][activeOn]",
            "filter[timePeriod][from]", "filter[timePeriod][to]");
    private static final Set<String> COMMAND_FILTERS = Set.of(
            "filter[exceptionSummary][minimumItemCount]",
            "filter[exceptionSummary][maximumItemCount]",
            "filter[commandReceipt][receiptIds]",
            "filter[commandReceipt][states]");
    private static final Set<String> DAILY_FILTERS = Set.of(
            "filter[impactSummary][minimumItemCount]",
            "filter[impactSummary][maximumItemCount]",
            "filter[closeStatus][codes]",
            "filter[closeStatus][minimumRevision]");
    private static final Set<String> MONTHLY_FILTERS = Set.of(
            "filter[payrollHandoff][receiptIds]",
            "filter[payrollHandoff][states]",
            "filter[closeStatus][codes]",
            "filter[closeStatus][minimumRevision]");

    static TimeCloseQuery parse(
            TimeCloseOwnerRoute route,
            VerifiedCloseRequest authority,
            HttpServletRequest request,
            UUID correlationHeader) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(request, "request");
        if (correlationHeader == null || !correlationHeader.equals(authority.correlationId())) {
            throw unavailable("The TIM close correlation contract is mismatched.");
        }
        Map<String, String[]> parameters = request.getParameterMap();
        if (parameters.size() > 32) throw invalid("Too many IAQ filter parameters.");
        Set<String> allowed = new LinkedHashSet<>(BASE_PARAMETERS);
        allowed.addAll(filters(route));
        if (!allowed.containsAll(parameters.keySet())) {
            throw invalid("The IAQ query contains an unknown parameter.");
        }
        int limit = integer(single(parameters, "limit", true), "limit", 1, 100);
        List<String> sort = many(parameters, "sort", true, 3);
        Set<String> sortAllowlist = sorts(route);
        if (new HashSet<>(sort).size() != sort.size() || !sortAllowlist.containsAll(sort)) {
            throw invalid("IAQ sort values must be unique and allowlisted.");
        }
        String locale = locale(single(parameters, "locale", true));
        String timeZone = zone(single(parameters, "timeZone", true));
        UUID correlationId = uuid(single(parameters, "correlationId", true));
        if (!correlationHeader.equals(correlationId)) {
            throw unavailable("The TIM close correlation contract is mismatched.");
        }
        Instant asOf = instant(single(parameters, "asOf", false));
        String cursor = single(parameters, "cursor", false);
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{16,1024}")) {
            throw invalid("The IAQ cursor is malformed.");
        }
        LocalDate effectiveOn = date(single(parameters, "filter[effectiveOn]", false));
        EffectiveRangeFilter range = new EffectiveRangeFilter(
                date(single(parameters, "filter[timePeriod][activeOn]", false)),
                date(single(parameters, "filter[timePeriod][from]", false)),
                date(single(parameters, "filter[timePeriod][to]", false)));
        if (range.from() != null && range.to() != null && range.to().isBefore(range.from())) {
            throw invalid("The IAQ effective range is inverted.");
        }
        AggregateFilter aggregate = switch (route) {
            case COMMAND_CENTER -> aggregate(parameters, "exceptionSummary");
            case DAILY_CLOSE -> aggregate(parameters, "impactSummary");
            default -> null;
        };
        StatusFilter status = route == TimeCloseOwnerRoute.DAILY_CLOSE
                        || route == TimeCloseOwnerRoute.MONTHLY_CLOSE
                ? status(parameters) : null;
        ReceiptFilter receipt = switch (route) {
            case COMMAND_CENTER -> receipt(parameters, "commandReceipt");
            case MONTHLY_CLOSE -> receipt(parameters, "payrollHandoff");
            default -> null;
        };
        return new TimeCloseQuery(
                limit, sort, locale, timeZone, correlationId, asOf, cursor,
                effectiveOn, range, aggregate, status, receipt);
    }

    private static Set<String> filters(TimeCloseOwnerRoute route) {
        return switch (route) {
            case COMMAND_CENTER -> COMMAND_FILTERS;
            case DAILY_CLOSE -> DAILY_FILTERS;
            case MONTHLY_CLOSE -> MONTHLY_FILTERS;
            default -> throw new IllegalArgumentException("not an IAQ route");
        };
    }

    private static Set<String> sorts(TimeCloseOwnerRoute route) {
        return switch (route) {
            case COMMAND_CENTER -> Set.of(
                    "timePeriod:ASC", "exceptionSummary:ASC", "commandReceipt:ASC",
                    "timePeriod:DESC", "exceptionSummary:DESC", "commandReceipt:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC");
            case DAILY_CLOSE -> Set.of(
                    "timePeriod:ASC", "closeStatus:ASC", "impactSummary:ASC",
                    "timePeriod:DESC", "closeStatus:DESC", "impactSummary:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC");
            case MONTHLY_CLOSE -> Set.of(
                    "timePeriod:ASC", "closeStatus:ASC", "payrollHandoff:ASC",
                    "timePeriod:DESC", "closeStatus:DESC", "payrollHandoff:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC");
            default -> throw new IllegalArgumentException("not an IAQ route");
        };
    }

    private static AggregateFilter aggregate(Map<String, String[]> values, String group) {
        Long minimum = nullableLong(single(
                values, "filter[" + group + "][minimumItemCount]", false), 0);
        Long maximum = nullableLong(single(
                values, "filter[" + group + "][maximumItemCount]", false), 0);
        if (minimum != null && maximum != null && maximum < minimum) {
            throw invalid("The IAQ aggregate range is inverted.");
        }
        return minimum == null && maximum == null ? null : new AggregateFilter(minimum, maximum);
    }

    private static StatusFilter status(Map<String, String[]> values) {
        List<String> codes = many(values, "filter[closeStatus][codes]", false, 50);
        Long revision = nullableLong(single(
                values, "filter[closeStatus][minimumRevision]", false), 1);
        for (String code : codes) {
            if (!code.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw invalid("The IAQ status code is invalid.");
            }
        }
        return codes.isEmpty() && revision == null ? null : new StatusFilter(codes, revision);
    }

    private static ReceiptFilter receipt(Map<String, String[]> values, String group) {
        List<String> ids = many(values, "filter[" + group + "][receiptIds]", false, 50);
        List<String> states = many(values, "filter[" + group + "][states]", false, 50);
        for (String value : ids) {
            if (!value.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw invalid("The IAQ receipt id is invalid.");
            }
        }
        for (String state : states) {
            if (!state.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw invalid("The IAQ receipt state is invalid.");
            }
        }
        return ids.isEmpty() && states.isEmpty() ? null : new ReceiptFilter(ids, states);
    }

    private static String single(
            Map<String, String[]> values, String name, boolean required) {
        String[] candidates = values.get(name);
        if (candidates == null) {
            if (required) throw invalid("Missing IAQ parameter " + name + ".");
            return null;
        }
        if (candidates.length != 1 || candidates[0] == null
                || candidates[0].isBlank() || candidates[0].length() > 1_024
                || !candidates[0].equals(candidates[0].strip())) {
            throw invalid("IAQ parameter " + name + " is malformed.");
        }
        return candidates[0];
    }

    private static List<String> many(
            Map<String, String[]> values, String name, boolean required, int maximum) {
        String[] candidates = values.get(name);
        if (candidates == null) {
            if (required) throw invalid("Missing IAQ parameter " + name + ".");
            return List.of();
        }
        if (candidates.length < 1 || candidates.length > maximum) {
            throw invalid("IAQ parameter " + name + " has invalid cardinality.");
        }
        List<String> result = new ArrayList<>(candidates.length);
        Set<String> seen = new HashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || candidate.length() > 256
                    || !candidate.equals(candidate.strip()) || !seen.add(candidate)) {
                throw invalid("IAQ parameter " + name + " is malformed.");
            }
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    private static int integer(String value, String name, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw invalid("IAQ parameter " + name + " is outside its bounds.");
        }
    }

    private static Long nullableLong(String value, long minimum) {
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw invalid("IAQ numeric filter is outside its bounds.");
        }
    }

    private static LocalDate date(String value) {
        if (value == null) return null;
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) throw new DateTimeParseException("", value, 0);
            return parsed;
        } catch (DateTimeParseException invalid) {
            throw invalid("IAQ date is invalid.");
        }
    }

    private static Instant instant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw invalid("IAQ asOf is invalid.");
        }
    }

    private static UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value) || parsed.equals(new UUID(0, 0))) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw invalid("IAQ correlation id is invalid.");
        }
    }

    private static String locale(String value) {
        if (value.length() < 2 || value.length() > 35) throw invalid("IAQ locale is invalid.");
        Locale parsed = Locale.forLanguageTag(value);
        if (parsed.getLanguage().isBlank() || "und".equals(parsed.toLanguageTag())) {
            throw invalid("IAQ locale is invalid.");
        }
        return parsed.toLanguageTag();
    }

    private static String zone(String value) {
        try {
            if (value.length() > 255) throw new DateTimeException("too long");
            return ZoneId.of(value).getId();
        } catch (DateTimeException invalid) {
            throw invalid("IAQ timeZone is invalid.");
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
