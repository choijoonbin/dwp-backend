package com.dwp.services.people.hr.performance.populationfreeze;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerformancePerIaEntryQueryContractTest {

    private static final Instant NOW = Instant.parse("2027-04-01T00:00:00Z");
    private static final long TENANT_ID = 77L;
    private static final long ACTOR_ID = 19L;
    private static final String CURSOR_SECRET =
            "performance-review-operations-test-secret";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final UUID subjectId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final PerformanceReviewOperationsRepository repository =
            mock(PerformanceReviewOperationsRepository.class);
    private final PopulationFreezeAuthorityPort authority =
            mock(PopulationFreezeAuthorityPort.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final PerformanceReviewOperationsCursorCodec cursors =
            new PerformanceReviewOperationsCursorCodec(
                    CURSOR_SECRET, Duration.ofMinutes(15), objectMapper,
                    Clock.fixed(NOW, ZoneOffset.UTC));
    private PerformanceReviewOperationsService service;

    @BeforeEach
    void setUp() {
        PeopleRequestContext.set(
                ACTOR_ID, TENANT_ID, subjectId, Set.of("HRIS_PERFORMANCE_OPERATOR"),
                Set.of("APP.HRIS:VIEW", "DATA.HR_TALENT:VIEW",
                        "DATA.HR_TALENT:UPDATE"));
        when(authority.authorize(any())).thenReturn(authorityEvidence(31L));
        when(repository.query(any())).thenReturn(
                new PerformanceReviewOperationsRepository.Page(List.of(), false));
        service = service(() -> authority);
    }

    @AfterEach
    void tearDown() {
        PeopleRequestContext.clear();
    }

    @Test
    void emptyUnavailableHaveNoItems() {
        PerformanceReviewOperationsDtos.Projection empty = service.query(request());

        assertThat(empty.queryState().state()).isEqualTo("EMPTY");
        assertThat(empty.items()).isEmpty();
        PerformanceReviewOperationsService unavailable = service(() -> null);
        assertCode(() -> unavailable.query(request()),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void partialRequiresFailure() {
        PerformanceReviewOperationsDtos.QueryState invalid = state(
                "PARTIAL", List.of());
        PerformanceReviewOperationsDtos.QueryState valid = state(
                "PARTIAL", List.of(new PerformanceReviewOperationsDtos.PartialFailure(
                        "performance.preview", "OWNER_UNAVAILABLE", correlationId, true)));

        assertThat(stateInvariant(invalid, List.of())).isFalse();
        assertThat(stateInvariant(valid, List.of())).isTrue();
    }

    @Test
    void completeHasNoFailure() {
        when(repository.query(any())).thenReturn(page(row(1), false));

        PerformanceReviewOperationsDtos.Projection projection = service.query(request());

        assertThat(projection.queryState().state()).isEqualTo("COMPLETE");
        assertThat(projection.queryState().partialFailures()).isEmpty();
        assertThat(stateInvariant(projection.queryState(), projection.items())).isTrue();
    }

    @Test
    void cursorBindsScopeAndRevision() {
        String fingerprint = PerformanceReviewOperationsCursorCodec.fingerprint("request");
        String cursor = cursors.issue(TENANT_ID, 25L, fingerprint, "scope:one");

        assertThat(cursors.verify(cursor, TENANT_ID, fingerprint, "scope:one"))
                .isEqualTo(25L);
        assertCode(() -> cursors.verify(
                cursor, TENANT_ID, fingerprint, "scope:changed"), ErrorCode.FORBIDDEN);
        assertCode(() -> cursors.verify(
                cursor, TENANT_ID + 1, fingerprint, "scope:one"), ErrorCode.FORBIDDEN);
    }

    @Test
    void pageHonorsLimitAndStableOrder() {
        when(repository.query(any())).thenReturn(page(row(1), true));
        PerformanceReviewOperationsDtos.QueryRequest first = new PerformanceReviewOperationsDtos.QueryRequest(
                1, PerformanceReviewOperationsDtos.Filter.empty(),
                List.of("dueStatus:DESC", "itemPublicId:ASC"),
                "ko-KR", "Asia/Seoul", correlationId, NOW, null);

        PerformanceReviewOperationsDtos.Projection firstPage = service.query(first);
        ArgumentCaptor<PerformanceReviewOperationsRepository.QueryCriteria> criteria =
                ArgumentCaptor.forClass(
                        PerformanceReviewOperationsRepository.QueryCriteria.class);
        verify(repository).query(criteria.capture());

        assertThat(criteria.getValue().limit()).isEqualTo(1);
        assertThat(criteria.getValue().offset()).isZero();
        assertThat(criteria.getValue().asOf()).isEqualTo(NOW);
        assertThat(criteria.getValue().sort())
                .containsExactly("dueStatus:DESC", "itemPublicId:ASC");
        assertThat(firstPage.queryState().nextCursor()).matches("[A-Za-z0-9_-]{16,1024}");

        when(repository.query(any())).thenReturn(page(row(2), false));
        service.query(new PerformanceReviewOperationsDtos.QueryRequest(
                1, PerformanceReviewOperationsDtos.Filter.empty(),
                List.of("dueStatus:DESC", "itemPublicId:ASC"),
                "ko-KR", "Asia/Seoul", UUID.randomUUID(), NOW,
                firstPage.queryState().nextCursor()));
        verify(repository, org.mockito.Mockito.times(2)).query(criteria.capture());
        assertThat(criteria.getAllValues().getLast().offset()).isEqualTo(1L);
    }

    @Test
    void fieldSummaryMatchesSerializedDecisions() {
        when(repository.query(any())).thenReturn(page(row(1), false));

        PerformanceReviewOperationsDtos.Projection projection = service.query(request());
        PerformanceReviewOperationsDtos.FieldDecisionSummary summary =
                projection.queryState().fieldDecisionSummary();

        assertThat(summary.viewCount()).isEqualTo(3);
        assertThat(summary.maskedCount()).isZero();
        assertThat(summary.omittedCount()).isZero();
        assertThat(objectMapper.valueToTree(projection.items().getFirst().fieldGroups()).size())
                .isEqualTo(summary.viewCount());
    }

    @Test
    void deepLinksAndActionsAreAllowlisted() {
        when(repository.query(any())).thenReturn(page(row(1), false));

        PerformanceReviewOperationsDtos.Item item = service.query(request()).items().getFirst();

        assertThat(item.deepLink().route())
                .isEqualTo("/hr/operations/performance/review-operations");
        assertThat(item.deepLink().resourcePublicId()).isEqualTo(item.fieldGroups()
                .REVIEW_ASSIGNMENT() instanceof PerformanceReviewOperationsDtos.ViewedReferenceField field
                ? field.value().publicId() : null);
        assertThat(item.availableActions())
                .extracting(PerformanceReviewOperationsDtos.AvailableAction::actionKey)
                .containsExactly(
                        "hris.action.per.preview-population",
                        "hris.action.per.freeze-population");
        assertThat(item.availableActions())
                .allSatisfy(action -> {
                    assertThat(action.method()).isEqualTo("POST");
                    assertThat(action.idempotencyPolicy())
                            .isEqualTo("REQUIRED_IDEMPOTENCY_KEY");
                    assertThat(action.stepUpPolicy())
                            .isEqualTo("OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
                });
    }

    @Test
    void trustedTenantContextIsNotSerialized() throws Exception {
        when(repository.query(any())).thenReturn(page(row(1), false));

        String json = objectMapper.writeValueAsString(service.query(request()));

        assertThat(json)
                .doesNotContain("tenantId")
                .doesNotContain(Long.toString(TENANT_ID))
                .doesNotContain(subjectId.toString());
    }

    @Test
    void localeAndTimeZoneAreCanonical() {
        LocalDate effectiveOn = LocalDate.of(2027, 4, 2);
        PerformanceReviewOperationsDtos.Filter filter =
                new PerformanceReviewOperationsDtos.Filter(null, null, null, effectiveOn);
        when(repository.query(any())).thenReturn(page(row(1), true));

        PerformanceReviewOperationsDtos.Projection first = service.query(
                new PerformanceReviewOperationsDtos.QueryRequest(
                        1, filter, List.of("itemPublicId:ASC"),
                        "ko_kr", "Asia/Seoul", correlationId, NOW, null));
        ArgumentCaptor<PerformanceReviewOperationsRepository.QueryCriteria> criteria =
                ArgumentCaptor.forClass(
                        PerformanceReviewOperationsRepository.QueryCriteria.class);
        verify(repository).query(criteria.capture());
        assertThat(criteria.getValue().effectiveStart())
                .isEqualTo(Instant.parse("2027-04-01T15:00:00Z"));
        assertThat(criteria.getValue().effectiveEnd())
                .isEqualTo(Instant.parse("2027-04-02T15:00:00Z"));

        when(repository.query(any())).thenReturn(page(row(2), false));
        service.query(new PerformanceReviewOperationsDtos.QueryRequest(
                1, filter, List.of("itemPublicId:ASC"),
                "ko-KR", "Asia/Seoul", UUID.randomUUID(), NOW,
                first.queryState().nextCursor()));
    }

    @Test
    void fieldDecisionViewMaskOmitSemantics() throws Exception {
        PerformanceReviewOperationsDtos.FieldGroups groups =
                new PerformanceReviewOperationsDtos.FieldGroups(
                        new PerformanceReviewOperationsDtos.ViewedReferenceField(
                                "VIEW", new PerformanceReviewOperationsDtos.ReferenceValue(
                                "cycle:1", "PERFORMANCE_REVIEW", "Review", 1)),
                        new PerformanceReviewOperationsDtos.MaskedField(
                                "MASK", null, "SENSITIVE_FIELD",
                                "hris.field.masked"),
                        null);

        JsonNode value = objectMapper.readTree(objectMapper.writeValueAsBytes(groups));

        assertThat(value.path("REVIEW_ASSIGNMENT").path("decision").asText())
                .isEqualTo("VIEW");
        assertThat(value.path("WORK_QUEUE").path("decision").asText())
                .isEqualTo("MASK");
        assertThat(value.path("WORK_QUEUE").has("value")).isTrue();
        assertThat(value.path("WORK_QUEUE").path("value").isNull()).isTrue();
        assertThat(value.has("DUE_STATUS")).isFalse();
    }

    @Test
    void problemStatusAndCorrelationAreClosed() {
        PerformanceReviewOperationsDtos.QueryRequest invalid =
                new PerformanceReviewOperationsDtos.QueryRequest(
                        101, PerformanceReviewOperationsDtos.Filter.empty(),
                        List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                        correlationId, NOW, null);

        assertCode(() -> service.query(invalid), ErrorCode.INVALID_INPUT_VALUE);
        verify(repository, never()).query(any());
        PerformanceReviewOperationsDtos.Projection projection = service.query(request());
        assertThat(projection.queryState().correlationId()).isEqualTo(correlationId);
    }

    private PerformanceReviewOperationsService service(
            java.util.function.Supplier<PopulationFreezeAuthorityPort> authoritySupplier) {
        return new PerformanceReviewOperationsService(
                repository, cursors, audit, Clock.fixed(NOW, ZoneOffset.UTC),
                authoritySupplier);
    }

    private PerformanceReviewOperationsDtos.QueryRequest request() {
        return new PerformanceReviewOperationsDtos.QueryRequest(
                25, PerformanceReviewOperationsDtos.Filter.empty(),
                List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                correlationId, NOW, null);
    }

    private PerformanceReviewOperationsRepository.Page page(
            PerformanceReviewOperationsRepository.ReviewOperationRow row,
            boolean hasMore) {
        return new PerformanceReviewOperationsRepository.Page(List.of(row), hasMore);
    }

    private PerformanceReviewOperationsRepository.ReviewOperationRow row(int ordinal) {
        UUID cycleId = new UUID(0, ordinal);
        return new PerformanceReviewOperationsRepository.ReviewOperationRow(
                cycleId, "FY27 Review " + ordinal, "VALIDATED", 4L,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "READY", 12, NOW.minusSeconds(60), NOW.plusSeconds(3_600),
                null, "READY_TO_FREEZE", "WITHIN_SLA");
    }

    private PopulationFreezeAuthorityPort.AuthorityEvidence authorityEvidence(long revision) {
        return new PopulationFreezeAuthorityPort.AuthorityEvidence(
                TENANT_ID, ACTOR_ID, subjectId,
                PerformanceReviewOperationsService.APPLICATION_ENTITLEMENT,
                PerformanceReviewOperationsService.RESOURCE,
                PerformanceReviewOperationsService.AUTHORITY_ACTION,
                PerformanceReviewOperationsService.QUERY_OPERATION,
                PerformanceReviewOperationsService.QUERY_PURPOSE,
                "a".repeat(64), 11L, revision);
    }

    private PerformanceReviewOperationsDtos.QueryState state(
            String state,
            List<PerformanceReviewOperationsDtos.PartialFailure> failures) {
        return new PerformanceReviewOperationsDtos.QueryState(
                state, "scope:" + "a".repeat(64),
                "PARTIAL".equals(state) ? "STALE" : "CURRENT", null,
                new PerformanceReviewOperationsDtos.FieldDecisionSummary(0, 0, 0),
                failures, correlationId,
                new PerformanceReviewOperationsDtos.CachePolicy(
                        "auth:1", "dwp.hris.access.assignment.changed.v1", 0));
    }

    private boolean stateInvariant(
            PerformanceReviewOperationsDtos.QueryState state,
            List<PerformanceReviewOperationsDtos.Item> items) {
        if (List.of("EMPTY", "UNAVAILABLE").contains(state.state()) && !items.isEmpty()) {
            return false;
        }
        if ("PARTIAL".equals(state.state()) && state.partialFailures().isEmpty()) {
            return false;
        }
        return !"COMPLETE".equals(state.state()) || state.partialFailures().isEmpty();
    }

    private void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(expected);
    }
}
