package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.AuthorityEvidence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** IAQ-038 v2: baseline projection plus the separately authorized feedback overlay. */
@Service
@ConditionalOnProperty(name = "dwp.hris.performance.feedback.enabled", havingValue = "true")
final class Per008IaEntryQuery {
    interface ProjectionSource {
        SourcePage query(AuthorityEvidence authority, Query request);
    }
    interface RevisionBoundCursorPort {
        boolean verify(String cursor, long tenantId, String scopeDecisionRevision,
                String authorizationDecisionRevision, String fieldPolicyRevision,
                String populationScopeDigest, String projectionVersion,
                String canonicalEffectiveQuery);
        String bind(String sourceCursor, long tenantId, String scopeDecisionRevision,
                String authorizationDecisionRevision, String fieldPolicyRevision,
                String populationScopeDigest, String projectionVersion,
                String canonicalEffectiveQuery);
    }
    /** Independent PEP-PER-015 action authority; IAQ visibility never implies mutation rights. */
    interface FeedbackCreateActionAuthorityPort {
        boolean permits(AuthorityEvidence pageAuthority, FeedbackActionTarget target);
    }

    record ReferenceFilter(Set<String> publicIds, Set<String> codes) {
        ReferenceFilter {
            publicIds = publicIds == null ? Set.of() : Set.copyOf(publicIds);
            codes = codes == null ? Set.of() : Set.copyOf(codes);
        }
    }
    record StatusFilter(Set<String> codes, Long minimumRevision) {
        StatusFilter { codes = codes == null ? Set.of() : Set.copyOf(codes); }
    }
    record Filter(ReferenceFilter performanceAssignment, StatusFilter dueStatus,
            StatusFilter limitedProfile, LocalDate effectiveOn) {
        Filter {
            performanceAssignment = performanceAssignment == null
                    ? new ReferenceFilter(Set.of(), Set.of()) : performanceAssignment;
            dueStatus = dueStatus == null ? new StatusFilter(Set.of(), null) : dueStatus;
            limitedProfile = limitedProfile == null
                    ? new StatusFilter(Set.of(), null) : limitedProfile;
        }
    }
    record Query(int limit, Filter filter, List<String> sort, String locale, String timeZone,
            UUID correlationId, OffsetDateTime asOf, String cursor) {
        Query { sort = sort == null ? List.of() : List.copyOf(sort); }
    }
    record SourceItem(String itemPublicId, String title, String statusCode,
            ReferenceValue performanceAssignment, StatusValue dueStatus,
            StatusValue limitedProfile, List<CreateAction> existingActions,
            FeedbackActionTarget feedbackTarget, boolean targetCurrent, boolean targetAmbiguous) {
        SourceItem { existingActions = List.copyOf(existingActions); }
    }
    record SourcePage(QueryState state, String freshness, List<SourceItem> items,
            String nextCursor, List<PartialFailure> partialFailures) {
        SourcePage {
            items = List.copyOf(items);
            partialFailures = List.copyOf(partialFailures);
        }
    }

    static final Set<String> SORTS = Set.of(
            "performanceAssignment:ASC", "dueStatus:ASC", "limitedProfile:ASC",
            "performanceAssignment:DESC", "dueStatus:DESC", "limitedProfile:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");
    private static final List<String> GROUPS = List.of("PERFORMANCE_ASSIGNMENT", "DUE_STATUS",
            "LIMITED_PROFILE", "PERFORMANCE_FEEDBACK_ACTION_TARGET");
    private static final Map<String, List<String>> PRIOR_ACTIONS = Map.of(
            "hris.action.per.create-goal", List.of("POST", "/api/people/v1/hris/performance/goals"),
            "hris.action.per.submit-goal", List.of("POST", "/api/people/v1/hris/performance/goals/{goalId}/submit"),
            "hris.action.per.save-review-draft", List.of("PATCH", "/api/people/v1/hris/performance/reviews/{reviewId}"),
            "hris.action.per.submit-review", List.of("POST", "/api/people/v1/hris/performance/reviews/{reviewId}/submit"));
    private static final String IDEMPOTENCY_POLICY = "REQUIRED_IDEMPOTENCY_KEY";
    private static final String STEP_UP_POLICY = "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED";

    private final ProjectionSource source;
    private final RevisionBoundCursorPort cursors;
    private final FeedbackCreateActionAuthorityPort feedbackActions;

    Per008IaEntryQuery(ObjectProvider<ProjectionSource> source,
            ObjectProvider<RevisionBoundCursorPort> cursors,
            ObjectProvider<FeedbackCreateActionAuthorityPort> feedbackActions) {
        this.source = source.getIfAvailable();
        this.cursors = cursors.getIfAvailable();
        this.feedbackActions = feedbackActions.getIfAvailable();
    }

    TeamProjection query(AuthorityEvidence authority, Query query) {
        validate(query);
        String effectiveQuery = canonicalEffectiveQuery(query);
        if (query.cursor() != null && (cursors == null || !cursors.verify(query.cursor(),
                authority.tenantId(), authority.scopeDecisionRevision(),
                authority.authorizationDecisionRevision(), authority.fieldPolicyRevision(),
                authority.populationScopeDigest(), "2", effectiveQuery))) {
            throw Per008Problem.conflict("PER008_CURSOR_STALE");
        }
        OffsetDateTime now = query.asOf() == null
                ? OffsetDateTime.now(ZoneOffset.UTC) : query.asOf();
        if (source == null) return unavailable(authority, query.correlationId(), now);
        SourcePage page;
        try { page = source.query(authority, query); }
        catch (RuntimeException unavailable) {
            return unavailable(authority, query.correlationId(), now);
        }
        if (!valid(page, query.limit())) return unavailable(authority, query.correlationId(), now);
        Map<String, Integer> targetOccurrences = new HashMap<>();
        page.items().stream().map(SourceItem::feedbackTarget).filter(java.util.Objects::nonNull)
                .map(this::identity).forEach(key -> targetOccurrences.merge(key, 1, Integer::sum));
        int views = 0;
        int masks = 0;
        int omissions = 0;
        List<TeamItem> items = new ArrayList<>();
        for (SourceItem sourceItem : page.items()) {
            Map<String, ProjectedField<?>> fields = new LinkedHashMap<>();
            Object[] values = {sourceItem.performanceAssignment(), sourceItem.dueStatus(),
                    sourceItem.limitedProfile(), sourceItem.feedbackTarget()};
            for (int index = 0; index < GROUPS.size(); index++) {
                String group = GROUPS.get(index);
                FieldDecision decision = authority.fieldDecisions().getOrDefault(group,
                        FieldDecision.OMIT);
                if (decision == FieldDecision.VIEW && values[index] != null) {
                    fields.put(group, new ViewedField<>(values[index]));
                    views++;
                } else if (decision == FieldDecision.MASK) {
                    fields.put(group, new MaskedField<>("POLICY_MASKED",
                            "hris.performance.masked." + group.toLowerCase()));
                    masks++;
                } else {
                    omissions++;
                }
            }
            List<CreateAction> actions = new ArrayList<>(sourceItem.existingActions());
            if (!validPriorActions(actions)) {
                return unavailable(authority, query.correlationId(), now);
            }
            FeedbackActionTarget target = sourceItem.feedbackTarget();
            boolean exactActionCandidate = page.state() == QueryState.COMPLETE
                    && "CURRENT".equals(page.freshness())
                    && target != null && sourceItem.targetCurrent() && !sourceItem.targetAmbiguous()
                    && targetOccurrences.getOrDefault(identity(target), 0) == 1
                    && authority.permits(target.subjectWorkerPublicId(),
                        target.subjectAssignmentPublicId())
                    && authority.fieldDecisions().get("PERFORMANCE_FEEDBACK_ACTION_TARGET")
                        == FieldDecision.VIEW
                    && authority.scopeDecisionRevision().equals(target.scopeDecisionRevision())
                    && authority.authorizationDecisionRevision()
                        .equals(target.authorizationDecisionRevision())
                    && feedbackActions != null;
            boolean exactAction = false;
            if (exactActionCandidate) {
                try {
                    exactAction = feedbackActions.permits(authority, target);
                } catch (RuntimeException denied) {
                    exactAction = false;
                }
            }
            if (exactAction) actions.add(CreateAction.feedback());
            if (fields.isEmpty()) {
                return unavailable(authority, query.correlationId(), now);
            }
            items.add(new TeamItem(sourceItem.itemPublicId(), sourceItem.title(),
                    sourceItem.statusCode(), fields,
                    new DeepLink("/hr/team/performance", sourceItem.itemPublicId()), actions));
        }
        FieldDecisionSummary summary = new FieldDecisionSummary(views, masks, omissions);
        String nextCursor = null;
        if (page.nextCursor() != null) {
            if (cursors == null) return unavailable(authority, query.correlationId(), now);
            nextCursor = cursors.bind(page.nextCursor(), authority.tenantId(),
                    authority.scopeDecisionRevision(), authority.authorizationDecisionRevision(),
                    authority.fieldPolicyRevision(), authority.populationScopeDigest(), "2",
                    effectiveQuery);
            if (nextCursor == null || !nextCursor.matches("^[A-Za-z0-9_-]{16,1024}$")) {
                return unavailable(authority, query.correlationId(), now);
            }
        }
        QueryStateEnvelope state = new QueryStateEnvelope(page.state(),
                authority.scopeDecisionRevision(), page.freshness(), nextCursor, summary,
                page.partialFailures(), query.correlationId(), new CachePolicy(
                authority.authorizationDecisionRevision(),
                "dwp.hris.access.assignment.changed.v1", 0));
        return new TeamProjection("2", now, "PERFORMANCE", state, items);
    }

    private TeamProjection unavailable(AuthorityEvidence authority, UUID correlation,
            OffsetDateTime asOf) {
        UUID id = correlation == null ? UUID.randomUUID() : correlation;
        QueryStateEnvelope state = new QueryStateEnvelope(QueryState.UNAVAILABLE,
                authority.scopeDecisionRevision(), "UNAVAILABLE", null,
                new FieldDecisionSummary(0, 0, 0), List.of(), id,
                new CachePolicy(authority.authorizationDecisionRevision(),
                        "dwp.hris.access.assignment.changed.v1", 0));
        return new TeamProjection("2", asOf, "PERFORMANCE", state, List.of());
    }

    private boolean valid(SourcePage page, int limit) {
        if (page == null || page.state() == null || page.freshness() == null
                || page.items() == null || page.items().size() > limit
                || page.partialFailures() == null) return false;
        if ((page.state() == QueryState.EMPTY || page.state() == QueryState.UNAVAILABLE)
                && !page.items().isEmpty()) return false;
        if (!Set.of("CURRENT", "STALE", "UNAVAILABLE").contains(page.freshness())) return false;
        if (page.state() == QueryState.COMPLETE && (page.items().isEmpty()
                || !page.partialFailures().isEmpty()
                || "UNAVAILABLE".equals(page.freshness()))) return false;
        if (page.state() == QueryState.EMPTY && (!page.partialFailures().isEmpty()
                || "UNAVAILABLE".equals(page.freshness()))) return false;
        if (page.state() == QueryState.PARTIAL && (page.partialFailures().isEmpty()
                || "UNAVAILABLE".equals(page.freshness()))) return false;
        if (page.state() == QueryState.UNAVAILABLE
                && (!"UNAVAILABLE".equals(page.freshness()) || page.nextCursor() != null)) return false;
        if (page.partialFailures().size() > 32
                || page.partialFailures().stream().anyMatch(failure -> !valid(failure))) {
            return false;
        }
        return page.items().stream().allMatch(this::valid)
                && page.items().stream().map(SourceItem::itemPublicId).distinct().count()
                == page.items().size();
    }

    private boolean valid(SourceItem item) {
        if (item == null || !publicId(item.itemPublicId())
                || !display(item.title(), 512) || !code(item.statusCode())
                || item.existingActions() == null || item.existingActions().size() > 4
                || item.performanceAssignment() != null
                    && !valid(item.performanceAssignment())
                || item.dueStatus() != null && !valid(item.dueStatus())
                || item.limitedProfile() != null && !valid(item.limitedProfile())
                || item.feedbackTarget() != null && !valid(item.feedbackTarget())
                || item.feedbackTarget() == null
                    && (item.targetCurrent() || item.targetAmbiguous())
                || item.targetCurrent() && item.targetAmbiguous()) {
            return false;
        }
        return true;
    }

    private boolean valid(ReferenceValue value) {
        return publicId(value.publicId()) && code(value.code())
                && display(value.label(), 512) && value.revision() >= 1;
    }

    private boolean valid(StatusValue value) {
        return code(value.code()) && value.revision() >= 1;
    }

    private boolean valid(FeedbackActionTarget target) {
        return target.cycleVersionId() != null
                && target.subjectWorkerPublicId() != null
                && target.subjectAssignmentPublicId() != null
                && revision(target.scopeDecisionRevision())
                && revision(target.authorizationDecisionRevision());
    }

    private boolean valid(PartialFailure failure) {
        return failure != null && failure.componentKey() != null
                && failure.componentKey().matches("^[a-z][a-z0-9._-]{0,127}$")
                && code(failure.errorCode()) && failure.correlationId() != null;
    }

    private void validate(Query query) {
        if (query == null || query.limit() < 1 || query.limit() > 100
                || query.filter() == null || query.locale() == null
                || query.locale().length() < 2 || query.locale().length() > 35
                || query.timeZone() == null || query.timeZone().isBlank()
                || query.timeZone().length() > 255 || query.correlationId() == null
                || query.sort().isEmpty() || query.sort().size() > 3
                || new java.util.HashSet<>(query.sort()).size() != query.sort().size()
                || !SORTS.containsAll(query.sort())
                || query.cursor() != null && !query.cursor().matches("^[A-Za-z0-9_-]{16,1024}$")) {
            throw Per008Problem.invalid("PER008_IAQ038_REQUEST_INVALID");
        }
        ReferenceFilter assignment = query.filter().performanceAssignment();
        StatusFilter due = query.filter().dueStatus();
        StatusFilter profile = query.filter().limitedProfile();
        if (assignment.publicIds().size() > 50 || assignment.codes().size() > 50
                || due.codes().size() > 50 || profile.codes().size() > 50
                || assignment.publicIds().stream().anyMatch(value -> !publicId(value))
                || assignment.codes().stream().anyMatch(value -> !code(value))
                || due.codes().stream().anyMatch(value -> !code(value))
                || profile.codes().stream().anyMatch(value -> !code(value))
                || due.minimumRevision() != null && due.minimumRevision() < 1
                || profile.minimumRevision() != null && profile.minimumRevision() < 1) {
            throw Per008Problem.invalid("PER008_IAQ038_REQUEST_INVALID");
        }
    }

    private boolean validPriorActions(List<CreateAction> actions) {
        if (actions == null || actions.size() > 4
                || actions.stream().anyMatch(java.util.Objects::isNull)
                || actions.stream().map(CreateAction::actionKey).distinct().count() != actions.size()) {
            return false;
        }
        for (CreateAction action : actions) {
            List<String> expected = PRIOR_ACTIONS.get(action.actionKey());
            if (expected == null || !expected.get(0).equals(action.method())
                    || !expected.get(1).equals(action.hrefTemplate())
                    || !IDEMPOTENCY_POLICY.equals(action.idempotencyPolicy())
                    || !STEP_UP_POLICY.equals(action.stepUpPolicy())) {
                return false;
            }
        }
        return true;
    }

    /** Cursor signers bind the opaque source page position plus this canonical query. */
    private String canonicalEffectiveQuery(Query query) {
        StringBuilder value = new StringBuilder();
        token(value, query.limit());
        sorted(value, query.filter().performanceAssignment().publicIds());
        sorted(value, query.filter().performanceAssignment().codes());
        sorted(value, query.filter().dueStatus().codes());
        token(value, query.filter().dueStatus().minimumRevision());
        sorted(value, query.filter().limitedProfile().codes());
        token(value, query.filter().limitedProfile().minimumRevision());
        token(value, query.filter().effectiveOn());
        query.sort().forEach(item -> token(value, item));
        token(value, query.locale());
        token(value, query.timeZone());
        token(value, query.asOf() == null ? null : query.asOf().toInstant());
        return value.toString();
    }

    private void sorted(StringBuilder target, Set<String> values) {
        values.stream().sorted().forEach(value -> token(target, value));
        token(target, "#" + values.size());
    }

    private void token(StringBuilder target, Object candidate) {
        String value = candidate == null ? "<null>" : String.valueOf(candidate);
        target.append(value.length()).append(':').append(value).append('|');
    }

    private boolean publicId(String value) {
        return value != null && value.matches("^[A-Za-z0-9._:-]{1,128}$");
    }

    private boolean code(String value) {
        return value != null && value.matches("^[A-Z][A-Z0-9_]{1,63}$");
    }

    private boolean display(String value, int maximum) {
        return value != null && !value.isBlank() && value.equals(value.trim())
                && value.length() <= maximum;
    }

    private boolean revision(String value) {
        return value != null && value.matches("^[A-Za-z0-9._:-]{1,128}$");
    }

    private String identity(FeedbackActionTarget target) {
        return target.cycleVersionId() + ":" + target.subjectWorkerPublicId() + ':'
                + target.subjectAssignmentPublicId();
    }
}
