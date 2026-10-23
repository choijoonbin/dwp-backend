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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Per008IaEntryQueryContractTest {
    private static final UUID CYCLE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WORKER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ASSIGNMENT = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID CORRELATION = UUID.fromString("40000000-0000-0000-0000-000000000004");

    @Test
    void acceptsRequiredEmptyFilterAndPreservesOnlyExactPriorActions() {
        Per008IaEntryQuery query = query((authority, request) -> page("CURRENT",
                List.of(item(exactPriorAction(), true, false)), null), null);

        TeamProjection result = query.query(authority(), request(null, null));

        assertThat(result.projectionVersion()).isEqualTo("2");
        assertThat(result.variantKey()).isEqualTo("PERFORMANCE");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.availableActions()).extracting(CreateAction::actionKey)
                    .containsExactly("hris.action.per.create-goal",
                            "hris.action.per.create-feedback-request");
            assertThat(item.fieldGroups()).containsKey("PERFORMANCE_FEEDBACK_ACTION_TARGET");
        });
    }

    @Test
    void keepsStaleCompleteProjectionButOmitsFeedbackAction() {
        Per008IaEntryQuery query = query((authority, request) -> page("STALE",
                List.of(item(exactPriorAction(), true, false)), null), null);

        TeamProjection result = query.query(authority(), request(null, null));

        assertThat(result.queryState().state()).isEqualTo(QueryState.COMPLETE);
        assertThat(result.queryState().freshness()).isEqualTo("STALE");
        assertThat(result.items().getFirst().availableActions())
                .extracting(CreateAction::actionKey)
                .containsExactly("hris.action.per.create-goal");
    }

    @Test
    void rejectsNearMatchPriorActionDescriptorWithoutReflectingIt() {
        CreateAction hostile = new CreateAction("hris.action.per.create-goal", "POST",
                "/api/people/admin/delete-all", "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
        Per008IaEntryQuery query = query((authority, request) -> page("CURRENT",
                List.of(item(hostile, true, false)), null), null);

        TeamProjection result = query.query(authority(), request(null, null));

        assertThat(result.queryState().state()).isEqualTo(QueryState.UNAVAILABLE);
        assertThat(result.items()).isEmpty();
    }

    @Test
    void acceptsEmptyPartialAndBindsCursorToPopulationAndCanonicalQuery() {
        AtomicReference<String> verifiedPopulation = new AtomicReference<>();
        AtomicReference<String> boundQuery = new AtomicReference<>();
        Per008IaEntryQuery.RevisionBoundCursorPort cursor =
                new Per008IaEntryQuery.RevisionBoundCursorPort() {
                    @Override
                    public boolean verify(String value, long tenantId, String scope,
                            String authorization, String field, String population,
                            String projection, String canonicalQuery) {
                        verifiedPopulation.set(population);
                        return canonicalQuery.contains("worker.external:42");
                    }

                    @Override
                    public String bind(String sourcePosition, long tenantId, String scope,
                            String authorization, String field, String population,
                            String projection, String canonicalQuery) {
                        boundQuery.set(sourcePosition + '|' + canonicalQuery);
                        return "abcdefghijklmnop";
                    }
                };
        Per008IaEntryQuery query = query((authority, request) -> new Per008IaEntryQuery.SourcePage(
                QueryState.PARTIAL, "STALE", List.of(), "source-position-2",
                List.of(new PartialFailure("review", "DEPENDENCY_STALE", CORRELATION, true))),
                cursor);
        Per008IaEntryQuery.Filter filter = new Per008IaEntryQuery.Filter(
                new Per008IaEntryQuery.ReferenceFilter(Set.of("worker.external:42"), Set.of()),
                null, null, null);

        TeamProjection result = query.query(authority(), request(filter, "abcdefghijklmnop"));

        assertThat(result.queryState().state()).isEqualTo(QueryState.PARTIAL);
        assertThat(result.items()).isEmpty();
        assertThat(result.queryState().nextCursor()).isEqualTo("abcdefghijklmnop");
        assertThat(verifiedPopulation.get()).isEqualTo("a".repeat(64));
        assertThat(boundQuery.get()).startsWith("source-position-2|")
                .contains("worker.external:42");
    }

    @Test
    void enforcesTheAuthoritativeCodePatternInEveryFilter() {
        for (String rejected : List.of("1", "1ABC", "A-B", "A.B")) {
            Per008IaEntryQuery.ReferenceFilter filter =
                    new Per008IaEntryQuery.ReferenceFilter(Set.of(), Set.of(rejected));
            assertThatThrownBy(() -> query((authority, request) -> page("CURRENT",
                    List.of(item(exactPriorAction(), true, false)), null), null)
                    .query(authority(), request(new Per008IaEntryQuery.Filter(
                            filter, null, null, null), null)))
                    .isInstanceOf(RuntimeException.class);
        }
        for (String accepted : List.of("AB", "A_1")) {
            Per008IaEntryQuery.ReferenceFilter filter =
                    new Per008IaEntryQuery.ReferenceFilter(Set.of(), Set.of(accepted));
            TeamProjection projection = query((authority, request) -> page("CURRENT",
                    List.of(item(exactPriorAction(), true, false)), null), null)
                    .query(authority(), request(new Per008IaEntryQuery.Filter(
                            filter, null, null, null), null));
            assertThat(projection.queryState().state()).isEqualTo(QueryState.COMPLETE);
        }
    }

    @Test
    void suppressesMalformedProviderItemsValuesTargetsAndFailures() {
        List<Per008IaEntryQuery.SourcePage> malformed = List.of(
                page("CURRENT", List.of(sourceItem("bad/id", "성과 항목", "OPEN",
                        validReference(), validStatus(), validStatus(), validTarget())), null),
                page("CURRENT", List.of(sourceItem("performance:item-1", " ", "OPEN",
                        validReference(), validStatus(), validStatus(), validTarget())), null),
                page("CURRENT", List.of(sourceItem("performance:item-1", "성과 항목", "A.B",
                        validReference(), validStatus(), validStatus(), validTarget())), null),
                page("CURRENT", List.of(sourceItem("performance:item-1", "성과 항목", "OPEN",
                        new ReferenceValue("assignment:1", "1ABC", "주 소속", 7),
                        validStatus(), validStatus(), validTarget())), null),
                page("CURRENT", List.of(sourceItem("performance:item-1", "성과 항목", "OPEN",
                        validReference(), new StatusValue("DUE", 0), validStatus(),
                        validTarget())), null),
                page("CURRENT", List.of(sourceItem("performance:item-1", "성과 항목", "OPEN",
                        validReference(), validStatus(), validStatus(),
                        new FeedbackActionTarget(CYCLE, WORKER, ASSIGNMENT, null, "auth-9"))), null),
                page("CURRENT", List.of(new Per008IaEntryQuery.SourceItem(
                        "performance:item-1", "성과 항목", "OPEN", validReference(),
                        validStatus(), validStatus(), List.of(exactPriorAction()), null,
                        true, false)), null),
                page("CURRENT", List.of(new Per008IaEntryQuery.SourceItem(
                        "performance:item-1", "성과 항목", "OPEN", validReference(),
                        validStatus(), validStatus(), List.of(exactPriorAction()), validTarget(),
                        true, true)), null),
                new Per008IaEntryQuery.SourcePage(QueryState.PARTIAL, "CURRENT", List.of(), null,
                        IntStream.range(0, 33).mapToObj(index -> new PartialFailure(
                                "component-" + index, "DEPENDENCY_STALE", CORRELATION, true))
                                .toList()),
                new Per008IaEntryQuery.SourcePage(QueryState.PARTIAL, "CURRENT", List.of(), null,
                        List.of(new PartialFailure("Bad Component", "BAD.CODE", CORRELATION,
                                true))));

        for (Per008IaEntryQuery.SourcePage page : malformed) {
            TeamProjection result = query((authority, request) -> page, null)
                    .query(authority(), request(null, null));
            assertThat(result.queryState().state()).isEqualTo(QueryState.UNAVAILABLE);
            assertThat(result.items()).isEmpty();
        }
    }

    private Per008IaEntryQuery query(Per008IaEntryQuery.ProjectionSource source,
            Per008IaEntryQuery.RevisionBoundCursorPort cursor) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("source", source);
        factory.addBean("feedbackActions",
                (Per008IaEntryQuery.FeedbackCreateActionAuthorityPort) (authority, target) -> true);
        if (cursor != null) factory.addBean("cursor", cursor);
        return new Per008IaEntryQuery(
                factory.getBeanProvider(Per008IaEntryQuery.ProjectionSource.class),
                factory.getBeanProvider(Per008IaEntryQuery.RevisionBoundCursorPort.class),
                factory.getBeanProvider(Per008IaEntryQuery.FeedbackCreateActionAuthorityPort.class));
    }

    private Per008IaEntryQuery.Query request(Per008IaEntryQuery.Filter filter, String cursor) {
        return new Per008IaEntryQuery.Query(25,
                filter == null ? new Per008IaEntryQuery.Filter(null, null, null, null) : filter,
                List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul", CORRELATION,
                OffsetDateTime.parse("2026-10-23T10:00:00+09:00"), cursor);
    }

    private Per008IaEntryQuery.SourcePage page(String freshness,
            List<Per008IaEntryQuery.SourceItem> items, String nextCursor) {
        return new Per008IaEntryQuery.SourcePage(QueryState.COMPLETE, freshness, items,
                nextCursor, List.of());
    }

    private Per008IaEntryQuery.SourceItem item(CreateAction prior, boolean current,
            boolean ambiguous) {
        Per008IaEntryQuery.SourceItem source = sourceItem("performance:item-1", "성과 항목",
                "OPEN", validReference(), validStatus(), new StatusValue("LIMITED", 2),
                validTarget());
        return new Per008IaEntryQuery.SourceItem(source.itemPublicId(), source.title(),
                source.statusCode(), source.performanceAssignment(), source.dueStatus(),
                source.limitedProfile(), List.of(prior), source.feedbackTarget(), current,
                ambiguous);
    }

    private Per008IaEntryQuery.SourceItem sourceItem(String itemPublicId, String title,
            String status, ReferenceValue assignment, StatusValue due, StatusValue profile,
            FeedbackActionTarget target) {
        return new Per008IaEntryQuery.SourceItem(itemPublicId, title, status, assignment, due,
                profile, List.of(exactPriorAction()), target, true, false);
    }

    private ReferenceValue validReference() {
        return new ReferenceValue(ASSIGNMENT.toString(), "PRIMARY", "주 소속", 7);
    }

    private StatusValue validStatus() {
        return new StatusValue("DUE", 3);
    }

    private FeedbackActionTarget validTarget() {
        return new FeedbackActionTarget(CYCLE, WORKER, ASSIGNMENT, "scope-7", "auth-9");
    }

    private CreateAction exactPriorAction() {
        return new CreateAction("hris.action.per.create-goal", "POST",
                "/api/people/v1/hris/performance/goals", "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
    }

    private AuthorityEvidence authority() {
        return new AuthorityEvidence(7, 9, UUID.randomUUID(), WORKER, ASSIGNMENT,
                "route.hcm.team.performance.data", "team-performance-reader",
                "hcm.team.workbench.view", "TEAM_MANAGEMENT", "scope-7", "auth-9",
                "fields-3", "a".repeat(64), Map.of(
                "PERFORMANCE_ASSIGNMENT", FieldDecision.VIEW,
                "DUE_STATUS", FieldDecision.VIEW,
                "LIMITED_PROFILE", FieldDecision.VIEW,
                "PERFORMANCE_FEEDBACK_ACTION_TARGET", FieldDecision.VIEW),
                Set.of(new SubjectBinding(WORKER, ASSIGNMENT)), Set.of(PepLayer.values()));
    }
}
