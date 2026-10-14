package com.dwp.services.people.hris.performance.review;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Closed transport graph for the sealed assigned-review contract. */
public final class PerformanceReviewDtos {

    private PerformanceReviewDtos() {
    }

    public static final String HASH_PATTERN = "^[0-9a-f]{64}$";
    public static final String DECIMAL_PATTERN =
            "^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?$";

    public interface ClosedInput {
        @JsonAnySetter
        default void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("Unknown performance-review field: " + field);
        }
    }

    public enum ReviewerType {
        SELF, MANAGER, SECOND_MANAGER, THIRD_MANAGER,
        PEER, DIRECT_REPORT, EXTERNAL, REFEREE
    }

    public enum ReviewState {
        NOT_STARTED, IN_PROGRESS, SUBMITTED, RETURNED, COMPLETED, CANCELLED
    }

    public enum FormItemKind {
        SECTION, TEXT, DECIMAL, RATING, CHOICE
    }

    public enum AnswerKind {
        TEXT, DECIMAL, RATING, CHOICE
    }

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind",
            visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TextAnswer.class, name = "TEXT"),
            @JsonSubTypes.Type(value = DecimalAnswer.class, name = "DECIMAL"),
            @JsonSubTypes.Type(value = RatingAnswer.class, name = "RATING"),
            @JsonSubTypes.Type(value = ChoiceAnswer.class, name = "CHOICE")
    })
    @Schema(name = "ReviewAnswerV1", oneOf = {
            TextAnswer.class, DecimalAnswer.class, RatingAnswer.class, ChoiceAnswer.class
    }, discriminatorProperty = "kind")
    public sealed interface ReviewAnswer extends ClosedInput
            permits TextAnswer, DecimalAnswer, RatingAnswer, ChoiceAnswer {
        String itemKey();

        AnswerKind kind();
    }

    @Schema(name = "TextReviewAnswerV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record TextAnswer(
            @NotBlank @Size(max = 120) String itemKey,
            @NotNull AnswerKind kind,
            @NotNull @Size(max = 10_000) String textValue) implements ReviewAnswer {
        public TextAnswer {
            requireKind(kind, AnswerKind.TEXT);
        }
    }

    @Schema(name = "DecimalReviewAnswerV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DecimalAnswer(
            @NotBlank @Size(max = 120) String itemKey,
            @NotNull AnswerKind kind,
            @NotBlank @Pattern(regexp = DECIMAL_PATTERN) String decimalValue)
            implements ReviewAnswer {
        public DecimalAnswer {
            requireKind(kind, AnswerKind.DECIMAL);
        }
    }

    @Schema(name = "RatingReviewAnswerV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record RatingAnswer(
            @NotBlank @Size(max = 120) String itemKey,
            @NotNull AnswerKind kind,
            @NotBlank @Pattern(regexp = DECIMAL_PATTERN) String ratingValue)
            implements ReviewAnswer {
        public RatingAnswer {
            requireKind(kind, AnswerKind.RATING);
        }
    }

    @Schema(name = "ChoiceReviewAnswerV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ChoiceAnswer(
            @NotBlank @Size(max = 120) String itemKey,
            @NotNull AnswerKind kind,
            @NotBlank @Size(max = 120) String optionKey) implements ReviewAnswer {
        public ChoiceAnswer {
            requireKind(kind, AnswerKind.CHOICE);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({
            @JsonSubTypes.Type(SectionConstraints.class),
            @JsonSubTypes.Type(TextConstraints.class),
            @JsonSubTypes.Type(DecimalConstraints.class),
            @JsonSubTypes.Type(RatingConstraints.class),
            @JsonSubTypes.Type(ChoiceConstraints.class)
    })
    @Schema(name = "ReviewAnswerConstraintsV1", oneOf = {
            SectionConstraints.class, TextConstraints.class, DecimalConstraints.class,
            RatingConstraints.class, ChoiceConstraints.class
    })
    public sealed interface AnswerConstraints extends ClosedInput
            permits SectionConstraints, TextConstraints, DecimalConstraints,
            RatingConstraints, ChoiceConstraints {
    }

    @Schema(name = "ReviewSectionAnswerConstraintsV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SectionConstraints() implements AnswerConstraints {
    }

    @Schema(name = "ReviewTextAnswerConstraintsV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record TextConstraints(
            @Min(1) @Max(10_000) int maxLength,
            boolean multiline) implements AnswerConstraints {
    }

    @Schema(name = "ReviewDecimalAnswerConstraintsV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DecimalConstraints(
            @Pattern(regexp = DECIMAL_PATTERN) String minimum,
            @Pattern(regexp = DECIMAL_PATTERN) String maximum,
            @Min(0) @Max(6) int scale) implements AnswerConstraints {
    }

    @Schema(name = "ReviewRatingAnswerConstraintsV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record RatingConstraints(
            @NotBlank @Size(max = 80) String scaleKey) implements AnswerConstraints {
    }

    @Schema(name = "ReviewChoiceAnswerConstraintsV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ChoiceConstraints(
            @Min(0) @Max(1) int minimumSelections,
            @Min(1) @Max(1) int maximumSelections,
            @NotEmpty @Size(max = 100) List<@Valid ChoiceOption> options)
            implements AnswerConstraints {
        public ChoiceConstraints {
            options = options == null ? List.of() : List.copyOf(options);
            if (maximumSelections != 1) {
                throw new IllegalArgumentException("maximumSelections must be one");
            }
        }
    }

    @Schema(name = "ReviewChoiceOptionV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ChoiceOption(
            @NotBlank @Size(max = 120) String optionKey,
            @NotBlank @Size(max = 200) String label) implements ClosedInput {
    }

    @Schema(name = "ReviewFormItemV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record FormItem(
            @NotBlank @Size(max = 100) String itemKey,
            @Size(max = 100) String parentItemKey,
            @NotNull FormItemKind kind,
            @Min(1) int sequence,
            boolean required,
            @Pattern(regexp = DECIMAL_PATTERN) String weight,
            @NotBlank @Size(max = 2_000) String label,
            @Size(max = 5_000) String helpText,
            @NotNull @Valid AnswerConstraints answerConstraints) implements ClosedInput {
    }

    @Schema(name = "ReviewRubricLevelV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record RubricLevel(
            @NotBlank @Size(max = 80) String levelKey,
            @Min(1) int sequence,
            @NotBlank @Pattern(regexp = DECIMAL_PATTERN) String numericValue,
            @NotBlank @Size(max = 200) String label) implements ClosedInput {
    }

    @Schema(name = "ReviewRubricScaleV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record RubricScale(
            @NotBlank @Size(max = 80) String scaleKey,
            @NotEmpty @Size(max = 20) List<@Valid RubricLevel> levels)
            implements ClosedInput {
        public RubricScale {
            levels = levels == null ? List.of() : List.copyOf(levels);
        }
    }

    @Schema(name = "ReviewFormV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ReviewForm(
            @NotBlank @Size(max = 30) String schemaVersion,
            @NotEmpty @Size(max = 500) List<@Valid FormItem> items,
            @NotNull @Size(max = 100) List<@Valid RubricScale> rubricScales)
            implements ClosedInput {
        public ReviewForm {
            items = items == null ? List.of() : List.copyOf(items);
            rubricScales = rubricScales == null ? List.of() : List.copyOf(rubricScales);
        }
    }

    @Schema(name = "ReviewDraftCommandV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DraftCommand(
            @NotEmpty @Size(max = 500) List<@Valid ReviewAnswer> answers)
            implements ClosedInput {
        public DraftCommand {
            answers = answers == null ? List.of() : List.copyOf(answers);
        }
    }

    @Schema(name = "SubmitReviewCommandV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SubmitCommand(
            @NotBlank @Pattern(regexp = HASH_PATTERN) String draftContentHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean acknowledgement)
            implements ClosedInput {
        public SubmitCommand {
            if (Boolean.FALSE.equals(acknowledgement)) {
                throw new IllegalArgumentException("acknowledgement may only be true");
            }
        }
    }

    @Schema(name = "AssignedReviewV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record AssignedReview(
            @NotNull UUID reviewId,
            @NotNull UUID participantId,
            @NotNull UUID reviewerAssignmentId,
            @NotNull ReviewerType reviewerType,
            @NotBlank @Size(max = 80) String stageKey,
            @NotNull ReviewState state,
            @NotNull UUID templateVersionId,
            @NotBlank @Pattern(regexp = HASH_PATTERN) String templateContentHash,
            @Min(0) long aggregateVersion,
            boolean writable,
            @NotBlank @Pattern(regexp = HASH_PATTERN) String draftContentHash,
            @NotNull @Valid ReviewForm form,
            @NotNull @Size(max = 500) List<@Valid ReviewAnswer> answers,
            Instant dueAt) {
        public AssignedReview {
            answers = answers == null ? List.of() : List.copyOf(answers);
        }
    }

    @Schema(name = "ReviewSubmissionV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ReviewSubmission(
            @NotNull UUID submissionId,
            @Min(1) long submissionNo,
            @NotNull ReviewState state,
            @NotBlank @Pattern(regexp = HASH_PATTERN) String contentHash,
            @NotNull Instant submittedAt) {
        public ReviewSubmission {
            if (state != ReviewState.SUBMITTED) {
                throw new IllegalArgumentException("submission state must be SUBMITTED");
            }
        }
    }

    private static void requireKind(AnswerKind actual, AnswerKind expected) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalArgumentException("Answer kind must be " + expected);
        }
    }
}
