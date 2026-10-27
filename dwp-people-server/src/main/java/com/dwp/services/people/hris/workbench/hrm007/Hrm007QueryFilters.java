package com.dwp.services.people.hris.workbench.hrm007;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonicalizes workbench query filters and locale preferences. */
final class Hrm007QueryFilters {

    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern POLICY_KEY =
            Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    static Object filter(Hrm007QueryDefinition definition, Object source) {
        return switch (definition) {
            case PEOPLE_CHANGES -> {
                if (!(source instanceof Hrm007Dtos.PeopleChangesFilter value)) {
                    throw Hrm007Exception.invalid("IAQ-005 filter shape is invalid.");
                }
                yield new Hrm007Dtos.PeopleChangesFilter(
                        aggregate(value.changeRequestSummary()),
                        normalizeStatus(value.limitedEmployment()),
                        normalizeStatus(value.approvalStatus()),
                        value.effectiveOn());
            }
            case APPOINTMENTS -> {
                if (!(source instanceof Hrm007Dtos.AppointmentsFilter value)) {
                    throw Hrm007Exception.invalid("IAQ-007 filter shape is invalid.");
                }
                yield new Hrm007Dtos.AppointmentsFilter(
                        temporal(value.appointment()), reference(value.assignment()),
                        range(value.employment()), value.effectiveOn());
            }
            case PEOPLE_EMPLOYMENT -> {
                if (!(source instanceof Hrm007Dtos.PeopleEmploymentFilter value)) {
                    throw Hrm007Exception.invalid("IAQ-013 filter shape is invalid.");
                }
                yield new Hrm007Dtos.PeopleEmploymentFilter(
                        policy(value.policyMetadata()), policy(value.employmentRule()),
                        aggregate(value.impactSummary()), value.effectiveOn());
            }
        };
    }

    static Hrm007Dtos.AggregateFilter aggregate(Hrm007Dtos.AggregateFilter value) {
        if (value == null) return null;
        if (value.minimumItemCount() != null && value.minimumItemCount() < 0
                || value.maximumItemCount() != null && value.maximumItemCount() < 0
                || value.minimumItemCount() != null && value.maximumItemCount() != null
                && value.minimumItemCount() > value.maximumItemCount()) {
            throw Hrm007Exception.invalid("Aggregate filter bounds are invalid.");
        }
        return value;
    }

    private static Hrm007Dtos.StatusFilter normalizeStatus(Hrm007Dtos.StatusFilter value) {
        if (value == null) return null;
        List<String> codes = list(value.codes(), CODE, 50, "status code");
        if (value.minimumRevision() != null && value.minimumRevision() < 1) {
            throw Hrm007Exception.invalid("Status minimum revision is invalid.");
        }
        return new Hrm007Dtos.StatusFilter(codes, value.minimumRevision());
    }

    static Hrm007Dtos.TemporalFilter temporal(Hrm007Dtos.TemporalFilter value) {
        if (value == null) return null;
        if (after(value.startFrom(), value.startTo())
                || after(value.endFrom(), value.endTo())) {
            throw Hrm007Exception.invalid("Temporal filter bounds are invalid.");
        }
        return value;
    }

    static Hrm007Dtos.ReferenceFilter reference(Hrm007Dtos.ReferenceFilter value) {
        if (value == null) return null;
        return new Hrm007Dtos.ReferenceFilter(
                list(value.codes(), CODE, 50, "reference code"),
                list(value.publicIds(), PUBLIC_ID, 50, "reference public id"));
    }

    static Hrm007Dtos.EffectiveRangeFilter range(Hrm007Dtos.EffectiveRangeFilter value) {
        if (value == null) return null;
        if (value.from() != null && value.to() != null
                && value.from().isAfter(value.to())) {
            throw Hrm007Exception.invalid("Effective-range filter bounds are invalid.");
        }
        return value;
    }

    static Hrm007Dtos.PolicyFilter policy(Hrm007Dtos.PolicyFilter value) {
        if (value == null) return null;
        List<String> keys = list(value.policyKeys(), POLICY_KEY, 50, "policy key");
        if (value.minimumRevision() != null && value.minimumRevision() < 1) {
            throw Hrm007Exception.invalid("Policy minimum revision is invalid.");
        }
        return new Hrm007Dtos.PolicyFilter(keys, value.minimumRevision());
    }

    static List<String> list(
            List<String> source,
            Pattern pattern,
            int maximum,
            String label) {
        if (source == null) return List.of();
        List<String> copy = List.copyOf(source);
        if (copy.size() > maximum || new HashSet<>(copy).size() != copy.size()
                || copy.stream().anyMatch(value ->
                value == null || !pattern.matcher(value).matches())) {
            throw Hrm007Exception.invalid("HRM-007 " + label + " values are invalid.");
        }
        return copy;
    }

    static String locale(String source) {
        if (source == null || !source.equals(source.trim())
                || source.length() < 2 || source.length() > 35) {
            throw Hrm007Exception.invalid("HRM-007 locale is invalid.");
        }
        Locale value = Locale.forLanguageTag(source.replace('_', '-'));
        if (value.getLanguage().isBlank() || "und".equals(value.toLanguageTag())) {
            throw Hrm007Exception.invalid("HRM-007 locale is invalid.");
        }
        return value.toLanguageTag();
    }

    static String timeZone(String source) {
        if (source == null || !source.equals(source.trim())
                || source.isBlank() || source.length() > 255
                || ZoneId.SHORT_IDS.containsKey(source)) {
            throw Hrm007Exception.invalid("HRM-007 time zone is invalid.");
        }
        try {
            ZoneId value = ZoneId.of(source);
            return value.getRules().isFixedOffset()
                    ? value.normalized().getId() : value.getId();
        } catch (DateTimeException exception) {
            throw Hrm007Exception.invalid("HRM-007 time zone is invalid.");
        }
    }

    private static boolean after(Instant left, Instant right) {
        return left != null && right != null && left.isAfter(right);
    }
}
