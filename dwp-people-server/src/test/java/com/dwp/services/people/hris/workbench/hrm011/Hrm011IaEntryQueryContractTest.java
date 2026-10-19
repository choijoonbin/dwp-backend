package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractCanonicalizer;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractDtos;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractRolloutGate;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractTemplateQueryPort;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Hrm011IaEntryQueryContractTest {
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final long TENANT = 71L;
    private static final UUID ACTOR = UUID.fromString(
            "11111111-1111-4111-8111-111111111111");
    private static final UUID RELATIONSHIP = UUID.fromString(
            "22222222-2222-4222-8222-222222222222");
    private static final UUID TEMPLATE = UUID.fromString(
            "33333333-3333-4333-8333-333333333333");
    private static final UUID CORRELATION = UUID.fromString(
            "44444444-4444-4444-8444-444444444444");

    @AfterEach
    void clear() {
        PeopleRequestContext.clear();
    }

    @Test
    void defaultOffEnrollmentPerformsNoAuthoritySourceOrTemplateCalls() {
        PeopleRequestContext.set(7L, TENANT, ACTOR, Set.of(), Set.of());
        Hrm011QueryAuthority authority = mock(Hrm011QueryAuthority.class);
        Hrm011QuerySource source = mock(Hrm011QuerySource.class);
        EmploymentContractTemplateQueryPort templates =
                mock(EmploymentContractTemplateQueryPort.class);
        Hrm011IaEntryQueryService service = service(
                new EmploymentContractRolloutGate(Set.of()), authority, source, templates);

        assertThatThrownBy(() -> service.query(request()))
                .isInstanceOf(EmploymentContractException.class)
                .satisfies(error -> assertThat(
                        ((EmploymentContractException) error).status()).isEqualTo(403));
        verifyNoInteractions(authority, source, templates);
    }

    @Test
    void ambiguousAuthorityIsUnavailableAndNeverExposesAction() {
        Fixture fixture = fixture();
        Hrm011QueryModels.SourceRow ambiguous = withCandidateCount(row(), 2);
        when(fixture.source.read(eq(TENANT), any(Hrm011QuerySource.QuerySpec.class),
                eq(fixture.decision.population().scope())))
                .thenReturn(page(List.of(ambiguous)));

        Hrm011QueryModels.Projection projection = fixture.service.query(request()).projection();

        assertThat(projection.queryState().state()).isEqualTo("UNAVAILABLE");
        assertThat(projection.items()).isEmpty();
        assertThat(projection.queryState().partialFailures())
                .extracting(Hrm011QueryModels.PartialFailure::errorCode)
                .containsExactly("HRM011_AMBIGUOUS_ASSIGNMENT_OR_BASIS");
        verifyNoInteractions(fixture.templates);
    }

    @Test
    void uniqueAuthorityBuildsDigestResourceVersionAndIssueActionWithoutTenantLeak()
            throws Exception {
        Fixture fixture = fixture();
        when(fixture.source.read(eq(TENANT), any(Hrm011QuerySource.QuerySpec.class),
                eq(fixture.decision.population().scope())))
                .thenReturn(page(List.of(row())));
        when(fixture.templates.requirePublished(
                org.mockito.ArgumentMatchers.any())).thenReturn(template());

        Hrm011QueryModels.Projection projection = fixture.service.query(request()).projection();

        assertThat(projection.projectionVersion()).isEqualTo("2");
        assertThat(projection.queryState().state()).isEqualTo("COMPLETE");
        assertThat(projection.items()).singleElement().satisfies(item -> {
            assertThat(item.itemPublicId()).isEqualTo(RELATIONSHIP.toString());
            assertThat(item.resourceVersion()).isZero();
            assertThat(item.statusCode()).isEqualTo("READY_TO_ISSUE");
            assertThat(item.availableActions()).singleElement()
                    .extracting(Hrm011QueryModels.AvailableAction::actionKey)
                    .isEqualTo("hris.action.hrm.issue-employment-contract");
            var field = (Hrm011QueryModels.ViewedField)
                    item.fieldGroups().get("CONTRACT_METADATA");
            assertThat(((Hrm011QueryModels.MetadataValue) field.value()).digest())
                    .matches("[0-9a-f]{64}");
        });
        assertThat(projection.queryState().fieldDecisionSummary().viewCount()).isEqualTo(2);
        assertThat(new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(projection))
                .doesNotContain("tenantId", "trustedTenant");
    }

    @Test
    void nonCanonicalLocaleAndTimeZoneAreRejectedBeforeAuthorityOrDataAccess() {
        Fixture fixture = fixture();

        for (String locale : List.of("ko_kr", "ko-kr", " ko-KR")) {
            assertThatThrownBy(() -> fixture.service.query(
                    request(100, locale, "Asia/Seoul")))
                    .isInstanceOf(EmploymentContractException.class)
                    .satisfies(error -> assertThat(
                            ((EmploymentContractException) error).status()).isEqualTo(400));
        }
        for (String timeZone : List.of(" Asia/Seoul", "EST", "Mars/Colony")) {
            assertThatThrownBy(() -> fixture.service.query(
                    request(100, "ko-KR", timeZone)))
                    .isInstanceOf(EmploymentContractException.class)
                    .satisfies(error -> assertThat(
                            ((EmploymentContractException) error).status()).isEqualTo(400));
        }
        verifyNoInteractions(fixture.authority, fixture.source, fixture.templates);
    }

    @Test
    void standardUtcTimeZoneIsAcceptedWithoutAliasRewriting() {
        Fixture fixture = fixture();
        when(fixture.source.read(eq(TENANT), any(Hrm011QuerySource.QuerySpec.class),
                eq(fixture.decision.population().scope())))
                .thenReturn(page(List.of()));

        Hrm011QueryModels.Projection projection = fixture.service.query(
                request(100, "ko-KR", "UTC")).projection();

        assertThat(projection.queryState().state()).isEqualTo("EMPTY");
    }

    @Test
    void controllerRejectsUnknownQueryFieldBeforeCallingService() {
        Hrm011IaEntryQueryService service = mock(Hrm011IaEntryQueryService.class);
        Hrm011IaEntryQueryController controller =
                new Hrm011IaEntryQueryController(service);
        LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("limit", "100");
        parameters.add("sort", "itemPublicId:ASC");
        parameters.add("locale", "ko-KR");
        parameters.add("timeZone", "Asia/Seoul");
        parameters.add("correlationId", CORRELATION.toString());
        parameters.add("filter", "{}");
        parameters.add("unknown", "true");

        assertThatThrownBy(() -> controller.query(
                100, List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                CORRELATION.toString(), NOW, null, parameters))
                .isInstanceOf(EmploymentContractException.class);
        verifyNoInteractions(service);
    }

    @Test
    void controllerRejectsLegacyPluralAndBasisTypeMoneyFilters() {
        Hrm011IaEntryQueryService service = mock(Hrm011IaEntryQueryService.class);
        Hrm011IaEntryQueryController controller =
                new Hrm011IaEntryQueryController(service);

        for (String legacy : List.of(
                "filter[compensationBasis][currencies]",
                "filter[compensationBasis][basisTypes]")) {
            LinkedMultiValueMap<String, String> parameters = parameters();
            parameters.add(legacy, "KRW");
            assertThatThrownBy(() -> controller.query(
                    100, List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                    CORRELATION.toString(), NOW, null, parameters))
                    .isInstanceOf(EmploymentContractException.class)
                    .hasMessageContaining("Unknown IAQ-008 query parameter");
        }
        verifyNoInteractions(service);
    }

    @Test
    void controllerPreservesExactSingularMoneyAndStringMetadataFilters() {
        Hrm011IaEntryQueryService service = mock(Hrm011IaEntryQueryService.class);
        Hrm011IaEntryQueryController controller =
                new Hrm011IaEntryQueryController(service);
        Hrm011QueryModels.Projection empty = new Hrm011QueryModels.Projection(
                "2", Hrm011QueryModels.VARIANT, NOW,
                new Hrm011QueryModels.QueryState(
                        "EMPTY", "scope-v1", "CURRENT", null,
                        new Hrm011QueryModels.FieldDecisionSummary(0, 0, 0),
                        List.of(), CORRELATION,
                        new Hrm011QueryModels.CachePolicy(
                                "auth-v1", Hrm011QueryModels.INVALIDATION_EVENT, 0)),
                List.of());
        when(service.query(org.mockito.ArgumentMatchers.any())).thenReturn(
                new Hrm011IaEntryQueryService.QueryResult(empty, "auth-v1", "\"etag\""));
        LinkedMultiValueMap<String, String> parameters = parameters();
        parameters.add("filter[contractMetadata][keys]", TEMPLATE.toString());
        parameters.add("filter[contractMetadata][versions]", "version.1");
        parameters.add("filter[compensationBasis][currency]", "KRW");
        parameters.add("filter[compensationBasis][minimumAmount]", "-100.5");

        controller.query(100, List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                CORRELATION.toString(), NOW, null, parameters);

        ArgumentCaptor<Hrm011QueryModels.QueryRequest> captured =
                ArgumentCaptor.forClass(Hrm011QueryModels.QueryRequest.class);
        org.mockito.Mockito.verify(service).query(captured.capture());
        assertThat(captured.getValue().filter().contractMetadata().versions())
                .containsExactly("version.1");
        assertThat(captured.getValue().filter().compensationBasis().currency())
                .isEqualTo("KRW");
        assertThat(captured.getValue().filter().compensationBasis().minimumAmount())
                .isEqualTo("-100.5");
    }

    @Test
    void missingAuthoritativeBasisIsPerItemFailureNotWholeSourceFailure() {
        Fixture fixture = fixture();
        Hrm011QueryModels.SourceRow complete = row();
        Hrm011QueryModels.SourceRow incomplete = new Hrm011QueryModels.SourceRow(
                complete.workerId(), complete.subjectPersonPublicId(), complete.title(),
                complete.relationship(), complete.assignment(), null, 1);
        when(fixture.source.read(eq(TENANT), any(Hrm011QuerySource.QuerySpec.class),
                eq(fixture.decision.population().scope())))
                .thenReturn(page(List.of(incomplete)));

        Hrm011QueryModels.Projection projection = fixture.service.query(request()).projection();

        assertThat(projection.queryState().state()).isEqualTo("UNAVAILABLE");
        assertThat(projection.queryState().partialFailures())
                .extracting(Hrm011QueryModels.PartialFailure::errorCode)
                .containsExactly("HRM011_COMPENSATION_BASIS_INCOMPLETE");
        verifyNoInteractions(fixture.templates);
    }

    @Test
    void maskedFieldsUseStrictLowercaseDisplayKeysAndSuppressActions() {
        Fixture fixture = fixture();
        Hrm011QueryAuthority.Decision masked = new Hrm011QueryAuthority.Decision(
                fixture.decision.actor(), fixture.decision.population(),
                fixture.decision.scopeRevision(), fixture.decision.authorizationRevision(),
                Map.of(
                        "CONTRACT_METADATA", Hrm011QueryAuthority.FieldDecision.MASK,
                        "COMPENSATION_BASIS", Hrm011QueryAuthority.FieldDecision.MASK),
                true);
        when(fixture.authority.authorize()).thenReturn(masked);
        when(fixture.source.read(eq(TENANT), any(Hrm011QuerySource.QuerySpec.class),
                eq(fixture.decision.population().scope())))
                .thenReturn(page(List.of(row())));
        when(fixture.templates.requirePublished(
                org.mockito.ArgumentMatchers.any())).thenReturn(template());

        Hrm011QueryModels.Item item = fixture.service.query(request())
                .projection().items().getFirst();

        assertThat(((Hrm011QueryModels.MaskedField) item.fieldGroups()
                .get("CONTRACT_METADATA")).maskedDisplayKey())
                .isEqualTo("hris.masked.contract_metadata");
        assertThat(((Hrm011QueryModels.MaskedField) item.fieldGroups()
                .get("COMPENSATION_BASIS")).maskedDisplayKey())
                .isEqualTo("hris.masked.compensation_basis");
        assertThat(item.availableActions()).isEmpty();
    }

    @Test
    void mismatchedTemplateScopeIsUnavailableAndNeverExposesAction() {
        Fixture fixture = fixture();
        when(fixture.source.read(eq(TENANT), any(Hrm011QuerySource.QuerySpec.class),
                eq(fixture.decision.population().scope())))
                .thenReturn(page(List.of(row())));
        when(fixture.templates.requirePublished(
                org.mockito.ArgumentMatchers.any())).thenReturn(
                template("LEGAL_ENTITY", UUID.randomUUID()));

        Hrm011QueryModels.Projection projection = fixture.service.query(request()).projection();

        assertThat(projection.queryState().state()).isEqualTo("UNAVAILABLE");
        assertThat(projection.items()).isEmpty();
        assertThat(projection.queryState().partialFailures()).singleElement()
                .extracting(Hrm011QueryModels.PartialFailure::errorCode)
                .isEqualTo("EMPLOYMENT_CONTRACT_ARTIFACT_UNAVAILABLE");
    }

    @Test
    void scansPastUnsafeCandidatesUntilSafePageIsFullAndMoreIsKnown() {
        Fixture fixture = fixture();
        UUID ambiguousId = UUID.fromString("22222222-2222-4222-8222-222222222221");
        UUID incompleteId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID firstSafeId = UUID.fromString("22222222-2222-4222-8222-222222222223");
        UUID secondSafeId = UUID.fromString("22222222-2222-4222-8222-222222222224");
        UUID extraSafeId = UUID.fromString("22222222-2222-4222-8222-222222222225");
        Hrm011QueryModels.SourceRow ambiguous = withCandidateCount(
                row(ambiguousId), 2);
        Hrm011QueryModels.SourceRow baseIncomplete = row(incompleteId);
        Hrm011QueryModels.SourceRow incomplete = new Hrm011QueryModels.SourceRow(
                baseIncomplete.workerId(), baseIncomplete.subjectPersonPublicId(),
                baseIncomplete.title(), baseIncomplete.relationship(),
                baseIncomplete.assignment(), null, 1);
        when(fixture.source.read(eq(TENANT), any(Hrm011QuerySource.QuerySpec.class),
                eq(fixture.decision.population().scope()))).thenReturn(
                new Hrm011QuerySource.Page(
                        List.of(ambiguous, incomplete), true, true, incompleteId),
                new Hrm011QuerySource.Page(
                        List.of(row(firstSafeId), row(secondSafeId)),
                        true, true, secondSafeId),
                new Hrm011QuerySource.Page(
                        List.of(row(extraSafeId)), true, false, extraSafeId));
        when(fixture.templates.requirePublished(any())).thenReturn(template());

        Hrm011QueryModels.Projection projection = fixture.service.query(
                request(2)).projection();

        assertThat(projection.items())
                .extracting(Hrm011QueryModels.Item::itemPublicId)
                .containsExactly(firstSafeId.toString(), secondSafeId.toString());
        assertThat(projection.queryState().nextCursor()).isNotBlank();
        assertThat(projection.queryState().partialFailures())
                .extracting(Hrm011QueryModels.PartialFailure::errorCode)
                .containsExactly(
                        "HRM011_AMBIGUOUS_ASSIGNMENT_OR_BASIS",
                        "HRM011_COMPENSATION_BASIS_INCOMPLETE");
        ArgumentCaptor<Hrm011QuerySource.QuerySpec> specifications =
                ArgumentCaptor.forClass(Hrm011QuerySource.QuerySpec.class);
        verify(fixture.source, times(3)).read(
                eq(TENANT), specifications.capture(),
                eq(fixture.decision.population().scope()));
        assertThat(specifications.getAllValues())
                .extracting(Hrm011QuerySource.QuerySpec::afterRelationshipPublicId)
                .containsExactly(null, incompleteId, secondSafeId);
    }

    @Test
    void limitOneScansUnsafePrefixInOneBoundedDatabaseBatch() {
        Fixture fixture = fixture();
        UUID ambiguousId = UUID.fromString("22222222-2222-4222-8222-222222222231");
        UUID firstSafeId = UUID.fromString("22222222-2222-4222-8222-222222222232");
        UUID extraSafeId = UUID.fromString("22222222-2222-4222-8222-222222222233");
        when(fixture.source.read(eq(TENANT), any(Hrm011QuerySource.QuerySpec.class),
                eq(fixture.decision.population().scope()))).thenReturn(
                new Hrm011QuerySource.Page(List.of(
                        withCandidateCount(row(ambiguousId), 2),
                        row(firstSafeId), row(extraSafeId)),
                        true, false, extraSafeId));
        when(fixture.templates.requirePublished(any())).thenReturn(template());

        Hrm011QueryModels.Projection projection = fixture.service.query(
                request(1)).projection();

        assertThat(projection.items())
                .extracting(Hrm011QueryModels.Item::itemPublicId)
                .containsExactly(firstSafeId.toString());
        assertThat(projection.queryState().nextCursor()).isNotBlank();
        ArgumentCaptor<Hrm011QuerySource.QuerySpec> specification =
                ArgumentCaptor.forClass(Hrm011QuerySource.QuerySpec.class);
        verify(fixture.source).read(eq(TENANT), specification.capture(),
                eq(fixture.decision.population().scope()));
        assertThat(specification.getValue().limit()).isEqualTo(100);
    }

    @Test
    void cursorRejectsScopeRevisionTampering() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Hrm011CursorCodec codec = new Hrm011CursorCodec(
                "01234567890123456789012345678901", Duration.ofMinutes(15),
                mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        Hrm011CursorCodec.Binding binding = new Hrm011CursorCodec.Binding(
                TENANT, "scope-a", "auth-a", "population-a", NOW,
                List.of("itemPublicId:ASC"), 100, RELATIONSHIP.toString(),
                "a".repeat(64), "ko-KR", "Asia/Seoul");
        String cursor = codec.issue(binding);

        assertThatThrownBy(() -> codec.verify(cursor,
                new Hrm011CursorCodec.Binding(
                        TENANT, "scope-b", "auth-a", "population-a", NOW,
                        List.of("itemPublicId:ASC"), 100, null,
                        "a".repeat(64), "ko-KR", "Asia/Seoul")))
                .isInstanceOf(EmploymentContractException.class);
    }

    private Fixture fixture() {
        PeopleRequestContext.set(
                7L, TENANT, ACTOR, Set.of(),
                Set.of(Hrm011QueryAuthority.PERMISSION));
        Hrm011QueryAuthority authority = mock(Hrm011QueryAuthority.class);
        Hrm011QuerySource source = mock(Hrm011QuerySource.class);
        EmploymentContractTemplateQueryPort templates =
                mock(EmploymentContractTemplateQueryPort.class);
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        9L, null, true, Set.of(),
                        Set.of("EMPLOYMENT", "COMPENSATION", "DOCUMENT_CONTENT"),
                        "policy-v1");
        HcmPopulationScopeService.ResolvedPopulation population =
                new HcmPopulationScopeService.ResolvedPopulation(
                        null, scope,
                        new HcmPopulationRepository.PopulationEvidence(1, "evidence-v1"));
        Hrm011QueryAuthority.Decision decision = new Hrm011QueryAuthority.Decision(
                PeopleRequestContext.require(), population, "scope-v1", "auth-v1",
                Map.of(
                        "CONTRACT_METADATA", Hrm011QueryAuthority.FieldDecision.VIEW,
                        "COMPENSATION_BASIS", Hrm011QueryAuthority.FieldDecision.VIEW),
                true);
        when(authority.authorize()).thenReturn(decision);
        return new Fixture(service(
                new EmploymentContractRolloutGate(Set.of(TENANT)),
                authority, source, templates), authority, source, templates, decision);
    }

    private Hrm011IaEntryQueryService service(
            EmploymentContractRolloutGate rollout,
            Hrm011QueryAuthority authority,
            Hrm011QuerySource source,
            EmploymentContractTemplateQueryPort templates) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new Hrm011IaEntryQueryService(
                rollout, authority, source, templates,
                new EmploymentContractCanonicalizer(mapper),
                new Hrm011CursorCodec(
                        "01234567890123456789012345678901",
                        Duration.ofMinutes(15), mapper,
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Hrm011QueryModels.QueryRequest request() {
        return request(100);
    }

    private Hrm011QueryModels.QueryRequest request(int limit) {
        return request(limit, "ko-KR", "Asia/Seoul");
    }

    private Hrm011QueryModels.QueryRequest request(
            int limit, String locale, String timeZone) {
        return new Hrm011QueryModels.QueryRequest(
                limit, new Hrm011QueryModels.Filter(
                new Hrm011QueryModels.MetadataFilter(
                        List.of(TEMPLATE.toString()), List.of("1")),
                null, LocalDate.parse("2026-10-19")),
                List.of("itemPublicId:ASC"), locale, timeZone,
                CORRELATION, NOW, null);
    }

    private Hrm011QueryModels.SourceRow row() {
        return row(RELATIONSHIP);
    }

    private Hrm011QueryModels.SourceRow row(UUID relationshipPublicId) {
        return new Hrm011QueryModels.SourceRow(
                91L, UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                "Senior Engineer",
                new EmploymentContractDtos.RelationshipSnapshot(
                        relationshipPublicId, 0,
                        UUID.fromString("55555555-5555-4555-8555-555555555555"),
                        UUID.fromString("66666666-6666-4666-8666-666666666666"),
                        LocalDate.parse("2026-01-01"), null),
                new EmploymentContractDtos.AssignmentSnapshot(
                        UUID.fromString("77777777-7777-4777-8777-777777777777"), 1,
                        LocalDate.parse("2026-01-01"), null),
                new EmploymentContractDtos.CompensationBasisSnapshot(
                        UUID.fromString("88888888-8888-4888-8888-888888888888"), 1,
                        "SALARY", "100000", "KRW", "ANNUAL",
                        LocalDate.parse("2026-01-01"), null), 1);
    }

    private Hrm011QueryModels.SourceRow withCandidateCount(
            Hrm011QueryModels.SourceRow row, long candidateCount) {
        return new Hrm011QueryModels.SourceRow(
                row.workerId(), row.subjectPersonPublicId(), row.title(),
                row.relationship(), row.assignment(), row.compensationBasis(),
                candidateCount);
    }

    private Hrm011QuerySource.Page page(List<Hrm011QueryModels.SourceRow> rows) {
        UUID last = rows.isEmpty()
                ? null : rows.getLast().relationship().relationshipPublicId();
        return new Hrm011QuerySource.Page(rows, true, false, last);
    }

    private EmploymentContractTemplateSnapshotV1 template() {
        return template("GLOBAL", null);
    }

    private EmploymentContractTemplateSnapshotV1 template(
            String scopeType, UUID scopeId) {
        return new EmploymentContractTemplateSnapshotV1(
                UUID.fromString("99999999-9999-4999-8999-999999999999"),
                "employment-contract.v1", "STANDARD",
                LocalDate.parse("2026-01-01"), null, "ko-KR",
                "a".repeat(64), NOW.minusSeconds(60), scopeId, scopeType, 1,
                "PUBLISHED", new EmploymentContractTemplateSnapshotV1.TemplateContentReference(
                UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"), 1,
                "b".repeat(64)), TEMPLATE, "EMPLOYMENT_CONTRACT", 1, TENANT);
    }

    private record Fixture(
            Hrm011IaEntryQueryService service,
            Hrm011QueryAuthority authority,
            Hrm011QuerySource source,
            EmploymentContractTemplateQueryPort templates,
            Hrm011QueryAuthority.Decision decision) {
    }

    private LinkedMultiValueMap<String, String> parameters() {
        LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("limit", "100");
        parameters.add("sort", "itemPublicId:ASC");
        parameters.add("locale", "ko-KR");
        parameters.add("timeZone", "Asia/Seoul");
        parameters.add("correlationId", CORRELATION.toString());
        return parameters;
    }
}
