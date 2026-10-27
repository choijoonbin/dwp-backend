package com.dwp.services.payroll.statutory.pay004;

import com.dwp.services.payroll.foundation.PlainBigDecimalSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class Pay004Models {

    static final String FEATURE = "HRIS_PAY_RETIREMENT_WORKBENCH";
    static final String PURPOSE = "PAYROLL_OPERATIONS";
    static final String VARIANT = "RETIREMENT";
    static final String CANONICAL_ROUTE = "/hr/operations/payroll/retirement";
    static final String CAPABILITY = "DATA.HRIS_PAYROLL_STATUS:VIEW";
    static final String PROFILE = "HRIS_PAYROLL_SUPPORT_VIEWER";
    static final String DUTY = "HRIS_DUTY_PAYROLL_SUPPORT_VIEWER";
    static final String FIELD_POLICY = "pay004.iaq-049.fields.v1";
    static final List<String> FIELD_GROUPS = List.of(
            "RETIREMENT_RESULT", "POLICY_VERSION", "CASE_STATUS");
    static final Set<String> FILTERS = Set.of(
            "filter[retirementResult][minimumAmount]",
            "filter[retirementResult][maximumAmount]",
            "filter[retirementResult][currency]",
            "filter[policyVersion][policyKeys]",
            "filter[policyVersion][minimumRevision]",
            "filter[caseStatus][codes]",
            "filter[caseStatus][minimumRevision]",
            "filter[effectiveOn]");
    static final Set<String> SORTS = Set.of(
            "retirementResult:ASC", "retirementResult:DESC",
            "policyVersion:ASC", "policyVersion:DESC",
            "caseStatus:ASC", "caseStatus:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");
    static final Set<String> CASE_STATES = Set.of(
            "DRAFT", "VALIDATED", "BLOCKED_PACK_REQUIRED", "CALCULATED",
            "APPROVED", "FINALIZED", "CORRECTED", "CANCELLED");

    private Pay004Models() {
    }

    enum Decision { VIEW, MASK, OMIT }

    enum QueryState { COMPLETE, EMPTY, PARTIAL, UNAVAILABLE }

    enum Availability { AVAILABLE, PARTIAL, UNAVAILABLE }

    record QueryRequest(
            String variantKey,
            Instant asOf,
            String cursor,
            Map<String, List<String>> filter,
            int limit,
            String locale,
            List<String> sort,
            String timeZone,
            UUID correlationId) {

        QueryRequest {
            if (!VARIANT.equals(variantKey)) {
                throw new IllegalArgumentException("variantKey must be RETIREMENT");
            }
            if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{16,1024}")) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            filter = immutableFilters(filter);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (locale == null || locale.length() < 2 || locale.length() > 35) {
                throw new IllegalArgumentException(
                        "locale must be between 2 and 35 characters");
            }
            if (!Locale.forLanguageTag(locale).toLanguageTag().equals(locale)) {
                throw new IllegalArgumentException("locale must be canonical");
            }
            if (sort == null || sort.isEmpty()) {
                throw new IllegalArgumentException(
                        "sort must contain between 1 and 3 entries");
            }
            sort = List.copyOf(sort);
            if (sort.size() > 3 || sort.stream().distinct().count() != sort.size()
                    || !SORTS.containsAll(sort)
                    || sort.stream().map(value -> value.substring(
                    0, value.indexOf(':'))).distinct().count() != sort.size()
                    || sort.stream().filter(value -> value.startsWith(
                    "itemPublicId:")).count() != 1
                    || !sort.getLast().startsWith("itemPublicId:")) {
                throw new IllegalArgumentException("sort is outside IAQ-049");
            }
            if (timeZone == null || !ZoneId.of(timeZone).getId().equals(timeZone)) {
                throw new IllegalArgumentException("timeZone must be canonical");
            }
            Objects.requireNonNull(correlationId, "correlationId");
        }

        private static Map<String, List<String>> immutableFilters(
                Map<String, List<String>> source) {
            LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
            (source == null ? Map.<String, List<String>>of() : source)
                    .forEach((key, values) -> {
                        if (!FILTERS.contains(key) || values == null) {
                            throw new IllegalArgumentException(
                                    "filter is outside IAQ-049");
                        }
                        List<String> copy = List.copyOf(values);
                        if (copy.isEmpty() || copy.size() > 50
                                || copy.stream().anyMatch(value -> value == null
                                || value.isBlank() || !value.equals(value.strip())
                                || value.indexOf(',') >= 0)
                                || copy.stream().distinct().count() != copy.size()) {
                            throw new IllegalArgumentException("filter is invalid");
                        }
                        validateFilter(key, copy);
                        result.put(key, copy);
                    });
            validateMoneyRange(result);
            return Map.copyOf(result);
        }

        private static void validateFilter(String key, List<String> values) {
            if (key.endsWith("[minimumAmount]")
                    || key.endsWith("[maximumAmount]")) {
                if (values.size() != 1 || !canonicalDecimal(values.getFirst())) {
                    throw new IllegalArgumentException("amount filter is invalid");
                }
            } else if (key.endsWith("[currency]")) {
                if (values.size() != 1 || !values.getFirst().matches("[A-Z]{3}")) {
                    throw new IllegalArgumentException("currency filter is invalid");
                }
            } else if (key.endsWith("[minimumRevision]")) {
                if (values.size() != 1 || !positiveLong(values.getFirst())) {
                    throw new IllegalArgumentException("revision filter is invalid");
                }
            } else if (key.endsWith("[policyKeys]")) {
                if (values.stream().anyMatch(value ->
                        !value.matches("[A-Za-z0-9._:-]{1,128}"))) {
                    throw new IllegalArgumentException("policy filter is invalid");
                }
            } else if (key.endsWith("[codes]")) {
                if (!CASE_STATES.containsAll(values)) {
                    throw new IllegalArgumentException("status filter is invalid");
                }
            } else if ("filter[effectiveOn]".equals(key)) {
                try {
                    if (values.size() != 1
                            || !LocalDate.parse(values.getFirst()).toString()
                            .equals(values.getFirst())) {
                        throw new IllegalArgumentException(
                                "effectiveOn filter is invalid");
                    }
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException(
                            "effectiveOn filter is invalid", exception);
                }
            }
        }

        private static boolean canonicalDecimal(String value) {
            if (!value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?")) {
                return false;
            }
            try {
                BigDecimal decimal = new BigDecimal(value);
                return decimal.scale() <= 6;
            } catch (NumberFormatException exception) {
                return false;
            }
        }

        private static boolean positiveLong(String value) {
            if (!value.matches("[1-9][0-9]*")) return false;
            try {
                return Long.parseLong(value) > 0;
            } catch (NumberFormatException exception) {
                return false;
            }
        }

        private static void validateMoneyRange(
                Map<String, List<String>> filters) {
            List<String> minimum = filters.get(
                    "filter[retirementResult][minimumAmount]");
            List<String> maximum = filters.get(
                    "filter[retirementResult][maximumAmount]");
            if (minimum != null && maximum != null
                    && new BigDecimal(minimum.getFirst()).compareTo(
                    new BigDecimal(maximum.getFirst())) > 0) {
                throw new IllegalArgumentException("amount range is invalid");
            }
        }
    }

    record PostedMoney(
            @JsonSerialize(using = PlainBigDecimalSerializer.class)
            BigDecimal amount,
            String currency,
            String roundingMode) {

        PostedMoney {
            Objects.requireNonNull(amount, "amount");
            if (amount.signum() < 0 || amount.scale() < 0 || amount.scale() > 4
                    || amount.precision() + Math.max(0, 4 - amount.scale()) > 19) {
                throw new IllegalArgumentException(
                        "amount must be an exact nonnegative NUMERIC(19,4)");
            }
            amount = amount.setScale(4, RoundingMode.UNNECESSARY);
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency is invalid");
            }
            if (!Set.of("HALF_UP", "HALF_EVEN", "DOWN", "UP")
                    .contains(roundingMode)) {
                throw new IllegalArgumentException("roundingMode is invalid");
            }
        }
    }

    record MoneyValue(String amount, String currency) {
        MoneyValue {
            if (amount == null || !amount.matches(
                    "(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,4})?")
                    || currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("money value is invalid");
            }
        }

        static MoneyValue from(PostedMoney money) {
            return new MoneyValue(money.amount().toPlainString(), money.currency());
        }
    }

    record PolicyValue(
            String policyKey,
            long revision,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status) {
        PolicyValue {
            if (policyKey == null
                    || !policyKey.matches("[a-z][a-z0-9._-]{1,127}")) {
                throw new IllegalArgumentException("policyKey is invalid");
            }
            if (revision < 1 || effectiveFrom == null
                    || effectiveTo != null && effectiveTo.isBefore(effectiveFrom)
                    || status == null
                    || !status.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("policy value is invalid");
            }
        }
    }

    record StatusValue(String code, long revision) {
        StatusValue {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")
                    || revision < 1) {
                throw new IllegalArgumentException("status value is invalid");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"decision", "value", "maskReason", "maskedDisplayKey"})
    record FieldDecision(
            String decision,
            @JsonInclude(JsonInclude.Include.ALWAYS) Object value,
            String maskReason,
            String maskedDisplayKey) {
        FieldDecision {
            if ("VIEW".equals(decision)) {
                Objects.requireNonNull(value, "value");
                if (maskReason != null || maskedDisplayKey != null) {
                    throw new IllegalArgumentException("VIEW cannot carry mask data");
                }
            } else if ("MASK".equals(decision)) {
                if (value != null || maskReason == null || maskedDisplayKey == null) {
                    throw new IllegalArgumentException("MASK is incomplete");
                }
                token(maskReason, 64, "maskReason");
                token(maskedDisplayKey, 128, "maskedDisplayKey");
            } else {
                throw new IllegalArgumentException("field decision is invalid");
            }
        }

        static FieldDecision view(Object value) {
            return new FieldDecision("VIEW", value, null, null);
        }

        static FieldDecision mask() {
            return new FieldDecision(
                    "MASK", null, "POLICY_REDACTED", "payroll.pay004.masked");
        }
    }

    record DeepLink(String route, String resourcePublicId) {
        DeepLink {
            if (!CANONICAL_ROUTE.equals(route)) {
                throw new IllegalArgumentException("deep link route is invalid");
            }
            canonicalUuid(resourcePublicId, "deepLink.resourcePublicId");
        }
    }

    @JsonPropertyOrder({
            "itemPublicId", "title", "statusCode", "fieldGroups",
            "deepLink", "availableActions"})
    record EntryItem(
            String itemPublicId,
            String title,
            String statusCode,
            Map<String, FieldDecision> fieldGroups,
            DeepLink deepLink,
            List<Void> availableActions) {
        EntryItem {
            canonicalUuid(itemPublicId, "itemPublicId");
            token(title, 256, "title");
            if (statusCode == null
                    || !statusCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("statusCode is invalid");
            }
            fieldGroups = Map.copyOf(new LinkedHashMap<>(fieldGroups));
            if (fieldGroups.isEmpty()
                    || !Set.copyOf(FIELD_GROUPS).containsAll(
                    fieldGroups.keySet())) {
                throw new IllegalArgumentException("field groups are invalid");
            }
            Objects.requireNonNull(deepLink, "deepLink");
            if (!itemPublicId.equals(deepLink.resourcePublicId())) {
                throw new IllegalArgumentException("deep link identity differs");
            }
            availableActions = List.copyOf(availableActions);
            if (!availableActions.isEmpty()) {
                throw new IllegalArgumentException(
                        "IAQ-049 exposes no action target");
            }
        }
    }

    record FieldDecisionSummary(
            int viewCount, int maskedCount, int omittedCount) {
        FieldDecisionSummary {
            if (viewCount < 0 || maskedCount < 0 || omittedCount < 0) {
                throw new IllegalArgumentException("decision counts are invalid");
            }
        }
    }

    record PartialFailure(
            String componentKey,
            String errorCode,
            UUID correlationId,
            boolean retryable) {
        PartialFailure {
            if (componentKey == null
                    || !componentKey.matches("[a-z][a-z0-9._-]{0,127}")
                    || errorCode == null
                    || !errorCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("partial failure is invalid");
            }
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    record CachePolicy(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) {
        CachePolicy {
            revision(authorizationDecisionRevision,
                    "authorizationDecisionRevision");
            if (!"dwp.hris.access.assignment.changed.v1".equals(
                    invalidationEvent) || maxAgeSeconds != 0) {
                throw new IllegalArgumentException("IAQ-049 cache must revalidate");
            }
        }
    }

    @JsonPropertyOrder({
            "state", "scopeDecisionRevision", "freshness", "nextCursor",
            "fieldDecisionSummary", "partialFailures", "correlationId",
            "cachePolicy"})
    record QueryStateValue(
            QueryState state,
            String scopeDecisionRevision,
            String freshness,
            String nextCursor,
            FieldDecisionSummary fieldDecisionSummary,
            List<PartialFailure> partialFailures,
            UUID correlationId,
            CachePolicy cachePolicy) {
        QueryStateValue {
            Objects.requireNonNull(state, "state");
            revision(scopeDecisionRevision, "scopeDecisionRevision");
            if (!Set.of("CURRENT", "STALE", "UNAVAILABLE")
                    .contains(freshness)) {
                throw new IllegalArgumentException("freshness is invalid");
            }
            if (nextCursor != null
                    && !nextCursor.matches("[A-Za-z0-9_-]{16,1024}")) {
                throw new IllegalArgumentException("nextCursor is invalid");
            }
            partialFailures = List.copyOf(partialFailures);
            Objects.requireNonNull(fieldDecisionSummary, "fieldDecisionSummary");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(cachePolicy, "cachePolicy");
            if ((state == QueryState.PARTIAL) != !partialFailures.isEmpty()
                    && state != QueryState.UNAVAILABLE) {
                throw new IllegalArgumentException(
                        "partial failure state does not match");
            }
            if (state == QueryState.UNAVAILABLE && partialFailures.isEmpty()) {
                throw new IllegalArgumentException(
                        "unavailable requires a failure");
            }
            if ((state == QueryState.UNAVAILABLE)
                    != "UNAVAILABLE".equals(freshness)
                    || state == QueryState.UNAVAILABLE && nextCursor != null) {
                throw new IllegalArgumentException(
                        "unavailable freshness is inconsistent");
            }
        }
    }

    @JsonPropertyOrder({
            "projectionVersion", "asOf", "variantKey", "queryState", "items"})
    record EntryQueryResponse(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryStateValue queryState,
            List<EntryItem> items) {
        EntryQueryResponse {
            if (!"1".equals(projectionVersion) || asOf == null
                    || !VARIANT.equals(variantKey) || queryState == null) {
                throw new IllegalArgumentException("projection envelope is invalid");
            }
            items = List.copyOf(items);
            if ((queryState.state() == QueryState.EMPTY
                    || queryState.state() == QueryState.UNAVAILABLE)
                    && !items.isEmpty()) {
                throw new IllegalArgumentException("query state cardinality differs");
            }
            if ((queryState.state() == QueryState.COMPLETE
                    || queryState.state() == QueryState.PARTIAL)
                    && items.isEmpty()) {
                throw new IllegalArgumentException("query state requires items");
            }
        }
    }

    record RetirementProjection(
            long tenantId,
            UUID caseId,
            UUID workerId,
            long caseRevision,
            RetirementYearEndCase.State caseState,
            RetirementYearEndCase.CountryPackState countryPackState,
            LocalDate effectiveOn,
            PostedMoney retirementResult,
            String policyKey,
            long policyRevision,
            LocalDate policyEffectiveFrom,
            LocalDate policyEffectiveTo) {
        RetirementProjection {
            if (tenantId < 1 || caseId == null || workerId == null
                    || caseRevision < 1 || caseState == null
                    || countryPackState == null || effectiveOn == null) {
                throw new IllegalArgumentException("retirement projection is invalid");
            }
            boolean resultPresent = retirementResult != null;
            boolean policyPresent = policyKey != null || policyRevision != 0
                    || policyEffectiveFrom != null || policyEffectiveTo != null;
            if (resultPresent != policyPresent) {
                throw new IllegalArgumentException(
                        "result and policy projection must be co-present");
            }
            if (resultPresent) {
                if (!policyKey.matches("[a-z][a-z0-9._-]{1,127}")
                        || policyRevision < 1 || policyEffectiveFrom == null
                        || policyEffectiveTo != null
                        && policyEffectiveTo.isBefore(policyEffectiveFrom)) {
                    throw new IllegalArgumentException(
                            "policy projection is invalid");
                }
            }
        }
    }

    record SourcePage(
            Availability availability,
            List<RetirementProjection> rows,
            List<String> failedGroups,
            boolean hasNext) {
        SourcePage(
                Availability availability,
                List<RetirementProjection> rows,
                List<String> failedGroups) {
            this(availability, rows, failedGroups, false);
        }

        SourcePage {
            Objects.requireNonNull(availability, "availability");
            rows = List.copyOf(rows);
            failedGroups = List.copyOf(failedGroups);
            if (Set.copyOf(failedGroups).size() != failedGroups.size()
                    || !Set.copyOf(FIELD_GROUPS).containsAll(failedGroups)) {
                throw new IllegalArgumentException(
                        "source failures are outside IAQ-049 field groups");
            }
            if (availability == Availability.UNAVAILABLE && !rows.isEmpty()) {
                throw new IllegalArgumentException("unavailable source has rows");
            }
            if (hasNext && rows.isEmpty()) {
                throw new IllegalArgumentException(
                        "a continued source page requires rows");
            }
            if ((availability == Availability.PARTIAL) != !failedGroups.isEmpty()) {
                throw new IllegalArgumentException("source failure state differs");
            }
        }

        static SourcePage unavailable() {
            return new SourcePage(
                    Availability.UNAVAILABLE, List.of(), List.of(), false);
        }
    }

    static String canonicalUuid(String value, String name) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) throw new IllegalArgumentException();
            return canonical;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " is not a canonical UUID",
                    exception);
        }
    }

    static void token(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip()) || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static void revision(String value, String name) {
        if (value == null
                || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static void digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
