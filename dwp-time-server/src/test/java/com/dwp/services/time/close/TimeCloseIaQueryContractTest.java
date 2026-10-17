package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseIaQueryModels.AvailableAction;
import com.dwp.services.time.close.TimeCloseIaQueryModels.CloseProjectionRow;
import com.dwp.services.time.close.TimeCloseIaQueryModels.EffectiveRangeFilter;
import com.dwp.services.time.close.TimeCloseIaQueryModels.StatusFilter;
import com.dwp.services.time.close.TimeCloseIaQueryModels.TimeCloseProjection;
import com.dwp.services.time.close.TimeCloseIaQueryModels.TimeCloseQuery;
import com.dwp.services.time.close.TimeCloseModels.CloseAction;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

class TimeCloseIaQueryContractTest {

    private static final String CURSOR_SECRET = "tim-close-cursor-contract-secret";

    @ParameterizedTest
    @EnumSource(
            value = TimeCloseOwnerRoute.class,
            names = {"COMMAND_CENTER", "DAILY_CLOSE", "MONTHLY_CLOSE"})
    void emptyUnavailablePartialAndCompleteStatesHaveExactFailureInvariants(
            TimeCloseOwnerRoute route) {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        TimeCloseIaProjectionService service = service(repository);
        VerifiedCloseRequest authority = authority(route, Set.of(), Set.of(), false, 13L);
        TimeCloseQuery query = query(25, List.of("itemPublicId:ASC"), null);

        doReturn(List.of()).when(repository).queryProjectionRows(
                anyLong(), any(), any(), nullable(String.class), any());
        TimeCloseProjection empty = service.query(authority, route, query);

        doThrow(new IllegalStateException("repository unavailable"))
                .when(repository).queryProjectionRows(
                        anyLong(), any(), any(), nullable(String.class), any());
        TimeCloseProjection unavailable = service.query(authority, route, query);

        CloseProjectionRow valid = row(
                TimeCloseTestFixtures.PERIOD,
                route == TimeCloseOwnerRoute.MONTHLY_CLOSE ? "CLOSED" : "OPEN",
                LocalDate.parse("2026-10-01"));
        doReturn(Arrays.asList(valid, null)).when(repository).queryProjectionRows(
                anyLong(), any(), any(), nullable(String.class), any());
        TimeCloseProjection partial = service.query(authority, route, query);

        doReturn(List.of(valid)).when(repository).queryProjectionRows(
                anyLong(), any(), any(), nullable(String.class), any());
        TimeCloseProjection complete = service.query(authority, route, query);

        assertThat(empty.queryState().state()).isEqualTo("EMPTY");
        assertThat(empty.queryState().freshness()).isEqualTo("CURRENT");
        assertThat(empty.queryState().partialFailures()).isEmpty();
        assertThat(empty.items()).isEmpty();

        assertThat(unavailable.queryState().state()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.queryState().freshness()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.queryState().nextCursor()).isNull();
        assertThat(unavailable.items()).isEmpty();
        assertThat(unavailable.queryState().partialFailures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.componentKey()).isEqualTo("time.close.repository");
                    assertThat(failure.errorCode()).isEqualTo("TIME_CLOSE_QUERY_UNAVAILABLE");
                    assertThat(failure.correlationId())
                            .isEqualTo(TimeCloseTestFixtures.CORRELATION);
                    assertThat(failure.retryable()).isTrue();
                });

        assertThat(partial.queryState().state()).isEqualTo("PARTIAL");
        assertThat(partial.queryState().freshness()).isEqualTo("STALE");
        assertThat(partial.queryState().partialFailures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.errorCode()).isEqualTo("INVALID_CLOSE_SUMMARY");
                    assertThat(failure.retryable()).isFalse();
                });
        assertThat(partial.items()).hasSize(1);

        assertThat(complete.queryState().state()).isEqualTo("COMPLETE");
        assertThat(complete.queryState().freshness()).isEqualTo("CURRENT");
        assertThat(complete.queryState().partialFailures()).isEmpty();
        assertThat(complete.items()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = TimeCloseOwnerRoute.class,
            names = {"COMMAND_CENTER", "DAILY_CLOSE", "MONTHLY_CLOSE"})
    void variantsExposeOnlyTheirFieldGroupsDeepLinksAndAllowlistedActions(
            TimeCloseOwnerRoute route) {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        TimeCloseIaProjectionService service = service(repository);
        VerifiedCloseRequest authority = authority(route, Set.of(), Set.of(), true, 13L);
        String status = route == TimeCloseOwnerRoute.MONTHLY_CLOSE ? "CLOSED" : "OPEN";
        when(repository.queryProjectionRows(
                anyLong(), any(), any(), nullable(String.class), any()))
                .thenReturn(List.of(row(
                        TimeCloseTestFixtures.PERIOD, status,
                        LocalDate.parse("2026-10-01"))));

        TimeCloseProjection projection = service.query(
                authority, route, query(25, List.of("itemPublicId:ASC"), null));

        var item = projection.items().getFirst();
        assertThat(item.fieldGroups().keySet()).containsExactlyInAnyOrderElementsOf(
                route.fieldGroups());
        assertThat(item.fieldGroups().values())
                .allSatisfy(decision -> assertThat(decision.get("decision")).isEqualTo("VIEW"));
        assertThat(item.deepLink().route()).isEqualTo(switch (route) {
            case COMMAND_CENTER -> "/hr/operations/time/command-center";
            case DAILY_CLOSE -> "/hr/operations/time/daily-close";
            case MONTHLY_CLOSE -> "/hr/operations/time/monthly-close";
            default -> throw new AssertionError(route);
        });
        assertThat(item.deepLink().resourcePublicId())
                .isEqualTo(TimeCloseTestFixtures.PERIOD.toString());
        assertThat(item.availableActions()).extracting(AvailableAction::actionKey)
                .containsExactlyElementsOf(route == TimeCloseOwnerRoute.MONTHLY_CLOSE
                        ? List.of(
                                "hris.action.tim.close.reopen",
                                "hris.action.tim.handoff.create")
                        : List.of(
                                "hris.action.tim.close.validate",
                                "hris.action.tim.close.close"));
        assertThat(item.availableActions()).allSatisfy(action -> {
            assertThat(action.method()).isEqualTo("POST");
            assertThat(action.idempotencyPolicy()).isEqualTo("REQUIRED_IDEMPOTENCY_KEY");
            assertThat(action.stepUpPolicy())
                    .isEqualTo("OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
            assertThat(action.hrefTemplate()).startsWith("/api/time/v1/");
        });
    }

    @Test
    void cursorBindsScopeAndQueryAndPreservesStableTieOrdering() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        TimeCloseIaProjectionService service = service(repository);
        TimeCloseOwnerRoute route = TimeCloseOwnerRoute.COMMAND_CENTER;
        VerifiedCloseRequest authority = authority(route, Set.of(), Set.of(), false, 13L);
        UUID firstId = uuid("71000000-0000-4000-8000-000000000101");
        UUID secondId = uuid("71000000-0000-4000-8000-000000000102");
        UUID thirdId = uuid("71000000-0000-4000-8000-000000000103");
        when(repository.queryProjectionRows(
                anyLong(), any(), any(), nullable(String.class), any()))
                .thenReturn(List.of(
                        row(thirdId, "OPEN", LocalDate.parse("2026-10-01")),
                        row(firstId, "OPEN", LocalDate.parse("2026-10-01")),
                        row(secondId, "OPEN", LocalDate.parse("2026-10-01"))));
        TimeCloseQuery firstQuery = query(2, List.of("timePeriod:ASC"), null);

        TimeCloseProjection first = service.query(authority, route, firstQuery);
        String cursor = first.queryState().nextCursor();
        TimeCloseProjection second = service.query(
                authority, route, query(2, List.of("timePeriod:ASC"), cursor));

        assertThat(first.items()).extracting(item -> item.itemPublicId())
                .containsExactly(firstId.toString(), secondId.toString());
        assertThat(cursor).matches("[A-Za-z0-9_-]{16,1024}");
        assertThat(second.items()).extracting(item -> item.itemPublicId())
                .containsExactly(thirdId.toString());
        assertThat(second.queryState().nextCursor()).isNull();

        VerifiedCloseRequest changedScope = authority(
                route, Set.of(), Set.of(), false, 14L);
        assertThatThrownBy(() -> service.query(
                changedScope, route, query(2, List.of("timePeriod:ASC"), cursor)))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertThatThrownBy(() -> service.query(
                authority, route, query(2, List.of("itemPublicId:ASC"), cursor)))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
    }

    @Test
    void fieldDecisionsRejectConcealedFiltersAndLocaleTimeZoneAreCanonical() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        TimeCloseIaProjectionService service = service(repository);
        TimeCloseOwnerRoute route = TimeCloseOwnerRoute.MONTHLY_CLOSE;
        VerifiedCloseRequest concealed = authority(
                route, Set.of("CLOSE_STATUS"), Set.of("PAYROLL_HANDOFF"), false, 13L);
        when(repository.queryProjectionRows(
                anyLong(), any(), any(), nullable(String.class), any()))
                .thenReturn(List.of(row(
                        TimeCloseTestFixtures.PERIOD, "CLOSED",
                        LocalDate.parse("2026-10-01"))));

        TimeCloseProjection projection = service.query(
                concealed, route, query(25, List.of("itemPublicId:ASC"), null));

        var item = projection.items().getFirst();
        assertThat(item.fieldGroups()).containsOnlyKeys("TIME_PERIOD", "CLOSE_STATUS");
        assertThat(item.fieldGroups().get("CLOSE_STATUS"))
                .containsEntry("decision", "MASK")
                .containsEntry("value", null)
                .containsEntry("maskReason", "FIELD_POLICY_DENIED");
        assertThat(projection.queryState().fieldDecisionSummary().viewCount()).isEqualTo(1);
        assertThat(projection.queryState().fieldDecisionSummary().maskedCount()).isEqualTo(1);
        assertThat(projection.queryState().fieldDecisionSummary().omittedCount()).isEqualTo(1);

        TimeCloseQuery concealedFilter = new TimeCloseQuery(
                25, List.of("itemPublicId:ASC"), "en-US", "UTC",
                TimeCloseTestFixtures.CORRELATION, null, null, null,
                new EffectiveRangeFilter(null, null, null), null,
                new StatusFilter(List.of("CLOSED"), null), null);
        assertThatThrownBy(() -> service.query(concealed, route, concealedFilter))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        VerifiedCloseRequest visible = authority(route, Set.of(), Set.of(), false, 13L);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/hris/workbench/time-monthly-close");
        request.addParameter("limit", "25");
        request.addParameter("sort", "timePeriod:ASC", "itemPublicId:ASC");
        request.addParameter("locale", "en-us");
        request.addParameter("timeZone", "Asia/Seoul");
        request.addParameter("correlationId", TimeCloseTestFixtures.CORRELATION.toString());

        TimeCloseQuery parsed = service.parse(
                route, visible, request, TimeCloseTestFixtures.CORRELATION);

        assertThat(parsed.locale()).isEqualTo("en-US");
        assertThat(parsed.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(parsed.sort()).containsExactly("timePeriod:ASC", "itemPublicId:ASC");
    }

    @Test
    void maskAndOmitCloseStatusAreNonInterferingAcrossStatusValues() {
        LocalDate start = LocalDate.parse("2026-10-01");

        for (TimeCloseOwnerRoute route :
                List.of(TimeCloseOwnerRoute.DAILY_CLOSE, TimeCloseOwnerRoute.MONTHLY_CLOSE)) {
            String closeScope = route == TimeCloseOwnerRoute.DAILY_CLOSE ? "DAY" : "MONTH";
            for (boolean mask : List.of(true, false)) {
                Set<String> masked = mask ? Set.of("CLOSE_STATUS") : Set.of();
                Set<String> omitted = mask ? Set.of() : Set.of("CLOSE_STATUS");
                VerifiedCloseRequest authority =
                        authority(route, masked, omitted, true, 13L);
                TimeCloseRepository repository = mock(TimeCloseRepository.class);
                TimeCloseIaProjectionService service = service(repository);
                when(repository.queryProjectionRows(
                        anyLong(), any(), any(), nullable(String.class), any()))
                        .thenReturn(List.of(row(
                                TimeCloseTestFixtures.PERIOD, closeScope,
                                "REOPENED", start, 3L, 0, 4)))
                        .thenReturn(List.of(row(
                                TimeCloseTestFixtures.PERIOD, closeScope,
                                "CLOSED", start, 3L, 0, 4)));

                TimeCloseProjection reopened = service.query(
                        authority, route, query(25, List.of("itemPublicId:ASC"), null));
                TimeCloseProjection closed = service.query(
                        authority, route, query(25, List.of("itemPublicId:ASC"), null));

                assertThat(closed).as("%s %s", route, mask ? "MASK" : "OMIT")
                        .isEqualTo(reopened);
                assertThat(reopened.items()).singleElement().satisfies(item -> {
                    assertThat(item.statusCode()).isEqualTo("RESTRICTED");
                    assertThat(item.availableActions()).isEmpty();
                });
            }
        }
    }

    @Test
    void maskAndOmitExceptionSummaryDoNotLeakBlockersThroughCloseActions() {
        LocalDate start = LocalDate.parse("2026-10-01");
        TimeCloseOwnerRoute route = TimeCloseOwnerRoute.COMMAND_CENTER;

        for (boolean mask : List.of(true, false)) {
            Set<String> masked = mask ? Set.of("EXCEPTION_SUMMARY") : Set.of();
            Set<String> omitted = mask ? Set.of() : Set.of("EXCEPTION_SUMMARY");
            VerifiedCloseRequest authority = authority(route, masked, omitted, true, 13L);
            TimeCloseRepository repository = mock(TimeCloseRepository.class);
            TimeCloseIaProjectionService service = service(repository);
            when(repository.queryProjectionRows(
                    anyLong(), any(), any(), nullable(String.class), any()))
                    .thenReturn(List.of(row(
                            TimeCloseTestFixtures.PERIOD, "DAY",
                            "REOPENED", start, 3L, 0, 4)))
                    .thenReturn(List.of(row(
                            TimeCloseTestFixtures.PERIOD, "DAY",
                            "REOPENED", start, 3L, 7, 4)));

            TimeCloseProjection noBlockers = service.query(
                    authority, route, query(25, List.of("itemPublicId:ASC"), null));
            TimeCloseProjection blockers = service.query(
                    authority, route, query(25, List.of("itemPublicId:ASC"), null));

            assertThat(blockers).as(mask ? "MASK" : "OMIT").isEqualTo(noBlockers);
            assertThat(noBlockers.items()).singleElement().satisfies(item ->
                    assertThat(item.availableActions())
                            .extracting(AvailableAction::actionKey)
                            .containsExactly("hris.action.tim.close.validate"));
        }
    }

    @Test
    void maskAndOmitImpactSummaryDoNotInfluenceAnyPublicFieldOrAction() {
        LocalDate start = LocalDate.parse("2026-10-01");
        TimeCloseOwnerRoute route = TimeCloseOwnerRoute.DAILY_CLOSE;

        for (boolean mask : List.of(true, false)) {
            Set<String> masked = mask ? Set.of("IMPACT_SUMMARY") : Set.of();
            Set<String> omitted = mask ? Set.of() : Set.of("IMPACT_SUMMARY");
            VerifiedCloseRequest authority = authority(route, masked, omitted, true, 13L);
            TimeCloseRepository repository = mock(TimeCloseRepository.class);
            TimeCloseIaProjectionService service = service(repository);
            when(repository.queryProjectionRows(
                    anyLong(), any(), any(), nullable(String.class), any()))
                    .thenReturn(List.of(row(
                            TimeCloseTestFixtures.PERIOD, "DAY",
                            "REOPENED", start, 3L, 0, 0)))
                    .thenReturn(List.of(row(
                            TimeCloseTestFixtures.PERIOD, "DAY",
                            "REOPENED", start, 3L, 0, 91)));

            TimeCloseProjection zeroImpact = service.query(
                    authority, route, query(25, List.of("itemPublicId:ASC"), null));
            TimeCloseProjection highImpact = service.query(
                    authority, route, query(25, List.of("itemPublicId:ASC"), null));

            assertThat(highImpact).as(mask ? "MASK" : "OMIT").isEqualTo(zeroImpact);
            assertThat(zeroImpact.items()).singleElement().satisfies(item ->
                    assertThat(item.availableActions())
                            .extracting(AvailableAction::actionKey)
                            .containsExactly(
                                    "hris.action.tim.close.validate",
                                    "hris.action.tim.close.close"));
        }
    }

    @Test
    void duplicateSortAndFilterValuesAreRejected() {
        TimeCloseIaProjectionService service = service(mock(TimeCloseRepository.class));
        TimeCloseOwnerRoute route = TimeCloseOwnerRoute.MONTHLY_CLOSE;
        VerifiedCloseRequest authority = authority(route, Set.of(), Set.of(), false, 13L);
        MockHttpServletRequest duplicateSort = queryRequest(route);
        duplicateSort.addParameter("sort", "itemPublicId:ASC", "itemPublicId:ASC");

        assertThatThrownBy(() -> service.parse(
                route, authority, duplicateSort, TimeCloseTestFixtures.CORRELATION))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        MockHttpServletRequest duplicateFilter = queryRequest(route);
        duplicateFilter.addParameter("sort", "itemPublicId:ASC");
        duplicateFilter.addParameter(
                "filter[closeStatus][codes]", "CLOSED", "CLOSED");

        assertThatThrownBy(() -> service.parse(
                route, authority, duplicateFilter, TimeCloseTestFixtures.CORRELATION))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void fullFieldOmissionFailsClosedInsteadOfEmittingEmptyFieldGroups() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        TimeCloseIaProjectionService service = service(repository);
        TimeCloseOwnerRoute route = TimeCloseOwnerRoute.COMMAND_CENTER;
        VerifiedCloseRequest authority = authority(
                route, Set.of(), route.fieldGroups(), false, 13L);
        when(repository.queryProjectionRows(
                anyLong(), any(), any(), nullable(String.class), any()))
                .thenReturn(List.of(row(
                        TimeCloseTestFixtures.PERIOD, "OPEN",
                        LocalDate.parse("2026-10-01"))));

        assertThatThrownBy(() -> service.query(
                authority, route, query(25, List.of("itemPublicId:ASC"), null)))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void malformedIaInputRendersSchemaProblemWithNoStoreAndStableCorrelation() {
        TimeCloseOwnerProblemAdvice advice = new TimeCloseOwnerProblemAdvice();
        VerifiedCloseRequest authority = authority(
                TimeCloseOwnerRoute.COMMAND_CENTER, Set.of(), Set.of(), false, 13L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                TimeCloseOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE, authority);

        var response = advice.domain(
                new BaseException(ErrorCode.INVALID_INPUT_VALUE, "malformed IA query"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull().containsEntry("code", "SCHEMA_INVALID")
                .containsEntry("correlationId", TimeCloseTestFixtures.CORRELATION)
                .containsEntry("retryable", false);
    }

    private static TimeCloseIaProjectionService service(TimeCloseRepository repository) {
        return new TimeCloseIaProjectionService(
                repository, Clock.fixed(TimeCloseTestFixtures.NOW, ZoneOffset.UTC), CURSOR_SECRET);
    }

    private static TimeCloseQuery query(int limit, List<String> sort, String cursor) {
        return new TimeCloseQuery(
                limit, sort, "en-US", "UTC", TimeCloseTestFixtures.CORRELATION,
                null, cursor, null, new EffectiveRangeFilter(null, null, null),
                null, null, null);
    }

    private static MockHttpServletRequest queryRequest(TimeCloseOwnerRoute route) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/hris/workbench/time-" + switch (route) {
                    case COMMAND_CENTER -> "command-center";
                    case DAILY_CLOSE -> "daily-close";
                    case MONTHLY_CLOSE -> "monthly-close";
                    default -> throw new AssertionError(route);
                });
        request.addParameter("limit", "25");
        request.addParameter("locale", "en-US");
        request.addParameter("timeZone", "UTC");
        request.addParameter(
                "correlationId", TimeCloseTestFixtures.CORRELATION.toString());
        return request;
    }

    private static VerifiedCloseRequest authority(
            TimeCloseOwnerRoute route,
            Set<String> masked,
            Set<String> omitted,
            boolean includeActions,
            long fieldPolicyRevision) {
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        permissions.add("APP.HCM:VIEW");
        permissions.add(route.permission());
        LinkedHashSet<String> responsibilities = new LinkedHashSet<>();
        if (includeActions) {
            permissions.add("ACTION.HRIS_TIME_CLOSE:EXECUTE");
            permissions.add("ACTION.HRIS_TIME_REOPEN:MANAGE");
            responsibilities.add("HRIS_DUTY_TIME_CLOSE_OPERATOR");
            responsibilities.add("HRIS_DUTY_TIME_REOPEN_OPERATOR");
        }
        return new VerifiedCloseRequest(
                TimeCloseTestFixtures.TENANT, 72L, TimeCloseTestFixtures.SUBJECT,
                Set.copyOf(permissions), Set.copyOf(responsibilities), CloseAction.QUERY,
                route.routeContractKey(), "psc-" + "2".repeat(64), "scope-key", "NORMAL",
                TimeCloseTestFixtures.POPULATION, 9L, 11L,
                TimeCloseTestFixtures.SCOPE_DIGEST, TimeCloseTestFixtures.LEGAL_ENTITY,
                TimeCloseTestFixtures.TIME_GROUP, fieldPolicyRevision, 17L,
                TimeCloseTestFixtures.DECISION_REVISION, route.purpose(),
                TimeCloseTestFixtures.NOW.plusSeconds(300),
                TimeCloseTestFixtures.CORRELATION, masked, omitted);
    }

    private static CloseProjectionRow row(UUID id, String status, LocalDate start) {
        return row(
                id, "CLOSED".equals(status) ? "MONTH" : "DAY", status, start,
                "CLOSED".equals(status) ? 3L : 0L, 0, 4);
    }

    private static CloseProjectionRow row(
            UUID id,
            String closeScope,
            String status,
            LocalDate start,
            long closeRevision,
            int blockerCount,
            int impactCount) {
        return new CloseProjectionRow(
                id, closeScope, start, start.plusDays(1), status, closeRevision,
                7L, TimeCloseTestFixtures.NOW.minusSeconds(3_600),
                TimeCloseTestFixtures.NOW.minusSeconds(60), blockerCount, impactCount,
                TimeCloseTestFixtures.RECEIPT, "SUCCEEDED",
                TimeCloseTestFixtures.NOW.minusSeconds(120),
                TimeCloseTestFixtures.NOW.minusSeconds(110), 7L,
                TimeCloseTestFixtures.HANDOFF, "PENDING",
                TimeCloseTestFixtures.NOW.minusSeconds(100),
                TimeCloseTestFixtures.NOW.minusSeconds(90), 1L);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
