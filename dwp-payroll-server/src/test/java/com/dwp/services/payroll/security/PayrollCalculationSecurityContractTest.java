package com.dwp.services.payroll.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PayrollCalculationSecurityContractTest {

    private static final Instant NOW = Instant.parse("2026-10-14T02:00:00Z");
    private static final UUID RUN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000001101");

    @Test
    void v41RoutesCapabilitiesPurposeAndPopulationsAreExact() {
        PayrollV41PepRegistry registry = registry();
        assertThat(PayrollV41PepRegistry.AUTHORIZATION_VERSION).isEqualTo(41);
        assertThat(PayrollV41PepRegistry.PROJECTION_CHECKSUM)
                .isEqualTo("211067fd8550388691f08ac5172257bbd7164a2942d39119415dc55a6e209206");
        assertThat(PayrollV41PepRegistry.REGISTRY_CHECKSUM)
                .isEqualTo("026157acf598ebabf156b40e3c084c40d52346ebfd2019e2476f30ecdd2b8d93");

        assertRoute(registry, "POST", "/v1/runs/" + RUN_ID + "/attempts",
                "/v1/runs/{runId}/attempts",
                PayrollCalculationAuthority.Action.CALCULATE,
                "route.hcm.operations.payroll-run-calculate.action",
                Map.of("hcm.operations.payroll-run.execute",
                        "ACTION.HRIS_PAYROLL_RUN:EXECUTE"),
                "PAYROLL_PROCESSING", Set.of("LEGAL_ENTITY", "PAY_GROUP"),
                Set.of("PAY_INPUT", "PAY_RESULT"), false, false);
        assertRoute(registry, "GET", "/v1/runs/" + RUN_ID,
                "/v1/runs/{runId}",
                PayrollCalculationAuthority.Action.RUN_VIEW,
                "route.hcm.operations.payroll-run.data",
                Map.of(
                        "hcm.operations.payroll-run.execute",
                        "ACTION.HRIS_PAYROLL_RUN:EXECUTE",
                        "hcm.operations.payroll-support.view",
                        "DATA.HRIS_PAYROLL_STATUS:VIEW"),
                "PAYROLL_PROCESSING",
                Set.of("LEGAL_ENTITY", "PAY_GROUP", "NAMED_POPULATION"),
                Set.of("PAY_INPUT", "PAY_RESULT", "PAY_RESULT_MASKED"),
                false, true);
        assertRoute(registry, "POST", "/v1/runs/" + RUN_ID + "/approvals",
                "/v1/runs/{runId}/approvals",
                PayrollCalculationAuthority.Action.APPROVE,
                "route.hcm.operations.payroll-run-approve.action",
                Map.of("hcm.operations.payroll.approve",
                        "ACTION.HRIS_PAYROLL_APPROVE:APPROVE"),
                "PAYROLL_PROCESSING", Set.of("LEGAL_ENTITY", "PAY_GROUP"),
                Set.of("PAY_RESULT", "TAX_LIMITED"), true, false);
        assertRoute(registry, "POST", "/v1/runs/" + RUN_ID + "/finalize",
                "/v1/runs/{runId}/finalize",
                PayrollCalculationAuthority.Action.FINALIZE,
                "route.hcm.operations.payroll-run-finalize.action",
                Map.of("hcm.operations.payroll.release",
                        "ACTION.HRIS_PAYROLL_RELEASE:EXECUTE"),
                "PAYROLL_PROCESSING", Set.of("LEGAL_ENTITY", "PAY_GROUP"),
                Set.of("BANK_PAYMENT", "PAY_RESULT", "PAY_STATEMENT"),
                true, false);
        assertRoute(registry, "GET", "/v1/command-receipts/" + UUID.randomUUID(),
                "/v1/command-receipts/{receiptId}",
                PayrollCalculationAuthority.Action.RECEIPT_VIEW,
                "route.hcm.operations.payroll-command-receipt.data",
                Map.of("hcm.command-receipt.view",
                        "DATA.HRIS_COMMAND_RECEIPT:VIEW"),
                "COMMAND_STATUS", Set.of("ORIGINATING_COMMAND_SCOPE"),
                Set.of("COMMAND_RECEIPT"), false, true);
    }

    @Test
    void trustedGatewayEvidenceIsRequiredAndBrowserProjectionOverridesAreDenied()
            throws Exception {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        PayrollCalculationSecurityFilter filter = new PayrollCalculationSecurityFilter(
                "service-secret", mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                new PayrollV41PepRegistry(mapper));
        MockHttpServletRequest request = runViewRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<PayrollCalculationAuthority.Subject> captured =
                new AtomicReference<>();

        filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> captured.set(
                        PayrollCalculationRequestContext.require()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get().tenantId()).isEqualTo(1);
        assertThat(captured.get().routeAction())
                .isEqualTo(PayrollCalculationAuthority.Action.RUN_VIEW);
        assertThatThrownBy(PayrollCalculationRequestContext::require)
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNAUTHORIZED));

        request = runViewRequest();
        request.addHeader("X-DWP-Masked-Field-Groups", "PAY_RUN_SUMMARY");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("E2001");
    }

    @Test
    void everyGeneratedRouteRejectsTheClosedQuerySchemaBeforeDispatch()
            throws Exception {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        PayrollCalculationSecurityFilter filter = new PayrollCalculationSecurityFilter(
                "service-secret", mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                new PayrollV41PepRegistry(mapper));
        List<Map.Entry<String, String>> routes = List.of(
                Map.entry("POST", "/v1/runs/" + RUN_ID + "/attempts"),
                Map.entry("GET", "/v1/runs/" + RUN_ID),
                Map.entry("POST", "/v1/runs/" + RUN_ID + "/approvals"),
                Map.entry("POST", "/v1/runs/" + RUN_ID + "/finalize"),
                Map.entry("GET", "/v1/command-receipts/" + UUID.randomUUID()));

        for (Map.Entry<String, String> route : routes) {
            MockHttpServletRequest request = new MockHttpServletRequest(
                    route.getKey(), route.getValue());
            request.addHeader("Authorization", "Bearer user-token");
            request.addHeader("X-DWP-Service-Token", "service-secret");
            request.addParameter("unexpected", "true");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<Boolean> dispatched = new AtomicReference<>(false);

            filter.doFilter(request, response,
                    (ignoredRequest, ignoredResponse) -> dispatched.set(true));

            assertThat(response.getStatus()).as(route.toString()).isEqualTo(400);
            assertThat(response.getContentAsString()).contains("E1001");
            assertThat(dispatched.get()).isFalse();
        }
    }

    @Test
    void allFiveRouteProjectionsFailClosedForEveryDeclaredFieldGroup() {
        PayrollV41PepRegistry registry = registry();
        for (PayrollCalculationRoute route : PayrollCalculationRoute.values()) {
            PayrollCalculationAuthority.Subject subject = subject(route, registry);
            for (String protectedGroup : route.fieldGroups()) {
                for (PayrollFieldProjectionDecisionPort.Decision decision : Set.of(
                        PayrollFieldProjectionDecisionPort.Decision.MASK,
                        PayrollFieldProjectionDecisionPort.Decision.OMIT)) {
                    PayrollFieldProjectionGuard guard = new PayrollFieldProjectionGuard(
                            (ignored, group) -> group.equals(protectedGroup)
                                    ? decision
                                    : PayrollFieldProjectionDecisionPort.Decision.FULL);
                    assertThatThrownBy(() -> guard.requireFull(subject))
                            .isInstanceOfSatisfying(BaseException.class,
                                    exception -> assertThat(exception.getErrorCode())
                                            .isEqualTo(ErrorCode.FORBIDDEN));
                }
                PayrollFieldProjectionGuard unavailable = new PayrollFieldProjectionGuard(
                        (ignored, group) -> group.equals(protectedGroup)
                                ? PayrollFieldProjectionDecisionPort.Decision.UNAVAILABLE
                                : PayrollFieldProjectionDecisionPort.Decision.FULL);
                assertThatThrownBy(() -> unavailable.requireFull(subject))
                        .isInstanceOfSatisfying(BaseException.class,
                                exception -> assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
            }
        }
    }

    @Test
    void generatedRegistryMissingResourceTamperAndMissingRouteFailClosed() throws Exception {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        assertThatThrownBy(() -> new PayrollV41PepRegistry(mapper, () -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent");

        ObjectNode tampered = generated(mapper);
        tampered.put("projectionKey", "tampered");
        assertThatThrownBy(() -> registry(mapper, tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected generated Payroll PEP envelope");

        ObjectNode missingRoute = generated(mapper);
        ArrayNode routes = (ArrayNode) missingRoute.path("routes");
        routes.remove(0);
        assertThatThrownBy(() -> registry(mapper, missingRoute))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void populationTuplesDoNotAuthorizeCartesianPairsAndUnsupportedKindsFailClosed() {
        UUID legalEntityTwo = UUID.randomUUID();
        UUID payrollGroupTwo = UUID.randomUUID();
        PayrollCalculationAuthority.Subject subject = subject(
                PayrollCalculationRoute.RUN_VIEW, registry());
        PayrollCalculationAuthority.Actor actor = new PayrollCalculationAuthority.Actor(
                subject,
                new PayrollCalculationAuthority.Scope(Set.of(
                        new PayrollCalculationAuthority.PopulationTuple(
                                UUID.fromString("00000000-0000-0000-0000-000000001103"),
                                UUID.fromString("00000000-0000-0000-0000-000000001104"),
                                "LEGAL_ENTITY", subject.contextScopeKey()),
                        new PayrollCalculationAuthority.PopulationTuple(
                                legalEntityTwo, payrollGroupTwo,
                                "LEGAL_ENTITY", subject.contextScopeKey())),
                        "projection-41", "a".repeat(64)));
        assertThat(actor.contains(
                UUID.fromString("00000000-0000-0000-0000-000000001103"),
                payrollGroupTwo)).isFalse();

        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcPayrollCalculationScopeResolver resolver =
                new JdbcPayrollCalculationScopeResolver(
                        jdbc, registry(), new PayrollFieldProjectionGuard(
                        (ignored, group) ->
                                PayrollFieldProjectionDecisionPort.Decision.FULL),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        for (String population : List.of("PAY_GROUP", "NAMED_POPULATION")) {
            PayrollCalculationAuthority.Subject unsupported =
                    new PayrollCalculationAuthority.Subject(
                            subject.tenantId(), subject.actorId(),
                            subject.subjectPrincipalPublicId(), subject.routeAction(),
                            subject.routeContractKey(), subject.capabilityContractKey(),
                            subject.purposeCode(), subject.contextKey(),
                            subject.contextScopeKey(), population,
                            subject.fieldPolicyRevision(), subject.authorizationRevision(),
                            subject.decisionRevision(), subject.revalidateAt(),
                            subject.accessMode(), subject.permissions());
            assertThatThrownBy(() -> resolver.resolve(unsupported))
                    .isInstanceOfSatisfying(BaseException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(
                                            ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void receiptReauthorizationUsesSealedOriginAndEveryDenialIsOpaque() {
        PayrollV41PepRegistry registry = registry();
        UUID principal = UUID.fromString(
                "00000000-0000-4000-8000-000000001109");
        UUID receiptId = UUID.fromString(
                "00000000-0000-4000-8000-000000001110");
        UUID legalEntityId = UUID.fromString(
                "00000000-0000-4000-8000-000000001111");
        UUID payrollGroupId = UUID.fromString(
                "00000000-0000-4000-8000-000000001112");
        String scopeKey = "hcm-scope-" + "b".repeat(40);
        Set<String> permissions = new LinkedHashSet<>();
        permissions.addAll(registry.binding(PayrollCalculationRoute.CALCULATE)
                .capabilityCodesByContract().values());
        permissions.addAll(registry.binding(PayrollCalculationRoute.RECEIPT_VIEW)
                .capabilityCodesByContract().values());

        ReceiptScopeJdbc jdbc = new ReceiptScopeJdbc();
        jdbc.scopeRows(List.of(Map.of(
                "projection_revision", "projection-41",
                "legal_entity_id", legalEntityId,
                "payroll_group_id", payrollGroupId)));
        PayrollFieldProjectionDecisionPort full =
                (ignored, group) -> PayrollFieldProjectionDecisionPort.Decision.FULL;
        JdbcPayrollCalculationScopeResolver resolver = receiptResolver(
                jdbc, registry, full);
        PayrollCalculationAuthority.Subject origin = originSubject(
                registry, principal, scopeKey, permissions);
        PayrollCalculationAuthority.Scope originScope = resolver.resolve(origin);
        jdbc.receipt(receiptRow(
                principal, scopeKey, originScope.scopeDigest(),
                legalEntityId, payrollGroupId, "PAYROLL_PROCESSING"));
        PayrollCalculationAuthority.Subject receipt = receiptSubject(
                registry, principal, scopeKey, permissions);

        PayrollCalculationAuthority.Scope authorized =
                resolver.resolveReceipt(receipt, receiptId);
        assertThat(authorized.scopeDigest()).isEqualTo(originScope.scopeDigest());
        assertThat(authorized.populations()).containsExactly(
                new PayrollCalculationAuthority.PopulationTuple(
                        legalEntityId, payrollGroupId,
                        "ORIGINATING_COMMAND_SCOPE", scopeKey));

        jdbc.receipt(receiptRow(
                principal, scopeKey, originScope.scopeDigest(),
                legalEntityId, payrollGroupId, "COMMAND_STATUS"));
        assertOpaque(() -> resolver.resolveReceipt(receipt, receiptId));
        jdbc.receipt(receiptRow(
                principal, scopeKey, originScope.scopeDigest(),
                legalEntityId, payrollGroupId, "PAYROLL_PROCESSING"));

        JdbcPayrollCalculationScopeResolver originRevoked = receiptResolver(
                jdbc, registry, (subject, group) ->
                        subject.routeAction()
                                == PayrollCalculationAuthority.Action.CALCULATE
                                && group.equals("PAY_RESULT")
                                ? PayrollFieldProjectionDecisionPort.Decision.MASK
                                : PayrollFieldProjectionDecisionPort.Decision.FULL);
        assertOpaque(() -> originRevoked.resolveReceipt(receipt, receiptId));

        JdbcPayrollCalculationScopeResolver receiptDenied = receiptResolver(
                jdbc, registry, (subject, group) ->
                        subject.routeAction()
                                == PayrollCalculationAuthority.Action.RECEIPT_VIEW
                                ? PayrollFieldProjectionDecisionPort.Decision.OMIT
                                : PayrollFieldProjectionDecisionPort.Decision.FULL);
        assertOpaque(() -> receiptDenied.resolveReceipt(receipt, receiptId));

        JdbcPayrollCalculationScopeResolver unavailable = receiptResolver(
                jdbc, registry, (subject, group) ->
                        subject.routeAction()
                                == PayrollCalculationAuthority.Action.RECEIPT_VIEW
                                ? PayrollFieldProjectionDecisionPort.Decision.UNAVAILABLE
                                : PayrollFieldProjectionDecisionPort.Decision.FULL);
        assertOpaque(() -> unavailable.resolveReceipt(receipt, receiptId));

        Set<String> receiptOnlyPermission = new LinkedHashSet<>(
                registry.binding(PayrollCalculationRoute.RECEIPT_VIEW)
                        .capabilityCodesByContract().values());
        assertOpaque(() -> resolver.resolveReceipt(receiptSubject(
                registry, principal, scopeKey, receiptOnlyPermission), receiptId));
    }

    private JdbcPayrollCalculationScopeResolver receiptResolver(
            ReceiptScopeJdbc jdbc,
            PayrollV41PepRegistry registry,
            PayrollFieldProjectionDecisionPort decisions) {
        return new JdbcPayrollCalculationScopeResolver(
                jdbc, registry, new PayrollFieldProjectionGuard(decisions),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PayrollCalculationAuthority.Subject originSubject(
            PayrollV41PepRegistry registry,
            UUID principal,
            String scopeKey,
            Set<String> permissions) {
        PayrollV41PepRegistry.Binding binding =
                registry.binding(PayrollCalculationRoute.CALCULATE);
        return new PayrollCalculationAuthority.Subject(
                1, 101, principal, PayrollCalculationAuthority.Action.CALCULATE,
                binding.routeContractKey(),
                binding.matchedCapabilityContract(permissions),
                "PAYROLL_PROCESSING", "ctx", scopeKey, "LEGAL_ENTITY",
                41, 41, "decision-41", NOW.plusSeconds(60), "NORMAL",
                permissions);
    }

    private PayrollCalculationAuthority.Subject receiptSubject(
            PayrollV41PepRegistry registry,
            UUID principal,
            String scopeKey,
            Set<String> permissions) {
        PayrollV41PepRegistry.Binding binding =
                registry.binding(PayrollCalculationRoute.RECEIPT_VIEW);
        return new PayrollCalculationAuthority.Subject(
                1, 101, principal, PayrollCalculationAuthority.Action.RECEIPT_VIEW,
                binding.routeContractKey(),
                binding.matchedCapabilityContract(permissions),
                "COMMAND_STATUS", "ctx", scopeKey,
                "ORIGINATING_COMMAND_SCOPE", 41, 41, "decision-41",
                NOW.plusSeconds(60), "NORMAL", permissions);
    }

    private Map<String, Object> receiptRow(
            UUID principal,
            String scopeKey,
            String scopeDigest,
            UUID legalEntityId,
            UUID payrollGroupId,
            String purposeCode) {
        Map<String, Object> row = new HashMap<>();
        row.put("subject_principal_public_id", principal);
        row.put("command_type", "RUN_CALCULATE");
        row.put("originating_action", "PAY_RUN_CALCULATE");
        row.put("population_kind", "LEGAL_ENTITY");
        row.put("population_scope_digest", scopeDigest);
        row.put("context_scope_key", scopeKey);
        row.put("purpose_code", purposeCode);
        row.put("field_policy_revision", 41L);
        row.put("authorization_revision", 41L);
        row.put("legal_entity_id", legalEntityId);
        row.put("payroll_group_id", payrollGroupId);
        return row;
    }

    private void assertOpaque(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));
    }

    private static final class ReceiptScopeJdbc
            extends NamedParameterJdbcTemplate {

        private List<Map<String, Object>> scopeRows = List.of();
        private Map<String, Object> receipt;

        private ReceiptScopeJdbc() {
            super(mock(DataSource.class));
        }

        private void scopeRows(List<Map<String, Object>> rows) {
            scopeRows = List.copyOf(rows);
        }

        private void receipt(Map<String, Object> row) {
            receipt = Map.copyOf(row);
        }

        @Override
        public <T> T queryForObject(
                String sql,
                SqlParameterSource parameters,
                Class<T> requiredType) {
            return requiredType.cast(Objects.toString(
                    parameters.getValue("tenantId")));
        }

        @Override
        public <T> List<T> query(
                String sql,
                SqlParameterSource parameters,
                RowMapper<T> mapper) {
            List<Map<String, Object>> rows = sql.contains("pay_command_receipts")
                    ? receipt == null ? List.of() : List.of(receipt)
                    : scopeRows;
            List<T> mapped = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                try {
                    mapped.add(mapper.mapRow(resultSet(rows.get(index)), index));
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            }
            return mapped;
        }

        private ResultSet resultSet(Map<String, Object> row) {
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getString" -> Objects.toString(
                                row.get(arguments[0]), null);
                        case "getLong" -> ((Number) row.get(arguments[0]))
                                .longValue();
                        case "getObject" -> row.get(arguments[0]);
                        case "wasNull" -> false;
                        case "toString" -> "receipt-scope-row";
                        default -> throw new UnsupportedOperationException(
                                method.getName());
                    });
        }
    }

    private void assertRoute(
            PayrollV41PepRegistry registry,
            String method,
            String path,
            String pathTemplate,
            PayrollCalculationAuthority.Action action,
            String routeKey,
            Map<String, String> capabilities,
            String purpose,
            Set<String> populations,
            Set<String> fieldGroups,
            boolean highRisk,
            boolean responseProjection) {
        PayrollV41PepRegistry.Binding binding = registry.resolve(method, path)
                .orElseThrow();
        PayrollCalculationRoute route = binding.route();
        assertThat(route.action()).isEqualTo(action);
        assertThat(route.routeContractKey()).isEqualTo(routeKey);
        assertThat(route.purposeCode()).isEqualTo(purpose);
        assertThat(route.fieldGroups()).containsExactlyInAnyOrderElementsOf(fieldGroups);
        for (String population : Set.of(
                "LEGAL_ENTITY", "PAY_GROUP", "NAMED_POPULATION",
                "ORIGINATING_COMMAND_SCOPE")) {
            assertThat(route.populationAllowed(population))
                    .as("%s population %s", route, population)
                    .isEqualTo(populations.contains(population));
        }
        assertThat(binding.routeContractKey()).isEqualTo(routeKey);
        assertThat(binding.method()).isEqualTo(method);
        assertThat(binding.pathTemplate()).isEqualTo(pathTemplate);
        assertThat(binding.capabilityCodesByContract()).containsExactlyInAnyOrderEntriesOf(
                capabilities);
        assertThat(binding.highRisk()).isEqualTo(highRisk);
        assertThat(binding.responseProjection()).isEqualTo(responseProjection);
    }

    private PayrollCalculationAuthority.Subject subject(
            PayrollCalculationRoute route,
            PayrollV41PepRegistry registry) {
        String capability = registry.binding(route).capabilityCodesByContract()
                .keySet().stream().sorted().findFirst().orElseThrow();
        String population = route == PayrollCalculationRoute.RECEIPT_VIEW
                ? "ORIGINATING_COMMAND_SCOPE" : "LEGAL_ENTITY";
        return new PayrollCalculationAuthority.Subject(
                1, 101, UUID.randomUUID(), route.action(), route.routeContractKey(),
                capability, route.purposeCode(), "ctx",
                "hcm-scope-" + "a".repeat(40), population,
                7, 41, "decision-41", NOW.plusSeconds(60), "NORMAL",
                Set.of("APP.HCM:VIEW"));
    }

    private PayrollV41PepRegistry registry() {
        return new PayrollV41PepRegistry(
                JsonMapper.builder().findAndAddModules().build());
    }

    private PayrollV41PepRegistry registry(JsonMapper mapper, ObjectNode projection) {
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(projection);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return new PayrollV41PepRegistry(
                mapper, () -> new ByteArrayInputStream(bytes));
    }

    private ObjectNode generated(JsonMapper mapper) throws Exception {
        try (var input = PayrollV41PepRegistry.class.getClassLoader()
                .getResourceAsStream(PayrollV41PepRegistry.RESOURCE)) {
            assertThat(input).isNotNull();
            return (ObjectNode) mapper.readTree(input);
        }
    }

    private MockHttpServletRequest runViewRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/runs/" + RUN_ID);
        request.addHeader("Authorization", "Bearer user-token");
        request.addHeader("X-DWP-Service-Token", "service-secret");
        request.addHeader("X-DWP-Tenant-ID", "1");
        request.addHeader("X-DWP-User-ID", "101");
        request.addHeader("X-DWP-Subject-Principal-Public-ID", UUID.randomUUID());
        request.addHeader("X-DWP-Permissions",
                "APP.HCM:VIEW,DATA.HRIS_PAYROLL_STATUS:VIEW");
        request.addHeader("X-DWP-Route-Contract-Key",
                "route.hcm.operations.payroll-run.data");
        request.addHeader("X-DWP-Current-Decision-Revision", "decision-41");
        request.addHeader("X-DWP-Current-Revalidate-At", NOW.plusSeconds(300));
        request.addHeader("X-DWP-Context-Key", "hcm.operations.payroll-support.view");
        request.addHeader("X-DWP-Context-Scope-Key", HcmEligibilityScopeKey.derived(
                1, 101, "payroll", "root", "41", "41"));
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        request.addHeader("X-DWP-Field-Policy-Revision", "7");
        request.addHeader("X-DWP-Authorization-Revision", "41");
        request.addHeader("X-DWP-Population-Kind", "PAY_GROUP");
        request.addHeader("X-Purpose-Code", "PAYROLL_PROCESSING");
        return request;
    }
}
