package com.dwp.services.payroll.configuration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.payroll.configuration.PayrollConfigurationAccess.Action;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CreateFormulaVersionRequest;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaErrorCode;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaNode;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaOperator;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.Lifecycle;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.MutationResult;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayFormulaAst;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayValueType;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ReceiptStatus;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.RoundingPolicy;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.SimulationCommand;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.TypedValue;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.VersionCommand;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.CLOCK;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.GOLDEN_DIGEST;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.LEGAL_ENTITY;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.NOW;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.PAYROLL_GROUP;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.actor;
import static com.dwp.services.payroll.configuration.PayrollConfigurationTestSupport.grossFormula;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayrollConfigurationContractTest {

    private ObjectMapper mapper;
    private PayrollConfigurationCanonical canonical;
    private PayrollFormulaEngine engine;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        canonical = new PayrollConfigurationCanonical(mapper);
        engine = new PayrollFormulaEngine(canonical, System::nanoTime);
    }

    @Test
    void exactPepRoutesAndCurrentHeadersAreBound() throws Exception {
        assertThat(PayrollConfigurationRoute.resolve(
                "GET", "/v1/workbench/settings-payroll")).get()
                .extracting(PayrollConfigurationRoute::bindingId)
                .isEqualTo("IAQ-055");
        assertThat(PayrollConfigurationRoute.resolve(
                "POST", "/v1/elements/versions")).get()
                .extracting(PayrollConfigurationRoute::bindingId)
                .isEqualTo("PEP-PAY-004");
        UUID formulaId = UUID.randomUUID();
        assertThat(PayrollConfigurationRoute.resolve(
                "POST", "/v1/formulas/versions")).get()
                .extracting(PayrollConfigurationRoute::bindingId)
                .isEqualTo("PEP-PAY-005");
        assertThat(PayrollConfigurationRoute.resolve(
                "POST", "/v1/formulas/" + formulaId + "/validations")).get()
                .extracting(PayrollConfigurationRoute::bindingId)
                .isEqualTo("PEP-PAY-006");
        assertThat(PayrollConfigurationRoute.resolve(
                "POST", "/v1/formulas/" + formulaId + "/simulations")).get()
                .extracting(PayrollConfigurationRoute::bindingId)
                .isEqualTo("PEP-PAY-007");
        assertThat(PayrollConfigurationRoute.resolve(
                "POST", "/v1/formulas/" + formulaId + "/publish")).get()
                .extracting(PayrollConfigurationRoute::bindingId)
                .isEqualTo("PEP-PAY-008");
        assertThat(PayrollConfigurationRoute.values())
                .extracting(PayrollConfigurationRoute::authorityContractKey)
                .containsExactly(
                        "route.hcm.configuration.payroll.page",
                        "route.hcm.configuration.payroll-element-version-create.action",
                        "route.hcm.configuration.payroll-formula-version-create.action",
                        "route.hcm.configuration.payroll-formula-validate.action",
                        "route.hcm.configuration.payroll-formula-simulate.action",
                        "route.hcm.configuration.payroll-formula-publish.action");
        assertThat(PayrollConfigurationRoute.AUTHORIZATION_VERSION).isEqualTo(38);
        assertThat(PayrollConfigurationRoute.AUTHORIZATION_SHA256).isEqualTo(
                "a41145d6a2faefd2e6030a2ca9a070769019d4db5513ea214bf542dda31c374a");
        assertThat(PayrollConfigurationRoute.GATEWAY_OPENAPI_SHA256).isEqualTo(
                "453f3620dca02a5ce82df273be986633fb5d694084ca080859bb47472027d234");
        assertThat(Action.WORKBENCH_VIEW.permission())
                .isEqualTo("CONFIG.HRIS_WORKBENCH:VIEW");
        assertThat(Action.ELEMENT_VERSION_CREATE.permission())
                .isEqualTo("DATA.HRIS_PAYROLL_INPUT:UPDATE");
        assertThat(Action.FORMULA_PUBLISH.permission())
                .isEqualTo("ACTION.HRIS_POLICY_PUBLISH:PUBLISH");
        assertThat(PayrollConfigurationController.EXPECTED_VERSION)
                .isEqualTo("X-DWP-Expected-Object-Version");
        assertThat(PayrollConfigurationSecurityFilter.STEP_UP_CHALLENGE)
                .isEqualTo("X-DWP-Step-Up-Challenge");
        assertThat(PayrollConfigurationSecurityFilter.AUTHORIZATION_REVISION)
                .isEqualTo("X-DWP-Authorization-Revision");
    }

    @Test
    void payPFormulaValidatesAndStringOrUnknownFunctionIsRejected() throws Exception {
        var validation = engine.validate(grossFormula(), NOW);

        assertThat(validation.valid()).isTrue();
        assertThat(validation.findings()).isEmpty();
        assertThat(validation.astDigest()).hasSize(64);
        assertThat(validation.validationDigest()).hasSize(64);

        String stringFormula = """
                {
                  "legalEntityId":"%s",
                  "payrollGroupId":"%s",
                  "formulaCode":"GROSS",
                  "displayName":"Gross pay",
                  "effectiveFrom":"2026-01-01",
                  "goldenTestDigest":"%s",
                  "ast":"gross = hours * rate"
                }
                """.formatted(LEGAL_ENTITY, PAYROLL_GROUP, GOLDEN_DIGEST);
        assertThatThrownBy(() -> mapper.readValue(
                stringFormula, CreateFormulaVersionRequest.class))
                .isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("TYPE");

        String unknownFunction = mapper.writeValueAsString(grossFormula())
                .replaceFirst("\\\"MULTIPLY\\\"", "\\\"RUNTIME_EXEC\\\"");
        assertThatThrownBy(() -> mapper.readValue(unknownFunction, PayFormulaAst.class))
                .isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("FUNCTION_NOT_ALLOWED");
    }

    @Test
    void cyclicFormulaReturnsCycle() {
        PayFormulaAst cyclic = new PayFormulaAst(
                "PAY_RULE_AST_V1", 1, PayValueType.MONEY, "gross",
                List.of(
                        new FormulaNode(
                                "hours", FormulaOperator.INPUT, List.of("gross"),
                                PayValueType.QUANTITY_HOUR, null, null,
                                "TIM.REGULAR_HOURS", null, null, null),
                        new FormulaNode(
                                "rate", FormulaOperator.INPUT, List.of(),
                                PayValueType.UNIT_PRICE_PER_HOUR, null, "USD",
                                "HRM.HOURLY_RATE", null, null, null),
                        new FormulaNode(
                                "gross", FormulaOperator.MULTIPLY,
                                List.of("hours", "rate"), PayValueType.MONEY,
                                null, "USD", null, null, null, null)));

        assertThat(engine.validate(cyclic, NOW).findings())
                .extracting(finding -> finding.code())
                .contains(FormulaErrorCode.CYCLE);
    }

    @Test
    void currencyUnitAndExplicitRoundingAreEnforced() {
        RoundingPolicy rounding = new RoundingPolicy(
                2, RoundingMode.HALF_UP, "0.01");
        PayFormulaAst syntheticMoneyRate = new PayFormulaAst(
                "PAY_RULE_AST_V1", 1, PayValueType.MONEY, "result",
                List.of(
                        new FormulaNode(
                                "money", FormulaOperator.LITERAL, List.of(),
                                PayValueType.MONEY, "100.00", "TST",
                                null, null, null, null),
                        new FormulaNode(
                                "rate", FormulaOperator.LITERAL, List.of(),
                                PayValueType.RATE, "0.125", null,
                                null, null, null, null),
                        new FormulaNode(
                                "result", FormulaOperator.MULTIPLY,
                                List.of("money", "rate"), PayValueType.MONEY,
                                null, "TST", null, null, null, null)));
        var synthetic = engine.simulate(
                syntheticMoneyRate, Map.of(), rounding, 2_000, NOW);
        assertThat(synthetic.succeeded()).isTrue();
        assertThat(synthetic.result().type()).isEqualTo(PayValueType.MONEY);
        assertThat(synthetic.result().currency()).isEqualTo("TST");
        assertThat(synthetic.result().value()).isEqualTo("12.50");

        var successful = engine.simulate(
                grossFormula(),
                Map.of(
                        "TIM.REGULAR_HOURS", new TypedValue(
                                PayValueType.QUANTITY_HOUR, "100.00", null, null),
                        "HRM.HOURLY_RATE", new TypedValue(
                                PayValueType.UNIT_PRICE_PER_HOUR, "0.125", null, "USD")),
                rounding, 2_000, NOW);
        assertThat(successful.succeeded()).isTrue();
        assertThat(successful.result().type()).isEqualTo(PayValueType.MONEY);
        assertThat(successful.result().currency()).isEqualTo("USD");
        assertThat(successful.result().value()).isEqualTo("12.50");

        var currencyMismatch = engine.simulate(
                grossFormula(),
                Map.of(
                        "TIM.REGULAR_HOURS", new TypedValue(
                                PayValueType.QUANTITY_HOUR, "100", null, null),
                        "HRM.HOURLY_RATE", new TypedValue(
                                PayValueType.UNIT_PRICE_PER_HOUR, "0.125", null, "EUR")),
                rounding, 2_000, NOW);
        assertThat(currencyMismatch.succeeded()).isFalse();
        assertThat(currencyMismatch.failureCode())
                .isEqualTo(FormulaErrorCode.CURRENCY_MISMATCH);
        assertThat(currencyMismatch.result()).isNull();

        PayFormulaAst invalidUnits = new PayFormulaAst(
                "PAY_RULE_AST_V1", 1, PayValueType.MONEY, "gross",
                List.of(
                        new FormulaNode(
                                "left", FormulaOperator.INPUT, List.of(),
                                PayValueType.QUANTITY_HOUR, null, null,
                                "TIM.REGULAR_HOURS", null, null, null),
                        new FormulaNode(
                                "right", FormulaOperator.INPUT, List.of(),
                                PayValueType.QUANTITY_HOUR, null, null,
                                "TIM.OVERTIME_HOURS", null, null, null),
                        new FormulaNode(
                                "gross", FormulaOperator.MULTIPLY,
                                List.of("left", "right"), PayValueType.MONEY,
                                null, "USD", null, null, null, null)));
        assertThat(engine.validate(invalidUnits, NOW).findings())
                .extracting(finding -> finding.code())
                .contains(FormulaErrorCode.UNIT_MISMATCH);
    }

    @Test
    void timeoutProducesNoPartialResultAndSandboxHasNoExecutableOperator() {
        AtomicInteger reads = new AtomicInteger();
        PayrollFormulaEngine expiring = new PayrollFormulaEngine(
                canonical,
                () -> reads.getAndIncrement() == 0 ? 0L : Long.MAX_VALUE);
        var result = expiring.simulate(
                grossFormula(),
                Map.of(
                        "TIM.REGULAR_HOURS", new TypedValue(
                                PayValueType.QUANTITY_HOUR, "100", null, null),
                        "HRM.HOURLY_RATE", new TypedValue(
                                PayValueType.UNIT_PRICE_PER_HOUR, "0.125", null, "USD")),
                new RoundingPolicy(2, RoundingMode.HALF_UP, "0.01"),
                1, NOW);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failureCode()).isEqualTo(FormulaErrorCode.TIMEOUT);
        assertThat(result.result()).isNull();
        assertThat(result.outputDigest()).isNull();
        assertThat(Set.of(FormulaOperator.values()))
                .doesNotContainNull()
                .noneMatch(operator -> operator.name().matches(
                        ".*(JAVA|SPEL|SQL|SCRIPT|REFLECT|NETWORK|FILE|RANDOM|CLOCK).*"));
    }

    @Test
    void publishIsIdempotentTenantIsolatedAndEmitsOneMinimalEvent() throws Exception {
        PayrollConfigurationTestSupport.InMemoryStore store =
                new PayrollConfigurationTestSupport.InMemoryStore();
        PayrollConfigurationService service = PayrollConfigurationTestSupport.service(store);
        var author = actor(
                1, 101,
                Action.FORMULA_VERSION_CREATE,
                Action.FORMULA_VALIDATE,
                Action.FORMULA_SIMULATE);
        var publisher = actor(1, 202, Action.FORMULA_PUBLISH);
        CreateFormulaVersionRequest createRequest = formulaRequest();
        MutationResult created = service.createFormulaVersion(
                author, UUID.randomUUID(), UUID.randomUUID().toString(), createRequest);
        UUID formulaId = created.formula().formulaId();
        MutationResult validated = service.validateFormula(
                author, formulaId, UUID.randomUUID(), UUID.randomUUID().toString(),
                new VersionCommand(1));
        MutationResult simulated = service.simulateFormula(
                author, formulaId, UUID.randomUUID(), UUID.randomUUID().toString(),
                new SimulationCommand(
                        2,
                        Map.of(
                                "TIM.REGULAR_HOURS", new TypedValue(
                                        PayValueType.QUANTITY_HOUR, "100", null, null),
                                "HRM.HOURLY_RATE", new TypedValue(
                                        PayValueType.UNIT_PRICE_PER_HOUR, "0.125", null, "USD")),
                        new RoundingPolicy(2, RoundingMode.HALF_UP, "0.01"),
                        2_000));
        UUID publishKey = UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();
        MutationResult published = service.publishFormula(
                publisher, formulaId, publishKey, correlationId,
                new VersionCommand(3));
        MutationResult replay = service.publishFormula(
                publisher, formulaId, publishKey, correlationId,
                new VersionCommand(3));

        assertThat(validated.validation().valid()).isTrue();
        assertThat(simulated.simulation().result().value()).isEqualTo("12.50");
        assertThat(published.formula().status()).isEqualTo(Lifecycle.PUBLISHED);
        assertThat(replay.receipt()).isEqualTo(published.receipt());
        assertThat(replay.publicationEvent()).isEqualTo(published.publicationEvent());
        assertThat(store.formulaVersionCount(1, formulaId)).isEqualTo(4);
        assertThat(store.eventCount()).isEqualTo(1);
        assertThat(published.publicationEvent().type())
                .isEqualTo("PayrollConfigurationPublished");
        JsonNode eventJson = mapper.valueToTree(published.publicationEvent());
        assertThat(fieldNames(eventJson.path("data")))
                .containsExactlyInAnyOrder(
                        "configurationId", "version", "effectiveFrom", "validationDigest");
        assertThat(eventJson.toString()).doesNotContain("ast", "inputs", "result");

        assertThatThrownBy(() -> service.publishFormula(
                publisher, formulaId, publishKey, correlationId,
                new VersionCommand(999)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> service.validateFormula(
                actor(2, 303, Action.FORMULA_VALIDATE), formulaId,
                UUID.randomUUID(), UUID.randomUUID().toString(),
                new VersionCommand(4)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));

        JsonNode receiptJson = mapper.valueToTree(published.receipt());
        assertThat(fieldNames(receiptJson)).containsExactlyInAnyOrder(
                "receiptId", "commandType", "originatingAction",
                "subjectPrincipalPublicId", "populationScopeDigest",
                "fieldPolicyRevision", "purposeCode", "authorizationRevision",
                "status", "correlationId", "requestDigest", "createdAt");
        assertThat(published.receipt().status()).isEqualTo(ReceiptStatus.SUCCEEDED);
        assertThat(published.receipt().originatingAction())
                .isEqualTo("PAY_FORMULA_PUBLISH");
    }

    private CreateFormulaVersionRequest formulaRequest() {
        return new CreateFormulaVersionRequest(
                null, null, LEGAL_ENTITY, PAYROLL_GROUP,
                "GROSS", "Gross pay", LocalDate.of(2026, 1, 1),
                GOLDEN_DIGEST, grossFormula());
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
