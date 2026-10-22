package com.dwp.services.payroll.statutory.pay004;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.payroll.statutory.pay004.Pay004TestFixtures.MAPPER;
import static com.dwp.services.payroll.statutory.pay004.Pay004TestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class Pay004IaEntryQueryContractTest {

    @Test
    void routeFiltersSortsLimitAndCanonicalIdentityAreExact() {
        assertThat(Pay004Route.resolve(
                "GET", "/v1/workbench/pay-retirement",
                "route.hcm.operations.pay-retirement.page"))
                .contains(Pay004Route.RETIREMENT);
        assertThat(Pay004Route.resolve(
                "GET", "/v1/workbench/pay-retirement",
                "route.hcm.operations.pay-retirement.data"))
                .contains(Pay004Route.RETIREMENT);
        assertThat(Pay004Route.resolve(
                "POST", "/v1/workbench/pay-retirement",
                "route.hcm.operations.pay-retirement.data")).isEmpty();
        assertThat(Pay004Models.FILTERS).containsExactlyInAnyOrder(
                "filter[retirementResult][minimumAmount]",
                "filter[retirementResult][maximumAmount]",
                "filter[retirementResult][currency]",
                "filter[policyVersion][policyKeys]",
                "filter[policyVersion][minimumRevision]",
                "filter[caseStatus][codes]",
                "filter[caseStatus][minimumRevision]",
                "filter[effectiveOn]");
        assertThat(Pay004Models.SORTS).containsExactlyInAnyOrder(
                "retirementResult:ASC", "retirementResult:DESC",
                "policyVersion:ASC", "policyVersion:DESC",
                "caseStatus:ASC", "caseStatus:DESC",
                "itemPublicId:ASC", "itemPublicId:DESC");
        assertThatThrownBy(() -> Pay004TestFixtures.query(
                null, 100, Map.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort must contain between 1 and 3");
        assertThatThrownBy(() -> queryWithLocale("en-GB", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort must contain between 1 and 3");
        assertThatThrownBy(() -> Pay004TestFixtures.query(
                null, 0, Map.of(), List.of("itemPublicId:ASC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pay004TestFixtures.query(
                null, 101, Map.of(), List.of("itemPublicId:ASC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Pay004Models.CANONICAL_ROUTE)
                .isEqualTo("/hr/operations/payroll/retirement");
    }

    @Test
    void localeLengthIsExplicitlyBoundedBeforeCanonicalValidation() {
        assertThat(queryWithLocale("en", List.of("itemPublicId:ASC")).locale())
                .isEqualTo("en");
        assertThatThrownBy(() -> queryWithLocale(
                "e", List.of("itemPublicId:ASC")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 2 and 35 characters");
        assertThatThrownBy(() -> queryWithLocale(
                "a".repeat(36), List.of("itemPublicId:ASC")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 2 and 35 characters");
    }

    @Test
    void httpQueryRejectsMissingSortBeforeServiceDispatch() throws Exception {
        Pay004Service service = mock(Pay004Service.class);
        Pay004ScopeResolver scopes = mock(Pay004ScopeResolver.class);
        when(scopes.resolve(Pay004TestFixtures.actor().subject()))
                .thenReturn(Pay004TestFixtures.actor().scope());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new Pay004Controller(service, scopes))
                .setControllerAdvice(new Pay004ProblemAdvice())
                .build();

        Pay004RequestContext.set(Pay004TestFixtures.actor().subject());
        try {
            mvc.perform(get("/v1/workbench/pay-retirement")
                            .queryParam("filter", "{}")
                            .queryParam("limit", "25")
                            .queryParam("locale", "en-GB")
                            .queryParam("timeZone", "Europe/London")
                            .queryParam("correlationId",
                                    Pay004TestFixtures.CORRELATION.toString())
                            .header("X-Correlation-ID",
                                    Pay004TestFixtures.CORRELATION.toString()))
                    .andExpect(status().isBadRequest());
        } finally {
            Pay004RequestContext.clear();
        }
        verifyNoInteractions(service);
    }

    @Test
    void closedQueryRejectsUnknownAmbiguousAndUnstableInputs() {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        filters.put("filter[retirementResult][minimumAmount]", List.of("1.0000"));
        filters.put("filter[retirementResult][maximumAmount]",
                List.of("999999999999999999999.123456"));
        filters.put("filter[retirementResult][currency]", List.of("GBP"));
        filters.put("filter[policyVersion][policyKeys]", List.of("PAY.RETIREMENT"));
        filters.put("filter[policyVersion][minimumRevision]", List.of("2"));
        filters.put("filter[caseStatus][codes]", List.of("CALCULATED"));
        filters.put("filter[caseStatus][minimumRevision]", List.of("3"));
        filters.put("filter[effectiveOn]", List.of("2026-10-22"));
        assertThat(Pay004TestFixtures.query(
                null, 50, filters,
                List.of("retirementResult:DESC", "itemPublicId:ASC"))
                .filter()).isEqualTo(filters);

        assertThatThrownBy(() -> Pay004TestFixtures.query(
                null, 25, Map.of("filter[runId]", List.of("forbidden")),
                List.of("itemPublicId:ASC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pay004TestFixtures.query(
                null, 25,
                Map.of("filter[retirementResult][minimumAmount]", List.of("10"),
                        "filter[retirementResult][maximumAmount]", List.of("9")),
                List.of("itemPublicId:ASC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pay004TestFixtures.query(
                null, 25, Map.of(), List.of("caseStatus:ASC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pay004TestFixtures.query(
                null, 25,
                Map.of("filter[caseStatus][codes]",
                        List.of("NOT_A_RETIREMENT_STATE")),
                List.of("itemPublicId:ASC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pay004TestFixtures.query(
                null, 25, Map.of(),
                List.of("caseStatus:ASC", "caseStatus:DESC",
                        "itemPublicId:ASC")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serviceEmitsAllFourQueryStatesAndClosedWireShape() {
        Pay004Store store = mock(Pay004Store.class);
        Pay004Ports.ProjectionDecisionPort decisions = mock(
                Pay004Ports.ProjectionDecisionPort.class);
        when(decisions.decisions(
                Pay004TestFixtures.actor(), Pay004Models.VARIANT))
                .thenReturn(Pay004TestFixtures.allView());
        Pay004Service service = new Pay004Service(
                store, decisions, Pay004TestFixtures.canonical(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(store.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(), NOW, 0))
                .thenReturn(new Pay004Models.SourcePage(
                        Pay004Models.Availability.AVAILABLE,
                        List.of(Pay004TestFixtures.projection()), List.of()));
        Pay004Models.EntryQueryResponse complete = service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query());
        assertThat(complete.queryState().state())
                .isEqualTo(Pay004Models.QueryState.COMPLETE);
        assertThat(complete.items()).hasSize(1);

        when(store.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(), NOW, 0))
                .thenReturn(new Pay004Models.SourcePage(
                        Pay004Models.Availability.AVAILABLE, List.of(), List.of()));
        assertThat(service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query())
                .queryState().state()).isEqualTo(Pay004Models.QueryState.EMPTY);

        when(store.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(), NOW, 0))
                .thenReturn(new Pay004Models.SourcePage(
                        Pay004Models.Availability.PARTIAL,
                        List.of(Pay004TestFixtures.projection()),
                        List.of("POLICY_VERSION")));
        Pay004Models.EntryQueryResponse partial = service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query());
        assertThat(partial.queryState().state())
                .isEqualTo(Pay004Models.QueryState.PARTIAL);
        assertThat(partial.queryState().freshness()).isEqualTo("CURRENT");
        assertThat(partial.items().getFirst().fieldGroups())
                .doesNotContainKey("POLICY_VERSION");
        assertThat(partial.queryState().partialFailures().getFirst()
                .componentKey()).isEqualTo("pay004.policy-version");

        when(store.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(), NOW, 0))
                .thenReturn(Pay004Models.SourcePage.unavailable());
        Pay004Models.EntryQueryResponse unavailable = service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query());
        assertThat(unavailable.queryState().state())
                .isEqualTo(Pay004Models.QueryState.UNAVAILABLE);
        JsonNode json = MAPPER.valueToTree(unavailable);
        assertThat(json.fieldNames()).toIterable().containsExactly(
                "projectionVersion", "asOf", "variantKey", "queryState", "items");
        assertThat(json.get("queryState").get("correlationId").asText())
                .isEqualTo(Pay004TestFixtures.CORRELATION.toString());
        assertThat(json.get("queryState").get("fieldDecisionSummary")
                .fieldNames()).toIterable().containsExactly(
                "viewCount", "maskedCount", "omittedCount");

        when(store.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(), NOW, 0))
                .thenReturn(new Pay004Models.SourcePage(
                        Pay004Models.Availability.AVAILABLE,
                        List.of(Pay004TestFixtures.projection()), List.of()));
        JsonNode completeJson = MAPPER.valueToTree(service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query()));
        assertThat(completeJson.at(
                "/items/0/fieldGroups/POLICY_VERSION/value/policyKey")
                .asText()).matches("[a-z][a-z0-9._-]{1,127}");
    }

    @Test
    void maskedOrOmittedStatusNeverLeaksThroughTopLevelStatusCode() {
        Pay004Store store = mock(Pay004Store.class);
        Pay004Ports.ProjectionDecisionPort decisions = mock(
                Pay004Ports.ProjectionDecisionPort.class);
        when(decisions.decisions(
                Pay004TestFixtures.actor(), Pay004Models.VARIANT))
                .thenReturn(Map.of(
                        "RETIREMENT_RESULT", Pay004Models.Decision.VIEW,
                        "POLICY_VERSION", Pay004Models.Decision.VIEW,
                        "CASE_STATUS", Pay004Models.Decision.MASK));
        when(store.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(), NOW, 0))
                .thenReturn(new Pay004Models.SourcePage(
                        Pay004Models.Availability.AVAILABLE,
                        List.of(Pay004TestFixtures.projection()), List.of()));
        Pay004Service service = new Pay004Service(
                store, decisions, Pay004TestFixtures.canonical(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Pay004Models.EntryItem item = service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query())
                .items().getFirst();
        assertThat(item.statusCode()).isEqualTo("RESTRICTED");
        assertThat(item.fieldGroups().get("CASE_STATUS").decision())
                .isEqualTo("MASK");
    }

    @Test
    void maskedAndOmittedProducerFailuresNeverLeakAndMaskWins() {
        Pay004Store store = mock(Pay004Store.class);
        Pay004Ports.ProjectionDecisionPort decisions = mock(
                Pay004Ports.ProjectionDecisionPort.class);
        when(decisions.decisions(
                Pay004TestFixtures.actor(), Pay004Models.VARIANT))
                .thenReturn(Map.of(
                        "RETIREMENT_RESULT", Pay004Models.Decision.MASK,
                        "POLICY_VERSION", Pay004Models.Decision.OMIT,
                        "CASE_STATUS", Pay004Models.Decision.VIEW));
        when(store.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(), NOW, 0))
                .thenReturn(new Pay004Models.SourcePage(
                        Pay004Models.Availability.PARTIAL,
                        List.of(Pay004TestFixtures.projection()),
                        List.of("RETIREMENT_RESULT", "POLICY_VERSION")));
        Pay004Service service = new Pay004Service(
                store, decisions, Pay004TestFixtures.canonical(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Pay004Models.EntryQueryResponse response = service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query());

        assertThat(response.queryState().state())
                .isEqualTo(Pay004Models.QueryState.COMPLETE);
        assertThat(response.queryState().partialFailures()).isEmpty();
        assertThat(response.items().getFirst().fieldGroups())
                .containsOnlyKeys("RETIREMENT_RESULT", "CASE_STATUS");
        assertThat(response.items().getFirst().fieldGroups()
                .get("RETIREMENT_RESULT").decision()).isEqualTo("MASK");

        when(store.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(), NOW, 0))
                .thenReturn(new Pay004Models.SourcePage(
                        Pay004Models.Availability.PARTIAL,
                        List.of(Pay004TestFixtures.projection()),
                        List.of("RETIREMENT_RESULT", "POLICY_VERSION",
                                "CASE_STATUS")));
        Pay004Models.EntryQueryResponse mixed = service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query());

        assertThat(mixed.queryState().state())
                .isEqualTo(Pay004Models.QueryState.PARTIAL);
        assertThat(mixed.queryState().partialFailures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.componentKey())
                            .isEqualTo("pay004.case-status");
                    assertThat(failure.errorCode())
                            .isEqualTo("PRODUCER_UNAVAILABLE");
                });
        assertThat(mixed.items().getFirst().fieldGroups()
                .get("RETIREMENT_RESULT").decision()).isEqualTo("MASK");
    }

    @Test
    void maskedOrOmittedFieldsCannotLeakThroughFilterOrSort() {
        Pay004Store store = mock(Pay004Store.class);
        Pay004Ports.ProjectionDecisionPort decisions = mock(
                Pay004Ports.ProjectionDecisionPort.class);
        when(decisions.decisions(
                Pay004TestFixtures.actor(), Pay004Models.VARIANT))
                .thenReturn(Map.of(
                        "RETIREMENT_RESULT", Pay004Models.Decision.MASK,
                        "POLICY_VERSION", Pay004Models.Decision.OMIT,
                        "CASE_STATUS", Pay004Models.Decision.MASK));
        Pay004Service service = new Pay004Service(
                store, decisions, Pay004TestFixtures.canonical(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(
                        null, 25,
                        Map.of("filter[retirementResult][minimumAmount]",
                                List.of("1.0000")),
                        List.of("itemPublicId:ASC"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(
                        null, 25, Map.of(),
                        List.of("policyVersion:ASC", "itemPublicId:ASC"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(
                        null, 25,
                        Map.of("filter[effectiveOn]", List.of("2026-10-22")),
                        List.of("itemPublicId:ASC"))))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void emptyFieldProjectionFailsClosedBeforeSerialization() {
        Pay004Store store = mock(Pay004Store.class);
        Pay004Ports.ProjectionDecisionPort decisions = mock(
                Pay004Ports.ProjectionDecisionPort.class);
        when(decisions.decisions(
                Pay004TestFixtures.actor(), Pay004Models.VARIANT))
                .thenReturn(Map.of(
                        "RETIREMENT_RESULT", Pay004Models.Decision.OMIT,
                        "POLICY_VERSION", Pay004Models.Decision.OMIT,
                        "CASE_STATUS", Pay004Models.Decision.OMIT));
        Pay004Service service = new Pay004Service(
                store, decisions, Pay004TestFixtures.canonical(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void normalUncalculatedCaseOmitsAbsentResultWithoutPartialFailure() {
        Pay004Models.RetirementProjection draft =
                new Pay004Models.RetirementProjection(
                        1, UUID.fromString(
                        "00000000-0000-0000-0000-000000000499"),
                        Pay004TestFixtures.WORKER, 1,
                        RetirementYearEndCase.State.DRAFT,
                        RetirementYearEndCase.CountryPackState.NOT_REQUIRED,
                        LocalDate.parse("2026-10-22"), null, null, 0,
                        null, null);
        Pay004Store store = mock(Pay004Store.class);
        Pay004Ports.ProjectionDecisionPort decisions = mock(
                Pay004Ports.ProjectionDecisionPort.class);
        when(decisions.decisions(
                Pay004TestFixtures.actor(), Pay004Models.VARIANT))
                .thenReturn(Pay004TestFixtures.allView());
        when(store.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query(), NOW, 0))
                .thenReturn(new Pay004Models.SourcePage(
                        Pay004Models.Availability.AVAILABLE,
                        List.of(draft, Pay004TestFixtures.projection()),
                        List.of()));
        Pay004Service service = new Pay004Service(
                store, decisions, Pay004TestFixtures.canonical(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Pay004Models.EntryQueryResponse response = service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query());
        assertThat(response.queryState().state())
                .isEqualTo(Pay004Models.QueryState.COMPLETE);
        assertThat(response.queryState().partialFailures()).isEmpty();
        assertThat(response.items().getFirst().fieldGroups())
                .containsOnlyKeys("CASE_STATUS");
        assertThat(response.items().get(1).fieldGroups())
                .containsKeys("RETIREMENT_RESULT", "POLICY_VERSION",
                        "CASE_STATUS");
    }

    @Test
    void cursorIsSignedAndBoundToScopeRevisionFilterSortAndLimit() {
        Pay004Canonical canonical = Pay004TestFixtures.canonical();
        Pay004Models.QueryRequest query = Pay004TestFixtures.query();
        String binding = canonical.queryBinding(
                Pay004TestFixtures.actor(), query, NOW,
                Pay004TestFixtures.allView());
        String token = canonical.cursor(25, binding, NOW);
        assertThat(token).matches("[A-Za-z0-9_-]{16,2048}");
        assertThat(canonical.cursorState(token))
                .isEqualTo(new Pay004Canonical.CursorState(25, binding, NOW));
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;
        assertThatThrownBy(() -> canonical.cursorState(tampered))
                .isInstanceOf(BaseException.class);

        String changed = canonical.queryBinding(
                Pay004TestFixtures.actor(),
                Pay004TestFixtures.query(null, 10, Map.of(),
                        List.of("itemPublicId:ASC")), NOW,
                Pay004TestFixtures.allView());
        assertThat(changed).isNotEqualTo(binding);
    }

    @Test
    void sourceFailureGroupsAreClosedAndUnique() {
        assertThatThrownBy(() -> new Pay004Models.SourcePage(
                Pay004Models.Availability.PARTIAL,
                List.of(Pay004TestFixtures.projection()),
                List.of("UNKNOWN_GROUP")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pay004Models.SourcePage(
                Pay004Models.Availability.PARTIAL,
                List.of(Pay004TestFixtures.projection()),
                List.of("CASE_STATUS", "CASE_STATUS")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void jdbcStorePushesFilterSortAndBoundedPageIntoTheDatabase() {
        NamedParameterJdbcTemplate jdbc = mock(
                NamedParameterJdbcTemplate.class);
        doReturn("1").when(jdbc).queryForObject(
                anyString(), any(MapSqlParameterSource.class),
                eq(String.class));
        doReturn(List.of()).when(jdbc).query(
                anyString(), any(MapSqlParameterSource.class),
                any(RowMapper.class));
        JdbcPay004Store store = new JdbcPay004Store(jdbc);
        Map<String, List<String>> filters = Map.of(
                "filter[retirementResult][minimumAmount]",
                List.of("-10.0000"),
                "filter[retirementResult][maximumAmount]",
                List.of("100.0000"),
                "filter[retirementResult][currency]", List.of("KRW"),
                "filter[policyVersion][policyKeys]",
                List.of("kr.retirement"),
                "filter[policyVersion][minimumRevision]", List.of("2"),
                "filter[caseStatus][codes]", List.of("CALCULATED"),
                "filter[caseStatus][minimumRevision]", List.of("3"),
                "filter[effectiveOn]", List.of("2026-10-22"));

        Pay004Models.SourcePage page = store.retirement(
                Pay004TestFixtures.actor(),
                Pay004TestFixtures.query(null, 25, filters,
                        List.of("retirementResult:DESC",
                                "itemPublicId:ASC")),
                NOW, 50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(),
                any(RowMapper.class));
        assertThat(page.availability())
                .isEqualTo(Pay004Models.Availability.AVAILABLE);
        assertThat(sql.getValue())
                .contains("result.retirement_amount >= :minimumAmount")
                .contains("result.retirement_amount <= :maximumAmount")
                .contains("result.currency IN (:currencies)")
                .contains("result.policy_key IN (:policyKeys)")
                .contains("result.policy_revision >= :minimumPolicyRevision")
                .contains("head.lifecycle_state IN (:caseStates)")
                .contains("head.case_revision >= :minimumCaseRevision")
                .contains("head.effective_on = :effectiveOn")
                .contains("ORDER BY result.currency DESC NULLS FIRST")
                .contains("head.case_id ASC OFFSET :offset LIMIT :fetch");
        assertThat(parameters.getValue().getValue("offset")).isEqualTo(50);
        assertThat(parameters.getValue().getValue("fetch")).isEqualTo(26);
        assertThat(parameters.getValue().getValue("minimumAmount"))
                .isEqualTo(new java.math.BigDecimal("-10.0000"));
        assertThat(parameters.getValue().getValue("effectiveOn"))
                .isEqualTo(java.sql.Date.valueOf("2026-10-22"));
    }

    private Pay004Models.QueryRequest queryWithLocale(
            String locale, List<String> sort) {
        return new Pay004Models.QueryRequest(
                Pay004Models.VARIANT, null, null, Map.of(), 25, locale,
                sort, "Europe/London", Pay004TestFixtures.CORRELATION);
    }
}
