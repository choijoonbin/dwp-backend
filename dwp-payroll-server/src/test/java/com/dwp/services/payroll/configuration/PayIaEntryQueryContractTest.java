package com.dwp.services.payroll.configuration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.payroll.configuration.PayrollConfigurationAccess.Action;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CachePolicy;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CreateElementVersionRequest;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CreateFormulaVersionRequest;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.DeepLink;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FieldDecision;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FieldDecisionSummary;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PartialFailure;
import static com.dwp.services.payroll.configuration.PayFormulaAstContract.PayValueType;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PolicyFilter;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PolicyValue;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.Problem;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.QueryState;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.RoundingPolicy;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.StatusValue;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.StatusFilter;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.VersionCommand;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.WorkbenchFilter;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.WorkbenchItem;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.WorkbenchQuery;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.WorkbenchView;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.GOLDEN_DIGEST;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.LEGAL_ENTITY;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.NOW;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.PAYROLL_GROUP;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.actor;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.grossFormula;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayIaEntryQueryContractTest {

    private PayrollConfigurationTestSupport.InMemoryStore store;
    private PayrollConfigurationService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        store = new PayrollConfigurationTestSupport.InMemoryStore();
        service = PayrollConfigurationTestSupport.service(store);
        mapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void emptyUnavailableHaveNoItems() {
        WorkbenchView empty = service.workbench(
                actor(1, 100, Action.WORKBENCH_VIEW), query(100, null));
        assertThat(empty.queryState().state()).isEqualTo("EMPTY");
        assertThat(empty.items()).isEmpty();

        WorkbenchView unavailable = new WorkbenchView(
                "1", NOW, "PAYROLL",
                new QueryState(
                        "UNAVAILABLE", "scope-v1", "UNAVAILABLE", null,
                        new FieldDecisionSummary(0, 0, 0), List.of(),
                        UUID.randomUUID(), cache("auth-v1")),
                List.of());
        assertThat(unavailable.items()).isEmpty();
        assertThatThrownBy(() -> new WorkbenchView(
                "1", NOW, "PAYROLL", unavailable.queryState(),
                List.of(sampleItem())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialRequiresFailure() {
        assertThatThrownBy(() -> new QueryState(
                "PARTIAL", "scope-v1", "STALE", null,
                new FieldDecisionSummary(0, 0, 0), List.of(),
                UUID.randomUUID(), cache("auth-v1")))
                .isInstanceOf(IllegalArgumentException.class);

        PartialFailure failure = new PartialFailure(
                "payroll.formula", "PROJECTION_STALE", UUID.randomUUID(), true);
        QueryState partial = new QueryState(
                "PARTIAL", "scope-v1", "STALE", null,
                new FieldDecisionSummary(0, 0, 0), List.of(failure),
                UUID.randomUUID(), cache("auth-v1"));
        assertThat(partial.partialFailures()).containsExactly(failure);
    }

    @Test
    void completeHasNoFailure() {
        PartialFailure failure = new PartialFailure(
                "payroll.formula", "PROJECTION_STALE", UUID.randomUUID(), true);
        assertThatThrownBy(() -> new QueryState(
                "COMPLETE", "scope-v1", "CURRENT", null,
                new FieldDecisionSummary(0, 0, 0), List.of(failure),
                UUID.randomUUID(), cache("auth-v1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new QueryState(
                "COMPLETE", "scope-v1", "CURRENT", null,
                new FieldDecisionSummary(0, 0, 0), List.of(),
                UUID.randomUUID(), cache("auth-v1")).partialFailures()).isEmpty();
    }

    @Test
    void cursorBindsScopeAndRevision() {
        seedElements(2);
        var actor = actor(
                1, 100, "scope-v1", "auth-v1",
                java.util.Set.of(Action.WORKBENCH_VIEW));
        WorkbenchView first = service.workbench(actor, query(1, null));
        assertThat(first.queryState().nextCursor()).isNotNull();

        var changedScope = actor(
                1, 100, "scope-v2", "auth-v1",
                java.util.Set.of(Action.WORKBENCH_VIEW));
        assertThatThrownBy(() -> service.workbench(
                changedScope, query(1, first.queryState().nextCursor())))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));

        var changedAuthorization = actor(
                1, 100, "scope-v1", "auth-v2",
                java.util.Set.of(Action.WORKBENCH_VIEW));
        assertThatThrownBy(() -> service.workbench(
                changedAuthorization, query(1, first.queryState().nextCursor())))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
    }

    @Test
    void pageHonorsLimitAndStableOrder() {
        seedElements(3);
        var actor = actor(1, 100, Action.WORKBENCH_VIEW);
        WorkbenchView first = service.workbench(actor, query(2, null));
        WorkbenchView second = service.workbench(
                actor, query(2, first.queryState().nextCursor()));

        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(1);
        List<String> combined = new ArrayList<>();
        first.items().forEach(item -> combined.add(item.itemPublicId()));
        second.items().forEach(item -> combined.add(item.itemPublicId()));
        assertThat(combined).isSorted();

        Method workbench = java.util.Arrays.stream(
                        PayrollConfigurationController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("workbench"))
                .findFirst().orElseThrow();
        Map<String, Class<?>> requestParameterTypes = new LinkedHashMap<>();
        for (Parameter parameter : workbench.getParameters()) {
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            if (requestParam != null) {
                requestParameterTypes.put(requestParam.value(), parameter.getType());
            }
        }
        assertThat(requestParameterTypes)
                .containsEntry("sort", List.class)
                .containsEntry("filter.effectiveStatus.codes", List.class)
                .containsEntry("filter.payrollPolicy.policyKeys", List.class)
                .containsEntry("filter.policyVersion.policyKeys", List.class);
        WorkbenchFilter repeatedFilters = new WorkbenchFilter(
                null,
                new StatusFilter(List.of("DRAFT", "VALIDATED"), null),
                new PolicyFilter(
                        List.of("pay.formula.gross", "pay.element.base"), null),
                new PolicyFilter(
                        List.of("pay.formula.gross", "pay.element.base"), null));
        assertThat(repeatedFilters.effectiveStatus().codes()).hasSize(2);
        assertThat(repeatedFilters.payrollPolicy().policyKeys()).hasSize(2);
        assertThat(repeatedFilters.policyVersion().policyKeys()).hasSize(2);
    }

    @Test
    void fieldSummaryMatchesSerializedDecisions() throws Exception {
        seedElements(2);
        WorkbenchView view = service.workbench(
                actor(1, 100, Action.WORKBENCH_VIEW), query(100, null));
        int viewed = view.items().stream().mapToInt(item -> (int) item.fieldGroups()
                .values().stream().filter(field -> "VIEW".equals(field.decision())).count()).sum();
        int masked = view.items().stream().mapToInt(item -> (int) item.fieldGroups()
                .values().stream().filter(field -> "MASK".equals(field.decision())).count()).sum();
        int omitted = view.items().size() * 3 - viewed - masked;

        assertThat(view.queryState().fieldDecisionSummary())
                .isEqualTo(new FieldDecisionSummary(viewed, masked, omitted));
        JsonNode json = mapper.valueToTree(view);
        assertThat(json.path("queryState").path("fieldDecisionSummary")
                .path("viewCount").asInt()).isEqualTo(viewed);
    }

    @Test
    void deepLinksAndActionsAreAllowlisted() {
        seedValidatedFormula();
        WorkbenchView view = service.workbench(
                actor(1, 200, Action.WORKBENCH_VIEW, Action.FORMULA_PUBLISH),
                query(100, null));
        WorkbenchItem formula = view.items().stream()
                .filter(item -> item.itemPublicId().startsWith("formula:"))
                .findFirst().orElseThrow();

        assertThat(formula.deepLink().route()).isEqualTo("/hr/settings/payroll");
        assertThat(formula.availableActions()).singleElement().satisfies(action -> {
            assertThat(action.actionKey()).isEqualTo("hris.action.pay.formula.publish");
            assertThat(action.method()).isEqualTo("POST");
            assertThat(action.hrefTemplate())
                    .isEqualTo("/api/payroll/v1/formulas/{formulaId}/publish");
            assertThat(action.idempotencyPolicy()).isEqualTo("REQUIRED_IDEMPOTENCY_KEY");
            assertThat(action.stepUpPolicy())
                    .isEqualTo("OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
        });
    }

    @Test
    void trustedTenantContextIsNotSerialized() throws Exception {
        seedElements(1);
        WorkbenchView view = service.workbench(
                actor(1, 100, Action.WORKBENCH_VIEW), query(100, null));
        String json = mapper.writeValueAsString(view);

        assertThat(json)
                .doesNotContain("tenantId", "actorId", "subjectPrincipalPublicId")
                .contains("scopeDecisionRevision", "authorizationDecisionRevision");
    }

    @Test
    void localeAndTimeZoneAreCanonical() {
        assertThat(query(100, null).locale()).isEqualTo("ko-KR");
        assertThat(query(100, null).timeZone()).isEqualTo("Asia/Seoul");
        assertThatThrownBy(() -> new WorkbenchQuery(
                null, null, emptyFilter(), 10, "ko_kr",
                List.of("itemPublicId:ASC"), "Asia/Seoul", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkbenchQuery(
                null, null, emptyFilter(), 10, "ko-KR",
                List.of("itemPublicId:ASC"), "KST", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fieldDecisionViewMaskOmitSemantics() throws Exception {
        Map<String, FieldDecision> fields = new LinkedHashMap<>();
        fields.put("POLICY_VERSION", FieldDecision.view(new PolicyValue(
                "pay.formula.gross", 1, LocalDate.of(2026, 1, 1), null, "DRAFT")));
        fields.put("PAYROLL_POLICY", new FieldDecision(
                "MASK", null, "PURPOSE_DENIED", "payroll.policy.masked"));
        WorkbenchItem item = new WorkbenchItem(
                "formula:" + UUID.randomUUID(), "Gross", "DRAFT", fields,
                new DeepLink("/hr/settings/payroll", "formula:gross"), List.of());
        JsonNode json = mapper.valueToTree(item).path("fieldGroups");

        assertThat(json.path("POLICY_VERSION").path("decision").asText())
                .isEqualTo("VIEW");
        assertThat(json.path("POLICY_VERSION").has("maskReason")).isFalse();
        assertThat(json.path("PAYROLL_POLICY").path("decision").asText())
                .isEqualTo("MASK");
        assertThat(json.path("PAYROLL_POLICY").has("value")).isTrue();
        assertThat(json.path("PAYROLL_POLICY").path("value").isNull()).isTrue();
        assertThat(json.has("EFFECTIVE_STATUS")).isFalse();
    }

    @Test
    void problemStatusAndCorrelationAreClosed() {
        UUID correlationId = UUID.randomUUID();
        Problem problem = new Problem(
                "https://dwp.example/problems/payroll-configuration",
                "Payroll configuration conflict", 409,
                "OBJECT_VERSION_CONFLICT", correlationId, false);
        assertThat(problem.status()).isEqualTo(409);
        assertThat(problem.correlationId()).isEqualTo(correlationId);
        assertThatThrownBy(() -> new Problem(
                "https://dwp.example/problems/payroll-configuration",
                "Unexpected", 500, "INTERNAL_ERROR", correlationId, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void seedElements(int count) {
        var author = actor(1, 101, Action.ELEMENT_VERSION_CREATE);
        for (int index = count; index > 0; index--) {
            service.createElementVersion(
                    author, UUID.randomUUID(), UUID.randomUUID().toString(),
                    new CreateElementVersionRequest(
                            null, null, LEGAL_ENTITY, PAYROLL_GROUP,
                            "ELEMENT_" + index, "Element " + index,
                            PayValueType.MONEY, "USD",
                            new RoundingPolicy(2, RoundingMode.HALF_UP, "0.01"),
                            LocalDate.of(2026, 1, index)));
        }
    }

    private void seedValidatedFormula() {
        var author = actor(
                1, 101, Action.FORMULA_VERSION_CREATE, Action.FORMULA_VALIDATE);
        var created = service.createFormulaVersion(
                author, UUID.randomUUID(), UUID.randomUUID().toString(),
                new CreateFormulaVersionRequest(
                        null, null, LEGAL_ENTITY, PAYROLL_GROUP,
                        "GROSS", "Gross formula", LocalDate.of(2026, 1, 1),
                        GOLDEN_DIGEST, grossFormula()));
        service.validateFormula(
                author, created.formula().formulaId(), UUID.randomUUID(),
                UUID.randomUUID().toString(), new VersionCommand(1));
    }

    private WorkbenchQuery query(int limit, String cursor) {
        return new WorkbenchQuery(
                null, cursor, emptyFilter(), limit, "ko-KR",
                List.of("itemPublicId:ASC"), "Asia/Seoul", UUID.randomUUID());
    }

    private WorkbenchFilter emptyFilter() {
        return new WorkbenchFilter(null, null, null, null);
    }

    private CachePolicy cache(String authorizationRevision) {
        return new CachePolicy(
                authorizationRevision,
                "dwp.hris.access.assignment.changed.v1", 0);
    }

    private WorkbenchItem sampleItem() {
        Map<String, FieldDecision> fields = Map.of(
                "POLICY_VERSION", FieldDecision.view(new PolicyValue(
                        "pay.formula.gross", 1, LocalDate.of(2026, 1, 1), null, "DRAFT")),
                "PAYROLL_POLICY", FieldDecision.view(new PolicyValue(
                        "pay.formula.gross", 1, LocalDate.of(2026, 1, 1), null, "DRAFT")),
                "EFFECTIVE_STATUS", FieldDecision.view(new StatusValue("DRAFT", 1)));
        String id = "formula:" + UUID.randomUUID();
        return new WorkbenchItem(
                id, "Gross", "DRAFT", fields,
                new DeepLink("/hr/settings/payroll", id), List.of());
    }
}
