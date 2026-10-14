package com.dwp.services.people.hris.performance.review;

import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.AnswerKind;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.AssignedReview;
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
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewState;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewerType;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.RubricLevel;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.RubricScale;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.SectionConstraints;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.TextAnswer;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.TextConstraints;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class PerformanceReviewTestFixtures {

    static final long TENANT = 71L;
    static final long ACTOR = 19L;
    static final UUID SUBJECT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID REVIEW = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final UUID PARTICIPANT = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final UUID ASSIGNMENT = UUID.fromString("40000000-0000-0000-0000-000000000004");
    static final UUID ORGANIZATION = UUID.fromString("50000000-0000-0000-0000-000000000005");
    static final UUID TEMPLATE = UUID.fromString("60000000-0000-0000-0000-000000000006");

    private PerformanceReviewTestFixtures() {
    }

    static ReviewForm form() {
        return new ReviewForm("1.0", List.of(
                new FormItem(
                        "section.overall", null, FormItemKind.SECTION, 1,
                        false, null, "Overall", null, new SectionConstraints()),
                new FormItem(
                        "comment", "section.overall", FormItemKind.TEXT, 2,
                        true, null, "Comment", "Give evidence",
                        new TextConstraints(500, true)),
                new FormItem(
                        "score", "section.overall", FormItemKind.DECIMAL, 3,
                        false, "0.25", "Score", null,
                        new DecimalConstraints("0", "5", 2)),
                new FormItem(
                        "rating", "section.overall", FormItemKind.RATING, 4,
                        true, "0.75", "Rating", null,
                        new RatingConstraints("overall")),
                new FormItem(
                        "choice", "section.overall", FormItemKind.CHOICE, 5,
                        false, null, "Choice", null,
                        new ChoiceConstraints(0, 1, List.of(
                                new ChoiceOption("YES", "Yes"),
                                new ChoiceOption("NO", "No"))))),
                List.of(new RubricScale("overall", List.of(
                        new RubricLevel("LOW", 1, "1", "Low"),
                        new RubricLevel("HIGH", 2, "5", "High")))));
    }

    static List<ReviewAnswer> completeAnswers() {
        return List.of(
                new ChoiceAnswer("choice", AnswerKind.CHOICE, "YES"),
                new RatingAnswer("rating", AnswerKind.RATING, "5.000000"),
                new TextAnswer("comment", AnswerKind.TEXT, "Delivered safely."),
                new DecimalAnswer("score", AnswerKind.DECIMAL, "3.500000"));
    }

    static AssignedReview review(
            PerformanceReviewCanonicalizer canonicalizer,
            long version,
            ReviewState state,
            boolean writable,
            List<ReviewAnswer> answers) {
        ReviewForm form = canonicalizer.canonicalForm(form());
        List<ReviewAnswer> canonical = canonicalizer.canonicalAnswers(form, answers, false);
        return new AssignedReview(
                REVIEW, PARTICIPANT, ASSIGNMENT, ReviewerType.MANAGER,
                "MANAGER_REVIEW", state, TEMPLATE, "a".repeat(64),
                version, writable, canonicalizer.draftContentHash(
                REVIEW, ASSIGNMENT, TEMPLATE, "a".repeat(64), canonical),
                form, canonical, Instant.parse("2027-06-30T00:00:00Z"));
    }

    static PerformanceReviewAuthorityPort.AuthorityEvidence authority(
            String operation) {
        String route = switch (operation) {
            case PerformanceReviewService.GET_OPERATION ->
                    HcmPerformanceReviewAuthorityAdapter.GET_ROUTE;
            case PerformanceReviewService.SAVE_OPERATION ->
                    HcmPerformanceReviewAuthorityAdapter.PATCH_ROUTE;
            case PerformanceReviewService.SUBMIT_OPERATION ->
                    HcmPerformanceReviewAuthorityAdapter.SUBMIT_ROUTE;
            default -> throw new IllegalArgumentException(operation);
        };
        return new PerformanceReviewAuthorityPort.AuthorityEvidence(
                TENANT, ACTOR, SUBJECT, REVIEW, operation,
                HcmPerformanceReviewAuthorityAdapter.PURPOSE,
                "TARGET_POPULATION", "assigned-reviewer", route,
                operation.equals(PerformanceReviewService.GET_OPERATION)
                        ? HcmPerformanceReviewAuthorityAdapter.GET_ROUTE
                        + ".assigned-reviewer.projection.v1" : null,
                "b".repeat(64), 17L, 23L);
    }
}
