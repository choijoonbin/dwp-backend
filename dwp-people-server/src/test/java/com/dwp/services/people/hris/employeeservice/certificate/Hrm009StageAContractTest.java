package com.dwp.services.people.hris.employeeservice.certificate;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class Hrm009StageAContractTest {

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                    + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";
    private static final Instant NOW = Instant.parse("2026-10-18T03:00:00Z");
    private static final UUID ACTOR =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CORRELATION =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CertificateCanonicalizer canonicalizer =
            new CertificateCanonicalizer(mapper);
    private final CertificateProjector projector =
            new CertificateProjector(canonicalizer);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void exposesOnlyTwoGetRoutesAndOnePostRouteBehindDefaultOffEnrollment() {
        RequestMapping root = CertificateController.class
                .getAnnotation(RequestMapping.class);
        ConditionalOnProperty controllerCondition = CertificateController.class
                .getAnnotation(ConditionalOnProperty.class);
        ConditionalOnProperty gateCondition = Hrm009RolloutGate.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(root.value()).containsExactly("/v1/hris");
        assertThat(Arrays.stream(CertificateController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(GetMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value())))
                .containsExactlyInAnyOrder(
                        "/workbench/self-service/requests",
                        "/workbench/operations/certificates/documents");
        assertThat(Arrays.stream(CertificateController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value())))
                .containsExactly("/certificates");
        assertDefaultOff(controllerCondition);
        assertDefaultOff(gateCondition);
        assertThat(new Hrm009RolloutGate("").enrolled(41L)).isFalse();
    }

    @Test
    void iaqDefinitionsAreTheExactClosedStageAContracts() {
        CertificateQueryDefinition self = CertificateQueryDefinition.SELF_REQUESTS;
        assertThat(self.queryId()).isEqualTo("IAQ-003");
        assertThat(self.operationId()).isEqualTo("IAQ.OP.003");
        assertThat(self.variantKey()).isEqualTo("REQUESTS");
        assertThat(self.routeContractKey())
                .isEqualTo("route.hcm.personal.requests.data");
        assertThat(self.profileKey()).isEqualTo("self-request-reader");
        assertThat(self.bindingKey())
                .isEqualTo("route.hcm.personal.requests.data.binding.01");
        assertThat(self.projectionPolicyKey()).isEqualTo(
                "route.hcm.personal.requests.data.self-request-reader.projection.v1");
        assertThat(self.responseSchemaKey())
                .isEqualTo("Hrm009SelfRequestsProjectionV1");
        assertThat(self.fieldPolicyKey())
                .isEqualTo("hrm009.iaq-003.fields.v1");
        assertThat(self.capability()).isEqualTo("hcm.personal.requests.view");
        assertThat(self.fieldGroups()).containsExactly(
                "REQUEST_SUMMARY", "REQUEST_STATUS", "COMMAND_RECEIPT");
        assertThat(self.actionKeys()).containsExactly(
                "hris.action.hrm.create-employee-change-request",
                "hris.action.hrm.submit-employee-change-request",
                CertificateDtos.REQUEST_CERTIFICATE_ACTION);

        CertificateQueryDefinition operations =
                CertificateQueryDefinition.OPERATIONS_CERTIFICATES;
        assertThat(operations.queryId()).isEqualTo("IAQ-010");
        assertThat(operations.operationId()).isEqualTo("IAQ.OP.010");
        assertThat(operations.variantKey()).isEqualTo("CERTIFICATES_DOCUMENTS");
        assertThat(operations.routeContractKey())
                .isEqualTo("route.hcm.operations.certificates-documents.data");
        assertThat(operations.profileKey()).isEqualTo("people-operations-reader");
        assertThat(operations.bindingKey()).isEqualTo(
                "route.hcm.operations.certificates-documents.data.binding.01");
        assertThat(operations.projectionPolicyKey()).isEqualTo(
                "route.hcm.operations.certificates-documents.data."
                        + "people-operations-reader.projection.v1");
        assertThat(operations.responseSchemaKey())
                .isEqualTo("Hrm009CertificatesDocumentsProjectionV1");
        assertThat(operations.fieldPolicyKey())
                .isEqualTo("hrm009.iaq-010.fields.v1");
        assertThat(operations.capability()).isEqualTo("hcm.operations.people.view");
        assertThat(operations.fieldGroups()).containsExactly(
                "CERTIFICATE_METADATA", "DOCUMENT_METADATA");
        assertThat(operations.actionKeys())
                .containsExactly(CertificateDtos.REQUEST_CERTIFICATE_ACTION);
        assertThat(CertificateDtos.INVALIDATION_EVENT)
                .isEqualTo("dwp.hris.access.assignment.changed.v1");
    }

    @Test
    void requiredEmptyFilterEnvelopeAndNestedFiltersAreStrictlyExclusive() {
        CertificateService service = mock(CertificateService.class);
        CertificateController controller = new CertificateController(service);
        when(service.query(any(), any())).thenReturn(emptyResult());

        controller.selfRequests(
                10, List.of("itemPublicId:ASC"), "en-US", "UTC",
                CORRELATION.toString(),
                NOW, null, emptyFilter());
        MultiValueMap<String, String> nested = common();
        nested.add("filter[requestStatus][codes]", "REQUESTED");
        controller.selfRequests(
                10, List.of("itemPublicId:ASC"), "en-US", "UTC",
                CORRELATION.toString(),
                NOW, null, nested);

        verify(service, org.mockito.Mockito.times(2)).query(
                eq(CertificateQueryDefinition.SELF_REQUESTS), any());

        MultiValueMap<String, String> missing = common();
        assertRejected(controller, missing);
        MultiValueMap<String, String> mixed = emptyFilter();
        mixed.add("filter[requestStatus][codes]", "REQUESTED");
        assertRejected(controller, mixed);
        MultiValueMap<String, String> nonempty = common();
        nonempty.add("filter", "{\"requestStatus\":{}}");
        assertRejected(controller, nonempty);
        MultiValueMap<String, String> blankLeaf = common();
        blankLeaf.add("filter[requestStatus][codes]", "REQUESTED,");
        assertRejected(controller, blankLeaf);
        MultiValueMap<String, String> tenantPollution = emptyFilter();
        tenantPollution.add("tenantId", "41");
        assertRejected(controller, tenantPollution);
    }

    @Test
    void commandJsonBoundaryRejectsUnknownBodyMembersWithDefaultMapperSettings()
            throws Exception {
        CertificateService service = mock(CertificateService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CertificateController(service))
                .setControllerAdvice(new CertificateProblemAdvice())
                .build();

        mvc.perform(post("/v1/hris/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-token")
                        .header(HcmStepUpHeaders.IDEMPOTENCY_KEY,
                                "certificate-key-00000001")
                        .header("If-Match", "\"v:5\"")
                        .header("X-Correlation-ID", CORRELATION)
                        .header(HcmStepUpHeaders.CHALLENGE, "signed-challenge")
                        .header(HcmStepUpHeaders.DECISION_REVISION,
                                "psr-" + "c".repeat(64))
                        .header(HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, "5")
                        .content("""
                                {
                                  "personPublicId":"11111111-1111-4111-8111-111111111111",
                                  "certificateType":"employment-letter",
                                  "purposeCode":"EMPLOYEE_SELF_SERVICE",
                                  "locale":"en-US",
                                  "asOf":"2026-10-18T03:00:00Z",
                                  "unexpected":true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId")
                        .value(matchesPattern(UUID_PATTERN)))
                .andExpect(header().string(
                        "X-Correlation-ID", matchesPattern(UUID_PATTERN)));

        verifyNoInteractions(service);
    }

    @Test
    void httpUuidBoundariesRejectVersionZeroBodiesAndShortFormCorrelations()
            throws Exception {
        CertificateService service = mock(CertificateService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CertificateController(service))
                .setControllerAdvice(new CertificateProblemAdvice())
                .build();
        String validBody = """
                {
                  "personPublicId":"11111111-1111-4111-8111-111111111111",
                  "certificateType":"employment-letter",
                  "purposeCode":"EMPLOYEE_SELF_SERVICE",
                  "locale":"en-US",
                  "asOf":"2026-10-18T03:00:00Z"
                }
                """;

        mvc.perform(post("/v1/hris/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-token")
                        .header(HcmStepUpHeaders.IDEMPOTENCY_KEY,
                                "certificate-key-00000001")
                        .header("If-Match", "\"v:5\"")
                        .header("X-Correlation-ID", CORRELATION)
                        .header(HcmStepUpHeaders.CHALLENGE, "signed-challenge")
                        .header(HcmStepUpHeaders.DECISION_REVISION,
                                "psr-" + "c".repeat(64))
                        .header(HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, "5")
                        .content(validBody.replace(
                                "11111111-1111-4111-8111-111111111111",
                                "11111111-1111-0111-8111-111111111111")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId")
                        .value(matchesPattern(UUID_PATTERN)))
                .andExpect(header().string(
                        "X-Correlation-ID", matchesPattern(UUID_PATTERN)));

        mvc.perform(post("/v1/hris/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer test-token")
                        .header(HcmStepUpHeaders.IDEMPOTENCY_KEY,
                                "certificate-key-00000001")
                        .header("If-Match", "\"v:5\"")
                        .header("X-Correlation-ID", "1-1-1-1-1")
                        .header(HcmStepUpHeaders.CHALLENGE, "signed-challenge")
                        .header(HcmStepUpHeaders.DECISION_REVISION,
                                "psr-" + "c".repeat(64))
                        .header(HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, "5")
                        .content(validBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId")
                        .value(matchesPattern(UUID_PATTERN)))
                .andExpect(header().string(
                        "X-Correlation-ID", matchesPattern(UUID_PATTERN)));

        mvc.perform(get("/v1/hris/workbench/self-service/requests")
                        .param("limit", "10")
                        .param("filter", "{}")
                        .param("sort", "itemPublicId:ASC")
                        .param("locale", "en-US")
                        .param("timeZone", "UTC")
                        .param("correlationId", "1-1-1-1-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId")
                        .value(matchesPattern(UUID_PATTERN)))
                .andExpect(header().string(
                        "X-Correlation-ID", matchesPattern(UUID_PATTERN)));

        verifyNoInteractions(service);
    }

    @Test
    void validThreeSortPageUsesInternalStableTieBreakAndCursorBindsOnlyRequestedSorts() {
        List<String> sorts = List.of(
                "requestSummary:ASC", "requestStatus:ASC", "commandReceipt:ASC");
        CertificateProjector.PreparedQuery prepared = projector.prepare(
                CertificateQueryDefinition.SELF_REQUESTS,
                new CertificateDtos.QueryRequest(
                        1, new CertificateDtos.SelfRequestsFilter(null, null, null, null),
                        sorts, "en-US", "UTC", CORRELATION, NOW, null), NOW);
        CertificateAuthority.QueryDecision decision = queryDecision(
                CertificateQueryDefinition.SELF_REQUESTS,
                Set.of("REQUEST_SUMMARY", "REQUEST_STATUS", "COMMAND_RECEIPT"));
        CertificateProjector.Page page = projector.project(
                prepared, List.of(row(4), row(3)), decision, null);

        assertThat(prepared.sort()).containsExactlyElementsOf(sorts);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).singleElement()
                .extracting(CertificateDtos.Item::itemPublicId)
                .isEqualTo(row(3).requestPublicId().toString());

        CertificateCursorCodec codec = new CertificateCursorCodec(
                "hrm009-test-cursor-secret-value", Duration.ofMinutes(15), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        CertificateCursorCodec.Binding binding = new CertificateCursorCodec.Binding(
                41L, prepared.definition().queryId(), decision.populationKind(),
                decision.scopeRevision(), decision.authorizationRevision(),
                prepared.asOf(), prepared.sort(), prepared.limit(),
                page.lastItemPublicId(), prepared.filterDigest(), prepared.locale(),
                prepared.timeZone());
        String cursor = codec.issue(binding);
        CertificateCursorCodec.Binding expected = binding.withLastItemPublicId(null);

        assertThat(codec.verify(cursor, expected).lastItemPublicId())
                .isEqualTo(page.lastItemPublicId());
    }

    @Test
    void fieldDecisionSummaryCountsOmittedGroupsFromTheSealedDefinition() {
        CertificateProjector.PreparedQuery prepared = projector.prepare(
                CertificateQueryDefinition.SELF_REQUESTS,
                new CertificateDtos.QueryRequest(
                        10, new CertificateDtos.SelfRequestsFilter(null, null, null, null),
                        List.of("itemPublicId:ASC"), "en-US", "UTC", CORRELATION,
                        NOW, null), NOW);
        CertificateProjector.Page page = projector.project(
                prepared, List.of(row(1)), queryDecision(
                        CertificateQueryDefinition.SELF_REQUESTS,
                        Set.of("REQUEST_SUMMARY", "REQUEST_STATUS", "COMMAND_RECEIPT")),
                null);

        assertThat(page.summary()).isEqualTo(
                new CertificateDtos.FieldDecisionSummary(2, 0, 1));
        CertificateDtos.Projection projection = projector.projection(
                prepared, queryDecision(CertificateQueryDefinition.SELF_REQUESTS,
                        Set.of("REQUEST_SUMMARY", "REQUEST_STATUS", "COMMAND_RECEIPT")),
                page, null);
        assertThat(projection.queryState().state()).isEqualTo("COMPLETE");
        assertThat(projection.queryState().cachePolicy().maxAgeSeconds()).isZero();
    }

    @Test
    void maskedOperationsMetadataCannotLeakThroughItemsFiltersOrOrdering() {
        CertificateAuthority.QueryDecision restricted = queryDecision(
                CertificateQueryDefinition.OPERATIONS_CERTIFICATES,
                Set.of("DIRECTORY"));
        CertificateDtos.OperationsFilter empty =
                new CertificateDtos.OperationsFilter(null, null, null);
        CertificateProjector.PreparedQuery prepared = projector.prepare(
                CertificateQueryDefinition.OPERATIONS_CERTIFICATES,
                new CertificateDtos.QueryRequest(
                        10, empty, List.of("itemPublicId:ASC"), "en-US", "UTC",
                        CORRELATION, NOW, null), NOW);
        CertificateRepository.ProjectionRow hiddenFirst = operationsRow(
                1, "secret-zulu", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
                "d".repeat(64));
        CertificateRepository.ProjectionRow hiddenSecond = operationsRow(
                2, "secret-alpha", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2",
                "e".repeat(64));

        CertificateProjector.Page page = projector.project(
                prepared, List.of(hiddenSecond, hiddenFirst), restricted, null);

        assertThat(page.items())
                .extracting(CertificateDtos.Item::itemPublicId)
                .containsExactly(
                        hiddenFirst.requestPublicId().toString(),
                        hiddenSecond.requestPublicId().toString());
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.title()).isEqualTo("Restricted certificate");
            assertThat(item.statusCode()).isEqualTo("RESTRICTED");
            assertThat(item.availableActions()).isEmpty();
            assertThat(item.fieldGroups().values())
                    .allMatch(CertificateDtos.MaskedField.class::isInstance);
        });
        assertThat(page.items().toString())
                .doesNotContain("secret-zulu", "secret-alpha", "Hidden Person")
                .doesNotContain("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1")
                .doesNotContain("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2")
                .doesNotContain("d".repeat(64), "e".repeat(64));

        CertificateProjector.PreparedQuery filtered = projector.prepare(
                CertificateQueryDefinition.OPERATIONS_CERTIFICATES,
                new CertificateDtos.QueryRequest(
                        10, new CertificateDtos.OperationsFilter(
                        new CertificateDtos.MetadataFilter(
                                List.of("secret-zulu"), List.of()), null, null),
                        List.of("itemPublicId:ASC"), "en-US", "UTC", CORRELATION,
                        NOW, null), NOW);
        assertThatThrownBy(() -> projector.project(
                filtered, List.of(hiddenFirst, hiddenSecond), restricted, null))
                .isInstanceOf(CertificateException.class)
                .satisfies(failure -> assertThat(((CertificateException) failure).code())
                        .isEqualTo("CERTIFICATE_ACCESS_DENIED"));

        CertificateProjector.PreparedQuery sorted = projector.prepare(
                CertificateQueryDefinition.OPERATIONS_CERTIFICATES,
                new CertificateDtos.QueryRequest(
                        10, empty, List.of("certificateMetadata:ASC"),
                        "en-US", "UTC", CORRELATION, NOW, null), NOW);
        assertThatThrownBy(() -> projector.project(
                sorted, List.of(hiddenFirst, hiddenSecond), restricted, null))
                .isInstanceOf(CertificateException.class)
                .satisfies(failure -> assertThat(((CertificateException) failure).code())
                        .isEqualTo("CERTIFICATE_ACCESS_DENIED"));
    }

    @Test
    void emptyEnrollmentStopsQueriesAndCommandsBeforeEveryRuntimeDependency() {
        CertificateAuthority authority = mock(CertificateAuthority.class);
        CertificateRepository repository = mock(CertificateRepository.class);
        HcmPopulationRepository populations = mock(HcmPopulationRepository.class);
        CertificateProjector mockedProjector = mock(CertificateProjector.class);
        CertificateCursorCodec cursors = mock(CertificateCursorCodec.class);
        CertificateCanonicalizer mockedCanonicalizer =
                mock(CertificateCanonicalizer.class);
        CertificateArtifactPort artifacts = mock(CertificateArtifactPort.class);
        HcmHighRiskCommandGuard highRisk = mock(HcmHighRiskCommandGuard.class);
        ObjectMapper mockedMapper = mock(ObjectMapper.class);
        CertificateService service = new CertificateService(
                new Hrm009RolloutGate(Set.of()), authority, repository, populations,
                mockedProjector, cursors, mockedCanonicalizer, artifacts, highRisk,
                mockedMapper, Clock.fixed(NOW, ZoneOffset.UTC));
        PeopleRequestContext.set(7L, 41L, ACTOR, Set.of(), Set.of());

        assertThatThrownBy(() -> service.query(null, null))
                .isInstanceOf(CertificateException.class)
                .satisfies(failure -> assertThat(((CertificateException) failure).status())
                        .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.requestCertificate(
                null, null, null, null, null, null))
                .isInstanceOf(CertificateException.class);

        verifyNoInteractions(authority, repository, populations, mockedProjector,
                cursors, mockedCanonicalizer, artifacts, highRisk, mockedMapper);
    }

    private void assertDefaultOff(ConditionalOnProperty condition) {
        assertThat(condition.prefix()).isEqualTo(Hrm009RolloutGate.PREFIX);
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    private void assertRejected(
            CertificateController controller,
            MultiValueMap<String, String> parameters) {
        assertThatThrownBy(() -> controller.selfRequests(
                10, List.of("itemPublicId:ASC"), "en-US", "UTC",
                CORRELATION.toString(),
                NOW, null, parameters)).isInstanceOf(CertificateException.class);
    }

    private MultiValueMap<String, String> common() {
        MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
        result.add("limit", "10");
        result.add("sort", "itemPublicId:ASC");
        result.add("locale", "en-US");
        result.add("timeZone", "UTC");
        result.add("correlationId", CORRELATION.toString());
        result.add("asOf", NOW.toString());
        return result;
    }

    private MultiValueMap<String, String> emptyFilter() {
        MultiValueMap<String, String> result = common();
        result.add("filter", "{}");
        return result;
    }

    private CertificateService.QueryResult emptyResult() {
        CertificateDtos.Projection projection = new CertificateDtos.Projection(
                "1", NOW, "REQUESTS",
                new CertificateDtos.QueryState(
                        "EMPTY", "self:scope", "CURRENT", null,
                        new CertificateDtos.FieldDecisionSummary(0, 0, 0), List.of(),
                        CORRELATION, new CertificateDtos.CachePolicy(
                        "psr-" + "c".repeat(64),
                        CertificateDtos.INVALIDATION_EVENT, 0)),
                List.of());
        return new CertificateService.QueryResult(
                projection, "psr-" + "c".repeat(64), "\"etag\"");
    }

    private CertificateAuthority.QueryDecision queryDecision(
            CertificateQueryDefinition definition,
            Set<String> fields) {
        PeopleRequestContext.Actor actor = new PeopleRequestContext.Actor(
                7L, 41L, ACTOR, Set.of(), Set.of());
        return new CertificateAuthority.QueryDecision(
                actor, "psr-" + "c".repeat(64),
                definition == CertificateQueryDefinition.SELF_REQUESTS
                        ? "SELF" : "LEGAL_ENTITY",
                "scope:17", null, fields, Set.of());
    }

    private CertificateRepository.ProjectionRow row(int suffix) {
        UUID request = UUID.fromString(String.format(
                "00000000-0000-4000-8000-%012d", suffix));
        return new CertificateRepository.ProjectionRow(
                request, ACTOR, "Taylor", "employment-letter", "REQUESTED",
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                1, 0, NOW.minusSeconds(60), null, null, null, null,
                null, null, null, null, null, null);
    }

    private CertificateRepository.ProjectionRow operationsRow(
            int suffix,
            String certificateType,
            String objectPublicId,
            String contentDigest) {
        UUID request = UUID.fromString(String.format(
                "00000000-0000-4000-8000-%012d", suffix));
        return new CertificateRepository.ProjectionRow(
                request, ACTOR, "Hidden Person " + suffix, certificateType, "ISSUED",
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                1, 2, NOW.minusSeconds(60), null, null, null, null,
                UUID.fromString(String.format(
                        "44444444-4444-4444-8444-%012d", suffix)),
                UUID.fromString(objectPublicId), 1, contentDigest,
                NOW.minusSeconds(30), NOW.plusSeconds(3600));
    }
}
