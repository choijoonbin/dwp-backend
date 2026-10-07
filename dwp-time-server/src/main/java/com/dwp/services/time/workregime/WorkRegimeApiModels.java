package com.dwp.services.time.workregime;

import java.time.DayOfWeek;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.dwp.services.time.workregime.WorkRegimeModels.ArrangementKind;
import com.dwp.services.time.workregime.WorkRegimeModels.DstOverlapPolicy;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyExtensionKind;
import com.dwp.services.time.workregime.WorkRegimeModels.ScopeType;
import com.dwp.services.time.workregime.WorkRegimeModels.SegmentKind;

/** Closed HTTP payloads for the default-off TIM owner API. */
public final class WorkRegimeApiModels {

    public static final String SIMULATION_PURPOSE = "PRE_PUBLISH_IMPACT_REVIEW";

    // Keeps the largest accepted JSON body bounded while remaining far above a weekly schedule's
    // legitimate cardinality and below the SMALLINT per-day segment sequence range.
    static final int MAX_POLICY_TERMS = 256;
    static final int MAX_SEGMENTS = 1_024;

    private static final int MAX_REGIME_KEY_LENGTH = 128;
    private static final int MAX_DISPLAY_NAME_LENGTH = 240;
    private static final int MAX_SCOPE_REF_LENGTH = 128;
    private static final int MAX_ZONE_ID_LENGTH = 80;
    private static final int MAX_JURISDICTION_LENGTH = 2;
    private static final int MAX_JURISDICTION_SUBDIVISION_LENGTH = 12;
    private static final int MAX_TERM_NAME_LENGTH = 100;
    private static final int MAX_TERM_VALUE_TYPE_LENGTH = 24;
    private static final int MAX_TERM_STRING_LENGTH = 1_024;
    private static final int MAX_SEGMENT_KEY_LENGTH = 160;
    private static final int MAX_PURPOSE_LENGTH = 80;
    private static final int MAX_BIGINT_TEXT_LENGTH = 19;
    private static final int TERM_DECIMAL_PRECISION = 24;
    private static final int TERM_DECIMAL_SCALE = 8;

    private WorkRegimeApiModels() {
    }

    public record CreateDraftRequest(
            String regimeKey,
            String displayName,
            ArrangementKind arrangementKind,
            TenantExtensionInput tenantExtension,
            ScopeType scopeType,
            String scopeRef,
            int priority,
            LocalDate effectiveStart,
            LocalDate effectiveEnd,
            String timeZone,
            UUID rulePackPublicId,
            String jurisdiction,
            String jurisdictionSubdivision,
            long policyRevision,
            int templateSchemaVersion,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long assignmentSnapshotRevision,
            List<PolicyTermInput> terms,
            List<SegmentInput> segments) {
        public CreateDraftRequest {
            regimeKey = boundedText(regimeKey, "regimeKey", MAX_REGIME_KEY_LENGTH);
            displayName = boundedText(displayName, "displayName", MAX_DISPLAY_NAME_LENGTH);
            Objects.requireNonNull(arrangementKind, "arrangementKind must not be null");
            Objects.requireNonNull(scopeType, "scopeType must not be null");
            scopeRef = boundedText(scopeRef, "scopeRef", MAX_SCOPE_REF_LENGTH);
            Objects.requireNonNull(effectiveStart, "effectiveStart must not be null");
            if (effectiveEnd != null && !effectiveEnd.isAfter(effectiveStart)) {
                throw new IllegalArgumentException("effective range must be non-empty and half-open");
            }
            timeZone = regionZoneId(timeZone);
            Objects.requireNonNull(rulePackPublicId, "rulePackPublicId must not be null");
            jurisdiction = boundedText(
                    jurisdiction, "jurisdiction", MAX_JURISDICTION_LENGTH);
            if (!jurisdiction.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("jurisdiction must be an ISO country code");
            }
            jurisdictionSubdivision = jurisdictionSubdivision == null
                    ? "" : jurisdictionSubdivision.trim();
            if (jurisdictionSubdivision.length() > MAX_JURISDICTION_SUBDIVISION_LENGTH
                    || !jurisdictionSubdivision.matches("[A-Z0-9-]*")) {
                throw new IllegalArgumentException("jurisdictionSubdivision is invalid");
            }
            if (policyRevision < 1 || templateSchemaVersion < 1
                    || assignmentSnapshotRevision < 0) {
                throw new IllegalArgumentException("revisions and schema version are invalid");
            }
            Objects.requireNonNull(workerPublicId, "workerPublicId must not be null");
            Objects.requireNonNull(
                    peopleAssignmentPublicId, "peopleAssignmentPublicId must not be null");
            terms = boundedCopy(terms, "terms", MAX_POLICY_TERMS);
            segments = boundedCopy(segments, "segments", MAX_SEGMENTS);
            if (segments.stream().map(SegmentInput::key).distinct().count() != segments.size()) {
                throw new IllegalArgumentException("segment keys must be unique");
            }
            if (terms.stream()
                    .map(term -> term.extensionKind() + ":" + term.parameterName())
                    .distinct().count() != terms.size()) {
                throw new IllegalArgumentException("policy term identities must be unique");
            }
            if ((arrangementKind == ArrangementKind.TENANT_EXTENSION)
                    != (tenantExtension != null)) {
                throw new IllegalArgumentException(
                        "tenant extension metadata must match the arrangement kind");
            }
        }
    }

    public enum TenantExtensionValueType {
        STRING,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        DATE
    }

    public record TenantExtensionInput(
            String schemaRef, int schemaVersion, List<TenantExtensionFieldInput> fields) {
        public TenantExtensionInput {
            schemaRef = WorkRegimeModels.requireText(schemaRef, "extension schemaRef");
            if (schemaRef.length() > 240
                    || !schemaRef.matches("[A-Za-z][A-Za-z0-9._:/-]{0,239}")) {
                throw new IllegalArgumentException("extension schemaRef is invalid");
            }
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("extension schemaVersion must be positive");
            }
            fields = List.copyOf(Objects.requireNonNull(fields, "extension fields must not be null"));
            if (fields.isEmpty() || fields.size() > 100
                    || fields.stream().map(TenantExtensionFieldInput::fieldName)
                            .distinct().count() != fields.size()) {
                throw new IllegalArgumentException(
                        "extension fields must be non-empty, bounded, and unique");
            }
        }
    }

    public record TenantExtensionFieldInput(
            String fieldName,
            TenantExtensionValueType valueType,
            String stringValue,
            Long integerValue,
            java.math.BigDecimal decimalValue,
            Boolean booleanValue,
            LocalDate dateValue) {
        public TenantExtensionFieldInput {
            fieldName = WorkRegimeModels.requireText(fieldName, "extension fieldName");
            Objects.requireNonNull(valueType, "extension valueType must not be null");
            if (!fieldName.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}")) {
                throw new IllegalArgumentException("extension fieldName is invalid");
            }
            int populated = (stringValue == null ? 0 : 1)
                    + (integerValue == null ? 0 : 1)
                    + (decimalValue == null ? 0 : 1)
                    + (booleanValue == null ? 0 : 1)
                    + (dateValue == null ? 0 : 1);
            boolean compatible = switch (valueType) {
                case STRING -> stringValue != null
                        && stringValue.length() <= 1_000
                        && stringValue.codePoints().noneMatch(
                                codePoint -> codePoint < 32 || codePoint == 127);
                case INTEGER -> integerValue != null;
                case DECIMAL -> decimalValue != null
                        && decimalValue.precision() <= 38
                        && decimalValue.scale() >= 0
                        && decimalValue.scale() <= 12;
                case BOOLEAN -> booleanValue != null;
                case DATE -> dateValue != null;
            };
            if (populated != 1 || !compatible) {
                throw new IllegalArgumentException("extension field value is not exactly typed");
            }
        }

        Object typedValue() {
            return switch (valueType) {
                case STRING -> stringValue;
                case INTEGER -> integerValue;
                case DECIMAL -> decimalValue;
                case BOOLEAN -> booleanValue;
                case DATE -> dateValue;
            };
        }
    }

    public record PolicyTermInput(
            PolicyExtensionKind extensionKind,
            String parameterName,
            String valueType,
            String stringValue,
            Long integerValue,
            java.math.BigDecimal decimalValue,
            Boolean booleanValue,
            LocalDate dateValue) {
        public PolicyTermInput {
            Objects.requireNonNull(extensionKind, "extensionKind must not be null");
            parameterName = boundedText(
                    parameterName, "parameterName", MAX_TERM_NAME_LENGTH);
            valueType = boundedText(
                    valueType, "valueType", MAX_TERM_VALUE_TYPE_LENGTH);
            if (!parameterName.matches("[A-Z][A-Z0-9_]{1,99}")) {
                throw new IllegalArgumentException("parameterName is invalid");
            }
            if (stringValue != null && stringValue.length() > MAX_TERM_STRING_LENGTH) {
                throw new IllegalArgumentException("policy term string value is too long");
            }
            if (decimalValue != null && !isDdlTermDecimal(decimalValue)) {
                throw new IllegalArgumentException("policy term decimal value is out of range");
            }
            int populated = (stringValue == null ? 0 : 1)
                    + (integerValue == null ? 0 : 1)
                    + (decimalValue == null ? 0 : 1)
                    + (booleanValue == null ? 0 : 1)
                    + (dateValue == null ? 0 : 1);
            boolean compatible = switch (valueType) {
                case "STRING" -> stringValue != null;
                case "INTEGER", "DURATION_MINUTES" -> integerValue != null;
                case "DECIMAL" -> decimalValue != null;
                case "BOOLEAN" -> booleanValue != null;
                case "DATE" -> dateValue != null;
                default -> false;
            };
            if (populated != 1 || !compatible) {
                throw new IllegalArgumentException("policy term value is not exactly typed");
            }
        }
    }

    public record SegmentInput(
            String key,
            DayOfWeek dayOfWeek,
            SegmentKind kind,
            LocalTime start,
            LocalTime end,
            int endDayOffset,
            DstOverlapPolicy overlapPolicy) {
        public SegmentInput {
            key = boundedText(key, "segment key", MAX_SEGMENT_KEY_LENGTH);
            Objects.requireNonNull(dayOfWeek, "dayOfWeek must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(start, "start must not be null");
            Objects.requireNonNull(end, "end must not be null");
            Objects.requireNonNull(overlapPolicy, "overlapPolicy must not be null");
            new WorkRegimeModels.LocalSegment(
                    key, dayOfWeek, kind, start, end, endDayOffset, overlapPolicy);
        }
    }

    public record SimulationRequest(
            long expectedVersion,
            String assignmentSnapshotRevision,
            String policyRevision,
            String purpose) {
        public SimulationRequest {
            if (expectedVersion < 1) {
                throw new IllegalArgumentException("expectedVersion must be positive");
            }
            assignmentSnapshotRevision = positiveRevision(
                    assignmentSnapshotRevision, true, "assignmentSnapshotRevision");
            policyRevision = positiveRevision(policyRevision, false, "policyRevision");
            purpose = boundedText(purpose, "purpose", MAX_PURPOSE_LENGTH);
            if (!SIMULATION_PURPOSE.equals(purpose)) {
                throw new IllegalArgumentException("simulation purpose is invalid");
            }
        }
    }

    public record LifecycleRequest(long expectedVersion) {
        public LifecycleRequest {
            if (expectedVersion < 1) {
                throw new IllegalArgumentException("expectedVersion must be positive");
            }
        }
    }

    public record StudioView(
            String queryState,
            String freshness,
            Instant asOf,
            List<String> partialFailures,
            List<WorkPlanView> workPlans) {
        public StudioView {
            partialFailures = List.copyOf(partialFailures);
            workPlans = List.copyOf(workPlans);
        }
    }

    public record WorkPlanView(
            String workPlanId,
            String title,
            long version,
            String lifecycle,
            ArrangementView arrangement,
            LocalDate effectiveStart,
            LocalDate effectiveEnd,
            String timeZone,
            AssignmentView assignment,
            PolicyPackView policyPack,
            ResolutionView resolution,
            List<SegmentView> segments,
            List<String> availableActions) {
        public WorkPlanView {
            segments = List.copyOf(segments);
            availableActions = List.copyOf(availableActions);
        }
    }

    public record ArrangementView(String kind, String extensionCode) {
    }

    public record AssignmentView(
            String assignmentId, String label, String snapshotRevision, String freshness) {
    }

    public record PolicyPackView(
            String state, String jurisdiction, LocalDate effectiveOn, String revision) {
    }

    public record ResolutionView(
            String state, String winningLevel, List<PolicyTraceView> trace) {
        public ResolutionView {
            trace = List.copyOf(trace);
        }
    }

    public record PolicyTraceView(
            String level,
            String label,
            String disposition,
            String policyCode,
            String policyRevision) {
    }

    public record SegmentView(
            String segmentId,
            String label,
            Instant startInstant,
            Instant endInstant,
            String localStart,
            String localEnd,
            String startOffset,
            String endOffset,
            boolean overnight,
            String dstResolution) {
    }

    public record SimulationCommandView(
            ReceiptView receipt, SimulationView simulation) {
    }

    public record CreateDraftView(ReceiptView receipt, String workPlanId) {
    }

    public record ReceiptView(
            String receiptId,
            String workPlanId,
            String operation,
            String idempotencyKey,
            String status,
            Instant updatedAt) {
    }

    public record SimulationView(
            String workPlanId,
            long baseVersion,
            String status,
            Instant generatedAt,
            String policyRevision,
            List<SimulationRowView> rows,
            List<String> findings,
            List<String> partialFailures) {
        public SimulationView {
            rows = List.copyOf(rows);
            findings = List.copyOf(findings);
            partialFailures = List.copyOf(partialFailures);
        }
    }

    public record SimulationRowView(
            String key,
            String label,
            String currentValue,
            String draftValue,
            String impact) {
    }

    private static String positiveRevision(String value, boolean allowZero, String label) {
        String canonical = boundedText(value, label, MAX_BIGINT_TEXT_LENGTH);
        if (!canonical.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException(label + " must be a canonical integer");
        }
        long parsed;
        try {
            parsed = Long.parseLong(canonical);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(label + " is out of range", invalid);
        }
        if ((!allowZero && parsed < 1) || (allowZero && parsed < 0)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return canonical;
    }

    private static boolean isDdlTermDecimal(java.math.BigDecimal value) {
        return value.precision() <= TERM_DECIMAL_PRECISION
                && value.scale() >= 0
                && value.scale() <= TERM_DECIMAL_SCALE
                && value.precision() - value.scale()
                        <= TERM_DECIMAL_PRECISION - TERM_DECIMAL_SCALE;
    }

    private static String boundedText(String value, String label, int maximumLength) {
        String canonical = WorkRegimeModels.requireText(value, label);
        if (canonical.length() > maximumLength) {
            throw new IllegalArgumentException(
                    label + " exceeds maximum length " + maximumLength);
        }
        return canonical;
    }

    private static String regionZoneId(String value) {
        String canonical = boundedText(value, "timeZone", MAX_ZONE_ID_LENGTH);
        final ZoneId zone;
        try {
            zone = ZoneId.of(canonical);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("timeZone must be an IANA region", invalid);
        }
        if (zone instanceof ZoneOffset) {
            throw new IllegalArgumentException("timeZone must be an IANA region");
        }
        return canonical;
    }

    private static <T> List<T> boundedCopy(List<T> values, String label, int maximumSize) {
        Objects.requireNonNull(values, label + " must not be null");
        if (values.size() > maximumSize) {
            throw new IllegalArgumentException(label + " exceeds maximum size " + maximumSize);
        }
        return List.copyOf(values);
    }
}
