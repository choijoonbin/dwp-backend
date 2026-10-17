package com.dwp.services.payroll.input.closedtime;

import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.dwp.contracts.hris.xcon.v1.Xcon010TimePeriodReopenedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon011PayrollTimeHandoffReadyV1;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure canonical identity checks for the non-durable PAY port.
 *
 * <p>The future transport adapter remains responsible for mTLS and workload
 * proof validation, byte and compression bounds, incremental digest checks and
 * replay reservation before it constructs these already-bounded objects.</p>
 */
public final class ClosedTimeResultContractVerifier {

    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ClosedTimeResultContractVerifier() {
    }

    public static VerifiedHandoff verifyHandoff(
            long tenantId,
            UUID correlationId,
            Xcon011PayrollTimeHandoffReadyV1 signal,
            Xcon008ClosedTimeResultV1 result,
            SourceEvidenceAttestation sourceEvidence) {
        requireTenant(tenantId);
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(sourceEvidence, "sourceEvidence");

        requireEqual(signal.closePeriodId(), result.closePeriodId(), "closePeriodId");
        requireEqual(signal.closeRevision(), result.closeRevision(), "closeRevision");
        requireEqual(
                signal.closedTimeResultId(), result.closedTimeResultId(),
                "closedTimeResultId");
        requireEqual(signal.payrollEntityId(), result.payrollEntityId(),
                "payrollEntityId");
        requireEqual(signal.payPeriodId(), result.payPeriodId(), "payPeriodId");
        requireEqual(signal.workerCount(), result.workerCount(), "workerCount");
        requireEqual(signal.lineCount(), result.lineCount(), "lineCount");
        requireEqual(signal.sourceDigest(), result.sourceDigest(), "sourceDigest");
        requireEqual(signal.ruleDigest(), result.ruleDigest(), "ruleDigest");
        requireEqual(signal.payloadDigest(), result.payloadDigest(), "payloadDigest");

        if (result.ledgerRevisionFrom() > result.ledgerRevisionTo()) {
            throw mismatch("ledgerRevisionRange");
        }
        int distinctWorkers = new HashSet<>(result.lines().stream()
                .map(Xcon008ClosedTimeResultV1.ClosedTimeLine::workerId)
                .toList()).size();
        if (distinctWorkers != result.workerCount()) {
            throw mismatch("workerCount");
        }

        String canonicalSourceDigest = sourceDigest(result.lines());
        requireEqual(result.sourceDigest(), canonicalSourceDigest,
                "sourceDigest");
        String canonicalPayloadDigest = payloadDigest(
                result.closePeriodId(), result.closeRevision(),
                result.closedTimeResultId(), result.ledgerRevisionFrom(),
                result.ledgerRevisionTo(), result.payPeriodId(),
                result.payrollEntityId(), result.ruleDigest(),
                canonicalSourceDigest, result.supersedesResultId(),
                result.workerCount(), result.lines());
        requireEqual(result.payloadDigest(), canonicalPayloadDigest,
                "payloadDigest");

        sourceEvidence.requireMatches(
                tenantId, correlationId, signal, canonicalSourceDigest);

        return new VerifiedHandoff(tenantId, correlationId, signal, result,
                sourceEvidence);
    }

    public static VerifiedReopen verifyReopen(
            long tenantId,
            UUID correlationId,
            Xcon010TimePeriodReopenedV1 signal,
            VerifiedHandoff priorHandoff) {
        requireTenant(tenantId);
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(priorHandoff, "priorHandoff");

        requireEqual(tenantId, priorHandoff.tenantId(), "tenantId");
        requireEqual(signal.closePeriodId(),
                priorHandoff.result().closePeriodId(), "closePeriodId");
        requireEqual(signal.priorCloseRevision(),
                priorHandoff.result().closeRevision(), "priorCloseRevision");
        if (signal.newRevision() != signal.priorCloseRevision() + 1) {
            throw mismatch("newRevision");
        }
        return new VerifiedReopen(tenantId, correlationId, signal,
                priorHandoff.result().closedTimeResultId());
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw mismatch(field);
        }
    }

    private static void requireEqual(long actual, long expected, String field) {
        if (actual != expected) {
            throw mismatch(field);
        }
    }

    private static ContractMismatchException mismatch(String field) {
        return new ContractMismatchException(
                "Closed-time owner evidence differs at " + field + '.');
    }

    static SourceEvidenceAttestation attestSourceEvidence(
            long tenantId,
            UUID correlationId,
            UUID handoffId,
            UUID resultId,
            String sourceDigest) {
        return new SourceEvidenceAttestation(
                tenantId, correlationId, handoffId, resultId, sourceDigest);
    }

    static String sourceDigest(
            List<Xcon008ClosedTimeResultV1.ClosedTimeLine> lines) {
        Objects.requireNonNull(lines, "lines");
        List<Map<String, Object>> ordered = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            Xcon008ClosedTimeResultV1.ClosedTimeLine line = lines.get(index);
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("lineIndex", index);
            value.put("workerId", line.workerId().toString());
            value.put("assignmentId", line.assignmentId().toString());
            value.put("sourceEntryCount", line.sourceEntryCount());
            value.put("sourceEntryDigest", line.sourceEntryDigest());
            ordered.add(value);
        }
        return sha256Utf8(canonicalJson(ordered));
    }

    static String payloadDigest(
            UUID closePeriodId,
            long closeRevision,
            UUID resultId,
            long ledgerRevisionFrom,
            long ledgerRevisionTo,
            UUID payPeriodId,
            UUID payrollEntityId,
            String ruleDigest,
            String sourceDigest,
            UUID supersedesResultId,
            int workerCount,
            List<Xcon008ClosedTimeResultV1.ClosedTimeLine> lines) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("closePeriodId", closePeriodId.toString());
        value.put("closeRevision", closeRevision);
        value.put("closedTimeResultId", resultId.toString());
        value.put("ledgerRevisionFrom", ledgerRevisionFrom);
        value.put("ledgerRevisionTo", ledgerRevisionTo);
        value.put("lineCount", lines.size());
        value.put("lines", lines);
        value.put("payPeriodId", payPeriodId.toString());
        value.put("payrollEntityId", payrollEntityId.toString());
        value.put("ruleDigest", ruleDigest);
        value.put("sourceDigest", sourceDigest);
        value.put("supersedesResultId",
                supersedesResultId == null ? null : supersedesResultId.toString());
        value.put("workerCount", workerCount);
        return sha256Utf8(canonicalJson(value));
    }

    private static String canonicalJson(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("canonical value is required");
        }
        StringBuilder output = new StringBuilder();
        append(JSON.valueToTree(value), output);
        return output.toString();
    }

    private static void append(JsonNode value, StringBuilder output) {
        if (value.isObject()) {
            output.append('{');
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            value.fields().forEachRemaining(fields::add);
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            for (int index = 0; index < fields.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                appendString(fields.get(index).getKey(), output);
                output.append(':');
                append(fields.get(index).getValue(), output);
            }
            output.append('}');
            return;
        }
        if (value.isArray()) {
            output.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                append(value.get(index), output);
            }
            output.append(']');
            return;
        }
        if (value.isTextual()) {
            appendString(value.textValue(), output);
            return;
        }
        if (value.isNumber()) {
            output.append(canonicalNumber(value.doubleValue()));
            return;
        }
        if (value.isBoolean()) {
            output.append(value.booleanValue() ? "true" : "false");
            return;
        }
        if (value.isNull()) {
            output.append("null");
            return;
        }
        throw new IllegalArgumentException(
                "JSON value cannot be canonically encoded");
    }

    private static String canonicalNumber(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("canonical numbers must be finite");
        }
        if (value == 0.0d) {
            return "0";
        }
        boolean negative = value < 0;
        double magnitude = Math.abs(value);
        BigDecimal decimal = new BigDecimal(
                Double.toString(magnitude)).stripTrailingZeros();
        String encoded;
        if (magnitude >= 1.0e-6d && magnitude < 1.0e21d) {
            encoded = decimal.toPlainString();
        } else {
            String digits = decimal.unscaledValue().abs().toString();
            int exponent = decimal.precision() - decimal.scale() - 1;
            StringBuilder scientific = new StringBuilder()
                    .append(digits.charAt(0));
            if (digits.length() > 1) {
                scientific.append('.').append(digits, 1, digits.length());
            }
            scientific.append('e');
            if (exponent >= 0) {
                scientific.append('+');
            }
            scientific.append(exponent);
            encoded = scientific.toString();
        }
        return negative ? "-" + encoded : encoded;
    }

    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            "canonical strings cannot contain lone surrogates");
                }
                output.append(unit).append(value.charAt(++index));
                continue;
            }
            if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(
                        "canonical strings cannot contain lone surrogates");
            }
            switch (unit) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\t' -> output.append("\\t");
                case '\n' -> output.append("\\n");
                case '\f' -> output.append("\\f");
                case '\r' -> output.append("\\r");
                default -> {
                    if (unit <= 0x1f) {
                        output.append("\\u00")
                                .append(Character.forDigit(
                                        (unit >>> 4) & 0xf, 16))
                                .append(Character.forDigit(unit & 0xf, 16));
                    } else {
                        output.append(unit);
                    }
                }
            }
        }
        output.append('"');
    }

    private static String sha256Utf8(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Capability token issued only inside the closed-time adapter package after
     * authenticated owner evidence has been checked. XCON-008 contains only the
     * per-line source digest, not the underlying source rows, so this attestation
     * prevents this in-memory pairing helper from claiming that missing evidence.
     */
    public static final class SourceEvidenceAttestation {
        private final long tenantId;
        private final UUID correlationId;
        private final UUID handoffId;
        private final UUID resultId;
        private final String sourceDigest;

        private SourceEvidenceAttestation(
                long tenantId,
                UUID correlationId,
                UUID handoffId,
                UUID resultId,
                String sourceDigest) {
            requireTenant(tenantId);
            this.tenantId = tenantId;
            this.correlationId = Objects.requireNonNull(
                    correlationId, "correlationId");
            this.handoffId = Objects.requireNonNull(handoffId, "handoffId");
            this.resultId = Objects.requireNonNull(resultId, "resultId");
            if (sourceDigest == null
                    || !sourceDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "sourceDigest must be canonical SHA-256");
            }
            this.sourceDigest = sourceDigest;
        }

        private void requireMatches(
                long expectedTenantId,
                UUID expectedCorrelationId,
                Xcon011PayrollTimeHandoffReadyV1 signal,
                String expectedSourceDigest) {
            requireEqual(tenantId, expectedTenantId, "attestedTenantId");
            requireEqual(correlationId, expectedCorrelationId,
                    "attestedCorrelationId");
            requireEqual(handoffId, signal.handoffId(), "attestedHandoffId");
            requireEqual(resultId, signal.closedTimeResultId(),
                    "attestedResultId");
            requireEqual(sourceDigest, expectedSourceDigest,
                    "attestedSourceDigest");
        }
    }

    public static final class VerifiedHandoff {
        private final long tenantId;
        private final UUID correlationId;
        private final Xcon011PayrollTimeHandoffReadyV1 signal;
        private final Xcon008ClosedTimeResultV1 result;
        private final SourceEvidenceAttestation sourceEvidence;

        private VerifiedHandoff(
                long tenantId,
                UUID correlationId,
                Xcon011PayrollTimeHandoffReadyV1 signal,
                Xcon008ClosedTimeResultV1 result,
                SourceEvidenceAttestation sourceEvidence) {
            this.tenantId = tenantId;
            this.correlationId = correlationId;
            this.signal = signal;
            this.result = result;
            this.sourceEvidence = sourceEvidence;
        }

        public long tenantId() {
            return tenantId;
        }

        public UUID correlationId() {
            return correlationId;
        }

        public Xcon011PayrollTimeHandoffReadyV1 signal() {
            return signal;
        }

        public Xcon008ClosedTimeResultV1 result() {
            return result;
        }

        SourceEvidenceAttestation sourceEvidence() {
            return sourceEvidence;
        }
    }

    public static final class VerifiedReopen {
        private final long tenantId;
        private final UUID correlationId;
        private final Xcon010TimePeriodReopenedV1 signal;
        private final UUID invalidatedResultId;

        private VerifiedReopen(
                long tenantId,
                UUID correlationId,
                Xcon010TimePeriodReopenedV1 signal,
                UUID invalidatedResultId) {
            this.tenantId = tenantId;
            this.correlationId = correlationId;
            this.signal = signal;
            this.invalidatedResultId = invalidatedResultId;
        }

        public long tenantId() {
            return tenantId;
        }

        public UUID correlationId() {
            return correlationId;
        }

        public Xcon010TimePeriodReopenedV1 signal() {
            return signal;
        }

        public UUID invalidatedResultId() {
            return invalidatedResultId;
        }
    }

    public static final class ContractMismatchException
            extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private ContractMismatchException(String message) {
            super(message);
        }
    }
}
