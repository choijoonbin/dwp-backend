package com.dwp.services.payroll.preparation;

import static com.dwp.services.payroll.preparation.PayrollPreparationValidation.*;

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
            PayrollPreparationValidation.aggregateFilterFields(minimumItemCount, maximumItemCount);
        }
    }

    record MetadataFilter(List<String> keys, List<String> versions) {
        MetadataFilter {
            keys = keys == null ? List.of() : List.copyOf(keys);
            versions = PayrollPreparationValidation.metadataFilterVersions(keys, versions);
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

        EntryQuery {
            PayrollPreparationValidation.entryQueryFields(cursor, filter, limit, locale);
            sort = PayrollPreparationValidation.entryQuerySort2(sort, timeZone, correlationId);
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
            PayrollPreparationValidation.fieldDecisionFields(decision, value, maskReason, maskedDisplayKey);
        }

        static FieldDecision view(Object value) {
            return new FieldDecision("VIEW", value, null, null);
        }
    }

    record DeepLink(String route, String resourcePublicId) {
        DeepLink {
            PayrollPreparationValidation.deepLinkFields(route, resourcePublicId);
        }
    }

    record AvailableAction(
            String actionKey,
            String method,
            String hrefTemplate,
            String idempotencyPolicy,
            String stepUpPolicy) {
        AvailableAction {
            PayrollPreparationValidation.availableActionFields(actionKey, method, hrefTemplate, idempotencyPolicy, stepUpPolicy);
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
            PayrollPreparationValidation.entryItemFields(itemPublicId, title, statusCode);
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
            PayrollPreparationValidation.partialFailureFields(componentKey, errorCode, correlationId);
        }
    }

    record CachePolicy(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) {
        CachePolicy {
            PayrollPreparationValidation.cachePolicyFields(authorizationDecisionRevision, invalidationEvent, maxAgeSeconds);
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
            PayrollPreparationValidation.queryStateFields(state, scopeDecisionRevision, freshness, nextCursor, fieldDecisionSummary);
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
            PayrollPreparationValidation.entryQueryResponseFields(projectionVersion, asOf, variantKey, queryState);
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
            PayrollPreparationValidation.populationSelectionFields(
                    legalEntityId, payrollGroupId, payPeriodId, populationKind, namedPopulationId);
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
            PayrollPreparationValidation.populationQueryFields(legalEntityId, payrollGroupId, payPeriodId, cursor, limit);
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
            PayrollPreparationValidation.sourceRevisionFields(
                    sourceType, sourceId, revision, payloadDigest, approvalReceiptId, approvalRevision, lineCount,
                    status);
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
            PayrollPreparationValidation.preflightDecisionFields(code, status, sourceType, sourceId, revision, evidenceDigest);
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
            PayrollPreparationValidation.populationResponseFields(
                    populationRevision, targetPopulationDigest, scopeRevision, scopeDigest, selection);
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
            PayrollPreparationValidation.prepareCommandFields(
                    preparationId, runType, payrollEntityId, payGroupId, payPeriodId, asOf, inputSnapshotDigest);
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
            PayrollPreparationValidation.payrollCommandReceiptFields(
                    receiptId, commandType, originatingAction, subjectPrincipalPublicId, populationScopeDigest,
                    fieldPolicyRevision, purposeCode, authorizationRevision, status, correlationId, requestDigest,
                    createdAt, aggregateVersion, resultRef);
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
            PayrollPreparationValidation.sourceLineFields(
                    sourceInputId, sourcePublicId, sourceEventId, snapshotId, snapshotRevision, snapshotPayloadDigest,
                    planId, cycleId, lineSequence, workerId, assignmentId, componentCode, currency, approvedAmount,
                    effectiveFrom, payloadDigest);
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
            PayrollPreparationValidation.preparedLineFields(
                    sourceInputId, sourcePublicId, sourceEventId, snapshotId, snapshotRevision, snapshotPayloadDigest,
                    planId, cycleId, lineSequence, workerId, assignmentId, componentCode, currency, approvedAmount,
                    effectiveFrom, payloadDigest, semanticBindingJson, semanticBindingDigest);
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
            PayrollPreparationValidation.frozenMaterialFields(
                    sourceSnapshotId, sourceSnapshotRevision, sourcePayloadDigest, formulaSetDigest,
                    compensationInputDigest, semanticBindingDigest, functionSetVersion, currency);
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
        PayrollPreparationValidation.digest(value, field);
    }

    static void token(String value, int maximum, String field) {
        PayrollPreparationValidation.token(value, maximum, field);
    }

}
