package com.dwp.services.payroll.preparation;

import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class PayrollPreparationModels {

    static final String FEATURE = "HRIS_PAYROLL_RUN_PREPARE";
    static final String ENGINE_VERSION = "PAY_CALCULATION_V1";
    static final String PREPARED_EVENT = "PayrollRunPrepared";

    private PayrollPreparationModels() {
    }

    enum RouteKind { PAGE, DATA, ACTION }

    enum SourceType {
        TARGET_POPULATION_PROJECTION,
        COMPENSATION_PLAN_SNAPSHOT,
        PAYROLL_FOUNDATION_POLICY,
        PAYROLL_FORMULA_SET,
        SEMANTIC_MAPPING_SET
    }

    enum SourceStatus { CURRENT, STALE, INCOMPLETE }

    enum DecisionStatus { PASS, FAIL }

    enum PreflightStatus { PASS, FAIL, PARTIAL }

    enum PreparationState { PREPARING, READY }

    enum RunType { REGULAR, OFF_CYCLE, RETRO, CORRECTION }

    enum ReceiptStatus {
        ACCEPTED, RUNNING, SUCCEEDED, REJECTED, FAILED, RESULT_UNKNOWN
    }

    record AggregateFilter(Long minimumItemCount, Long maximumItemCount) {
        AggregateFilter {
            if ((minimumItemCount != null && minimumItemCount < 0)
                    || (maximumItemCount != null && maximumItemCount < 0)
                    || (minimumItemCount != null && maximumItemCount != null
                    && minimumItemCount > maximumItemCount)) {
                throw new IllegalArgumentException("payrollPopulation filter is invalid");
            }
        }
    }

    record MetadataFilter(List<String> keys, List<String> versions) {
        MetadataFilter {
            keys = keys == null ? List.of() : List.copyOf(keys);
            versions = versions == null ? List.of() : List.copyOf(versions);
            if (keys.size() > 50 || versions.size() > 50
                    || keys.stream().distinct().count() != keys.size()
                    || versions.stream().distinct().count() != versions.size()
                    || java.util.stream.Stream.concat(keys.stream(), versions.stream())
                    .anyMatch(value -> value == null
                            || !value.matches("[A-Za-z0-9._:-]{1,128}"))) {
                throw new IllegalArgumentException("projectionVersion filter is invalid");
            }
        }
    }

    record EntryQueryFilter(
            AggregateFilter payrollPopulation,
            MetadataFilter projectionVersion,
            LocalDate effectiveOn) {
    }

    record EntryQuery(
            Instant asOf,
            String cursor,
            EntryQueryFilter filter,
            int limit,
            String locale,
            List<String> sort,
            String timeZone,
            UUID correlationId) {

        private static final Set<String> SORTS = Set.of(
                "payrollPopulation:ASC", "projectionVersion:ASC",
                "payrollPopulation:DESC", "projectionVersion:DESC",
                "itemPublicId:ASC", "itemPublicId:DESC");

        EntryQuery {
            if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{16,1024}")) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            Objects.requireNonNull(filter, "filter");
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (locale == null || locale.length() < 2 || locale.length() > 35) {
                throw new IllegalArgumentException("locale length is invalid");
            }
            sort = List.copyOf(sort);
            if (sort.isEmpty() || sort.size() > 3
                    || sort.stream().distinct().count() != sort.size()
                    || !SORTS.containsAll(sort)) {
                throw new IllegalArgumentException("sort is invalid");
            }
            if (timeZone == null || timeZone.isEmpty() || timeZone.length() > 255) {
                throw new IllegalArgumentException("timeZone length is invalid");
            }
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    @JsonPropertyOrder({"itemCount", "revision", "asOf"})
    record AggregateValue(long itemCount, long revision, Instant asOf) {
        AggregateValue {
            if (itemCount < 0 || revision < 1) {
                throw new IllegalArgumentException("aggregate value is invalid");
            }
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    @JsonPropertyOrder({"key", "version", "digest"})
    record MetadataValue(String key, String version, String digest) {
        MetadataValue {
            token(key, 128, "key");
            token(version, 64, "version");
            if (digest != null) PayrollPreparationModels.digest(digest, "digest");
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
            if (!Set.of("VIEW", "MASK").contains(decision)) {
                throw new IllegalArgumentException("field decision is invalid");
            }
            if ("VIEW".equals(decision)) {
                Objects.requireNonNull(value, "value");
                if (maskReason != null || maskedDisplayKey != null) {
                    throw new IllegalArgumentException("view decision cannot mask");
                }
            } else if (value != null || maskReason == null
                    || !maskReason.matches("[A-Z][A-Z0-9_]{1,63}")
                    || maskedDisplayKey == null
                    || !maskedDisplayKey.matches("[a-z][a-z0-9._-]{1,127}")) {
                throw new IllegalArgumentException("mask decision is incomplete");
            }
        }

        static FieldDecision view(Object value) {
            return new FieldDecision("VIEW", value, null, null);
        }
    }

    record DeepLink(String route, String resourcePublicId) {
        DeepLink {
            if (!"/hr/operations/payroll/population".equals(route)
                    || resourcePublicId == null
                    || !resourcePublicId.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new IllegalArgumentException("payroll deep link is invalid");
            }
        }
    }

    record AvailableAction(
            String actionKey,
            String method,
            String hrefTemplate,
            String idempotencyPolicy,
            String stepUpPolicy) {
        AvailableAction {
            if (!"hris.action.pay.run.prepare".equals(actionKey)
                    || !"POST".equals(method)
                    || !"/api/payroll/v1/runs".equals(hrefTemplate)
                    || !"REQUIRED_IDEMPOTENCY_KEY".equals(idempotencyPolicy)
                    || !"OWNER_PEP_RISK_POLICY_DECISION_REQUIRED".equals(stepUpPolicy)) {
                throw new IllegalArgumentException("prepare action is invalid");
            }
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
            if (itemPublicId == null
                    || !itemPublicId.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new IllegalArgumentException("itemPublicId is invalid");
            }
            if (title == null || title.isBlank() || title.length() > 512) {
                throw new IllegalArgumentException("title is invalid");
            }
            if (statusCode == null
                    || !statusCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("statusCode is invalid");
            }
            fieldGroups = Map.copyOf(new LinkedHashMap<>(fieldGroups));
            if (fieldGroups.isEmpty()
                    || !Set.of("PAYROLL_POPULATION", "PROJECTION_VERSION")
                    .containsAll(fieldGroups.keySet())) {
                throw new IllegalArgumentException("fieldGroups are invalid");
            }
            Objects.requireNonNull(deepLink, "deepLink");
            availableActions = List.copyOf(availableActions);
            if (availableActions.size() > 1) {
                throw new IllegalArgumentException("only prepare action is allowed");
            }
        }
    }

    record FieldDecisionSummary(int viewCount, int maskedCount, int omittedCount) {
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
            token(authorizationDecisionRevision, 128,
                    "authorizationDecisionRevision");
            if (!"dwp.hris.access.assignment.changed.v1".equals(invalidationEvent)
                    || maxAgeSeconds < 0 || maxAgeSeconds > 300) {
                throw new IllegalArgumentException("cache policy is invalid");
            }
        }
    }

    @JsonPropertyOrder({
            "state", "scopeDecisionRevision", "freshness", "nextCursor",
            "fieldDecisionSummary", "partialFailures", "correlationId", "cachePolicy"})
    record QueryState(
            String state,
            String scopeDecisionRevision,
            String freshness,
            String nextCursor,
            FieldDecisionSummary fieldDecisionSummary,
            List<PartialFailure> partialFailures,
            UUID correlationId,
            CachePolicy cachePolicy) {
        QueryState {
            if (!Set.of("COMPLETE", "EMPTY", "PARTIAL", "UNAVAILABLE")
                    .contains(state)
                    || !Set.of("CURRENT", "STALE", "UNAVAILABLE")
                    .contains(freshness)) {
                throw new IllegalArgumentException("query state is invalid");
            }
            token(scopeDecisionRevision, 128, "scopeDecisionRevision");
            if (nextCursor != null
                    && !nextCursor.matches("[A-Za-z0-9_-]{16,1024}")) {
                throw new IllegalArgumentException("nextCursor is invalid");
            }
            Objects.requireNonNull(fieldDecisionSummary, "fieldDecisionSummary");
            partialFailures = List.copyOf(partialFailures);
            if (partialFailures.size() > 32
                    || ("PARTIAL".equals(state) && partialFailures.isEmpty())
                    || (("COMPLETE".equals(state) || "EMPTY".equals(state))
                    && !partialFailures.isEmpty())
                    || ("UNAVAILABLE".equals(state)
                    && (!"UNAVAILABLE".equals(freshness) || nextCursor != null))) {
                throw new IllegalArgumentException("query state evidence is inconsistent");
            }
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(cachePolicy, "cachePolicy");
        }
    }

    @JsonPropertyOrder({
            "projectionVersion", "asOf", "variantKey", "queryState", "items"})
    record EntryQueryResponse(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryState queryState,
            List<EntryItem> items) {
        EntryQueryResponse {
            if (!"1".equals(projectionVersion) || !"POPULATION".equals(variantKey)) {
                throw new IllegalArgumentException("IAQ-014 projection identity is invalid");
            }
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(queryState, "queryState");
            items = List.copyOf(items);
            if (items.size() > 500
                    || (("EMPTY".equals(queryState.state())
                    || "UNAVAILABLE".equals(queryState.state())) && !items.isEmpty())) {
                throw new IllegalArgumentException("IAQ-014 item cardinality is invalid");
            }
        }
    }

    @JsonPropertyOrder({
            "legalEntityId", "payrollGroupId", "payPeriodId",
            "populationKind", "namedPopulationId"})
    record PopulationSelection(
            UUID legalEntityId,
            UUID payrollGroupId,
            UUID payPeriodId,
            String populationKind,
            UUID namedPopulationId) {

        PopulationSelection {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            Objects.requireNonNull(payPeriodId, "payPeriodId");
            if (!List.of("LEGAL_ENTITY", "PAY_GROUP", "NAMED_POPULATION")
                    .contains(populationKind)) {
                throw new IllegalArgumentException("populationKind is invalid");
            }
            if (!"NAMED_POPULATION".equals(populationKind)
                    && namedPopulationId != null) {
                throw new IllegalArgumentException(
                        "namedPopulationId is only valid for NAMED_POPULATION");
            }
        }
    }

    record PopulationQuery(
            UUID legalEntityId,
            UUID payrollGroupId,
            UUID payPeriodId,
            UUID namedPopulationId,
            String cursor,
            int limit) {

        PopulationQuery {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            Objects.requireNonNull(payPeriodId, "payPeriodId");
            if (limit < 1 || limit > 200) {
                throw new IllegalArgumentException("limit must be between 1 and 200");
            }
            if (cursor != null && (cursor.isBlank() || cursor.length() > 2048
                    || !cursor.equals(cursor.strip()))) {
                throw new IllegalArgumentException("cursor is invalid");
            }
        }

        PopulationSelection selection(String populationKind) {
            return new PopulationSelection(
                    legalEntityId, payrollGroupId, payPeriodId,
                    populationKind, namedPopulationId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({
            "sourceType", "sourceId", "revision", "payloadDigest",
            "approvalReceiptId", "approvalRevision", "lineCount", "status"})
    record SourceRevision(
            SourceType sourceType,
            UUID sourceId,
            long revision,
            String payloadDigest,
            UUID approvalReceiptId,
            Long approvalRevision,
            Integer lineCount,
            SourceStatus status) {

        SourceRevision {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(sourceId, "sourceId");
            if (revision < 1) throw new IllegalArgumentException("revision must be positive");
            digest(payloadDigest, "payloadDigest");
            Objects.requireNonNull(status, "status");
            if ((approvalReceiptId == null) != (approvalRevision == null)
                    || (approvalRevision != null && approvalRevision < 1)
                    || (lineCount != null && lineCount < 1)) {
                throw new IllegalArgumentException("source approval/cardinality is invalid");
            }
        }

        SourceIdentity identity() {
            return new SourceIdentity(sourceType, sourceId, revision, payloadDigest);
        }
    }

    @JsonPropertyOrder({"sourceType", "sourceId", "revision", "payloadDigest"})
    record SourceIdentity(
            SourceType sourceType,
            UUID sourceId,
            long revision,
            String payloadDigest) {

        SourceIdentity {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(sourceId, "sourceId");
            if (revision < 1) throw new IllegalArgumentException("revision must be positive");
            digest(payloadDigest, "payloadDigest");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({
            "code", "status", "sourceType", "sourceId", "revision",
            "evidenceDigest"})
    record PreflightDecision(
            String code,
            DecisionStatus status,
            SourceType sourceType,
            UUID sourceId,
            Long revision,
            String evidenceDigest) {

        PreflightDecision {
            token(code, 100, "code");
            Objects.requireNonNull(status, "status");
            if ((sourceType == null) != (sourceId == null)
                    || (sourceType == null) != (revision == null)
                    || (revision != null && revision < 1)) {
                throw new IllegalArgumentException("preflight source binding is invalid");
            }
            digest(evidenceDigest, "evidenceDigest");
        }
    }

    @JsonPropertyOrder({"status", "decisions"})
    record PreflightSummary(
            PreflightStatus status,
            List<PreflightDecision> decisions) {

        PreflightSummary {
            Objects.requireNonNull(status, "status");
            decisions = List.copyOf(decisions);
            if (decisions.isEmpty()) {
                throw new IllegalArgumentException("preflight decisions are required");
            }
            boolean failed = decisions.stream()
                    .anyMatch(value -> value.status() == DecisionStatus.FAIL);
            if ((status == PreflightStatus.PASS) == failed) {
                throw new IllegalArgumentException("preflight status differs from decisions");
            }
        }
    }

    @JsonPropertyOrder({"nextCursor", "bindingDigest"})
    record PopulationPage(String nextCursor, String bindingDigest) {
        PopulationPage {
            if (nextCursor != null && (nextCursor.isBlank() || nextCursor.length() > 2048)) {
                throw new IllegalArgumentException("nextCursor is invalid");
            }
            digest(bindingDigest, "bindingDigest");
        }
    }

    @JsonPropertyOrder({
            "populationRevision", "targetPopulationDigest", "scopeRevision",
            "scopeDigest", "selection", "sources", "preflight", "page"})
    record PopulationResponse(
            String populationRevision,
            String targetPopulationDigest,
            String scopeRevision,
            String scopeDigest,
            PopulationSelection selection,
            List<SourceRevision> sources,
            PreflightSummary preflight,
            PopulationPage page) {

        PopulationResponse {
            token(populationRevision, 200, "populationRevision");
            digest(targetPopulationDigest, "targetPopulationDigest");
            token(scopeRevision, 200, "scopeRevision");
            digest(scopeDigest, "scopeDigest");
            Objects.requireNonNull(selection, "selection");
            sources = List.copyOf(sources);
            Objects.requireNonNull(preflight, "preflight");
            Objects.requireNonNull(page, "page");
            List<SourceRevision> ordered = sources.stream()
                    .sorted(Comparator.comparing(SourceRevision::sourceType)
                            .thenComparing(SourceRevision::sourceId))
                    .toList();
            if (!ordered.equals(sources) || sources.stream().map(SourceRevision::identity)
                    .distinct().count() != sources.size()) {
                throw new IllegalArgumentException("sources must be unique and ordered");
            }
        }
    }

    @JsonPropertyOrder({
            "preparationId", "runType", "payrollEntityId", "payGroupId",
            "payPeriodId", "asOf", "inputSnapshotDigest"})
    @JsonDeserialize(using = PrepareCommandDeserializer.class)
    record PrepareCommand(
            UUID preparationId,
            RunType runType,
            UUID payrollEntityId,
            UUID payGroupId,
            UUID payPeriodId,
            Instant asOf,
            String inputSnapshotDigest) {

        PrepareCommand {
            Objects.requireNonNull(preparationId, "preparationId");
            Objects.requireNonNull(runType, "runType");
            Objects.requireNonNull(payrollEntityId, "payrollEntityId");
            Objects.requireNonNull(payGroupId, "payGroupId");
            Objects.requireNonNull(payPeriodId, "payPeriodId");
            Objects.requireNonNull(asOf, "asOf");
            digest(inputSnapshotDigest, "inputSnapshotDigest");
        }

        PopulationSelection selection(String populationKind) {
            return new PopulationSelection(
                    payrollEntityId, payGroupId, payPeriodId,
                    populationKind, null);
        }
    }

    static final class PrepareCommandDeserializer
            extends JsonDeserializer<PrepareCommand> {

        @Override
        public PrepareCommand deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {
            if (!parser.isExpectedStartObjectToken()) {
                return context.reportInputMismatch(
                        PrepareCommand.class, "RunCommand must be a JSON object");
            }
            UUID preparationId = null;
            RunType runType = null;
            UUID payrollEntityId = null;
            UUID payGroupId = null;
            UUID payPeriodId = null;
            Instant asOf = null;
            String inputSnapshotDigest = null;
            Set<String> seen = new java.util.HashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!parser.hasToken(JsonToken.FIELD_NAME)) {
                    return context.reportInputMismatch(
                            PrepareCommand.class, "RunCommand contains a non-field token");
                }
                String name = parser.currentName();
                if (!seen.add(name)) {
                    return context.reportInputMismatch(
                            PrepareCommand.class, "duplicate property %s", name);
                }
                JsonToken token = parser.nextToken();
                if (token != JsonToken.VALUE_STRING) {
                    return context.reportInputMismatch(
                            PrepareCommand.class, "%s must be a string", name);
                }
                String value = parser.getText();
                try {
                    switch (name) {
                        case "preparationId" -> preparationId = UUID.fromString(value);
                        case "runType" -> runType = RunType.valueOf(value);
                        case "payrollEntityId" -> payrollEntityId = UUID.fromString(value);
                        case "payGroupId" -> payGroupId = UUID.fromString(value);
                        case "payPeriodId" -> payPeriodId = UUID.fromString(value);
                        case "asOf" -> asOf = Instant.parse(value);
                        case "inputSnapshotDigest" -> inputSnapshotDigest = value;
                        default -> {
                            return context.reportInputMismatch(
                                    PrepareCommand.class,
                                    "unknown property %s", name);
                        }
                    }
                } catch (RuntimeException failure) {
                    return context.reportInputMismatch(
                            PrepareCommand.class, "%s is invalid", name);
                }
            }
            Set<String> required = Set.of(
                    "preparationId", "runType", "payrollEntityId",
                    "payGroupId", "payPeriodId", "asOf",
                    "inputSnapshotDigest");
            if (!seen.containsAll(required)) {
                java.util.TreeSet<String> missing = new java.util.TreeSet<>(required);
                missing.removeAll(seen);
                return context.reportInputMismatch(
                        PrepareCommand.class,
                        "RunCommand is missing required properties %s", missing);
            }
            return new PrepareCommand(
                    preparationId, runType, payrollEntityId, payGroupId,
                    payPeriodId, asOf, inputSnapshotDigest);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
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
            ReceiptStatus status,
            UUID correlationId,
            String requestDigest,
            Instant createdAt,
            Long aggregateVersion,
            String resultRef) {

        PayrollCommandReceipt {
            Objects.requireNonNull(receiptId, "receiptId");
            if (!"RUN_PREPARE".equals(commandType)
                    || !"PAY_RUN_PREPARE".equals(originatingAction)) {
                throw new IllegalArgumentException("prepare receipt identity is invalid");
            }
            Objects.requireNonNull(subjectPrincipalPublicId,
                    "subjectPrincipalPublicId");
            digest(populationScopeDigest, "populationScopeDigest");
            if (fieldPolicyRevision < 1 || authorizationRevision < 1) {
                throw new IllegalArgumentException("receipt revisions must be positive");
            }
            if (!"PAYROLL_PROCESSING".equals(purposeCode)) {
                throw new IllegalArgumentException("prepare receipt purpose is invalid");
            }
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(correlationId, "correlationId");
            digest(requestDigest, "requestDigest");
            Objects.requireNonNull(createdAt, "createdAt");
            if (aggregateVersion != null && aggregateVersion < 0) {
                throw new IllegalArgumentException("aggregateVersion must be nonnegative");
            }
            if (resultRef != null && (resultRef.isBlank()
                    || resultRef.length() > 240)) {
                throw new IllegalArgumentException("resultRef is invalid");
            }
        }
    }

    record FormulaRef(UUID formulaId, long version, String astDigest) {
        FormulaRef {
            Objects.requireNonNull(formulaId, "formulaId");
            if (version < 1) throw new IllegalArgumentException("formula version is invalid");
            digest(astDigest, "astDigest");
        }
    }

    record SourceLine(
            long sourceInputId,
            UUID sourcePublicId,
            UUID sourceEventId,
            UUID snapshotId,
            long snapshotRevision,
            String snapshotPayloadDigest,
            UUID planId,
            UUID cycleId,
            int lineSequence,
            UUID workerId,
            UUID assignmentId,
            String componentCode,
            String currency,
            BigDecimal approvedAmount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String payloadDigest) {

        SourceLine {
            validateSourceLine(
                    sourceInputId, sourcePublicId, sourceEventId, snapshotId,
                    snapshotRevision, snapshotPayloadDigest, planId, cycleId,
                    lineSequence, workerId, assignmentId, componentCode, currency,
                    approvedAmount, effectiveFrom, payloadDigest);
        }
    }

    record PreparedLine(
            long sourceInputId,
            UUID sourcePublicId,
            UUID sourceEventId,
            UUID snapshotId,
            long snapshotRevision,
            String snapshotPayloadDigest,
            UUID planId,
            UUID cycleId,
            int lineSequence,
            UUID workerId,
            UUID assignmentId,
            String componentCode,
            String currency,
            BigDecimal approvedAmount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String payloadDigest,
            String semanticBindingJson,
            String semanticBindingDigest) {

        PreparedLine {
            validateSourceLine(
                    sourceInputId, sourcePublicId, sourceEventId, snapshotId,
                    snapshotRevision, snapshotPayloadDigest, planId, cycleId,
                    lineSequence, workerId, assignmentId, componentCode, currency,
                    approvedAmount, effectiveFrom, payloadDigest);
            if (semanticBindingJson == null || semanticBindingJson.isBlank()) {
                throw new IllegalArgumentException("semanticBindingJson is required");
            }
            digest(semanticBindingDigest, "semanticBindingDigest");
        }
    }

    record FrozenMaterial(
            UUID sourceSnapshotId,
            long sourceSnapshotRevision,
            String sourcePayloadDigest,
            String formulaSetDigest,
            String compensationInputDigest,
            String semanticBindingDigest,
            String functionSetVersion,
            String currency,
            List<FormulaRef> formulas,
            List<PreparedLine> lines) {

        FrozenMaterial {
            Objects.requireNonNull(sourceSnapshotId, "sourceSnapshotId");
            if (sourceSnapshotRevision < 1) {
                throw new IllegalArgumentException("sourceSnapshotRevision is invalid");
            }
            digest(sourcePayloadDigest, "sourcePayloadDigest");
            digest(formulaSetDigest, "formulaSetDigest");
            digest(compensationInputDigest, "compensationInputDigest");
            digest(semanticBindingDigest, "semanticBindingDigest");
            token(functionSetVersion, 64, "functionSetVersion");
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency is invalid");
            }
            formulas = List.copyOf(formulas);
            lines = List.copyOf(lines);
            if (formulas.isEmpty() || lines.isEmpty()) {
                throw new IllegalArgumentException("frozen material is incomplete");
            }
            if (!formulas.equals(formulas.stream()
                    .sorted(Comparator.comparing(FormulaRef::formulaId)).toList())
                    || formulas.stream().map(FormulaRef::formulaId)
                    .distinct().count() != formulas.size()) {
                throw new IllegalArgumentException(
                        "frozen formulas must be unique and ordered");
            }
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).lineSequence() != index + 1) {
                    throw new IllegalArgumentException(
                            "frozen lines must be contiguous and ordered");
                }
            }
            if (lines.stream().map(PreparedLine::sourceInputId)
                    .distinct().count() != lines.size()
                    || lines.stream().map(PreparedLine::sourcePublicId)
                    .distinct().count() != lines.size()) {
                throw new IllegalArgumentException("frozen lines must be unique");
            }
        }
    }

    record PreparationCandidate(
            PopulationResponse population,
            FrozenMaterial material) {

        PreparationCandidate {
            Objects.requireNonNull(population, "population");
            Objects.requireNonNull(material, "material");
            if (population.preflight().status() != PreflightStatus.PASS) {
                throw new IllegalArgumentException("candidate preflight must pass");
            }
        }
    }

    record StoredCommand(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String idempotencyKey,
            String requestDigest,
            UUID preparationId,
            PayrollCommandReceipt response) {
    }

    record PreparationCommit(
            PayrollPreparationAuthority.Actor actor,
            PrepareCommand command,
            PreparationCandidate candidate,
            String idempotencyKey,
            String requestDigest,
            UUID runId,
            UUID snapshotId,
            String snapshotDigest,
            UUID receiptId,
            UUID eventId,
            UUID correlationId,
            PayrollStepUpEvidence stepUp,
            Instant preparedAt,
            PayrollCommandReceipt response) {
    }

    static void digest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical SHA-256");
        }
    }

    static void token(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip())
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void validateSourceLine(
            long sourceInputId,
            UUID sourcePublicId,
            UUID sourceEventId,
            UUID snapshotId,
            long snapshotRevision,
            String snapshotPayloadDigest,
            UUID planId,
            UUID cycleId,
            int lineSequence,
            UUID workerId,
            UUID assignmentId,
            String componentCode,
            String currency,
            BigDecimal approvedAmount,
            LocalDate effectiveFrom,
            String payloadDigest) {
        if (sourceInputId < 1 || snapshotRevision < 1 || lineSequence < 1) {
            throw new IllegalArgumentException("prepared line positive fields are invalid");
        }
        Objects.requireNonNull(sourcePublicId, "sourcePublicId");
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(cycleId, "cycleId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        token(componentCode, 80, "componentCode");
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency is invalid");
        }
        Objects.requireNonNull(approvedAmount, "approvedAmount");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        digest(snapshotPayloadDigest, "snapshotPayloadDigest");
        digest(payloadDigest, "payloadDigest");
    }
}
