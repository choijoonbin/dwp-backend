package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.calculated;
import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.constant;
import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.field;
import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.group;
import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.schema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApprovalFormSchemaV2EvaluatorTest {

    private final ApprovalFormSchemaV2Compiler compiler = new ApprovalFormSchemaV2Compiler();
    private final ApprovalFormSchemaV2Evaluator evaluator = new ApprovalFormSchemaV2Evaluator();

    @Test
    void separatesIncompleteDraftFromSubmitRequirements() {
        Map<String, Object> summary = field("summary", "TEXT");
        summary.put("required", true);
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(summary));
        assertThat(evaluator.evaluate(compiled, Map.of(), false).payload()).isEmpty();
        assertThatThrownBy(() -> evaluator.evaluate(compiled, Map.of(), true)).isInstanceOf(BaseException.class);
        assertThat(evaluator.evaluate(compiled, Map.of("summary", "Ready"), true).requiredFields()).containsExactly("summary");
    }

    @Test
    void stripsHiddenStaleInputsAndUsesOnlyVisibleNormalizedValuesForDownstreamConditions() {
        Map<String, Object> detail = field("details", "TEXT");
        detail.put("visibleWhen", Map.of("op", "EQ", "field", "category", "value", "OTHER"));
        detail.put("required", true);
        Map<String, Object> followup = field("followup", "TEXT");
        followup.put("visibleWhen", Map.of("op", "PRESENT", "field", "details"));
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(followup, detail, field("category", "TEXT")));
        ApprovalFormSchemaV2.Evaluation result = evaluator.evaluate(compiled,
                Map.of("category", "NORMAL", "details", "sensitive stale value", "followup", "must not persist"), true);
        assertThat(result.payload()).containsOnlyKeys("category");
        assertThat(result.visibleFields()).containsExactly("category");
        assertThat(result.requiredFields()).isEmpty();
        assertThat(compiled.sha256()).isEqualTo(result.schemaSha256());
    }

    @Test
    void enforcesConditionalRequiredOnlyWhenVisible() {
        Map<String, Object> details = field("details", "TEXT");
        details.put("requiredWhen", Map.of("op", "GTE", "field", "amount", "value", "100"));
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(details, field("amount", "NUMBER")));
        assertThat(evaluator.evaluate(compiled, Map.of("amount", 99), true).requiredFields()).isEmpty();
        assertThatThrownBy(() -> evaluator.evaluate(compiled, Map.of("amount", 100), true)).isInstanceOf(BaseException.class);
        assertThat(evaluator.evaluate(compiled, Map.of("amount", 100), false).requiredFields()).containsExactly("details");
    }

    @Test
    void supportsBooleanCombinationsAndKeepsMissingComparisonOperandsFalse() {
        Map<String, Object> details = field("details", "TEXT");
        details.put("visibleWhen", Map.of("op", "AND", "args", List.of(
                Map.of("op", "IN", "field", "category", "values", List.of("A", "B")),
                Map.of("op", "NOT", "args", List.of(Map.of("op", "PRESENT", "field", "amount"))))));
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(details, field("category", "TEXT"), field("amount", "NUMBER")));
        assertThat(evaluator.evaluate(compiled, Map.of("category", "A"), false).visibleFields()).contains("details");
        assertThat(evaluator.evaluate(compiled, Map.of("category", "A", "amount", 0), false).visibleFields()).doesNotContain("details");
        details.put("visibleWhen", Map.of("op", "NE", "field", "category", "value", "A"));
        assertThat(evaluator.evaluate(compiler.compile(schema(details, field("category", "TEXT"))), Map.of(), false).visibleFields())
                .doesNotContain("details");
    }

    @ParameterizedTest
    @CsvSource({"ADD,2,3,5", "SUBTRACT,2,3,-1", "MULTIPLY,1.5,2,3", "DIVIDE,1,3,0.33333333",
            "MIN,2,3,2", "MAX,2,3,3", "DIVIDE,-1,3,-0.33333333"})
    void computesBoundedDecimalArithmetic(String op, String left, String right, String expected) {
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(calculated("total",
                Map.of("op", op, "args", List.of(constant(left), constant(right))))));
        assertThat(evaluator.evaluate(compiled, Map.of(), true).payload().get("total"))
                .isEqualTo(expected);
    }

    @Test
    void roundsHalfUpAndResolvesCalculatedDependenciesRegardlessOfCanvasOrder() {
        Map<String, Object> rounded = calculated("rounded", Map.of("op", "ROUND", "scale", 2,
                "args", List.of(Map.of("op", "FIELD", "field", "total"))));
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(rounded, calculated("total", constant("-1.235"))));
        assertThat(evaluator.evaluate(compiled, Map.of(), true).payload()).containsEntry("rounded", "-1.24");
    }

    @Test
    void rejectsClientComputedTamperingAndChecksComputedBounds() {
        Map<String, Object> total = calculated("total", constant("20"));
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(total));
        assertThatThrownBy(() -> evaluator.evaluate(compiled, Map.of("total", 21), false)).isInstanceOf(BaseException.class);
        assertThat(evaluator.evaluate(compiled, Map.of("total", "20.0"), true).payload()).containsEntry("total", "20");
        total.put("max", 19);
        assertThatThrownBy(() -> evaluator.evaluate(compiler.compile(schema(total)), Map.of(), true)).isInstanceOf(BaseException.class);
    }

    @Test
    void incompleteCalculatedInputsRemainMissingInsteadOfSilentlyBecomingZero() {
        Map<String, Object> total = calculated("total", Map.of("op", "FIELD", "field", "amount"));
        total.put("required", true);
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(total, field("amount", "NUMBER")));
        assertThat(evaluator.evaluate(compiled, Map.of(), false).payload()).isEmpty();
        assertThatThrownBy(() -> evaluator.evaluate(compiled, Map.of(), true)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> evaluator.evaluate(compiled, Map.of("total", 0), false)).isInstanceOf(BaseException.class);
    }

    @Test
    void failsClosedForDivisionByZeroAndArithmeticOverflow() {
        for (Map<String, Object> expression : List.of(
                Map.<String, Object>of("op", "DIVIDE", "args", List.of(constant("1"), constant("0"))),
                Map.<String, Object>of("op", "MULTIPLY", "args", List.of(constant("9999999999999999999999999999"), constant("10"))))) {
            assertThatThrownBy(() -> evaluator.evaluate(compiler.compile(schema(calculated("total", expression))), Map.of(), false))
                    .isInstanceOf(BaseException.class);
        }
    }

    @Test
    void evaluatesRowLocalCalculationsAndRootSums() {
        Map<String, Object> quantity = field("quantity", "NUMBER");
        quantity.put("required", true);
        Map<String, Object> line = calculated("lineTotal", Map.of("op", "MULTIPLY", "args", List.of(
                Map.of("op", "FIELD", "field", "quantity"), Map.of("op", "FIELD", "field", "price"))));
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(
                calculated("total", Map.of("op", "SUM", "group", "items", "field", "lineTotal")),
                group("items", List.of(line, quantity, field("price", "NUMBER")))));
        ApprovalFormSchemaV2.Evaluation result = evaluator.evaluate(compiled,
                Map.of("items", List.of(Map.of("quantity", 2, "price", "10.25"), Map.of("quantity", 3, "price", "5"))), true);
        assertThat(result.payload()).containsEntry("total", "35.5");
        assertThat(result.requiredFields()).containsExactlyInAnyOrder("items[0].quantity", "items[1].quantity");
        assertThat(result.visibleFields()).contains("items[1].lineTotal", "items", "total");
        assertThatThrownBy(() -> result.payload().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void recomputesTrustedStoredAmendmentBaseButStillRejectsForgedClientPatchValues() {
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(field("amount", "NUMBER"),
                calculated("total", Map.of("op", "FIELD", "field", "amount"))));
        Map<String, Object> stored = evaluator.evaluate(compiled, Map.of("amount", 10), true).payload();
        Map<String, Object> base = evaluator.withoutComputedValues(compiled, stored);
        base.put("amount", 20);
        assertThat(evaluator.evaluate(compiled, base, true).payload()).containsEntry("total", "20");
        base.put("total", 10);
        assertThatThrownBy(() -> evaluator.evaluate(compiled, base, true)).isInstanceOf(BaseException.class);
        assertThat(stored).containsEntry("amount", "10").containsEntry("total", "10");
    }

    @Test
    void boundsRowCardinalityEvenOnPartialDraftsAndRejectsUnknownChildKeys() {
        Map<String, Object> items = group("items", List.of(field("amount", "NUMBER")));
        items.put("minRows", 2);
        items.put("maxRows", 2);
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(items));
        assertThat(evaluator.evaluate(compiled, Map.of("items", List.of()), false).payload()).containsKey("items");
        assertThatThrownBy(() -> evaluator.evaluate(compiled, Map.of("items", List.of()), true)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> evaluator.evaluate(compiled, Map.of("items", java.util.Collections.nCopies(3, Map.of("amount", 1))), false))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> evaluator.evaluate(compiled, Map.of("items", List.of(Map.of("unknown", 1))), false))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void stripsHiddenRowInputsAndDoesNotAggregateMissingHiddenValuesAsZero() {
        Map<String, Object> amount = field("amount", "NUMBER");
        amount.put("visibleWhen", Map.of("op", "EQ", "field", "category", "value", "BILLABLE"));
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(group("items", List.of(amount, field("category", "TEXT"))),
                calculated("total", Map.of("op", "SUM", "group", "items", "field", "amount"))));
        ApprovalFormSchemaV2.Evaluation result = evaluator.evaluate(compiled,
                Map.of("items", List.of(Map.of("category", "FREE", "amount", 99))), true);
        assertThat(result.payload()).doesNotContainKey("total");
        assertThat(((Map<?, ?>) ((List<?>) result.payload().get("items")).getFirst()).keySet()).isEqualTo(java.util.Set.of("category"));
    }

    @Test
    void validatesScalarTypesDatesOptionsNumericBoundsAndUnicodeLength() {
        Map<String, Object> amount = field("amount", "NUMBER");
        amount.put("min", 0);
        amount.put("max", 100);
        Map<String, Object> summary = field("summary", "TEXT");
        summary.put("minLength", 1);
        summary.put("maxLength", 1);
        Map<String, Object> currency = field("currency", "SELECT");
        currency.put("options", List.of("KRW", "USD"));
        ApprovalFormSchemaV2 compiled = compiler.compile(schema(amount, summary, currency, field("neededBy", "DATE")));
        assertThat(evaluator.evaluate(compiled, Map.of("summary", "😀", "amount", "0.00000001", "currency", "USD", "neededBy", "2024-02-29"), true)
                .payload()).containsEntry("summary", "😀");
        for (Map<String, Object> input : List.of(Map.<String, Object>of("amount", -1), Map.<String, Object>of("amount", Double.NaN),
                Map.<String, Object>of("summary", 1), Map.<String, Object>of("summary", "AB"),
                Map.<String, Object>of("currency", "EUR"), Map.<String, Object>of("neededBy", "2025-02-29"),
                Map.<String, Object>of("unknown", "value"))) {
            assertThatThrownBy(() -> evaluator.evaluate(compiled, input, false)).isInstanceOf(BaseException.class);
        }
    }
}
