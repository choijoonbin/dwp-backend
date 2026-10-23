package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Per008ExplicitSubjectActionBindingTest {
    private static final UUID WORKER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNMENT = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void omitsCreateActionForAmbiguousOrRevisionStaleTargetAsAWhole() {
        assertThat(actions(true, false, "scope-stale", "auth-1")).isEmpty();
        assertThat(actions(false, true, "scope-1", "auth-1")).isEmpty();
        assertThat(actions(false, false, "scope-1", "auth-1")).isEmpty();
    }

    @Test
    void serializesOnlyTheGenericActionForOneCurrentExactTuple() {
        assertThat(actions(true, false, "scope-1", "auth-1"))
                .singleElement().satisfies(action -> {
                    assertThat(action.actionKey()).isEqualTo("hris.action.per.create-feedback-request");
                    assertThat(action.hrefTemplate())
                            .isEqualTo("/api/people/v1/hris/performance/feedback-requests");
                });
    }

    @Test
    void pageReadAuthorityAloneNeverCreatesTheActionWhenIndependentPepDenies() {
        assertThat(actions(true, false, "scope-1", "auth-1", true, false)).isEmpty();
    }

    @Test
    void independentPepAloneNeverCreatesTheActionOutsideThePagePopulation() {
        assertThat(actions(true, false, "scope-1", "auth-1", false, true)).isEmpty();
    }

    private List<CreateAction> actions(boolean current, boolean ambiguous,
            String scopeRevision, String authorizationRevision) {
        return actions(current, ambiguous, scopeRevision, authorizationRevision, true);
    }

    private List<CreateAction> actions(boolean current, boolean ambiguous,
            String scopeRevision, String authorizationRevision, boolean actionAllowed) {
        return actions(current, ambiguous, scopeRevision, authorizationRevision, true,
                actionAllowed);
    }

    private List<CreateAction> actions(boolean current, boolean ambiguous,
            String scopeRevision, String authorizationRevision, boolean pageAllowed,
            boolean actionAllowed) {
        FeedbackActionTarget target = new FeedbackActionTarget(UUID.randomUUID(), WORKER,
                ASSIGNMENT, scopeRevision, authorizationRevision);
        Per008IaEntryQuery.SourceItem item = new Per008IaEntryQuery.SourceItem(
                "item-1", "성과", "OPEN", null, null, null, List.of(), target,
                current, ambiguous);
        Per008IaEntryQuery.ProjectionSource source = (authority, query) ->
                new Per008IaEntryQuery.SourcePage(QueryState.COMPLETE, "CURRENT",
                        List.of(item), null, List.of());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("source", source);
        beans.addBean("feedbackActions",
                (Per008IaEntryQuery.FeedbackCreateActionAuthorityPort) (authority, candidate) ->
                        actionAllowed && candidate.subjectWorkerPublicId().equals(WORKER)
                                && candidate.subjectAssignmentPublicId().equals(ASSIGNMENT));
        Per008IaEntryQuery iaq = new Per008IaEntryQuery(
                beans.getBeanProvider(Per008IaEntryQuery.ProjectionSource.class),
                beans.getBeanProvider(Per008IaEntryQuery.RevisionBoundCursorPort.class),
                beans.getBeanProvider(Per008IaEntryQuery.FeedbackCreateActionAuthorityPort.class));
        TeamProjection result = iaq.query(authority(pageAllowed), new Per008IaEntryQuery.Query(10,
                new Per008IaEntryQuery.Filter(null, null, null, null),
                List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul", UUID.randomUUID(),
                OffsetDateTime.parse("2026-10-23T00:00:00Z"), null));
        return result.items().getFirst().availableActions();
    }

    private AuthorityEvidence authority() {
        return authority(true);
    }

    private AuthorityEvidence authority(boolean pageAllowed) {
        return new AuthorityEvidence(1, 2, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "route", "team-performance-reader", "capability", "TEAM_MANAGEMENT",
                "scope-1", "auth-1", "field-1", "a".repeat(64), Map.of(
                "PERFORMANCE_ASSIGNMENT", FieldDecision.OMIT,
                "DUE_STATUS", FieldDecision.OMIT,
                "LIMITED_PROFILE", FieldDecision.OMIT,
                "PERFORMANCE_FEEDBACK_ACTION_TARGET", FieldDecision.VIEW),
                pageAllowed ? Set.of(new SubjectBinding(WORKER, ASSIGNMENT)) : Set.of(),
                Set.of(PepLayer.values()));
    }
}
