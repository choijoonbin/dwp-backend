package com.dwp.services.payroll.statutory.pay003;

import com.dwp.services.payroll.foundation.PlainBigDecimalSerializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
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

final class Pay003Models {

    static final String FEATURE = "HRIS_PAYROLL_STATUTORY_INSURANCE";
    static final String ACTIVATE_ACTION = "PAY_COUNTRY_PACK_ACTIVATE";
    static final String ACTIVATE_ACTION_KEY =
            "hris.action.pay.country.pack.activate";
    static final String ACTIVATION_PURPOSE = "STATUTORY_CONFIGURATION";
    static final String CONFIGURATION_PURPOSE = "PAYROLL_CONFIGURATION";
    static final String OPERATIONS_PURPOSE = "PAYROLL_OPERATIONS";
    static final List<String> STATUTORY_GROUPS = List.of(
            "COUNTRY_PACK_METADATA", "STATUTORY_RULE", "ACTIVATION_STATUS");
    static final List<String> RESULT_GROUPS = List.of(
            "SOCIAL_INSURANCE", "POLICY_VERSION");

    private Pay003Models() {
    }

    enum QueryState { COMPLETE, EMPTY, PARTIAL, UNAVAILABLE }

    enum LifecycleState {
        AVAILABLE, INSTALLED, VALIDATED, APPROVED, ACTIVE, SUSPENDED, RETIRED
    }

    enum Decision { VIEW, MASK, OMIT }

    enum Availability { AVAILABLE, UNAVAILABLE }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record ActivationCommand(
            @NotBlank String officialReferenceSet,
            @NotNull LocalDate effectiveDate,
            @NotBlank String goldenPackDigest,
            @NotNull UUID statutoryApprovalReceiptId) {

        ActivationCommand {
            token(officialReferenceSet, 240, "officialReferenceSet");
            Objects.requireNonNull(effectiveDate, "effectiveDate");
            digest(goldenPackDigest, "goldenPackDigest");
            Objects.requireNonNull(
                    statutoryApprovalReceiptId, "statutoryApprovalReceiptId");
        }

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object ignored) {
            throw new IllegalArgumentException(
                    "Unknown activation property: " + name);
        }
    }

    record ActivationHeaders(
            String idempotencyKey,
            long expectedRevision,
            String challenge,
            String expectedDecisionRevision,
            UUID correlationId) {

        ActivationHeaders {
            if (idempotencyKey == null
                    || idempotencyKey.length() < 16
                    || idempotencyKey.length() > 128
                    || !idempotencyKey.equals(idempotencyKey.strip())
                    || idempotencyKey.indexOf('\r') >= 0
                    || idempotencyKey.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Idempotency-Key is invalid");
            }
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expected revision is invalid");
            }
            token(challenge, 16_384, "step-up challenge");
            token(expectedDecisionRevision, 200, "decision revision");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

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
            if (!Set.of("STATUTORY", "TAX_SOCIAL_INSURANCE")
                    .contains(variantKey)) {
                throw new IllegalArgumentException("variantKey is invalid");
            }
            if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{16,1024}")) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            filter = immutableFilter(filter, variantKey);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (locale == null
                    || !Locale.forLanguageTag(locale).toLanguageTag().equals(locale)) {
                throw new IllegalArgumentException("locale must be canonical");
            }
            sort = List.copyOf(sort == null ? List.of() : sort);
            Set<String> allowedSorts = "STATUTORY".equals(variantKey)
                    ? Set.of(
                    "countryPackMetadata:ASC", "statutoryRule:ASC",
                    "activationStatus:ASC", "countryPackMetadata:DESC",
                    "statutoryRule:DESC", "activationStatus:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC")
                    : Set.of(
                    "taxResult:ASC", "socialInsurance:ASC",
                    "policyVersion:ASC", "taxResult:DESC",
                    "socialInsurance:DESC", "policyVersion:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC");
            if (sort.isEmpty() || sort.size() > 3
                    || sort.stream().distinct().count() != sort.size()
                    || !allowedSorts.containsAll(sort)
                    || sort.stream().filter(value ->
                    value.startsWith("itemPublicId:")).count() != 1
                    || !sort.getLast().startsWith("itemPublicId:")) {
                throw new IllegalArgumentException("sort is outside the closed schema");
            }
            if (timeZone == null || !ZoneId.of(timeZone).getId().equals(timeZone)) {
                throw new IllegalArgumentException("timeZone must be canonical");
            }
            Objects.requireNonNull(correlationId, "correlationId");
        }

        private static Map<String, List<String>> immutableFilter(
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

        private static boolean canonicalPositiveLong(String value) {
            if (value == null || !value.matches("[1-9][0-9]*")) return false;
            try {
                return Long.parseLong(value) > 0;
            } catch (NumberFormatException exception) {
                return false;
            }
        }

        private static boolean canonicalDate(String value) {
            try {
                return LocalDate.parse(value).toString().equals(value);
            } catch (RuntimeException exception) {
                return false;
            }
        }

        private static void validateRange(
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
                    throw new IllegalArgumentException("VIEW cannot carry mask metadata");
                }
            } else if ("MASK".equals(decision)) {
                if (value != null || maskReason == null || maskedDisplayKey == null
                        || !maskReason.matches("[A-Z][A-Z0-9_]{1,63}")
                        || !maskedDisplayKey.matches("[a-z][a-z0-9._-]{1,127}")) {
                    throw new IllegalArgumentException("MASK is incomplete");
                }
            } else {
                throw new IllegalArgumentException("field decision is invalid");
            }
        }

        static FieldDecision view(Object value) {
            return new FieldDecision("VIEW", value, null, null);
        }

        static FieldDecision mask(String reason, String displayKey) {
            return new FieldDecision("MASK", null, reason, displayKey);
        }
    }

    record MetadataValue(String key, String version, String digest) {
        MetadataValue {
            if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")
                    || version == null
                    || !version.matches("[A-Za-z0-9._:-]{1,64}")) {
                throw new IllegalArgumentException("metadata value is invalid");
            }
            if (digest != null) Pay003Models.digest(digest, "metadata digest");
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
                    || !policyKey.matches("[a-z][a-z0-9._-]{1,127}")
                    || revision < 1 || effectiveFrom == null
                    || (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom))
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

    record PostedMoney(
            @JsonSerialize(using = PlainBigDecimalSerializer.class)
            BigDecimal amount,
            String currency) {

        PostedMoney {
            Objects.requireNonNull(amount, "amount");
            if (amount.signum() < 0 || amount.scale() > 4
                    || amount.precision() + Math.max(0, 4 - amount.scale()) > 19) {
                throw new IllegalArgumentException(
                        "PostedMoney amount requires exact NUMERIC(19,4)");
            }
            amount = amount.setScale(4);
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency is invalid");
            }
        }
    }

    record MoneyValue(String amount, String currency) {
        MoneyValue {
            if (amount == null
                    || !amount.matches(
                    "(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,4})?")
                    || currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("money value is invalid");
            }
        }

        static MoneyValue from(PostedMoney value) {
            return new MoneyValue(value.amount().toPlainString(), value.currency());
        }
    }

    record DeepLink(String route, String resourcePublicId) {
        DeepLink {
            if (!Set.of(
                    "/hr/settings/statutory",
                    "/hr/operations/payroll/tax-social-insurance")
                    .contains(route)) {
                throw new IllegalArgumentException("deep link route is invalid");
            }
            canonicalUuid(resourcePublicId, "deep link resourcePublicId");
        }
    }

    @JsonPropertyOrder({
            "actionKey", "method", "targetResourceType",
            "targetResourcePublicId", "resolvedHref",
            "idempotencyPolicy", "stepUpPolicy"})
    record AvailableAction(
            String actionKey,
            String method,
            String targetResourceType,
            String targetResourcePublicId,
            String resolvedHref,
            String idempotencyPolicy,
            String stepUpPolicy) {

        AvailableAction {
            String packId = canonicalUuid(
                    targetResourcePublicId, "targetResourcePublicId");
            String expected = "/api/payroll/v1/country-packs/"
                    + packId + "/activate";
            if (!ACTIVATE_ACTION_KEY.equals(actionKey)
                    || !"POST".equals(method)
                    || !"COUNTRY_PACK".equals(targetResourceType)
                    || !expected.equals(resolvedHref)
                    || resolvedHref.indexOf(packId)
                    != resolvedHref.lastIndexOf(packId)
                    || resolvedHref.indexOf('?') >= 0
                    || resolvedHref.indexOf('#') >= 0
                    || !"REQUIRED_IDEMPOTENCY_KEY".equals(idempotencyPolicy)
                    || !"OWNER_PEP_RISK_POLICY_DECISION_REQUIRED"
                    .equals(stepUpPolicy)) {
                throw new IllegalArgumentException("activation action is invalid");
            }
        }

        static AvailableAction activation(UUID packId) {
            String canonical = packId.toString();
            return new AvailableAction(
                    ACTIVATE_ACTION_KEY, "POST", "COUNTRY_PACK", canonical,
                    "/api/payroll/v1/country-packs/" + canonical + "/activate",
                    "REQUIRED_IDEMPOTENCY_KEY",
                    "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
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
            List<AvailableAction> availableActions) {

        EntryItem {
            canonicalUuid(itemPublicId, "itemPublicId");
            token(title, 512, "title");
            if (statusCode == null
                    || !statusCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("statusCode is invalid");
            }
            fieldGroups = Map.copyOf(new LinkedHashMap<>(fieldGroups));
            if (fieldGroups.isEmpty()
                    || (!Set.copyOf(STATUTORY_GROUPS).containsAll(fieldGroups.keySet())
                    && !Set.copyOf(RESULT_GROUPS).containsAll(fieldGroups.keySet()))) {
                throw new IllegalArgumentException("fieldGroups are invalid");
            }
            Objects.requireNonNull(deepLink, "deepLink");
            availableActions = List.copyOf(availableActions);
            if (availableActions.size() > 1) {
                throw new IllegalArgumentException("availableActions is not closed");
            }
        }
    }

    record FieldDecisionSummary(int viewCount, int maskedCount, int omittedCount) {
        FieldDecisionSummary {
            if (viewCount < 0 || maskedCount < 0 || omittedCount < 0) {
                throw new IllegalArgumentException("field decision summary is invalid");
            }
        }
    }

    record CachePolicy(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) {
        CachePolicy {
            if (authorizationDecisionRevision == null
                    || !authorizationDecisionRevision
                    .matches("[A-Za-z0-9._:-]{1,128}")
                    || !"dwp.hris.access.assignment.changed.v1"
                    .equals(invalidationEvent)
                    || maxAgeSeconds < 0 || maxAgeSeconds > 300) {
                throw new IllegalArgumentException("cache policy is invalid");
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
            if (scopeDecisionRevision == null
                    || !scopeDecisionRevision.matches("[A-Za-z0-9._:-]{1,128}")
                    || !Set.of("CURRENT", "STALE", "UNAVAILABLE")
                    .contains(freshness)) {
                throw new IllegalArgumentException("query state is invalid");
            }
            Objects.requireNonNull(fieldDecisionSummary, "fieldDecisionSummary");
            partialFailures = List.copyOf(partialFailures);
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(cachePolicy, "cachePolicy");
            if ((state == QueryState.PARTIAL && partialFailures.isEmpty())
                    || (state == QueryState.COMPLETE && !partialFailures.isEmpty())
                    || (state == QueryState.UNAVAILABLE
                    && (!"UNAVAILABLE".equals(freshness) || nextCursor != null))) {
                throw new IllegalArgumentException("query state is inconsistent");
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({
            "projectionVersion", "asOf", "variantKey", "queryState", "items"})
    record EntryQueryResponse(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryStateValue queryState,
            List<EntryItem> items) {

        EntryQueryResponse {
            if (!("STATUTORY".equals(variantKey)
                    && "2".equals(projectionVersion))
                    && !("TAX_SOCIAL_INSURANCE".equals(variantKey)
                    && "1".equals(projectionVersion))) {
                throw new IllegalArgumentException("projection version is invalid");
            }
            if (!Set.of("STATUTORY", "TAX_SOCIAL_INSURANCE")
                    .contains(variantKey)) {
                throw new IllegalArgumentException("query envelope is invalid");
            }
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(queryState, "queryState");
            items = List.copyOf(items);
            if ((queryState.state() == QueryState.EMPTY && !items.isEmpty())
                    || (queryState.state() == QueryState.UNAVAILABLE
                    && !items.isEmpty())
                    || (queryState.state() == QueryState.PARTIAL
                    && items.isEmpty())
                    || (queryState.state() == QueryState.COMPLETE
                    && items.isEmpty())) {
                throw new IllegalArgumentException(
                        "query state and cardinality are inconsistent");
            }
        }
    }

    @JsonPropertyOrder({
            "receiptId", "commandType", "originatingAction",
            "subjectPrincipalPublicId", "populationScopeDigest",
            "fieldPolicyRevision", "purposeCode", "authorizationRevision",
            "status", "correlationId", "requestDigest", "createdAt",
            "aggregateVersion", "resultRef"})
    record PayrollCommandReceipt(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String status,
            UUID correlationId,
            String requestDigest,
            Instant createdAt,
            long aggregateVersion,
            String resultRef) {

        PayrollCommandReceipt {
            Objects.requireNonNull(receiptId, "receiptId");
            if (!"COUNTRY_PACK_ACTIVATE".equals(commandType)
                    || !ACTIVATE_ACTION.equals(originatingAction)
                    || !ACTIVATION_PURPOSE.equals(purposeCode)
                    || !Set.of("ACCEPTED", "SUCCEEDED").contains(status)
                    || aggregateVersion < 1 || fieldPolicyRevision < 1
                    || authorizationRevision < 1) {
                throw new IllegalArgumentException("receipt is invalid");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            digest(populationScopeDigest, "populationScopeDigest");
            Objects.requireNonNull(correlationId, "correlationId");
            digest(requestDigest, "requestDigest");
            Objects.requireNonNull(createdAt, "createdAt");
            token(resultRef, 240, "resultRef");
        }
    }

    record PackSnapshot(
            long tenantId,
            UUID packId,
            String packKey,
            String packVersion,
            long snapshotRevision,
            long currentRevision,
            UUID legalEntityId,
            UUID payrollGroupId,
            String jurisdictionCode,
            LifecycleState lifecycleState,
            String payloadDigest,
            String artifactDigest,
            String signatureDigest,
            String signatureAlgorithm,
            String signingKeyId,
            String compatibilityVersion,
            String policySchemaVersion,
            String officialReferenceSet,
            String goldenPackDigest,
            UUID statutoryApprovalReceiptId,
            UUID installerSubjectPublicId,
            UUID statutoryApproverSubjectPublicId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {

        PackSnapshot {
            if (tenantId < 1 || snapshotRevision < 0
                    || currentRevision < snapshotRevision) {
                throw new IllegalArgumentException("snapshot revision is invalid");
            }
            Objects.requireNonNull(packId, "packId");
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            Objects.requireNonNull(lifecycleState, "lifecycleState");
            digest(payloadDigest, "payloadDigest");
            digest(artifactDigest, "artifactDigest");
            digest(signatureDigest, "signatureDigest");
            digest(goldenPackDigest, "goldenPackDigest");
        }
    }

    record SocialInsuranceFact(
            long tenantId,
            UUID workerResultId,
            UUID socialInsuranceFactId,
            String workerTitle,
            PostedMoney amount,
            String resultDigest,
            String policyVersion,
            String packVersion,
            long activationRevision,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    record ProviderSourceLine(
            UUID resultLineId,
            PostedMoney contribution,
            String sourceSemantic) {
        ProviderSourceLine {
            Objects.requireNonNull(resultLineId, "resultLineId");
            Objects.requireNonNull(contribution, "contribution");
            if (!"TYPED_COUNTRY_RULE_OUTPUT".equals(sourceSemantic)) {
                throw new IllegalArgumentException(
                        "Generic result-line category cannot become social insurance");
            }
        }
    }

    record SocialInsuranceProviderOutcome(
            long tenantId,
            UUID runId,
            int attemptNo,
            UUID workerResultId,
            UUID packId,
            long activationRevision,
            String policyVersion,
            PostedMoney amount,
            List<ProviderSourceLine> sourceLines,
            String certifiedProviderEvidenceDigest,
            Instant recordedAt) {
        SocialInsuranceProviderOutcome {
            if (tenantId < 1 || attemptNo < 1 || activationRevision < 1) {
                throw new IllegalArgumentException("provider lineage is invalid");
            }
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(workerResultId, "workerResultId");
            Objects.requireNonNull(packId, "packId");
            token(policyVersion, 64, "policyVersion");
            Objects.requireNonNull(amount, "amount");
            sourceLines = List.copyOf(sourceLines);
            if (sourceLines.isEmpty()) {
                throw new IllegalArgumentException("sourceLines is empty");
            }
            digest(certifiedProviderEvidenceDigest,
                    "certifiedProviderEvidenceDigest");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }
    }

    record PreparedSocialInsuranceFact(
            UUID socialInsuranceFactId,
            SocialInsuranceProviderOutcome outcome,
            List<UUID> sourceResultLineIds,
            String sourceLineSetDigest,
            String providerOutcomeDigest,
            String resultDigest) {
    }

    record SourcePage<T>(Availability availability, List<T> rows) {
        SourcePage {
            Objects.requireNonNull(availability, "availability");
            rows = List.copyOf(rows == null ? List.of() : rows);
            if (availability == Availability.UNAVAILABLE && !rows.isEmpty()) {
                throw new IllegalArgumentException("unavailable source has rows");
            }
        }

        static <T> SourcePage<T> unavailable() {
            return new SourcePage<>(Availability.UNAVAILABLE, List.of());
        }
    }

    record StoredReceipt(String requestDigest, PayrollCommandReceipt receipt) {
    }

    record ActivationCommit(
            Pay003Authority.Actor actor,
            PackSnapshot snapshot,
            ActivationCommand command,
            ActivationHeaders headers,
            String requestDigest,
            UUID receiptId,
            UUID eventId,
            long activationRevision,
            Instant occurredAt,
            Pay003Ports.StepUpEvidence stepUpEvidence,
            PayrollCommandReceipt response) {
    }

    static void token(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static void digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static String canonicalUuid(String value, String name) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) throw new IllegalArgumentException();
            return canonical;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " is not a canonical UUID");
        }
    }
}
