package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Strict request, immutable snapshots, and artifact types for PEP-HRM-011. */
public final class EmploymentContractDtos {

    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                            + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final java.util.regex.Pattern SHA256 =
            java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    private EmploymentContractDtos() {
    }

    @JsonDeserialize(using = IssueRequestDeserializer.class)
    public record IssueRequest(
            @NotNull UUID relationshipPublicId,
            @NotNull UUID templatePublicId,
            @Min(1) long templateVersion,
            @NotBlank @Size(min = 2, max = 35)
            @Pattern(regexp = "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$") String locale,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String snapshotDigest) {

        public IssueRequest {
            requireUuid(relationshipPublicId, "relationshipPublicId");
            requireUuid(templatePublicId, "templatePublicId");
            if (templateVersion < 1) throw invalid("templateVersion");
            if (locale == null || !locale.matches(
                    "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")) {
                throw invalid("locale");
            }
            requireDigest(snapshotDigest, "snapshotDigest");
            if (effectiveFrom != null && effectiveTo != null
                    && !effectiveTo.isAfter(effectiveFrom)) {
                throw EmploymentContractException.invalid(
                        "effectiveTo must be after effectiveFrom.");
            }
        }
    }

    /** Duplicate- and unknown-field-rejecting additionalProperties:false reader. */
    public static final class IssueRequestDeserializer extends StdDeserializer<IssueRequest> {
        private static final long serialVersionUID = 1L;
        private static final Set<String> FIELDS = Set.of(
                "relationshipPublicId", "templatePublicId", "templateVersion",
                "locale", "effectiveFrom", "effectiveTo", "snapshotDigest");
        private static final Set<String> REQUIRED = Set.of(
                "relationshipPublicId", "templatePublicId", "templateVersion",
                "locale", "effectiveFrom", "snapshotDigest");

        public IssueRequestDeserializer() {
            super(IssueRequest.class);
        }

        @Override
        public IssueRequest deserialize(
                JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.isExpectedStartObjectToken()) {
                return context.reportInputMismatch(
                        IssueRequest.class, "Employment-contract request must be an object.");
            }
            UUID relationship = null;
            UUID template = null;
            long templateVersion = 0;
            String locale = null;
            LocalDate from = null;
            LocalDate to = null;
            String digest = null;
            Set<String> seen = new HashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!parser.hasToken(JsonToken.FIELD_NAME)) {
                    return context.reportInputMismatch(
                            IssueRequest.class, "Request contains a non-field token.");
                }
                String name = parser.currentName();
                if (!FIELDS.contains(name)) {
                    return context.reportInputMismatch(
                            IssueRequest.class, "Unknown property %s.", name);
                }
                if (!seen.add(name)) {
                    return context.reportInputMismatch(
                            IssueRequest.class, "Duplicate property %s.", name);
                }
                if (parser.nextToken() == null) {
                    return context.reportInputMismatch(
                            IssueRequest.class, "Request ends before property %s.", name);
                }
                switch (name) {
                    case "relationshipPublicId" -> relationship = uuid(parser, context, name);
                    case "templatePublicId" -> template = uuid(parser, context, name);
                    case "templateVersion" -> templateVersion = positiveLong(parser, context, name);
                    case "locale" -> locale = text(parser, context, name);
                    case "effectiveFrom" -> from = date(parser, context, name, false);
                    case "effectiveTo" -> to = date(parser, context, name, true);
                    case "snapshotDigest" -> digest = text(parser, context, name);
                    default -> throw new IllegalStateException("Unreachable field.");
                }
            }
            if (!seen.containsAll(REQUIRED)) {
                Set<String> missing = new java.util.TreeSet<>(REQUIRED);
                missing.removeAll(seen);
                return context.reportInputMismatch(
                        IssueRequest.class, "Missing required properties %s.", missing);
            }
            return new IssueRequest(
                    relationship, template, templateVersion, locale, from, to, digest);
        }

        private String text(
                JsonParser parser, DeserializationContext context, String name)
                throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(
                        IssueRequest.class, "%s must be a string.", name);
            }
            return parser.getText();
        }

        private UUID uuid(
                JsonParser parser, DeserializationContext context, String name)
                throws IOException {
            String value = text(parser, context, name);
            if (!UUID_PATTERN.matcher(value).matches()) {
                return context.reportInputMismatch(
                        IssueRequest.class, "%s must be a canonical UUID.", name);
            }
            return UUID.fromString(value);
        }

        private long positiveLong(
                JsonParser parser, DeserializationContext context, String name)
                throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)
                    || !parser.getNumberType().equals(JsonParser.NumberType.LONG)
                    && !parser.getNumberType().equals(JsonParser.NumberType.INT)) {
                return context.reportInputMismatch(
                        IssueRequest.class, "%s must be an integer.", name);
            }
            long value = parser.getLongValue();
            if (value < 1) {
                return context.reportInputMismatch(
                        IssueRequest.class, "%s must be positive.", name);
            }
            return value;
        }

        private LocalDate date(
                JsonParser parser, DeserializationContext context, String name,
                boolean nullable) throws IOException {
            if (nullable && parser.hasToken(JsonToken.VALUE_NULL)) return null;
            try {
                return LocalDate.parse(text(parser, context, name));
            } catch (RuntimeException exception) {
                return context.reportInputMismatch(
                        IssueRequest.class, "%s must be an ISO date.", name);
            }
        }
    }

    public record RelationshipSnapshot(
            UUID relationshipPublicId,
            long resourceVersion,
            UUID workerPublicId,
            UUID legalEmployerPublicId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        public RelationshipSnapshot {
            requireUuid(relationshipPublicId, "relationshipPublicId");
            if (resourceVersion < 0) throw invalid("resourceVersion");
            requireUuid(workerPublicId, "workerPublicId");
            requireUuid(legalEmployerPublicId, "legalEmployerPublicId");
            requireRange(effectiveFrom, effectiveTo, "relationship");
        }
    }

    public record AssignmentSnapshot(
            UUID assignmentPublicId,
            long assignmentVersion,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        public AssignmentSnapshot {
            requireUuid(assignmentPublicId, "assignmentPublicId");
            if (assignmentVersion < 1) throw invalid("assignmentVersion");
            requireRange(effectiveFrom, effectiveTo, "assignment");
        }
    }

    public record CompensationBasisSnapshot(
            UUID compensationBasisPublicId,
            long compensationBasisVersion,
            String basisType,
            String amountCanonicalDecimal,
            String currency,
            String frequency,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        public CompensationBasisSnapshot {
            requireUuid(compensationBasisPublicId, "compensationBasisPublicId");
            if (compensationBasisVersion < 1) throw invalid("compensationBasisVersion");
            if (basisType == null || !Set.of(
                    "SALARY", "HOURLY", "DAILY", "PIECE", "OTHER").contains(basisType)) {
                throw invalid("basisType");
            }
            amountCanonicalDecimal = canonicalDecimal(amountCanonicalDecimal);
            if (currency == null || !currency.matches("^[A-Z]{3}$")) {
                throw invalid("currency");
            }
            if (frequency == null || !Set.of(
                    "HOURLY", "DAILY", "WEEKLY", "BIWEEKLY", "SEMIMONTHLY",
                    "MONTHLY", "ANNUAL").contains(frequency)) {
                throw invalid("frequency");
            }
            requireRange(effectiveFrom, effectiveTo, "compensationBasis");
        }
    }

    public record GenerationSnapshot(
            RelationshipSnapshot relationshipSnapshot,
            AssignmentSnapshot assignmentSnapshot,
            CompensationBasisSnapshot compensationBasisSnapshot,
            EmploymentContractTemplateSnapshotV1 templateSnapshot,
            String locale) {
        public GenerationSnapshot {
            if (relationshipSnapshot == null || assignmentSnapshot == null
                    || compensationBasisSnapshot == null || templateSnapshot == null) {
                throw EmploymentContractException.unavailable(
                        "The generation snapshot is incomplete.");
            }
            if (locale == null || !locale.matches(
                    "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")) {
                throw invalid("locale");
            }
        }
    }

    public record ArtifactRequest(
            UUID artifactRequestId,
            UUID contractPublicId,
            long trustedTenantId,
            UUID correlationId,
            RelationshipSnapshot relationshipSnapshot,
            AssignmentSnapshot assignmentSnapshot,
            CompensationBasisSnapshot compensationBasisSnapshot,
            EmploymentContractTemplateSnapshotV1 templateSnapshot,
            String locale,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String generationSnapshotDigest) {
        public ArtifactRequest {
            requireUuid(artifactRequestId, "artifactRequestId");
            requireUuid(contractPublicId, "contractPublicId");
            if (trustedTenantId < 1) throw invalid("trustedTenantId");
            requireUuid(correlationId, "correlationId");
            if (relationshipSnapshot == null || assignmentSnapshot == null
                    || compensationBasisSnapshot == null || templateSnapshot == null) {
                throw invalid("artifact snapshots");
            }
            if (locale == null || !locale.matches(
                    "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")) {
                throw invalid("locale");
            }
            requireRange(effectiveFrom, effectiveTo, "contract");
            requireDigest(generationSnapshotDigest, "generationSnapshotDigest");
        }
    }

    public record DocumentReference(
            UUID objectPublicId, long objectVersion, String contentSha256) {
        public DocumentReference {
            requireUuid(objectPublicId, "objectPublicId");
            if (objectVersion < 1) throw invalid("objectVersion");
            requireDigest(contentSha256, "contentSha256");
        }
    }

    public record ArtifactResult(
            UUID artifactRequestId,
            UUID contractPublicId,
            UUID templatePublicId,
            long templateVersion,
            String generationSnapshotDigest,
            UUID providerReceiptId,
            DocumentReference documentReference,
            Instant issuedAt) {
        public ArtifactResult {
            requireUuid(artifactRequestId, "artifactRequestId");
            requireUuid(contractPublicId, "contractPublicId");
            requireUuid(templatePublicId, "templatePublicId");
            if (templateVersion < 1) throw invalid("templateVersion");
            requireDigest(generationSnapshotDigest, "generationSnapshotDigest");
            requireUuid(providerReceiptId, "providerReceiptId");
            if (documentReference == null || issuedAt == null) {
                throw invalid("artifact result");
            }
        }
    }

    private static String canonicalDecimal(String value) {
        try {
            BigDecimal decimal = new BigDecimal(value);
            if (decimal.signum() < 0 || decimal.scale() > 6) throw invalid("amount");
            String canonical = decimal.signum() == 0
                    ? "0" : decimal.stripTrailingZeros().toPlainString();
            if (!canonical.equals(value) || value.contains("E") || value.contains("e")) {
                throw invalid("amountCanonicalDecimal");
            }
            return canonical;
        } catch (NumberFormatException exception) {
            throw invalid("amountCanonicalDecimal");
        }
    }

    private static void requireUuid(UUID value, String field) {
        if (value == null) throw invalid(field);
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) throw invalid(field);
    }

    private static void requireRange(LocalDate from, LocalDate to, String field) {
        if (from == null || to != null && !to.isAfter(from)) throw invalid(field);
    }

    private static EmploymentContractException invalid(String field) {
        return EmploymentContractException.invalid(field + " is invalid.");
    }
}
