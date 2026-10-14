package com.dwp.services.people.hris.performance.review;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.AnswerConstraints;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.AnswerKind;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ChoiceAnswer;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ChoiceConstraints;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ChoiceOption;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.DecimalAnswer;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.DecimalConstraints;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.FormItem;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.FormItemKind;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.RatingAnswer;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.RatingConstraints;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewAnswer;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewForm;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.RubricLevel;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.RubricScale;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.SectionConstraints;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.TextAnswer;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.TextConstraints;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

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
import java.util.Set;
import java.util.UUID;

/** Canonicalizes the typed review graph before hashing or persistence. */
@Component
public final class PerformanceReviewCanonicalizer {

    private final ObjectMapper mapper;

    public PerformanceReviewCanonicalizer(ObjectMapper objectMapper) {
        mapper = objectMapper.copy().findAndRegisterModules()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public ReviewForm canonicalForm(ReviewForm form) {
        if (form == null || !boundedNonBlank(form.schemaVersion(), 30)
                || form.items().isEmpty() || form.items().size() > 500
                || form.rubricScales().size() > 100) {
            throw invalid("The assigned review form is incomplete.");
        }
        List<FormItem> canonicalItems = new ArrayList<>();
        for (FormItem item : form.items()) {
            if (item == null || !boundedNonBlank(item.itemKey(), 100)
                    || item.kind() == null || item.sequence() < 1
                    || item.parentItemKey() != null && item.parentItemKey().length() > 100
                    || !boundedNonBlank(item.label(), 2_000)
                    || item.helpText() != null && item.helpText().length() > 5_000) {
                throw invalid("The assigned review form item is outside its contract.");
            }
            canonicalItems.add(new FormItem(
                    item.itemKey(), item.parentItemKey(), item.kind(), item.sequence(),
                    item.required(), nullableDecimal(item.weight()), item.label(),
                    item.helpText(), canonicalConstraints(item.kind(), item.answerConstraints())));
        }
        List<FormItem> items = canonicalItems.stream()
                .sorted(Comparator.comparingInt(FormItem::sequence)
                        .thenComparing(FormItem::itemKey))
                .toList();
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            FormItem item = items.get(index);
            if (item.sequence() != index + 1 || !keys.add(item.itemKey())) {
                throw invalid("Review form items must have unique keys and contiguous order.");
            }
        }
        Map<String, FormItem> byKey = index(items);
        for (FormItem item : items) {
            if (item.parentItemKey() != null) {
                FormItem parent = byKey.get(item.parentItemKey());
                if (parent == null || parent.kind() != FormItemKind.SECTION
                        || parent.itemKey().equals(item.itemKey())) {
                    throw invalid("Review form parent items must be existing sections.");
                }
            }
        }
        List<RubricScale> normalizedScales = new ArrayList<>();
        for (RubricScale scale : form.rubricScales()) {
            if (scale == null || !boundedNonBlank(scale.scaleKey(), 80)
                    || scale.levels().isEmpty() || scale.levels().size() > 20) {
                throw invalid("The assigned review rubric scale is outside its contract.");
            }
            List<RubricLevel> normalizedLevels = new ArrayList<>();
            for (RubricLevel level : scale.levels()) {
                if (level == null || !boundedNonBlank(level.levelKey(), 80)
                        || level.sequence() < 1 || !boundedNonBlank(level.label(), 200)) {
                    throw invalid("The assigned review rubric level is outside its contract.");
                }
                normalizedLevels.add(new RubricLevel(
                        level.levelKey(), level.sequence(),
                        decimal(level.numericValue()), level.label()));
            }
            normalizedScales.add(new RubricScale(scale.scaleKey(), normalizedLevels));
        }
        List<RubricScale> scales = normalizedScales.stream()
                .sorted(Comparator.comparing(RubricScale::scaleKey)).toList();
        Set<String> scaleKeys = new HashSet<>();
        List<RubricScale> canonicalScales = new ArrayList<>();
        for (RubricScale scale : scales) {
            if (!scaleKeys.add(scale.scaleKey())) {
                throw invalid("Review rubric scale keys must be unique.");
            }
            List<RubricLevel> levels = scale.levels().stream()
                    .sorted(Comparator.comparingInt(RubricLevel::sequence)
                            .thenComparing(RubricLevel::levelKey)).toList();
            Set<String> levelKeys = new HashSet<>();
            Set<String> values = new HashSet<>();
            for (int index = 0; index < levels.size(); index++) {
                RubricLevel level = levels.get(index);
                if (level.sequence() != index + 1 || !levelKeys.add(level.levelKey())
                        || !values.add(level.numericValue())) {
                    throw invalid("Review rubric levels must be unique and contiguous.");
                }
            }
            canonicalScales.add(new RubricScale(scale.scaleKey(), levels));
        }
        for (FormItem item : items) {
            if (item.answerConstraints() instanceof RatingConstraints rating
                    && !scaleKeys.contains(rating.scaleKey())) {
                throw invalid("Review rating items must reference an existing scale.");
            }
        }
        return new ReviewForm(form.schemaVersion().trim(), items, canonicalScales);
    }

    public List<ReviewAnswer> canonicalAnswers(
            ReviewForm rawForm,
            List<ReviewAnswer> rawAnswers,
            boolean requireComplete) {
        ReviewForm form = canonicalForm(rawForm);
        if (rawAnswers == null || rawAnswers.size() > 500) {
            throw invalid("Review answers exceed the sealed contract.");
        }
        Map<String, FormItem> items = index(form.items());
        Map<String, RubricScale> scales = new LinkedHashMap<>();
        form.rubricScales().forEach(scale -> scales.put(scale.scaleKey(), scale));
        Set<String> answered = new HashSet<>();
        List<ReviewAnswer> canonical = new ArrayList<>();
        for (ReviewAnswer answer : rawAnswers) {
            if (answer == null || blank(answer.itemKey()) || !answered.add(answer.itemKey())) {
                throw invalid("Review answer item keys must be unique.");
            }
            FormItem item = items.get(answer.itemKey());
            if (item == null || item.kind() == FormItemKind.SECTION) {
                throw invalid("Review answers must reference an answerable form item.");
            }
            ReviewAnswer validated = validateAnswer(item, answer, scales);
            if (requireComplete && item.required()
                    && validated instanceof TextAnswer text
                    && text.textValue().isBlank()) {
                throw invalid("Required text review answers cannot be blank.");
            }
            canonical.add(validated);
        }
        if (requireComplete) {
            form.items().stream()
                    .filter(item -> item.required() && item.kind() != FormItemKind.SECTION)
                    .filter(item -> !answered.contains(item.itemKey()))
                    .findFirst()
                    .ifPresent(item -> {
                        throw invalid("Required review answers are incomplete.");
                    });
        }
        canonical.sort(Comparator.comparing(ReviewAnswer::itemKey));
        return List.copyOf(canonical);
    }

    public String draftContentHash(
            UUID reviewId,
            UUID reviewerAssignmentId,
            UUID templateVersionId,
            String templateContentHash,
            List<ReviewAnswer> canonicalAnswers) {
        if (reviewId == null || reviewerAssignmentId == null || templateVersionId == null
                || templateContentHash == null
                || !templateContentHash.matches(PerformanceReviewDtos.HASH_PATTERN)
                || canonicalAnswers == null) {
            throw invalid("Assigned-review draft identity is incomplete.");
        }
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("answers", canonicalAnswers);
        draft.put("reviewId", reviewId);
        draft.put("reviewerAssignmentId", reviewerAssignmentId);
        draft.put("templateContentHash", templateContentHash);
        draft.put("templateVersionId", templateVersionId);
        return digest(draft);
    }

    public String commandHash(
            String operation,
            java.util.UUID reviewId,
            long expectedVersion,
            Object canonicalBody) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("body", canonicalBody);
        command.put("expectedVersion", expectedVersion);
        command.put("operation", operation);
        command.put("reviewId", reviewId);
        return digest(command);
    }

    public String digest(Object value) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(value);
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Review canonicalization failed.", exception);
        }
    }

    private ReviewAnswer validateAnswer(
            FormItem item,
            ReviewAnswer answer,
            Map<String, RubricScale> scales) {
        return switch (item.kind()) {
            case TEXT -> validateText(item, answer);
            case DECIMAL -> validateDecimal(item, answer);
            case RATING -> validateRating(item, answer, scales);
            case CHOICE -> validateChoice(item, answer);
            case SECTION -> throw invalid("Sections cannot be answered.");
        };
    }

    private TextAnswer validateText(FormItem item, ReviewAnswer answer) {
        if (!(answer instanceof TextAnswer text)
                || answer.kind() != AnswerKind.TEXT
                || !(item.answerConstraints() instanceof TextConstraints constraints)
                || text.textValue() == null
                || text.textValue().length() > constraints.maxLength()
                || (!constraints.multiline()
                && (text.textValue().contains("\n") || text.textValue().contains("\r")))) {
            throw invalid("The text review answer violates its form constraint.");
        }
        return new TextAnswer(text.itemKey(), AnswerKind.TEXT, text.textValue());
    }

    private DecimalAnswer validateDecimal(FormItem item, ReviewAnswer answer) {
        if (!(answer instanceof DecimalAnswer decimalAnswer)
                || answer.kind() != AnswerKind.DECIMAL
                || !(item.answerConstraints() instanceof DecimalConstraints constraints)) {
            throw invalid("The decimal review answer violates its form constraint.");
        }
        String value = decimal(decimalAnswer.decimalValue());
        BigDecimal number = new BigDecimal(value);
        if (number.scale() > constraints.scale()
                || constraints.minimum() != null
                && number.compareTo(new BigDecimal(decimal(constraints.minimum()))) < 0
                || constraints.maximum() != null
                && number.compareTo(new BigDecimal(decimal(constraints.maximum()))) > 0) {
            throw invalid("The decimal review answer is outside its allowed range.");
        }
        return new DecimalAnswer(decimalAnswer.itemKey(), AnswerKind.DECIMAL, value);
    }

    private RatingAnswer validateRating(
            FormItem item,
            ReviewAnswer answer,
            Map<String, RubricScale> scales) {
        if (!(answer instanceof RatingAnswer ratingAnswer)
                || answer.kind() != AnswerKind.RATING
                || !(item.answerConstraints() instanceof RatingConstraints constraints)) {
            throw invalid("The rating review answer violates its form constraint.");
        }
        String value = decimal(ratingAnswer.ratingValue());
        RubricScale scale = scales.get(constraints.scaleKey());
        if (scale == null || scale.levels().stream()
                .noneMatch(level -> level.numericValue().equals(value))) {
            throw invalid("The rating review answer is not in its rubric scale.");
        }
        return new RatingAnswer(ratingAnswer.itemKey(), AnswerKind.RATING, value);
    }

    private ChoiceAnswer validateChoice(FormItem item, ReviewAnswer answer) {
        if (!(answer instanceof ChoiceAnswer choice)
                || answer.kind() != AnswerKind.CHOICE
                || !(item.answerConstraints() instanceof ChoiceConstraints constraints)
                || constraints.options().stream()
                .noneMatch(option -> option.optionKey().equals(choice.optionKey()))) {
            throw invalid("The choice review answer is not in its option set.");
        }
        return new ChoiceAnswer(choice.itemKey(), AnswerKind.CHOICE, choice.optionKey());
    }

    private AnswerConstraints canonicalConstraints(
            FormItemKind kind,
            AnswerConstraints constraints) {
        return switch (kind) {
            case SECTION -> {
                if (!(constraints instanceof SectionConstraints section)) {
                    throw invalid("Review item kind and constraints do not match.");
                }
                yield section;
            }
            case TEXT -> {
                if (!(constraints instanceof TextConstraints text)
                        || text.maxLength() < 1 || text.maxLength() > 10_000) {
                    throw invalid("Review text constraints are outside their contract.");
                }
                yield text;
            }
            case DECIMAL -> {
                if (!(constraints instanceof DecimalConstraints decimal)
                        || decimal.scale() < 0 || decimal.scale() > 6) {
                    throw invalid("Review decimal constraints are outside their contract.");
                }
                String minimum = nullableDecimal(decimal.minimum());
                String maximum = nullableDecimal(decimal.maximum());
                if (minimum != null && maximum != null
                        && new BigDecimal(minimum).compareTo(new BigDecimal(maximum)) > 0) {
                    throw invalid("Review decimal constraints have an invalid range.");
                }
                yield new DecimalConstraints(minimum, maximum, decimal.scale());
            }
            case RATING -> {
                if (!(constraints instanceof RatingConstraints rating)
                        || !boundedNonBlank(rating.scaleKey(), 80)) {
                    throw invalid("Review rating constraints are outside their contract.");
                }
                yield rating;
            }
            case CHOICE -> {
                if (!(constraints instanceof ChoiceConstraints choice)
                        || choice.minimumSelections() < 0 || choice.minimumSelections() > 1
                        || choice.maximumSelections() != 1
                        || choice.options().isEmpty() || choice.options().size() > 100) {
                    throw invalid("Review choice constraints are outside their contract.");
                }
                Set<String> optionKeys = new HashSet<>();
                Set<ChoiceOption> options = new HashSet<>();
                for (ChoiceOption option : choice.options()) {
                    if (option == null || !boundedNonBlank(option.optionKey(), 120)
                            || !boundedNonBlank(option.label(), 200)
                            || !optionKeys.add(option.optionKey()) || !options.add(option)) {
                        throw invalid("Review choice options must be bounded and unique.");
                    }
                }
                yield choice;
            }
        };
    }

    private Map<String, FormItem> index(List<FormItem> items) {
        Map<String, FormItem> indexed = new LinkedHashMap<>();
        items.forEach(item -> indexed.put(item.itemKey(), item));
        return Map.copyOf(indexed);
    }

    private String decimal(String raw) {
        if (raw == null || !raw.matches(PerformanceReviewDtos.DECIMAL_PATTERN)) {
            throw invalid("Review decimals must use the canonical six-decimal format.");
        }
        BigDecimal number;
        try {
            number = new BigDecimal(raw);
        } catch (NumberFormatException exception) {
            throw invalid("Review decimal is invalid.");
        }
        if (number.signum() == 0) return "0";
        return number.stripTrailingZeros().toPlainString();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean boundedNonBlank(String value, int maximumLength) {
        return !blank(value) && value.length() <= maximumLength;
    }

    private String nullableDecimal(String value) {
        return value == null ? null : decimal(value);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
