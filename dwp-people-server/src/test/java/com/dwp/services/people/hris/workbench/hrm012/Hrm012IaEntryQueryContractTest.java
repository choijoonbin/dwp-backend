package com.dwp.services.people.hris.workbench.hrm012;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Hrm012IaEntryQueryContractTest {
    private static final long TENANT = 71L;
    private static final Instant NOW = Instant.parse("2026-10-22T01:00:00Z");
    private static final UUID PERSON = UUID.fromString(
            "11111111-1111-4111-8111-111111111111");
    private static final UUID RUN = UUID.fromString(
            "22222222-2222-4222-8222-222222222222");
    private static final UUID RECEIPT = UUID.fromString(
            "33333333-3333-4333-8333-333333333333");
    private static final UUID CORRELATION = UUID.fromString(
            "44444444-4444-4444-8444-444444444444");
    private static final UUID ORGANIZATION = UUID.fromString(
            "55555555-5555-4555-8555-555555555555");

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void projectionIsExactRedactedAndCompatibleWithTheStrictFrontendContract()
            throws Exception {
        Fixture fixture = fixture(allView());
        when(fixture.source().read(eq(TENANT), any(), eq(fixture.scope())))
                .thenReturn(page(List.of(row()), List.of()));

        Hrm012QueryModels.Projection projection = fixture.service().query(request()).projection();
        JsonNode json = fixture.mapper().valueToTree(projection);

        assertThat(projection.queryState().state()).isEqualTo("COMPLETE");
        assertThat(projection.queryState().cachePolicy().invalidationEvent())
                .isEqualTo("dwp.hris.access.assignment.changed.v1");
        assertThat(json.path("items").get(0).properties().stream()
                .map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "itemPublicId", "title", "statusCode", "fieldGroups",
                        "deepLink", "availableActions");
        assertThat(json.path("items").get(0).has("resourceVersion")).isFalse();
        assertThat(json.path("items").get(0).path("availableActions").isEmpty()).isTrue();
        assertThat(json.path("items").get(0)
                .path("fieldGroups").path("QUALITY_FINDING")
                .path("value").path("evidenceDigest").asText())
                .matches("[a-f0-9]{64}");
        assertThat(fixture.mapper().writeValueAsString(projection))
                .doesNotContain("tenantId", "legalEntityPublicIds", "ciphertext",
                        "keyReference", "sourceRecordKey");
    }

    @Test
    void rowsAndRedactedComponentFailureProducePartialWhileSourceFailureIsUnavailable() {
        Fixture partial = fixture(allView());
        when(partial.source().read(eq(TENANT), any(), eq(partial.scope())))
                .thenReturn(page(List.of(row()), List.of(
                        new Hrm012QuerySource.ComponentFailure(
                                "finding-evidence", "HRM012_FINDING_DEGRADED", true))));

        Hrm012QueryModels.Projection partialProjection =
                partial.service().query(request()).projection();

        assertThat(partialProjection.queryState().state()).isEqualTo("PARTIAL");
        assertThat(partialProjection.items()).hasSize(1);
        assertThat(partialProjection.queryState().partialFailures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.componentKey()).isEqualTo("finding-evidence");
                    assertThat(failure.errorCode()).isEqualTo("HRM012_FINDING_DEGRADED");
                    assertThat(failure.correlationId()).isEqualTo(CORRELATION);
                });

        Fixture unavailable = fixture(allView());
        when(unavailable.source().read(eq(TENANT), any(), eq(unavailable.scope())))
                .thenThrow(new IllegalStateException("must not cross boundary"));

        Hrm012QueryModels.Projection unavailableProjection =
                unavailable.service().query(request()).projection();

        assertThat(unavailableProjection.queryState().state()).isEqualTo("UNAVAILABLE");
        assertThat(unavailableProjection.queryState().freshness()).isEqualTo("UNAVAILABLE");
        assertThat(unavailableProjection.queryState().partialFailures())
                .extracting(Hrm012QueryModels.PartialFailure::errorCode)
                .containsExactly("HRM012_SOURCE_UNAVAILABLE");
    }

    @Test
    void mutableRowsAfterAsOfFailClosedInsteadOfLeakingOrDestabilizingPages() {
        Fixture fixture = fixture(allView());
        when(fixture.source().read(eq(TENANT), any(), eq(fixture.scope())))
                .thenReturn(page(List.of(row()), List.of(), true));

        assertThatThrownBy(() -> fixture.service().query(request()))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> {
                    Hrm012Problem problem = (Hrm012Problem) error;
                    assertThat(problem.status()).isEqualTo(409);
                    assertThat(problem.code()).isEqualTo("HRM012_QUERY_SNAPSHOT_STALE");
                });
    }

    @Test
    void viewMaskOmitDecisionsApplyBeforeSerializationAndActionsStayEmpty() {
        Fixture fixture = fixture(Map.of(
                "INGESTION_STATUS", Hrm012QueryAuthority.FieldDecision.VIEW,
                "QUALITY_FINDING", Hrm012QueryAuthority.FieldDecision.MASK,
                "COMMAND_RECEIPT", Hrm012QueryAuthority.FieldDecision.OMIT));
        when(fixture.source().read(eq(TENANT), any(), eq(fixture.scope())))
                .thenReturn(page(List.of(row()), List.of()));

        Hrm012QueryModels.Projection projection = fixture.service().query(request()).projection();
        Hrm012QueryModels.Item item = projection.items().getFirst();

        assertThat(item.fieldGroups().keySet())
                .containsExactlyInAnyOrder("INGESTION_STATUS", "QUALITY_FINDING");
        assertThat(item.fieldGroups().get("QUALITY_FINDING"))
                .isInstanceOf(Hrm012QueryModels.MaskedField.class);
        assertThat(item.fieldGroups()).doesNotContainKey("COMMAND_RECEIPT");
        assertThat(item.availableActions()).isEmpty();
        assertThat(projection.queryState().fieldDecisionSummary())
                .isEqualTo(new Hrm012QueryModels.FieldDecisionSummary(1, 1, 1));
    }

    @Test
    void trustedFieldDecisionUsesMoreRestrictiveOriginAndAcceptsResolvablePartialPopulation() {
        PeopleRequestContext.Actor actor = actor();
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        HcmPopulationScopeService.ResolvedPopulation tenantWide = population(true);
        when(populations.requireOperations("READ")).thenReturn(tenantWide);
        Hrm012FieldDecisionPort port = mock(Hrm012FieldDecisionPort.class);
        when(port.resolve(any())).thenAnswer(invocation -> {
            Hrm012FieldDecisionPort.DecisionBinding binding = invocation.getArgument(0);
            return new Hrm012FieldDecisionPort.DecisionResult(
                    binding, Map.of(
                    "INGESTION_STATUS", Hrm012FieldDecisionPort.FieldDecision.VIEW,
                    "QUALITY_FINDING", Hrm012FieldDecisionPort.FieldDecision.MASK,
                    "COMMAND_RECEIPT", Hrm012FieldDecisionPort.FieldDecision.VIEW),
                    Hrm012FieldDecisionPort.FieldDecision.MASK, "field-v22",
                    NOW.minusSeconds(1), NOW.plusSeconds(60));
        });
        Hrm012QueryAuthority authority = new Hrm012QueryAuthority(
                () -> actor, this::pep, () -> port, populations,
                Clock.fixed(NOW, ZoneOffset.UTC));

        Hrm012QueryAuthority.Decision decision = authority.authorize();

        assertThat(decision.fields()).containsEntry(
                "INGESTION_STATUS", Hrm012QueryAuthority.FieldDecision.VIEW)
                .containsEntry("QUALITY_FINDING", Hrm012QueryAuthority.FieldDecision.MASK)
                .containsEntry("COMMAND_RECEIPT", Hrm012QueryAuthority.FieldDecision.MASK);
        assertThat(decision.fieldPolicyRevision()).isEqualTo("field-v22");

        HcmPopulationScopeService partials = mock(HcmPopulationScopeService.class);
        when(partials.requireOperations("READ")).thenReturn(population(false));
        Hrm012FieldDecisionPort partialPort = mock(Hrm012FieldDecisionPort.class);
        when(partialPort.resolve(any())).thenAnswer(invocation ->
                new Hrm012FieldDecisionPort.DecisionResult(
                        invocation.getArgument(0), Map.of(
                        "INGESTION_STATUS", Hrm012FieldDecisionPort.FieldDecision.VIEW,
                        "QUALITY_FINDING", Hrm012FieldDecisionPort.FieldDecision.VIEW,
                        "COMMAND_RECEIPT", Hrm012FieldDecisionPort.FieldDecision.VIEW),
                        Hrm012FieldDecisionPort.FieldDecision.VIEW, "field-v22",
                        NOW.minusSeconds(1), NOW.plusSeconds(60)));
        Hrm012QueryAuthority partialAuthority = new Hrm012QueryAuthority(
                () -> actor, this::pep, () -> partialPort, partials,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(partialAuthority.authorize().population().scope().tenantWide()).isFalse();

        HcmPopulationScopeService unresolved = mock(HcmPopulationScopeService.class);
        HcmPopulationRepository.PopulationScope emptyScope =
                new HcmPopulationRepository.PopulationScope(
                        9L, null, false, Set.of(), Set.of("DIRECTORY"), "policy-v1");
        when(unresolved.requireOperations("READ")).thenReturn(
                new HcmPopulationScopeService.ResolvedPopulation(
                        null, emptyScope,
                        new HcmPopulationRepository.PopulationEvidence(0, "evidence-v1")));
        Hrm012FieldDecisionPort neverUsed = mock(Hrm012FieldDecisionPort.class);
        Hrm012QueryAuthority unresolvedAuthority = new Hrm012QueryAuthority(
                () -> actor, this::pep, () -> neverUsed, unresolved,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(unresolvedAuthority::authorize)
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> assertThat(((Hrm012Problem) error).status())
                        .isEqualTo(403));
        verifyNoInteractions(neverUsed);
    }

    @Test
    void missingOrStaleFieldDecisionsAndMaskedSortFailClosedBeforeSource() {
        PeopleRequestContext.Actor actor = actor();
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        when(populations.requireOperations("READ")).thenReturn(population(true));
        Hrm012FieldDecisionPort stale = mock(Hrm012FieldDecisionPort.class);
        when(stale.resolve(any())).thenAnswer(invocation ->
                new Hrm012FieldDecisionPort.DecisionResult(
                        invocation.getArgument(0), Map.of(
                        "INGESTION_STATUS", Hrm012FieldDecisionPort.FieldDecision.VIEW,
                        "QUALITY_FINDING", Hrm012FieldDecisionPort.FieldDecision.VIEW,
                        "COMMAND_RECEIPT", Hrm012FieldDecisionPort.FieldDecision.VIEW),
                        Hrm012FieldDecisionPort.FieldDecision.VIEW, "field-v22",
                        NOW.minusSeconds(60), NOW));
        Hrm012QueryAuthority authority = new Hrm012QueryAuthority(
                () -> actor, this::pep, () -> stale, populations,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(authority::authorize).isInstanceOf(Hrm012Problem.class);

        Hrm012QueryAuthority missing = new Hrm012QueryAuthority(
                () -> actor, this::pep, () -> null, populations,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(missing::authorize).isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> assertThat(((Hrm012Problem) error).status())
                        .isEqualTo(403));

        Hrm012FieldDecisionPort allOmit = mock(Hrm012FieldDecisionPort.class);
        when(allOmit.resolve(any())).thenAnswer(invocation ->
                new Hrm012FieldDecisionPort.DecisionResult(
                        invocation.getArgument(0), Map.of(
                        "INGESTION_STATUS", Hrm012FieldDecisionPort.FieldDecision.OMIT,
                        "QUALITY_FINDING", Hrm012FieldDecisionPort.FieldDecision.OMIT,
                        "COMMAND_RECEIPT", Hrm012FieldDecisionPort.FieldDecision.OMIT),
                        Hrm012FieldDecisionPort.FieldDecision.OMIT, "field-v23",
                        NOW.minusSeconds(1), NOW.plusSeconds(60)));
        Hrm012QueryAuthority emptyProjection = new Hrm012QueryAuthority(
                () -> actor, this::pep, () -> allOmit, populations,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(emptyProjection::authorize)
                .isInstanceOf(Hrm012Problem.class);

        Hrm012QueryAuthority malformedRevision = new Hrm012QueryAuthority(
                () -> actor, () -> pep("auth revision", "scope-v1"), () -> stale,
                populations, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(malformedRevision::authorize)
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> assertThat(((Hrm012Problem) error).status())
                        .isEqualTo(403));

        Hrm012FieldDecisionPort overlong = mock(Hrm012FieldDecisionPort.class);
        when(overlong.resolve(any())).thenAnswer(invocation ->
                new Hrm012FieldDecisionPort.DecisionResult(
                        invocation.getArgument(0), Map.of(
                        "INGESTION_STATUS", Hrm012FieldDecisionPort.FieldDecision.VIEW,
                        "QUALITY_FINDING", Hrm012FieldDecisionPort.FieldDecision.VIEW,
                        "COMMAND_RECEIPT", Hrm012FieldDecisionPort.FieldDecision.VIEW),
                        Hrm012FieldDecisionPort.FieldDecision.VIEW, "x".repeat(129),
                        NOW.minusSeconds(1), NOW.plusSeconds(60)));
        Hrm012QueryAuthority overlongRevision = new Hrm012QueryAuthority(
                () -> actor, this::pep, () -> overlong, populations,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(overlongRevision::authorize)
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> assertThat(((Hrm012Problem) error).status())
                        .isEqualTo(403));

        Fixture masked = fixture(Map.of(
                "INGESTION_STATUS", Hrm012QueryAuthority.FieldDecision.VIEW,
                "QUALITY_FINDING", Hrm012QueryAuthority.FieldDecision.MASK,
                "COMMAND_RECEIPT", Hrm012QueryAuthority.FieldDecision.VIEW));
        Hrm012QueryModels.QueryRequest sorted = new Hrm012QueryModels.QueryRequest(
                100, emptyFilter(), List.of("qualityFinding:ASC", "itemPublicId:ASC"),
                "ko-KR", "Asia/Seoul", CORRELATION, NOW, null);
        assertThatThrownBy(() -> masked.service().query(sorted))
                .isInstanceOf(Hrm012Problem.class);
        verifyNoInteractions(masked.source());
    }

    @Test
    void exactBoundsAndCodePatternFailClosedBeforeAuthorityOrPermitSafeFindingCodes() {
        Fixture invalid = fixture(allView());
        assertThatThrownBy(() -> invalid.service().query(new Hrm012QueryModels.QueryRequest(
                100, emptyFilter(), List.of(
                "ingestionStatus:ASC", "qualityFinding:ASC",
                "commandReceipt:ASC", "itemPublicId:ASC"),
                "ko-KR", "Asia/Seoul", CORRELATION, NOW, null)))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> assertThat(((Hrm012Problem) error).status()).isEqualTo(400));
        assertThatThrownBy(() -> invalid.service().query(new Hrm012QueryModels.QueryRequest(
                100, new Hrm012QueryModels.Filter(
                new Hrm012QueryModels.IngestionStatusFilter(List.of(), 0L),
                null, null, null), List.of("itemPublicId:ASC"),
                "ko-KR", "Asia/Seoul", CORRELATION, NOW, null)))
                .isInstanceOf(Hrm012Problem.class);
        assertThatThrownBy(() -> invalid.service().query(new Hrm012QueryModels.QueryRequest(
                100, emptyFilter(), List.of("itemPublicId:ASC"),
                "ko-KR", "Asia/Seoul", CORRELATION, NOW, "a".repeat(1025))))
                .isInstanceOf(Hrm012Problem.class);
        verifyNoInteractions(invalid.authority(), invalid.source());

        assertThatThrownBy(() -> new Hrm012QuerySource.Page(
                List.of(), true, false, false, null,
                java.util.stream.IntStream.range(0, 33)
                        .mapToObj(index -> new Hrm012QuerySource.ComponentFailure(
                                "component-" + index, "HRM012_DEGRADED", true))
                        .toList()))
                .isInstanceOf(IllegalArgumentException.class);

        Fixture accepted = fixture(allView());
        when(accepted.source().read(eq(TENANT), any(), eq(accepted.scope())))
                .thenReturn(page(List.of(), List.of()));
        Hrm012QueryModels.Filter filter = new Hrm012QueryModels.Filter(null,
                new Hrm012QueryModels.QualityFindingFilter(
                        List.of("CUSTOM_POLICY_FAILURE_42"), null), null, null);

        assertThat(accepted.service().query(new Hrm012QueryModels.QueryRequest(
                100, filter, List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                CORRELATION, NOW, null)).projection().queryState().state())
                .isEqualTo("EMPTY");
    }

    @Test
    void cursorIsBoundToAllAuthorityDimensionsAndIssuedWithinThe1024CharacterContract() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Hrm012CursorCodec codec = new Hrm012CursorCodec(
                "01234567890123456789012345678901", Duration.ofMinutes(15),
                mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        Hrm012CursorCodec.Binding binding = new Hrm012CursorCodec.Binding(
                TENANT, "scope-v1", "auth-v1", "population-v1", "HRM-AUTH-007",
                NOW, List.of("qualityFinding:DESC", "itemPublicId:ASC"),
                100, RUN.toString(), "a".repeat(64), "ko-KR", "Asia/Seoul");

        String cursor = codec.issue(binding);

        assertThat(cursor).hasSizeBetween(16, 1024);
        assertThat(codec.verify(cursor, binding.withLastRunPublicId(null))).isEqualTo(binding);
        assertThatThrownBy(() -> codec.verify(cursor, new Hrm012CursorCodec.Binding(
                TENANT, "scope-v2", "auth-v1", "population-v1", "HRM-AUTH-007",
                NOW, binding.sort(), 100, null, "a".repeat(64),
                "ko-KR", "Asia/Seoul")))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> assertThat(((Hrm012Problem) error).status()).isEqualTo(403));

        Hrm012CursorCodec.Binding maximum = new Hrm012CursorCodec.Binding(
                TENANT, "s".repeat(128), "a".repeat(128), "p".repeat(128),
                "f".repeat(128), NOW,
                List.of("ingestionStatus:DESC", "qualityFinding:DESC",
                        "itemPublicId:ASC"),
                100, RUN.toString(), "b".repeat(64), "zh-Hant-TW", "America/Argentina/Ushuaia");
        assertThat(maximum.scopeRevision()).matches("[a-f0-9]{64}");
        assertThat(maximum.authorizationRevision()).matches("[a-f0-9]{64}");
        assertThat(codec.issue(maximum)).hasSizeBetween(16, 1024);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sqlResolvesCompleteLegalEntityClosureAndPinsReceiptAndOneBasedRevision() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class),
                eq(String.class))).thenReturn(Long.toString(TENANT));
        doReturn(List.of()).when(jdbc).query(anyString(),
                any(MapSqlParameterSource.class), any(RowMapper.class));
        JdbcHrm012QuerySource source = new JdbcHrm012QuerySource(jdbc);
        Hrm012QueryModels.Filter filter = new Hrm012QueryModels.Filter(
                new Hrm012QueryModels.IngestionStatusFilter(List.of(), 1L),
                new Hrm012QueryModels.QualityFindingFilter(
                        List.of("CUSTOM_POLICY_FAILURE_42"), "a".repeat(64)),
                null, LocalDate.parse("2026-10-22"));
        HcmPopulationRepository.PopulationScope population =
                new HcmPopulationRepository.PopulationScope(
                        9L, "manager-assignment-1", false, Set.of(ORGANIZATION),
                        Set.of("DIRECTORY"), "policy-v1");

        assertThatThrownBy(() -> source.read(TENANT,
                new Hrm012QuerySource.QuerySpec(
                        NOW, LocalDate.parse("2026-10-22"), filter,
                        List.of("itemPublicId:ASC"), 100, null), population))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("cardinality(run.legal_entity_public_ids)>0")
                .contains("run.version + 1 AS run_revision")
                .contains("(run.version + 1)>=")
                .contains("run.updated_at<=:asOf")
                .contains("receipt.updated_at<=:asOf")
                .contains("item.created_at<=:asOf")
                .contains("AS snapshot_drift")
                .contains("active_population_rows AS")
                .contains("trusted_population_legal_entity_closure AS")
                .contains("bool_and(")
                .contains("AS population_resolved")
                .contains("run.legal_entity_public_ids <@ ARRAY(")
                .contains("receipt.command_type=:commandType")
                .contains("receipt.originating_action=:originatingAction")
                .contains("receipt.purpose_code=:purpose")
                .contains("receipt.target_type='WORKFORCE_INGESTION_OBJECT'")
                .contains("OR item.finding_code IN (:findingCodes)")
                .contains("OR item.outcome_digest=")
                .contains("OR finding.item_public_id IS NOT NULL")
                .contains("ppl_legal_employers", "ppl_assignments assignment");
        assertThat(parameters.getValue().getValue("tenantWide")).isEqualTo(false);
        assertThat(parameters.getValue().getValue("managerKey"))
                .isEqualTo("manager-assignment-1");
        assertThat(parameters.getValue().getValue("organizationIds"))
                .isEqualTo(Set.of(ORGANIZATION));
        assertThat(parameters.getValue().getValue("commandType"))
                .isEqualTo("WORKFORCE_INGESTION");
        assertThat(parameters.getValue().getValue("originatingAction"))
                .isEqualTo("INITIATE_WORKFORCE_INGESTION");
        assertThat(parameters.getValue().getValue("purpose"))
                .isEqualTo("HRIS_INTEGRATION");
        assertThat(parameters.getValue().getValue("minimumRevision")).isEqualTo(1L);
    }

    private Fixture fixture(Map<String, Hrm012QueryAuthority.FieldDecision> fields) {
        PeopleRequestContext.Actor actor = actor();
        PeopleRequestContext.set(actor.userId(), actor.tenantId(), actor.personPublicId(),
                actor.roles(), actor.permissions());
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        9L, null, true, Set.of(), Set.of("DIRECTORY"), "policy-v1");
        HcmPopulationScopeService.ResolvedPopulation population =
                new HcmPopulationScopeService.ResolvedPopulation(
                        null, scope,
                        new HcmPopulationRepository.PopulationEvidence(1, "evidence-v1"));
        Hrm012QueryAuthority.Decision decision = new Hrm012QueryAuthority.Decision(
                actor, population, "scope-v1", "auth-v1", "HRM-AUTH-007", fields);
        Hrm012QueryAuthority authority = mock(Hrm012QueryAuthority.class);
        Hrm012QuerySource source = mock(Hrm012QuerySource.class);
        when(authority.authorize()).thenReturn(decision);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Hrm012IaEntryQueryService service = new Hrm012IaEntryQueryService(
                authority, source, new Hrm012CursorCodec(
                "01234567890123456789012345678901", Duration.ofMinutes(15),
                mapper, Clock.fixed(NOW, ZoneOffset.UTC)), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, authority, source, scope, mapper);
    }

    private PeopleRequestContext.Actor actor() {
        return new PeopleRequestContext.Actor(
                7001L, TENANT, PERSON,
                Set.of("HRIS_HR_BUSINESS_PARTNER", "HRIS_DUTY_CORE_VIEWER"),
                Set.of(Hrm012QueryAuthority.PERMISSION));
    }

    private HcmPepContext.Evidence pep() {
        return pep("auth-v1", "scope-v1");
    }

    private HcmPepContext.Evidence pep(String decisionRevision, String scopeRevision) {
        HcmV3PepRegistry.RouteAuthority route = new HcmV3PepRegistry.RouteAuthority(
                Hrm012QueryAuthority.ROUTE, "DATA", Hrm012QueryAuthority.PROFILE, true,
                Set.of("predicate.hcm-workforce-ingestion-scope.v1"),
                Set.of("TARGET_POPULATION"), Hrm012QueryAuthority.BINDING,
                Hrm012QueryAuthority.CAPABILITY, null, "GET",
                Hrm012QueryAuthority.BROWSER_PATH, null,
                Hrm012QueryAuthority.PROJECTION, Hrm012QueryAuthority.RESPONSE);
        return new HcmPepContext.Evidence(
                route, decisionRevision, OffsetDateTime.ofInstant(
                NOW.plusSeconds(60), ZoneOffset.UTC), "context-v1", scopeRevision,
                "ENABLED", "NORMAL", Hrm012QueryAuthority.PURPOSE,
                Hrm012QueryAuthority.FIELD_POLICY);
    }

    private HcmPopulationScopeService.ResolvedPopulation population(boolean tenantWide) {
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        9L, tenantWide ? null : "manager-assignment-1", tenantWide,
                        tenantWide ? Set.of() : Set.of(ORGANIZATION),
                        Set.of("DIRECTORY"), "policy-v1");
        return new HcmPopulationScopeService.ResolvedPopulation(
                null, scope,
                new HcmPopulationRepository.PopulationEvidence(1, "evidence-v1"));
    }

    private Map<String, Hrm012QueryAuthority.FieldDecision> allView() {
        return Map.of(
                "INGESTION_STATUS", Hrm012QueryAuthority.FieldDecision.VIEW,
                "QUALITY_FINDING", Hrm012QueryAuthority.FieldDecision.VIEW,
                "COMMAND_RECEIPT", Hrm012QueryAuthority.FieldDecision.VIEW);
    }

    private Hrm012QueryModels.QueryRequest request() {
        return new Hrm012QueryModels.QueryRequest(
                100, emptyFilter(), List.of("itemPublicId:ASC"),
                "ko-KR", "Asia/Seoul", CORRELATION, NOW, null);
    }

    private Hrm012QueryModels.Filter emptyFilter() {
        return new Hrm012QueryModels.Filter(null, null, null, null);
    }

    private Hrm012QueryModels.SourceRow row() {
        return new Hrm012QueryModels.SourceRow(
                RUN, 7, "SUCCEEDED", LocalDate.parse("2026-10-22"), NOW,
                null, null, null, 0, RECEIPT, "SUCCEEDED", NOW, 12);
    }

    private Hrm012QuerySource.Page page(
            List<Hrm012QueryModels.SourceRow> rows,
            List<Hrm012QuerySource.ComponentFailure> failures) {
        return page(rows, failures, false);
    }

    private Hrm012QuerySource.Page page(
            List<Hrm012QueryModels.SourceRow> rows,
            List<Hrm012QuerySource.ComponentFailure> failures,
            boolean snapshotDrift) {
        UUID last = rows.isEmpty() ? null : rows.getLast().runPublicId();
        return new Hrm012QuerySource.Page(
                rows, true, snapshotDrift, false, last, failures);
    }

    private record Fixture(
            Hrm012IaEntryQueryService service,
            Hrm012QueryAuthority authority,
            Hrm012QuerySource source,
            HcmPopulationRepository.PopulationScope scope,
            ObjectMapper mapper) {
    }
}
