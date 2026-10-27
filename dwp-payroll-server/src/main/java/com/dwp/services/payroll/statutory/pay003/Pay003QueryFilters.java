package com.dwp.services.payroll.statutory.pay003;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed country-pack and insurance query filters. */
final class Pay003QueryFilters {

    private Pay003QueryFilters() {
    }

    static Map<String, List<String>> immutableFilter(
            Map<String, List<String>> source, String variantKey) {
        Set<String> allowed = "STATUTORY".equals(variantKey)
                ? Set.of(
                "filter[countryPackMetadata][keys]",
                "filter[countryPackMetadata][versions]",
                "filter[statutoryRule][policyKeys]",
                "filter[statutoryRule][minimumRevision]",
                "filter[activationStatus][codes]",
                "filter[activationStatus][minimumRevision]",
                "filter[effectiveOn]")
                : Set.of(
                "filter[taxResult][minimumAmount]",
                "filter[taxResult][maximumAmount]",
                "filter[taxResult][currency]",
                "filter[socialInsurance][minimumAmount]",
                "filter[socialInsurance][maximumAmount]",
                "filter[socialInsurance][currency]",
                "filter[policyVersion][policyKeys]",
                "filter[policyVersion][minimumRevision]",
                "filter[effectiveOn]");
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        (source == null ? Map.<String, List<String>>of() : source)
                .forEach((key, values) -> {
                    token(key, 80, "filter key");
                    if (!allowed.contains(key)) {
                        throw new IllegalArgumentException(
                                "filter key is outside the closed schema");
                    }
                    List<String> copy = List.copyOf(values);
                    if (copy.size() > 50
                            || copy.stream().anyMatch(value ->
                            value == null || value.isBlank()
                                    || !value.equals(value.strip())
                                    || value.indexOf(',') >= 0)) {
                        throw new IllegalArgumentException("filter is invalid");
                    }
                    if (copy.stream().distinct().count() != copy.size()) {
                        throw new IllegalArgumentException(
                                "filter values must be unique");
                    }
                    if (key.endsWith("[minimumRevision]")
                            && (copy.size() != 1
                            || !canonicalPositiveLong(copy.getFirst()))) {
                        throw new IllegalArgumentException(
                                "minimumRevision is invalid");
                    }
                    if ((key.endsWith("[minimumAmount]")
                            || key.endsWith("[maximumAmount]"))
                            && (copy.size() != 1 || !copy.getFirst().matches(
                            "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?"))) {
                        throw new IllegalArgumentException(
                                "money filter is invalid");
                    }
                    if (key.endsWith("[currency]")
                            && (copy.size() != 1
                            || !copy.getFirst().matches("[A-Z]{3}"))) {
                        throw new IllegalArgumentException(
                                "currency filter is invalid");
                    }
                    if ("filter[effectiveOn]".equals(key)
                            && (copy.size() != 1
                            || !canonicalDate(copy.getFirst()))) {
                        throw new IllegalArgumentException(
                                "effectiveOn is invalid");
                    }
                    if ((key.endsWith("[keys]")
                            || key.endsWith("[policyKeys]"))
                            && copy.stream().anyMatch(value ->
                            !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
                        throw new IllegalArgumentException(
                                "filter key value is invalid");
                    }
                    if (key.endsWith("[versions]")
                            && copy.stream().anyMatch(value ->
                            !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
                        throw new IllegalArgumentException(
                                "filter version is invalid");
                    }
                    if (key.endsWith("[codes]")
                            && copy.stream().anyMatch(value ->
                            !value.matches("[A-Z][A-Z0-9_]{1,63}"))) {
                        throw new IllegalArgumentException(
                                "filter code is invalid");
                    }
                    result.put(key, copy);
                });
        validateRange(result, "taxResult");
        validateRange(result, "socialInsurance");
        return Map.copyOf(result);
    }

    static boolean canonicalPositiveLong(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    static boolean canonicalDate(String value) {
        try {
            return LocalDate.parse(value).toString().equals(value);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static void validateRange(
            Map<String, List<String>> values, String group) {
        List<String> minimum = values.get(
                "filter[" + group + "][minimumAmount]");
        List<String> maximum = values.get(
                "filter[" + group + "][maximumAmount]");
        if (minimum != null && maximum != null
                && new BigDecimal(minimum.getFirst()).compareTo(
                new BigDecimal(maximum.getFirst())) > 0) {
            throw new IllegalArgumentException(
                    group + " money range is invalid");
        }
    }

    private static void token(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
