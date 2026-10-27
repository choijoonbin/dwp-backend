package com.dwp.services.payroll.configuration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.configuration.PayrollConfigurationValueValidation.*;

/** Canonical normalization and validation for payroll model values. */
final class PayrollConfigurationWorkbenchValidation {

    private PayrollConfigurationWorkbenchValidation() {
    }

    static List<String> statusFilterCodes(
            List<String> codes,
            Long minimumRevision) {
        codes = codes == null ? List.of() : immutableList(codes, "codes");
        if (codes.size() > 50 || codes.stream().distinct().count() != codes.size()
                || codes.stream().anyMatch(code ->
                !code.matches("[A-Z][A-Z0-9_]{1,63}"))) {
            throw new IllegalArgumentException("status filter codes are invalid");
        }
        if (minimumRevision != null && minimumRevision < 1) {
            throw new IllegalArgumentException("minimumRevision must be positive");
        }
        return codes;
    }

    static List<String> policyFilterPolicyKeys(
            List<String> policyKeys,
            Long minimumRevision) {
        policyKeys = policyKeys == null
                ? List.of() : immutableList(policyKeys, "policyKeys");
        if (policyKeys.size() > 50
                || policyKeys.stream().distinct().count() != policyKeys.size()
                || policyKeys.stream().anyMatch(key ->
                !key.matches("[A-Za-z0-9._:-]{1,128}"))) {
            throw new IllegalArgumentException("policy filter keys are invalid");
        }
        if (minimumRevision != null && minimumRevision < 1) {
            throw new IllegalArgumentException("minimumRevision must be positive");
        }
        return policyKeys;
    }

    static String workbenchQueryCursor(
            String cursor,
            Object filter,
            int limit) {
        cursor = normalizeOptionalText(cursor, 1024, "cursor");
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{16,1024}")) {
            throw new IllegalArgumentException("cursor is invalid");
        }
        Objects.requireNonNull(filter, "filter");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return cursor;
    }

    static String workbenchQueryLocale(
            String locale) {
        locale = requireText(locale, "locale");
        java.util.Locale parsedLocale = java.util.Locale.forLanguageTag(locale);
        if (locale.length() < 2 || locale.length() > 35
                || parsedLocale.getLanguage().isBlank()
                || !parsedLocale.toLanguageTag().equals(locale)) {
            throw new IllegalArgumentException("locale must be a canonical BCP 47 tag");
        }
        return locale;
    }

    private static final Set<String> SORTS = Set.of(
            "policyVersion:ASC", "payrollPolicy:ASC", "effectiveStatus:ASC",
            "policyVersion:DESC", "payrollPolicy:DESC", "effectiveStatus:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");

    static List<String> workbenchQuerySort(
            List<String> sort) {
        sort = immutableList(sort, "sort");
        if (sort.isEmpty() || sort.size() > 3
                || sort.stream().distinct().count() != sort.size()
                || !SORTS.containsAll(sort)) {
            throw new IllegalArgumentException("sort is invalid");
        }
        return sort;
    }

    static String workbenchQueryTimeZone(
            String timeZone,
            UUID correlationId) {
        timeZone = requireText(timeZone, "timeZone");
        try {
            java.time.ZoneId parsedZone = java.time.ZoneId.of(timeZone);
            if (!parsedZone.getId().equals(timeZone)
                    || (!"UTC".equals(timeZone) && !timeZone.contains("/"))) {
                throw new IllegalArgumentException("timeZone must be canonical");
            }
        } catch (java.time.DateTimeException exception) {
            throw new IllegalArgumentException("timeZone must be canonical", exception);
        }
        Objects.requireNonNull(correlationId, "correlationId");
        return timeZone;
    }

    static void policyValueFields(
            String policyKey,
            long revision,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status) {
        if (!requireText(policyKey, "policyKey")
                .matches("[a-z][a-z0-9._-]{1,127}")) {
            throw new IllegalArgumentException("policyKey is invalid");
        }
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo cannot precede effectiveFrom");
        }
        if (!requireText(status, "status").matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("status is invalid");
        }
    }

    static void fieldDecisionFields(
            String decision,
            Object value,
            String maskReason,
            String maskedDisplayKey) {
        if (!Set.of("VIEW", "MASK").contains(decision)) {
            throw new IllegalArgumentException("field decision is invalid");
        }
        if ("VIEW".equals(decision)) {
            Objects.requireNonNull(value, "view field value");
            if (maskReason != null || maskedDisplayKey != null) {
                throw new IllegalArgumentException("view fields cannot expose mask metadata");
            }
        } else {
            if (value != null
                    || maskReason == null
                    || !maskReason.matches("[A-Z][A-Z0-9_]{1,63}")
                    || maskedDisplayKey == null
                    || !maskedDisplayKey.matches("[a-z][a-z0-9._-]{1,127}")) {
                throw new IllegalArgumentException("masked field decision is invalid");
            }
        }
    }

    static void deepLinkFields(
            String route,
            String resourcePublicId) {
        if (!"/hr/settings/payroll".equals(route)) {
            throw new IllegalArgumentException("payroll deep link route is invalid");
        }
        if (!requireText(resourcePublicId, "resourcePublicId")
                .matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("resourcePublicId is invalid");
        }
    }

    static void actionDescriptorFields(
            String actionKey,
            String method,
            String hrefTemplate,
            String idempotencyPolicy,
            String stepUpPolicy) {
        if (!"hris.action.pay.formula.publish".equals(actionKey)
                || !"POST".equals(method)
                || !"/api/payroll/v1/formulas/{formulaId}/publish".equals(hrefTemplate)
                || !"REQUIRED_IDEMPOTENCY_KEY".equals(idempotencyPolicy)
                || !"OWNER_PEP_RISK_POLICY_DECISION_REQUIRED".equals(stepUpPolicy)) {
            throw new IllegalArgumentException("payroll action descriptor is invalid");
        }
    }

    static String workbenchItemTitle(
            String title,
            String statusCode) {
        title = requireText(title, "title");
        if (title.length() > 512) throw new IllegalArgumentException("title is too long");
        if (!requireText(statusCode, "statusCode")
                .matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("statusCode is invalid");
        }
        return title;
    }

    static void partialFailureFields(
            String componentKey,
            String errorCode,
            UUID correlationId) {
        if (!requireText(componentKey, "componentKey")
                .matches("[a-z][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("componentKey is invalid");
        }
        if (!requireText(errorCode, "errorCode")
                .matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("errorCode is invalid");
        }
        Objects.requireNonNull(correlationId, "correlationId");
    }

    static String cachePolicyAuthorizationDecisionRevision(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) {
        authorizationDecisionRevision = requireText(
                authorizationDecisionRevision, "authorizationDecisionRevision");
        if (!"dwp.hris.access.assignment.changed.v1".equals(invalidationEvent)) {
            throw new IllegalArgumentException("cache invalidation event is invalid");
        }
        if (maxAgeSeconds < 0 || maxAgeSeconds > 300) {
            throw new IllegalArgumentException("maxAgeSeconds is invalid");
        }
        return authorizationDecisionRevision;
    }

    static String queryStateScopeDecisionRevision(
            String scopeDecisionRevision,
            String freshness) {
        scopeDecisionRevision = requireText(
                scopeDecisionRevision, "scopeDecisionRevision");
        if (!Set.of("CURRENT", "STALE", "UNAVAILABLE").contains(freshness)) {
            throw new IllegalArgumentException("freshness is invalid");
        }
        return scopeDecisionRevision;
    }

    static String queryStateNextCursor(
            String nextCursor,
            Object fieldDecisionSummary) {
        nextCursor = normalizeOptionalText(nextCursor, 1024, "nextCursor");
        if (nextCursor != null && !nextCursor.matches("[A-Za-z0-9_-]{16,1024}")) {
            throw new IllegalArgumentException("nextCursor is invalid");
        }
        Objects.requireNonNull(fieldDecisionSummary, "fieldDecisionSummary");
        return nextCursor;
    }

    static void workbenchViewFields(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            Object queryState) {
        if (!"1".equals(projectionVersion) || !"PAYROLL".equals(variantKey)) {
            throw new IllegalArgumentException("payroll projection identity is invalid");
        }
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(queryState, "queryState");
    }

    static void problemFields(
            String type) {
        try {
            java.net.URI.create(requireText(type, "type"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("problem type must be a URI", exception);
        }
    }

    static String problemTitle2(
            String title,
            int status,
            String code,
            UUID correlationId) {
        title = requireText(title, "title");
        if (title.length() > 256) throw new IllegalArgumentException("title is too long");
        if (!Set.of(400, 401, 403, 404, 409, 429, 503).contains(status)) {
            throw new IllegalArgumentException("problem status is invalid");
        }
        if (!requireText(code, "code").matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("problem code is invalid");
        }
        Objects.requireNonNull(correlationId, "correlationId");
        return title;
    }

    static <T> List<T> queryStatePartialFailures(
            String state,
            String freshness,
            String nextCursor,
            List<T> partialFailures,
            UUID correlationId,
            Object cachePolicy) {
        partialFailures = immutableList(partialFailures, "partialFailures");
        if (partialFailures.size() > 32) {
            throw new IllegalArgumentException("too many partialFailures");
        }
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(cachePolicy, "cachePolicy");
        if (("EMPTY".equals(state) || "COMPLETE".equals(state))
                && !partialFailures.isEmpty()) {
            throw new IllegalArgumentException("complete query state cannot have failures");
        }
        if ("PARTIAL".equals(state) && partialFailures.isEmpty()) {
            throw new IllegalArgumentException("partial query state requires a failure");
        }
        if ("UNAVAILABLE".equals(state)
                && (!"UNAVAILABLE".equals(freshness) || nextCursor != null)) {
            throw new IllegalArgumentException("unavailable query state is inconsistent");
        }
        return partialFailures;
    }

    static <T> Map<String, T> workbenchItemFieldGroups(
            Map<String, T> fieldGroups,
            Object deepLink) {
        fieldGroups = Map.copyOf(new LinkedHashMap<>(fieldGroups));
        if (fieldGroups.isEmpty()
                || !Set.of("POLICY_VERSION", "PAYROLL_POLICY", "EFFECTIVE_STATUS")
                .containsAll(fieldGroups.keySet())) {
            throw new IllegalArgumentException("fieldGroups are invalid");
        }
        Objects.requireNonNull(deepLink, "deepLink");
        return fieldGroups;
    }

    static <T> List<T> workbenchItemAvailableActions(
            List<T> availableActions) {
        availableActions = immutableList(availableActions, "availableActions");
        if (availableActions.size() > 6
                || availableActions.stream().distinct().count()
                != availableActions.size()) {
            throw new IllegalArgumentException("availableActions are invalid");
        }
        return availableActions;
    }

    static <T> List<T> validationReportFindings(
            boolean valid,
            List<T> findings,
            Instant validatedAt) {
        findings = immutableList(findings, "findings");
        Objects.requireNonNull(validatedAt, "validatedAt");
        if (valid == !findings.isEmpty()) {
            throw new IllegalArgumentException("validation state and findings disagree");
        }
        return findings;
    }

    static <T> List<T> payFormulaAstNodes(
            List<T> nodes) {
        nodes = immutableList(nodes, "nodes");
        if (nodes.isEmpty() || nodes.size() > 2048) {
            throw new IllegalArgumentException("formula node count must be between 1 and 2048");
        }
        return nodes;
    }
}
