package com.dwp.services.people.hris.performance.review;

import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.AnswerConstraints;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.AnswerKind;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ChoiceConstraints;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ChoiceOption;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.DecimalAnswer;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.DecimalConstraints;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.DraftCommand;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerformanceReviewCanonicalizerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PerformanceReviewCanonicalizer canonicalizer =
            new PerformanceReviewCanonicalizer(mapper);

    @Test
    void canonicalGoldenFixtureHasStableOrderDecimalsAndHash() throws Exception {
        List<ReviewAnswer> first = canonicalizer.canonicalAnswers(
                PerformanceReviewTestFixtures.form(),
                PerformanceReviewTestFixtures.completeAnswers(), true);
        List<ReviewAnswer> reverse = new ArrayList<>(
                PerformanceReviewTestFixtures.completeAnswers());
        java.util.Collections.reverse(reverse);
        List<ReviewAnswer> second = canonicalizer.canonicalAnswers(
                PerformanceReviewTestFixtures.form(), reverse, true);

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(ReviewAnswer::itemKey)
                .containsExactly("choice", "comment", "rating", "score");
        assertThat(((RatingAnswer) first.get(2)).ratingValue()).isEqualTo("5");
        assertThat(((DecimalAnswer) first.get(3)).decimalValue()).isEqualTo("3.5");
        String firstHash = draftHash(PerformanceReviewTestFixtures.REVIEW, first);
        assertThat(firstHash)
                .isEqualTo(draftHash(PerformanceReviewTestFixtures.REVIEW, second))
                .isEqualTo("56467878d91bb997daa22a61689e542966576039dea7f16feda6108525be12c7");
        assertThat(draftHash(PerformanceReviewTestFixtures.REVIEW, List.of()))
                .isEqualTo("d14584eaea88bb136e8d38c3d72851f9aff3db87d747551eb894d487211d8911");
        assertThat(draftHash(java.util.UUID.randomUUID(), first)).isNotEqualTo(firstHash);
        assertThat(canonicalizer.draftContentHash(
                PerformanceReviewTestFixtures.REVIEW, java.util.UUID.randomUUID(),
                PerformanceReviewTestFixtures.TEMPLATE,
                "a".repeat(64), first)).isNotEqualTo(firstHash);
        assertThat(canonicalizer.draftContentHash(
                PerformanceReviewTestFixtures.REVIEW,
                PerformanceReviewTestFixtures.ASSIGNMENT, java.util.UUID.randomUUID(),
                "a".repeat(64), first)).isNotEqualTo(firstHash);
        assertThat(canonicalizer.draftContentHash(
                PerformanceReviewTestFixtures.REVIEW,
                PerformanceReviewTestFixtures.ASSIGNMENT,
                PerformanceReviewTestFixtures.TEMPLATE,
                "b".repeat(64), first)).isNotEqualTo(firstHash);

        String json = mapper.writeValueAsString(new DraftCommand(first));
        DraftCommand roundTrip = mapper.readValue(json, DraftCommand.class);
        assertThat(roundTrip.answers()).isEqualTo(first);
    }

    @Test
    void rejectsMissingRequiredUnknownDuplicateAndOutOfRangeAnswers() {
        assertThatThrownBy(() -> canonicalizer.canonicalAnswers(
                PerformanceReviewTestFixtures.form(),
                List.of(new DecimalAnswer("score", AnswerKind.DECIMAL, "7")), true))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> canonicalizer.canonicalAnswers(
                PerformanceReviewTestFixtures.form(),
                List.of(
                        new DecimalAnswer("score", AnswerKind.DECIMAL, "3"),
                        new DecimalAnswer("score", AnswerKind.DECIMAL, "4")), false))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> canonicalizer.canonicalAnswers(
                PerformanceReviewTestFixtures.form(),
                List.of(new DecimalAnswer("absent", AnswerKind.DECIMAL, "1")), false))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> canonicalizer.canonicalAnswers(
                PerformanceReviewTestFixtures.form(),
                List.of(
                        new TextAnswer("comment", AnswerKind.TEXT, "  \n"),
                        new RatingAnswer("rating", AnswerKind.RATING, "5")), true))
                .isInstanceOf(BaseException.class);
        assertThat(canonicalizer.canonicalAnswers(
                PerformanceReviewTestFixtures.form(),
                List.of(new TextAnswer("comment", AnswerKind.TEXT, "  \n")), false))
                .hasSize(1);
    }

    @Test
    void rejectsOutOfContractFormsLoadedFromJsonbWithoutBeanValidation() {
        assertInvalid(new ReviewForm("v".repeat(31), List.of(section()), List.of()));
        assertInvalid(form(item("i".repeat(101), FormItemKind.TEXT,
                new TextConstraints(10, true))));
        assertInvalid(form(new FormItem(
                "item", null, FormItemKind.TEXT, 1, false, null,
                "l".repeat(2_001), null, new TextConstraints(10, true))));
        assertInvalid(form(new FormItem(
                "item", null, FormItemKind.TEXT, 1, false, null,
                "Label", "h".repeat(5_001), new TextConstraints(10, true))));
        assertInvalid(form(new FormItem(
                "item", null, FormItemKind.DECIMAL, 1, false, "01",
                "Label", null, new DecimalConstraints(null, null, 0))));
        assertInvalid(form(item("item", FormItemKind.TEXT,
                new TextConstraints(0, true))));
        assertInvalid(form(item("item", FormItemKind.TEXT,
                new TextConstraints(10_001, true))));
        assertInvalid(form(item("item", FormItemKind.DECIMAL,
                new DecimalConstraints("5", "1", 2))));
        assertInvalid(form(item("item", FormItemKind.DECIMAL,
                new DecimalConstraints(null, null, 7))));
        assertInvalid(form(item("item", FormItemKind.RATING,
                new RatingConstraints("s".repeat(81)))));
        assertInvalid(form(item("item", FormItemKind.CHOICE,
                new ChoiceConstraints(2, 1, List.of(new ChoiceOption("A", "A"))))));
        assertInvalid(form(item("item", FormItemKind.CHOICE,
                new ChoiceConstraints(0, 1, List.of()))));
        assertInvalid(form(item("item", FormItemKind.CHOICE,
                new ChoiceConstraints(0, 1, List.of(
                        new ChoiceOption("A", "First"),
                        new ChoiceOption("A", "Second"))))));
        assertInvalid(form(item("item", FormItemKind.CHOICE,
                new ChoiceConstraints(0, 1, List.of(new ChoiceOption(" ", "Label"))))));
        assertInvalid(new ReviewForm(
                "1", List.of(section()), List.of(new RubricScale("scale", List.of()))));
        assertInvalid(new ReviewForm(
                "1", List.of(section()), List.of(new RubricScale("scale", List.of(
                new RubricLevel(" ", 1, "1", "Label"))))));
        assertInvalid(new ReviewForm(
                "1", List.of(section()), List.of(new RubricScale("scale", List.of(
                new RubricLevel("level", 1, "1", " "))))));

        List<FormItem> tooManyItems = new ArrayList<>();
        for (int index = 1; index <= 501; index++) {
            tooManyItems.add(new FormItem(
                    "item-" + index, null, FormItemKind.SECTION, index,
                    false, null, "Label", null, new SectionConstraints()));
        }
        assertInvalid(new ReviewForm("1", tooManyItems, List.of()));

        List<RubricScale> tooManyScales = new ArrayList<>();
        for (int index = 1; index <= 101; index++) {
            tooManyScales.add(new RubricScale("scale-" + index, List.of(
                    new RubricLevel("level", 1, "1", "Label"))));
        }
        assertInvalid(new ReviewForm("1", List.of(section()), tooManyScales));
    }

    @Test
    void canonicalizesEveryDecimalInTheStoredForm() {
        ReviewForm form = new ReviewForm("1", List.of(new FormItem(
                "item", null, FormItemKind.DECIMAL, 1, false, "1.500000",
                "Label", null, new DecimalConstraints("0.000000", "5.000000", 6))),
                List.of(new RubricScale("scale", List.of(
                        new RubricLevel("level", 1, "3.500000", "Label")))));

        ReviewForm canonical = canonicalizer.canonicalForm(form);

        assertThat(canonical.items().getFirst().weight()).isEqualTo("1.5");
        DecimalConstraints constraints = (DecimalConstraints) canonical.items()
                .getFirst().answerConstraints();
        assertThat(constraints.minimum()).isEqualTo("0");
        assertThat(constraints.maximum()).isEqualTo("5");
        assertThat(canonical.rubricScales().getFirst().levels().getFirst().numericValue())
                .isEqualTo("3.5");
    }

    private String draftHash(java.util.UUID reviewId, List<ReviewAnswer> answers) {
        return canonicalizer.draftContentHash(
                reviewId, PerformanceReviewTestFixtures.ASSIGNMENT,
                PerformanceReviewTestFixtures.TEMPLATE, "a".repeat(64), answers);
    }

    private ReviewForm form(FormItem item) {
        return new ReviewForm("1", List.of(item), List.of());
    }

    private FormItem item(
            String itemKey,
            FormItemKind kind,
            AnswerConstraints constraints) {
        return new FormItem(
                itemKey, null, kind, 1, false, null,
                "Label", null, constraints);
    }

    private FormItem section() {
        return item("section", FormItemKind.SECTION, new SectionConstraints());
    }

    private void assertInvalid(ReviewForm form) {
        assertThatThrownBy(() -> canonicalizer.canonicalForm(form))
                .isInstanceOf(BaseException.class);
    }
}
