package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollStepUpVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.FilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class PayrollPopulationEntryQueryContractTest {

    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    private static final UUID LEGAL_ENTITY =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PAY_GROUP =
            UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID SUBJECT =
            UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final String SCOPE_DIGEST = "a".repeat(64);
    private static final String PROJECTION_DIGEST = "b".repeat(64);
    private static final String CURSOR_SECRET = "pay013-cursor-secret-32-byte-minimum";

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void acceptedIaqUsesClosedDeepObjectFilterRepresentations() throws Exception {
        PayrollPreparationController controller = new PayrollPreparationController(
                mock(PayrollPreparationService.class),
                mock(PayrollPreparationScopeResolver.class));

        MultiValueMap<String, String> empty = new LinkedMultiValueMap<>();
        empty.add("filter", "{}");
        PayrollPreparationModels.EntryQueryFilter emptyFilter = filter(controller, empty);
        assertThat(emptyFilter.payrollPopulation()).isNull();
        assertThat(emptyFilter.projectionVersion()).isNull();
        assertThat(emptyFilter.effectiveOn()).isNull();

        MultiValueMap<String, String> nested = new LinkedMultiValueMap<>();
        nested.add("filter[payrollPopulation][minimumItemCount]", "2");
        nested.add("filter[projectionVersion][keys]", "PAYROLL_POPULATION");
        nested.add("filter[projectionVersion][keys]", "PROJECTION_VERSION");
        nested.add("filter[effectiveOn]", "2026-10-17");
        PayrollPreparationModels.EntryQueryFilter nestedFilter =
                filter(controller, nested);
        assertThat(nestedFilter.payrollPopulation().minimumItemCount()).isEqualTo(2);
        assertThat(nestedFilter.projectionVersion().keys())
                .containsExactly("PAYROLL_POPULATION", "PROJECTION_VERSION");

        MultiValueMap<String, String> mixed = new LinkedMultiValueMap<>(nested);
        mixed.add("filter", "{}");
        assertFilterRejected(controller, mixed);
        assertFilterRejected(controller, new LinkedMultiValueMap<>());

        MultiValueMap<String, String> commaFolded = new LinkedMultiValueMap<>();
        commaFolded.add("filter[projectionVersion][keys]",
                "PAYROLL_POPULATION,PROJECTION_VERSION");
        assertFilterRejected(controller, commaFolded);
    }

    @Test
    void iaqSuccessWireBodyIsTheClosedDirectProjection() throws Exception {
        PayrollPreparationService transport = mock(PayrollPreparationService.class);
        PayrollPreparationModels.EntryQueryResponse projection =
                new PayrollPreparationModels.EntryQueryResponse(
                        "1", NOW, "POPULATION",
                        new PayrollPreparationModels.QueryState(
                                "EMPTY", "scope-revision-9", "CURRENT", null,
                                new PayrollPreparationModels.FieldDecisionSummary(
                                        0, 0, 0),
                                List.of(), UUID.randomUUID(),
                                new PayrollPreparationModels.CachePolicy(
                                        "decision-9",
                                        "dwp.hris.access.assignment.changed.v1", 0)),
                        List.of());
        when(transport.population(any(), any())).thenReturn(projection);
        PayrollPreparationController controller = new PayrollPreparationController(
                transport, mock(PayrollPreparationScopeResolver.class));
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("filter", "{}");

        PayrollPreparationModels.EntryQueryResponse response =
                controller.population(
                        actor(PayrollPreparationRoute.DATA), null, null, 10,
                        "en-GB", List.of("itemPublicId:ASC"), "Europe/London",
                        projection.queryState().correlationId(), parameters);
        JsonNode body = mapper.valueToTree(response);

        assertThat(body.fieldNames()).toIterable().containsExactly(
                "projectionVersion", "asOf", "variantKey", "queryState", "items");
        assertThat(body.has("data")).isFalse();
        assertThat(body.has("status")).isFalse();
        assertThat(body.has("success")).isFalse();
        assertThat(body.has("timestamp")).isFalse();
    }

    @Test
    void cursorIsAuthenticatedScopeBoundAsOfBoundAndExpires() {
        PayrollPreparationCanonical canonical = new PayrollPreparationCanonical(
                mapper, CURSOR_SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        String cursor = canonical.cursor(17, PROJECTION_DIGEST, NOW);

        PayrollPreparationCanonical.CursorState decoded = canonical.cursorState(cursor);
        assertThat(decoded.offset()).isEqualTo(17);
        assertThat(decoded.bindingDigest()).isEqualTo(PROJECTION_DIGEST);
        assertThat(decoded.asOf()).isEqualTo(NOW);

        char replacement = cursor.charAt(cursor.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = cursor.substring(0, cursor.length() - 1) + replacement;
        assertCode(() -> canonical.cursorState(tampered), ErrorCode.INVALID_INPUT_VALUE);

        PayrollPreparationCanonical anotherScope = new PayrollPreparationCanonical(
                mapper, "different-pay013-cursor-secret-32-byte",
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertCode(() -> anotherScope.cursorState(cursor), ErrorCode.INVALID_INPUT_VALUE);

        PayrollPreparationCanonical expired = new PayrollPreparationCanonical(
                mapper, CURSOR_SECRET,
                Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC));
        assertCode(() -> expired.cursorState(cursor), ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void missingCursorAuthorityFailsBeforeTenantOrPopulationCalls() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PayrollPreparationSemanticSnapshotPort semantics =
                mock(PayrollPreparationSemanticSnapshotPort.class);
        PayrollPreparationPopulationSnapshotPort populations =
                mock(PayrollPreparationPopulationSnapshotPort.class);
        JdbcPayrollPreparationStore store = new JdbcPayrollPreparationStore(
                jdbc,
                new PayrollPreparationCanonical(
                        mapper, "short", Clock.fixed(NOW, ZoneOffset.UTC)),
                semantics, populations, Clock.fixed(NOW, ZoneOffset.UTC));

        assertCode(() -> store.population(actor(PayrollPreparationRoute.DATA), query()),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        verifyNoInteractions(jdbc, semantics, populations);
    }

    @Test
    void readyAndActionRequireEveryCurrentPreflightPrerequisite() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PayrollPreparationQueryJdbc queries = new PayrollPreparationQueryJdbc(
                jdbc,
                new PayrollPreparationCanonical(
                        mapper, CURSOR_SECRET, Clock.fixed(NOW, ZoneOffset.UTC)),
                mock(PayrollPreparationSemanticSnapshotPort.class),
                mock(PayrollPreparationPopulationSnapshotPort.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PayrollPreparationSourceJdbc.class));
        PayrollPreparationAuthority.Actor actor = actor(PayrollPreparationRoute.DATA);
        PayrollPreparationAuthority.PopulationTuple tuple =
                actor.scope().populations().iterator().next();

        PayrollPreparationModels.EntryItem ready = queries.entryItem(
                actor, tuple, 4L, 9L, NOW, PROJECTION_DIGEST, true);
        assertThat(ready.statusCode()).isEqualTo("READY");
        assertThat(ready.availableActions()).hasSize(1);

        PayrollPreparationModels.EntryItem blocked = queries.entryItem(
                actor, tuple, 4L, 9L, NOW, PROJECTION_DIGEST, false);
        assertThat(blocked.statusCode()).isEqualTo("PREFLIGHT_BLOCKED");
        assertThat(blocked.availableActions()).isEmpty();
    }

    @Test
    void generatedV49PageDataAndPrepareBindingsArePinnedAndMutationsFailClosed()
            throws Exception {
        PayrollPreparationV49Registry registry =
                new PayrollPreparationV49Registry(mapper);
        assertThat(registry.binding(PayrollPreparationRoute.PAGE).route()
                .routeContractKey()).isEqualTo(
                "route.hcm.operations.payroll-population.page");
        assertThat(registry.binding(PayrollPreparationRoute.DATA).route()
                .routeContractKey()).isEqualTo(
                "route.hcm.operations.payroll-support.data");
        assertThat(PayrollPreparationRoute.PREPARE.predicatePolicyKey())
                .isEqualTo("predicate.hcm-payroll-run-prepare-authority.v1");
        assertThat(PayrollPreparationRoute.PREPARE.activeAccessModes())
                .containsExactly("ELEVATED");

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                PayrollPreparationV49Registry.RESOURCE)) {
            byte[] original = input.readAllBytes();
            String changed = new String(original, StandardCharsets.UTF_8)
                    .replaceFirst("\"sideEffectFree\"\\s*:\\s*true",
                            "\"sideEffectFree\":false");
            assertThat(changed.getBytes(StandardCharsets.UTF_8))
                    .isNotEqualTo(original);
            assertThatThrownBy(() -> new PayrollPreparationV49Registry(
                    mapper, () -> new ByteArrayInputStream(
                    changed.getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void formulaSetUsesJavaUuidOrderAndCanonicalDigestForMixedHighBits() {
        PayrollPreparationModels.FormulaRef positive =
                new PayrollPreparationModels.FormulaRef(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        3, "c".repeat(64));
        PayrollPreparationModels.FormulaRef negative =
                new PayrollPreparationModels.FormulaRef(
                        UUID.fromString("f0000000-0000-0000-0000-000000000001"),
                        7, "d".repeat(64));
        List<PayrollPreparationModels.FormulaRef> ordered =
                JdbcPayrollPreparationStore.orderFormulas(
                        List.of(positive, negative));
        assertThat(ordered).containsExactly(negative, positive);

        PayrollPreparationCanonical canonical = new PayrollPreparationCanonical(
                mapper, CURSOR_SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(canonical.digest(ordered))
                .isEqualTo(canonical.digest(List.of(negative, positive)))
                .isNotEqualTo(canonical.digest(List.of(positive, negative)));
    }

    @Test
    void unsupportedPopulationKindsFailBeforeAnyScopeStoreCall() {
        for (String kind : List.of("PAY_GROUP", "NAMED_POPULATION")) {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            JdbcPayrollPreparationScopeResolver resolver =
                    new JdbcPayrollPreparationScopeResolver(
                            jdbc,
                            new PayrollPreparationCanonical(
                                    mapper, CURSOR_SECRET,
                                    Clock.fixed(NOW, ZoneOffset.UTC)),
                            Clock.fixed(NOW, ZoneOffset.UTC));
            assertCode(() -> resolver.resolve(subject(PayrollPreparationRoute.DATA, kind)),
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
            verifyNoInteractions(jdbc);
        }
    }

    @Test
    void defaultOffRegistersNoPreparationRuntimeBeansAndEnabledDefaultsAreUnavailable() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        PayrollPreparationController.class,
                        PayrollPreparationSecurityFilter.class,
                        PayrollPreparationService.class,
                        JdbcPayrollPreparationStore.class,
                        JdbcPayrollPreparationScopeResolver.class,
                        PayrollPreparationStepUpGuard.class,
                        PayrollPreparationCanonical.class,
                        PayrollPreparationV49Registry.class,
                        PayrollPreparationProblemAdvice.class,
                        PayrollPreparationSemanticConfiguration.class,
                        PayrollStepUpVerifier.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationController.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationSecurityFilter.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationService.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationStore.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationScopeResolver.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationStepUpGuard.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationCanonical.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationV49Registry.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationProblemAdvice.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollStepUpVerifier.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationSemanticSnapshotPort.class);
                    assertThat(context).doesNotHaveBean(
                            PayrollPreparationPopulationSnapshotPort.class);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(PayrollPreparationSemanticConfiguration.class)
                .withPropertyValues(
                        "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled=true")
                .run(context -> {
                    PayrollPreparationSemanticSnapshotPort semantic = context.getBean(
                            PayrollPreparationSemanticSnapshotPort.class);
                    PayrollPreparationPopulationSnapshotPort population = context.getBean(
                            PayrollPreparationPopulationSnapshotPort.class);
                    assertThat(semantic.resolve(List.of(), List.of()).availability())
                            .isEqualTo(
                                    PayrollPreparationSemanticSnapshotPort.Availability.UNAVAILABLE);
                    assertThat(population.review(
                            actor(PayrollPreparationRoute.DATA),
                            actor(PayrollPreparationRoute.DATA).scope().populations()
                                    .iterator().next(), NOW).availability())
                            .isEqualTo(
                                    PayrollPreparationPopulationSnapshotPort.Availability.UNAVAILABLE);
                });
    }

    @Test
    void unenrolledOtherwiseValidRequestStopsBeforeEveryDownstreamCall()
            throws Exception {
        PayrollPreparationService service = mock(PayrollPreparationService.class);
        PayrollPreparationStore store = mock(PayrollPreparationStore.class);
        PayrollPreparationSemanticSnapshotPort semantics =
                mock(PayrollPreparationSemanticSnapshotPort.class);
        PayrollPreparationPopulationSnapshotPort populations =
                mock(PayrollPreparationPopulationSnapshotPort.class);
        Runnable calculation = mock(Runnable.class);
        Runnable worker = mock(Runnable.class);
        FilterChain downstream = mock(FilterChain.class);
        doAnswer(ignored -> {
            service.population(null, null);
            store.population(null, null);
            semantics.resolve(List.of(), List.of());
            populations.review(null, null, NOW);
            calculation.run();
            worker.run();
            return null;
        }).when(downstream).doFilter(any(), any());
        PayrollPreparationSecurityFilter filter =
                new PayrollPreparationSecurityFilter(
                        "service-secret", "2", mapper,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new PayrollPreparationV49Registry(mapper));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/workbench/population");
        request.addHeader("Authorization", "Bearer verified-user");
        request.addHeader(PayrollPreparationSecurityFilter.SERVICE_TOKEN,
                "service-secret");
        request.addHeader(PayrollPreparationSecurityFilter.TENANT, "1");
        request.addHeader(PayrollPreparationSecurityFilter.ACTOR, "101");
        request.addHeader(PayrollPreparationSecurityFilter.SUBJECT,
                SUBJECT.toString());
        request.addHeader(PayrollPreparationSecurityFilter.PERMISSIONS,
                "APP.HCM:VIEW,DATA.HRIS_PAYROLL_STATUS:VIEW");
        request.addHeader(PayrollPreparationSecurityFilter.ROUTE,
                PayrollPreparationRoute.DATA.routeContractKey());
        request.addHeader(PayrollPreparationSecurityFilter.DECISION_REVISION,
                "decision-9");
        request.addHeader(PayrollPreparationSecurityFilter.REVALIDATE_AT,
                NOW.plusSeconds(300).toString());
        request.addHeader(PayrollPreparationSecurityFilter.CONTEXT,
                "PAYROLL_OPERATIONS");
        request.addHeader(PayrollPreparationSecurityFilter.SCOPE,
                "hcm-scope-" + "a".repeat(40));
        request.addHeader(PayrollPreparationSecurityFilter.ACCESS_MODE,
                "ELEVATED");
        request.addHeader(PayrollPreparationSecurityFilter.FIELD_POLICY_REVISION,
                "7");
        request.addHeader(PayrollPreparationSecurityFilter.AUTHORIZATION_REVISION,
                "9");
        request.addHeader(PayrollPreparationSecurityFilter.POPULATION_KIND,
                "LEGAL_ENTITY");
        request.addHeader(PayrollPreparationSecurityFilter.PURPOSE,
                "PAYROLL_OPERATIONS");
        request.addParameter("limit", "10");
        request.addParameter("filter", "{}");
        request.addParameter("sort", "itemPublicId:ASC");
        request.addParameter("locale", "en-GB");
        request.addParameter("timeZone", "Europe/London");
        request.addParameter("correlationId", UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, downstream);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("TARGET_NOT_DISCLOSED");
        verifyNoInteractions(
                downstream, service, store, semantics, populations,
                calculation, worker);
        assertCode(PayrollPreparationRequestContext::require,
                ErrorCode.UNAUTHORIZED);
    }

    @Test
    void mismatchedPurposeStopsBeforeEveryDownstreamCall() throws Exception {
        PayrollPreparationService service = mock(PayrollPreparationService.class);
        PayrollPreparationStore store = mock(PayrollPreparationStore.class);
        PayrollPreparationSemanticSnapshotPort semantics =
                mock(PayrollPreparationSemanticSnapshotPort.class);
        PayrollPreparationPopulationSnapshotPort populations =
                mock(PayrollPreparationPopulationSnapshotPort.class);
        FilterChain downstream = mock(FilterChain.class);
        PayrollPreparationSecurityFilter filter =
                new PayrollPreparationSecurityFilter(
                        "service-secret", "1", mapper,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new PayrollPreparationV49Registry(mapper));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/runs");
        request.addHeader("Authorization", "Bearer verified-user");
        request.addHeader(PayrollPreparationSecurityFilter.SERVICE_TOKEN,
                "service-secret");
        request.addHeader(PayrollPreparationSecurityFilter.TENANT, "1");
        request.addHeader(PayrollPreparationSecurityFilter.ACTOR, "101");
        request.addHeader(PayrollPreparationSecurityFilter.SUBJECT,
                SUBJECT.toString());
        request.addHeader(PayrollPreparationSecurityFilter.PERMISSIONS,
                "APP.HCM:VIEW,ACTION.HRIS_PAYROLL_RUN:EXECUTE");
        request.addHeader(PayrollPreparationSecurityFilter.ROUTE,
                PayrollPreparationRoute.PREPARE.routeContractKey());
        request.addHeader(PayrollPreparationSecurityFilter.DECISION_REVISION,
                "decision-9");
        request.addHeader(PayrollPreparationSecurityFilter.REVALIDATE_AT,
                NOW.plusSeconds(300).toString());
        request.addHeader(PayrollPreparationSecurityFilter.CONTEXT,
                "PAYROLL_OPERATIONS");
        request.addHeader(PayrollPreparationSecurityFilter.SCOPE,
                "hcm-scope-" + "a".repeat(40));
        request.addHeader(PayrollPreparationSecurityFilter.ACCESS_MODE,
                "ELEVATED");
        request.addHeader(PayrollPreparationSecurityFilter.FIELD_POLICY_REVISION,
                "7");
        request.addHeader(PayrollPreparationSecurityFilter.AUTHORIZATION_REVISION,
                "9");
        request.addHeader(PayrollPreparationSecurityFilter.POPULATION_KIND,
                "LEGAL_ENTITY");
        request.addHeader(PayrollPreparationSecurityFilter.PURPOSE,
                "PAYROLL_OPERATIONS");
        request.addHeader(PayrollPreparationSecurityFilter.IDEMPOTENCY,
                "pay013-purpose-mismatch-0001");
        request.addHeader(PayrollPreparationSecurityFilter.CORRELATION,
                UUID.randomUUID().toString());
        request.addHeader(PayrollPreparationSecurityFilter.EXPECTED_VERSION, "0");
        request.addHeader(
                PayrollPreparationSecurityFilter.EXPECTED_DECISION_REVISION,
                "decision-9");
        request.addHeader(PayrollPreparationSecurityFilter.STEP_UP,
                "signed-command-challenge");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, downstream);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("RESULT_UNKNOWN");
        verifyNoInteractions(downstream, service, store, semantics, populations);
        assertCode(PayrollPreparationRequestContext::require,
                ErrorCode.UNAUTHORIZED);
    }

    @Test
    void problemStatusAndCorrelationAreClosed() {
        UUID correlation = UUID.randomUUID();
        Set<Integer> iaqStatuses = Set.of(400, 401, 403, 404, 409, 429, 503);
        for (ErrorCode error : List.of(
                ErrorCode.INVALID_INPUT_VALUE, ErrorCode.UNAUTHORIZED,
                ErrorCode.FORBIDDEN, ErrorCode.NOT_FOUND,
                ErrorCode.RESOURCE_CONFLICT, ErrorCode.INVALID_STATE,
                ErrorCode.SERVICE_RATE_LIMITED,
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE)) {
            PayrollPreparationProblems.Definition definition =
                    PayrollPreparationProblems.define(true, error, "closed", correlation);
            JsonNode body = mapper.valueToTree(definition.body());
            assertThat(iaqStatuses).contains(definition.status());
            assertThat(body.size()).isEqualTo(6);
            assertThat(body.path("correlationId").asText())
                    .isEqualTo(correlation.toString());
            assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder(
                    "type", "title", "status", "code", "correlationId", "retryable");
        }
        PayrollPreparationProblems.Definition command =
                PayrollPreparationProblems.define(
                        false, ErrorCode.INVALID_STATE, "closed", correlation);
        assertThat(command.status()).isEqualTo(422);
        assertThat(mapper.valueToTree(command.body()).size()).isEqualTo(6);
    }

    private PayrollPreparationModels.EntryQueryFilter filter(
            PayrollPreparationController controller,
            MultiValueMap<String, String> values) throws Exception {
        Method method = PayrollPreparationController.class.getDeclaredMethod(
                "filter", MultiValueMap.class);
        method.setAccessible(true);
        return (PayrollPreparationModels.EntryQueryFilter) method.invoke(controller, values);
    }

    private void assertFilterRejected(
            PayrollPreparationController controller,
            MultiValueMap<String, String> values) throws Exception {
        assertThatThrownBy(() -> filter(controller, values))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(BaseException.class);
    }

    private PayrollPreparationModels.EntryQuery query() {
        return new PayrollPreparationModels.EntryQuery(
                NOW, null, new PayrollPreparationModels.EntryQueryFilter(null, null, null),
                10, "xx", List.of("itemPublicId:ASC"), "x", UUID.randomUUID());
    }

    private PayrollPreparationAuthority.Actor actor(PayrollPreparationRoute route) {
        PayrollPreparationAuthority.Subject subject = subject(route, "LEGAL_ENTITY");
        return new PayrollPreparationAuthority.Actor(
                subject, new PayrollPreparationAuthority.Scope(
                "scope-revision-9", SCOPE_DIGEST,
                Set.of(new PayrollPreparationAuthority.PopulationTuple(
                        LEGAL_ENTITY, PAY_GROUP, subject.contextScopeKey()))));
    }

    private PayrollPreparationAuthority.Subject subject(
            PayrollPreparationRoute route, String populationKind) {
        return new PayrollPreparationAuthority.Subject(
                1, 101, SUBJECT, route, route.capabilityContractKey(),
                route.purposeCode(), "PAYROLL_OPERATIONS", "scope-key-101",
                populationKind, 7, 9, "decision-9", NOW.plusSeconds(300),
                "ELEVATED", Set.of(
                route.capabilityCode(), PayrollPreparationRoute.PREPARE.capabilityCode()));
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            ErrorCode expected) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(expected));
    }
}
