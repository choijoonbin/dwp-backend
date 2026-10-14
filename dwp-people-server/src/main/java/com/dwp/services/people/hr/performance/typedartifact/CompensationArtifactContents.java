package com.dwp.services.people.hr.performance.typedartifact;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed v1 content models for the three PER compensation typed artifacts. */
public final class CompensationArtifactContents {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,39}$");
    private static final Pattern POLICY = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,79}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Comparator<PopulationLine> POPULATION_ORDER = Comparator
            .comparing((PopulationLine line) -> line.assignmentPublicId().toString())
            .thenComparing(line -> line.workerPublicId().toString());

    private CompensationArtifactContents() {
    }

    public interface ClosedContent {
        @JsonAnySetter
        default void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException(
                    "Unknown compensation artifact field: " + field);
        }
    }

    public sealed interface Content extends ClosedContent
            permits Guidelines, Population, Proposal {
    }

    @Schema(name = "CompensationGuidelineV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Guideline(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String componentCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String minimumAmount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String maximumAmount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String exceptionPolicyCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID roundingPolicyVersionPublicId) implements ClosedContent {
        public Guideline {
            componentCode = code(componentCode, "componentCode");
            currency = CompensationArtifactContents.currency(currency);
            minimumAmount = PostedMoney.require(minimumAmount);
            maximumAmount = PostedMoney.require(maximumAmount);
            if (PostedMoney.decimal(maximumAmount).compareTo(
                    PostedMoney.decimal(minimumAmount)) < 0) {
                throw new IllegalArgumentException("maximumAmount must be >= minimumAmount");
            }
            if (exceptionPolicyCode == null || !POLICY.matcher(exceptionPolicyCode).matches()) {
                throw new IllegalArgumentException("exceptionPolicyCode is not canonical");
            }
            Objects.requireNonNull(roundingPolicyVersionPublicId,
                    "roundingPolicyVersionPublicId must not be null");
        }
    }

    @Schema(name = "CompensationGuidelinesContentV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Guidelines(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<Guideline> components,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String budgetMode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            LocalDate effectiveFrom,
            @JsonProperty(required = true)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            LocalDate effectiveTo) implements Content {
        public Guidelines {
            components = List.copyOf(Objects.requireNonNull(components,
                    "components must not be null"));
            if (components.isEmpty() || components.size() > 1_000
                    || components.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "components must contain between 1 and 1000 values");
            }
            Set<String> componentCurrencies = new HashSet<>();
            for (Guideline component : components) {
                if (!componentCurrencies.add(component.componentCode() + ':'
                        + component.currency())) {
                    throw new IllegalArgumentException(
                            "guideline component and currency must be unique");
                }
            }
            if (!"HARD_CAP".equals(budgetMode)) {
                throw new IllegalArgumentException(
                        "only HARD_CAP is implemented; EXCEPTION_APPROVAL is withheld");
            }
            dates(effectiveFrom, effectiveTo);
        }
    }

    @Schema(name = "CompensationPopulationLineV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PopulationLine(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID workerPublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID assignmentPublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID organizationPublicId,
            @JsonProperty(required = true)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            UUID gradePublicId,
            @JsonProperty(required = true)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            UUID jobProfilePublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long assignmentOwnerRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> eligibleComponentCodes) implements ClosedContent {
        public PopulationLine {
            Objects.requireNonNull(workerPublicId, "workerPublicId must not be null");
            Objects.requireNonNull(assignmentPublicId, "assignmentPublicId must not be null");
            Objects.requireNonNull(organizationPublicId,
                    "organizationPublicId must not be null");
            if (assignmentOwnerRevision <= 0) {
                throw new IllegalArgumentException("assignmentOwnerRevision must be positive");
            }
            eligibleComponentCodes = List.copyOf(Objects.requireNonNull(
                    eligibleComponentCodes, "eligibleComponentCodes must not be null"));
            if (eligibleComponentCodes.isEmpty() || eligibleComponentCodes.size() > 1_000) {
                throw new IllegalArgumentException(
                        "eligibleComponentCodes must contain between 1 and 1000 values");
            }
            String previous = null;
            for (String component : eligibleComponentCodes) {
                String canonical = code(component, "eligibleComponentCodes[]");
                if (previous != null && previous.compareTo(canonical) >= 0) {
                    throw new IllegalArgumentException(
                            "eligibleComponentCodes must be unique and sorted");
                }
                previous = canonical;
            }
        }
    }

    @Schema(name = "CompensationPopulationContentV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Population(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant asOf,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long hrmOwnerRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<PopulationLine> lines) implements Content {
        public Population {
            Objects.requireNonNull(asOf, "asOf must not be null");
            if (hrmOwnerRevision <= 0) {
                throw new IllegalArgumentException("hrmOwnerRevision must be positive");
            }
            lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
            if (lines.isEmpty() || lines.size() > 100_000
                    || lines.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "lines must contain between 1 and 100000 values");
            }
            UUID previousAssignment = null;
            PopulationLine previous = null;
            Set<String> pairs = new HashSet<>();
            for (PopulationLine line : lines) {
                String pair = line.assignmentPublicId() + ":" + line.workerPublicId();
                if (!pairs.add(pair)
                        || (previousAssignment != null
                        && previousAssignment.equals(line.assignmentPublicId()))) {
                    throw new IllegalArgumentException(
                            "population assignments and worker-assignment pairs must be unique");
                }
                if (previous != null && POPULATION_ORDER.compare(previous, line) >= 0) {
                    throw new IllegalArgumentException(
                            "population must follow assignmentPublicId/workerPublicId order");
                }
                previousAssignment = line.assignmentPublicId();
                previous = line;
            }
        }
    }

    @Schema(name = "CompensationProposalContentV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Proposal(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID cyclePublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID workerPublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID assignmentPublicId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String componentCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String amount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            LocalDate effectiveFrom,
            @JsonProperty(required = true)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            LocalDate effectiveTo,
            @JsonProperty(required = true)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String exceptionReasonCode,
            @JsonProperty(required = true)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            UUID supersedesProposalPublicId) implements Content {
        public Proposal {
            Objects.requireNonNull(cyclePublicId, "cyclePublicId must not be null");
            Objects.requireNonNull(workerPublicId, "workerPublicId must not be null");
            Objects.requireNonNull(assignmentPublicId, "assignmentPublicId must not be null");
            componentCode = code(componentCode, "componentCode");
            currency = CompensationArtifactContents.currency(currency);
            amount = PostedMoney.require(amount);
            dates(effectiveFrom, effectiveTo);
            if (exceptionReasonCode != null) {
                throw new IllegalArgumentException(
                        "exceptionReasonCode is unavailable in HARD_CAP mode");
            }
        }
    }

    private static String code(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not canonical");
        }
        return value;
    }

    private static String currency(String value) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw new IllegalArgumentException("currency must be uppercase ISO-4217");
        }
        return value;
    }

    private static void dates(LocalDate effectiveFrom, LocalDate effectiveTo) {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
    }
}
