package com.dwp.services.people.hris.performance.per008;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Closed PER-008 browser contracts. No persistence or protection metadata is exposed. */
public final class Per008Contracts {
    private Per008Contracts() { }

    public enum QueryState { COMPLETE, EMPTY, PARTIAL, UNAVAILABLE }
    public enum RequestState { DRAFT, OPEN, CLOSED, AGGREGATED, RELEASED, CANCELLED }
    public enum AssignmentState { ASSIGNED, SUBMITTED, DECLINED, CANCELLED }
    public enum AnonymityMode { IDENTIFIED, MASKED, ANONYMOUS_AGGREGATE }
    public enum RelationshipType {
        SELF, MANAGER, SECOND_MANAGER, THIRD_MANAGER, PEER, DIRECT_REPORT, REFEREE
    }
    public enum AnswerType { TEXT, NUMBER, SINGLE_CHOICE, MULTI_CHOICE }
    public enum FieldDecision { VIEW, MASK, OMIT }

    public record FeedbackActionTarget(
            UUID cycleVersionId,
            UUID subjectWorkerPublicId,
            UUID subjectAssignmentPublicId,
            String scopeDecisionRevision,
            String authorizationDecisionRevision) { }

    public record CreateAction(
            String actionKey,
            String method,
            String hrefTemplate,
            String idempotencyPolicy,
            String stepUpPolicy) {
        static CreateAction feedback() {
            return new CreateAction(
                    "hris.action.per.create-feedback-request", "POST",
                    "/api/people/v1/hris/performance/feedback-requests",
                    "REQUIRED_IDEMPOTENCY_KEY",
                    "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
        }
    }

    public sealed interface ProjectedField<T> permits ViewedField, MaskedField {
        FieldDecision decision();
    }

    public record ViewedField<T>(FieldDecision decision, T value) implements ProjectedField<T> {
        public ViewedField(T value) { this(FieldDecision.VIEW, value); }
        public ViewedField {
            if (decision != FieldDecision.VIEW || value == null) throw invalid();
        }
    }

    public record MaskedField<T>(
            FieldDecision decision,
            T value,
            String maskReason,
            String maskedDisplayKey) implements ProjectedField<T> {
        public MaskedField(String reason, String displayKey) {
            this(FieldDecision.MASK, null, reason, displayKey);
        }
        public MaskedField {
            if (decision != FieldDecision.MASK || value != null
                    || blank(maskReason) || blank(maskedDisplayKey)) throw invalid();
        }
    }

    public record ReferenceValue(String publicId, String code, String label, long revision) { }
    public record StatusValue(String code, long revision) { }
    public record DeepLink(String route, String resourcePublicId) {
        public DeepLink(UUID id) { this("/hr/team/performance", id.toString()); }
    }

    public record TeamItem(
            String itemPublicId,
            String title,
            String statusCode,
            Map<String, ProjectedField<?>> fieldGroups,
            DeepLink deepLink,
            List<CreateAction> availableActions) {
        public TeamItem {
            fieldGroups = Map.copyOf(fieldGroups);
            availableActions = List.copyOf(availableActions);
        }
    }

    public record FieldDecisionSummary(int viewCount, int maskedCount, int omittedCount) { }
    public record PartialFailure(
            String componentKey, String errorCode, UUID correlationId, boolean retryable) { }
    public record CachePolicy(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) { }
    public record QueryStateEnvelope(
            QueryState state,
            String scopeDecisionRevision,
            String freshness,
            String nextCursor,
            FieldDecisionSummary fieldDecisionSummary,
            List<PartialFailure> partialFailures,
            UUID correlationId,
            CachePolicy cachePolicy) {
        public QueryStateEnvelope { partialFailures = List.copyOf(partialFailures); }
    }
    public record TeamProjection(
            String projectionVersion,
            OffsetDateTime asOf,
            String variantKey,
            QueryStateEnvelope queryState,
            List<TeamItem> items) {
        public TeamProjection {
            items = List.copyOf(items);
        }
    }

    public record TemplateOption(
            UUID templateVersionId,
            String label,
            String schemaKey,
            int schemaVersion,
            String templateContentHash) { }
    public record ThresholdLimits(int minimum, int maximum) { }
    public record WindowLimits(
            OffsetDateTime earliestOpensAt,
            OffsetDateTime latestClosesAt,
            int maximumDurationDays) { }
    public record RequestOptions(
            String schemaKey,
            UUID cycleVersionId,
            UUID subjectWorkerPublicId,
            UUID subjectAssignmentPublicId,
            List<TemplateOption> templates,
            List<AnonymityMode> anonymityModes,
            ThresholdLimits thresholdLimits,
            WindowLimits windowLimits,
            UUID correlationId) {
        public RequestOptions {
            templates = List.copyOf(templates);
            anonymityModes = List.copyOf(anonymityModes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateFeedbackRequestCommand(
            UUID cycleVersionId,
            UUID subjectWorkerPublicId,
            UUID subjectAssignmentPublicId,
            UUID templateVersionId,
            AnonymityMode anonymityMode,
            int minimumReleaseCount,
            OffsetDateTime opensAt,
            OffsetDateTime closesAt) { }

    public record FeedbackRequestReceipt(
            UUID receiptId,
            UUID requestId,
            RequestState state,
            long version,
            OffsetDateTime occurredAt,
            boolean replayed) { }

    public record AssignedListItem(
            UUID feedbackAssignmentId,
            UUID feedbackRequestId,
            String requestTitle,
            RequestState requestState,
            AssignmentState assignmentState,
            OffsetDateTime opensAt,
            OffsetDateTime closesAt) { }
    public record AssignedList(
            String schemaVersion,
            OffsetDateTime asOf,
            QueryState queryState,
            List<AssignedListItem> items,
            String nextCursor,
            UUID problemCorrelationId) {
        public AssignedList { items = List.copyOf(items); }
    }

    public record ChoiceOption(UUID choiceOptionPublicId, String label) { }
    public record Question(
            UUID questionPublicId,
            String prompt,
            AnswerType answerType,
            boolean required,
            List<ChoiceOption> choiceOptions) {
        public Question { choiceOptions = List.copyOf(choiceOptions); }
    }
    public record AssignedDetail(
            String schemaKey,
            UUID feedbackAssignmentId,
            UUID feedbackRequestId,
            String requestTitle,
            RequestState requestState,
            AssignmentState assignmentState,
            long expectedRequestVersion,
            UUID templateVersionId,
            String templateContentHash,
            OffsetDateTime opensAt,
            OffsetDateTime closesAt,
            List<Question> questions,
            UUID correlationId) {
        public AssignedDetail { questions = List.copyOf(questions); }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FeedbackAnswer(
            UUID questionPublicId,
            AnswerType answerType,
            String textValue,
            BigDecimal numberValue,
            List<UUID> choiceOptionPublicIds) {
        public FeedbackAnswer {
            choiceOptionPublicIds = choiceOptionPublicIds == null
                    ? List.of() : List.copyOf(choiceOptionPublicIds);
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FeedbackResponse(
            String schemaKey,
            List<FeedbackAnswer> answers) {
        public FeedbackResponse {
            answers = answers == null ? List.of() : List.copyOf(answers);
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SubmitFeedbackResponseCommand(
            long expectedRequestVersion,
            String templateContentHash,
            FeedbackResponse response) { }

    public record FeedbackSubmissionReceipt(
            UUID receiptId,
            UUID requestId,
            UUID assignmentId,
            AssignmentState assignmentState,
            long version,
            OffsetDateTime submittedAt,
            boolean replayed) { }

    /** Owner-internal aggregate sections are intentionally not part of this public projection. */
    record ReleaseSection(
            UUID sectionPublicId,
            String label,
            String narrative,
            BigDecimal score) { }
    public record FeedbackRelease(
            String schemaKey,
            String aggregateSchemaKey,
            UUID feedbackRequestId,
            RequestState requestState,
            AnonymityMode anonymityMode,
            String policyRevision,
            OffsetDateTime aggregatedAt,
            OffsetDateTime releasedAt,
            String title,
            UUID correlationId) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Problem(
            String type,
            String title,
            int status,
            String code,
            UUID correlationId,
            boolean retryable) { }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || !value.equals(value.trim());
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("PER008_CONTRACT_INVALID");
    }
}
