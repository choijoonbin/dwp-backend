package com.dwp.services.payroll.preparation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Canonical normalization and validation for payroll model values. */
final class PayrollPreparationValidation {

    private PayrollPreparationValidation() {
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

    static void validateSourceLine(
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

    static void aggregateFilterFields(
            Long minimumItemCount,
            Long maximumItemCount) {
        if ((minimumItemCount != null && minimumItemCount < 0)
                || (maximumItemCount != null && maximumItemCount < 0)
                || (minimumItemCount != null && maximumItemCount != null
                && minimumItemCount > maximumItemCount)) {
            throw new IllegalArgumentException("payrollPopulation filter is invalid");
        }
    }

    static List<String> metadataFilterVersions(
            List<String> keys,
            List<String> versions) {
        versions = versions == null ? List.of() : List.copyOf(versions);
        if (keys.size() > 50 || versions.size() > 50
                || keys.stream().distinct().count() != keys.size()
                || versions.stream().distinct().count() != versions.size()
                || java.util.stream.Stream.concat(keys.stream(), versions.stream())
                .anyMatch(value -> value == null
                        || !value.matches("[A-Za-z0-9._:-]{1,128}"))) {
            throw new IllegalArgumentException("projectionVersion filter is invalid");
        }
        return versions;
    }

    static void entryQueryFields(
            String cursor,
            Object filter,
            int limit,
            String locale) {
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
    }

    private static final Set<String> SORTS = Set.of(
            "payrollPopulation:ASC", "projectionVersion:ASC",
            "payrollPopulation:DESC", "projectionVersion:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");

    static List<String> entryQuerySort2(
            List<String> sort,
            String timeZone,
            UUID correlationId) {
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
        return sort;
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

    static void deepLinkFields(
            String route,
            String resourcePublicId) {
        if (!"/hr/operations/payroll/population".equals(route)
                || resourcePublicId == null
                || !resourcePublicId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("payroll deep link is invalid");
        }
    }

    static void availableActionFields(
            String actionKey,
            String method,
            String hrefTemplate,
            String idempotencyPolicy,
            String stepUpPolicy) {
        if (!"hris.action.pay.run.prepare".equals(actionKey)
                || !"POST".equals(method)
                || !"/api/payroll/v1/runs".equals(hrefTemplate)
                || !"REQUIRED_IDEMPOTENCY_KEY".equals(idempotencyPolicy)
                || !"OWNER_PEP_RISK_POLICY_DECISION_REQUIRED".equals(stepUpPolicy)) {
            throw new IllegalArgumentException("prepare action is invalid");
        }
    }

    static void entryItemFields(
            String itemPublicId,
            String title,
            String statusCode) {
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
    }

    static void partialFailureFields(
            String componentKey,
            String errorCode,
            UUID correlationId) {
        if (componentKey == null
                || !componentKey.matches("[a-z][a-z0-9._-]{0,127}")
                || errorCode == null
                || !errorCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("partial failure is invalid");
        }
        Objects.requireNonNull(correlationId, "correlationId");
    }

    static void cachePolicyFields(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) {
        token(authorizationDecisionRevision, 128,
                "authorizationDecisionRevision");
        if (!"dwp.hris.access.assignment.changed.v1".equals(invalidationEvent)
                || maxAgeSeconds < 0 || maxAgeSeconds > 300) {
            throw new IllegalArgumentException("cache policy is invalid");
        }
    }

    static void queryStateFields(
            String state,
            String scopeDecisionRevision,
            String freshness,
            String nextCursor,
            Object fieldDecisionSummary) {
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
    }

    static void entryQueryResponseFields(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            Object queryState) {
        if (!"1".equals(projectionVersion) || !"POPULATION".equals(variantKey)) {
            throw new IllegalArgumentException("IAQ-014 projection identity is invalid");
        }
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(queryState, "queryState");
    }

    static void populationSelectionFields(
            UUID legalEntityId,
            UUID payrollGroupId,
            UUID payPeriodId,
            String populationKind,
            UUID namedPopulationId) {
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

    static void populationQueryFields(
            UUID legalEntityId,
            UUID payrollGroupId,
            UUID payPeriodId,
            String cursor,
            int limit) {
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

    static void sourceRevisionFields(
            Enum<?> sourceType,
            UUID sourceId,
            long revision,
            String payloadDigest,
            UUID approvalReceiptId,
            Long approvalRevision,
            Integer lineCount,
            Enum<?> status) {
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

    static void preflightDecisionFields(
            String code,
            Enum<?> status,
            Enum<?> sourceType,
            UUID sourceId,
            Long revision,
            String evidenceDigest) {
        token(code, 100, "code");
        Objects.requireNonNull(status, "status");
        if ((sourceType == null) != (sourceId == null)
                || (sourceType == null) != (revision == null)
                || (revision != null && revision < 1)) {
            throw new IllegalArgumentException("preflight source binding is invalid");
        }
        digest(evidenceDigest, "evidenceDigest");
    }

    static void populationResponseFields(
            String populationRevision,
            String targetPopulationDigest,
            String scopeRevision,
            String scopeDigest,
            Object selection) {
        token(populationRevision, 200, "populationRevision");
        digest(targetPopulationDigest, "targetPopulationDigest");
        token(scopeRevision, 200, "scopeRevision");
        digest(scopeDigest, "scopeDigest");
        Objects.requireNonNull(selection, "selection");
    }

    static void prepareCommandFields(
            UUID preparationId,
            Enum<?> runType,
            UUID payrollEntityId,
            UUID payGroupId,
            UUID payPeriodId,
            Instant asOf,
            String inputSnapshotDigest) {
        Objects.requireNonNull(preparationId, "preparationId");
        Objects.requireNonNull(runType, "runType");
        Objects.requireNonNull(payrollEntityId, "payrollEntityId");
        Objects.requireNonNull(payGroupId, "payGroupId");
        Objects.requireNonNull(payPeriodId, "payPeriodId");
        Objects.requireNonNull(asOf, "asOf");
        digest(inputSnapshotDigest, "inputSnapshotDigest");
    }

    static void payrollCommandReceiptFields(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            Enum<?> status,
            UUID correlationId,
            String requestDigest,
            Instant createdAt,
            Long aggregateVersion,
            String resultRef) {
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

    static void sourceLineFields(
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
        validateSourceLine(
                sourceInputId, sourcePublicId, sourceEventId, snapshotId,
                snapshotRevision, snapshotPayloadDigest, planId, cycleId,
                lineSequence, workerId, assignmentId, componentCode, currency,
                approvedAmount, effectiveFrom, payloadDigest);
    }

    static void preparedLineFields(
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
            String payloadDigest,
            String semanticBindingJson,
            String semanticBindingDigest) {
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

    static void frozenMaterialFields(
            UUID sourceSnapshotId,
            long sourceSnapshotRevision,
            String sourcePayloadDigest,
            String formulaSetDigest,
            String compensationInputDigest,
            String semanticBindingDigest,
            String functionSetVersion,
            String currency) {
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
    }
}
