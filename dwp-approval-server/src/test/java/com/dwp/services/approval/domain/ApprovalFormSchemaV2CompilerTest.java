package com.dwp.services.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovalFormSchemaV2CompilerTest {

    private final ApprovalFormSchemaV2Compiler compiler = new ApprovalFormSchemaV2Compiler();

    @Test
    void copiesTheSchemaDeeplyAndPinsTheCanonicalHash() {
        Map<String, Object> field = field("summary", "TEXT");
        List<Map<String, Object>> fields = new ArrayList<>(List.of(field));
        Map<String, Object> schema = new LinkedHashMap<>(Map.of("schemaContract", ApprovalFormSchemaV2.CONTRACT, "schemaVersion", 2, "fields", fields));
        ApprovalFormSchemaV2 compiled = compiler.compile(schema);
        field.put("labelEn", "Changed after compile");
        fields.clear();
        assertThat(compiled.canonicalJson()).contains("Summary").doesNotContain("Changed after compile");
        assertThat(compiled.sha256()).hasSize(64).isEqualTo(ApprovalFormSchemaV2Canonical.sha256(compiled.canonicalJson()));
        assertThatThrownBy(() -> compiled.definition().put("schemaVersion", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) compiled.definition().get("fields")).clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void canonicalHashIgnoresObjectKeyOrderButBindsLabelsAndArrayOrder() {
        Map<String, Object> first = schema(field("summary", "TEXT"), field("amount", "NUMBER"));
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("fields", first.get("fields"));
        reordered.put("schemaVersion", 2L);
        reordered.put("schemaContract", ApprovalFormSchemaV2.CONTRACT);
        assertThat(compiler.compile(first).sha256()).isEqualTo(compiler.compile(reordered).sha256());
        assertThat(compiler.compile(schema(field("amount", "NUMBER"), field("summary", "TEXT"))).sha256())
                .isNotEqualTo(compiler.compile(first).sha256());
        Map<String, Object> renamed = field("summary", "TEXT");
        renamed.put("labelEn", "Private classified summary");
        assertThat(compiler.compile(schema(renamed)).sha256()).isNotEqualTo(compiler.compile(schema(field("summary", "TEXT"))).sha256());
    }

    @Test
    void keepsV1OutOfTheV2PathWithoutChangingTheLegacyValidator() {
        assertRejected(Map.of("schemaVersion", 1, "fields", List.of(field("summary", "TEXT"))));
        assertRejected(Map.of("schemaVersion", 2, "fields", List.of(field("summary", "TEXT"))));
        assertRejected(Map.of("schemaContract", "UNKNOWN", "schemaVersion", 2, "fields", List.of(field("summary", "TEXT"))));
    }

    @Test
    void rejectsDuplicateAndReservedKeys() {
        assertRejected(schema(field("summary", "TEXT"), field("summary", "NUMBER")));
        assertRejected(schema(field("createdFrom", "TEXT")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCRIPT", "URL", "LOOKUP", "<script>", "number"})
    void rejectsUnsupportedTypes(String type) { assertRejected(schema(field("summary", type))); }

    @Test
    void requiresExactTypedPropertiesRatherThanLooseScalarCoercion() {
        Map<String, Object> value = field("summary", "TEXT");
        value.put("required", "true");
        assertRejected(schema(value));
        value.put("required", false);
        value.put("arbitraryUrl", "http://localhost/internal");
        assertRejected(schema(value));
        assertRejected(Map.of("schemaVersion", 2, "fields", List.of(field("summary", "TEXT")), "script", "run()"));
    }

    @Test
    void requiresUniqueNonBlankSelectOptionsAndRejectsOptionsOnOtherTypes() {
        Map<String, Object> select = field("currency", "SELECT");
        for (List<String> options : List.of(List.of("KRW"), List.of("KRW", "KRW"), List.of("KRW", " "))) {
            select.put("options", options);
            assertRejected(schema(select));
        }
        select.put("options", List.of("KRW", "USD"));
        assertThat(compiler.compile(schema(select))).isNotNull();
        Map<String, Object> text = field("summary", "TEXT");
        text.put("options", List.of("A", "B"));
        assertRejected(schema(text));
    }

    @Test
    void rejectsUnknownAndCrossScopeReferences() {
        Map<String, Object> total = calculated("total", Map.of("op", "FIELD", "field", "missing"));
        assertRejected(schema(total));
        Map<String, Object> child = calculated("total", Map.of("op", "FIELD", "field", "amount"));
        assertRejected(schema(field("amount", "NUMBER"), group("items", List.of(child))));
    }

    @Test
    void detectsCyclesAcrossCalculatedAndConditionalReferences() {
        Map<String, Object> amount = field("amount", "NUMBER");
        amount.put("visibleWhen", Map.of("op", "GT", "field", "total", "value", "0"));
        Map<String, Object> total = calculated("total", Map.of("op", "FIELD", "field", "amount"));
        assertRejected(schema(amount, total));
        Map<String, Object> self = field("summary", "TEXT");
        self.put("requiredWhen", Map.of("op", "PRESENT", "field", "summary"));
        assertRejected(schema(self));
        Map<String, Object> items = group("items", List.of(field("amount", "NUMBER")));
        items.put("visibleWhen", Map.of("op", "GT", "field", "total", "value", "0"));
        assertRejected(schema(items, calculated("total", Map.of("op", "SUM", "group", "items", "field", "amount"))));
    }

    @Test
    void rejectsRowCyclesAndNestedGroups() {
        assertRejected(schema(group("items", List.of(group("nested", List.of(field("amount", "NUMBER")))))));
        assertRejected(schema(group("items", List.of(
                calculated("first", Map.of("op", "FIELD", "field", "second")),
                calculated("second", Map.of("op", "FIELD", "field", "first"))))));
    }

    @Test
    void rejectsCalculationAndConditionTypeMismatches() {
        assertRejected(schema(field("summary", "TEXT"), calculated("total", Map.of("op", "FIELD", "field", "summary"))));
        Map<String, Object> summary = field("summary", "TEXT");
        summary.put("visibleWhen", Map.of("op", "GT", "field", "currency", "value", "0"));
        Map<String, Object> currency = field("currency", "SELECT");
        currency.put("options", List.of("KRW", "USD"));
        assertRejected(schema(currency, summary));
        summary.put("visibleWhen", Map.of("op", "EQ", "field", "currency", "value", "EUR"));
        assertRejected(schema(currency, summary));
        summary.put("visibleWhen", Map.of("op", "EQ", "field", "currency", "value", 1));
        assertRejected(schema(currency, summary));
    }

    @Test
    void rejectsUnknownOperatorsExtraArgumentsAndScriptProperties() {
        for (Map<String, Object> expression : List.of(Map.<String, Object>of("op", "EVAL", "value", "1+1"),
                Map.<String, Object>of("op", "CONST", "value", "1", "script", "run()"),
                Map.<String, Object>of("op", "ADD", "args", List.of(constant("1"))))) {
            assertRejected(schema(calculated("total", expression)));
        }
    }

    @Test
    void boundsDepthAndInValues() {
        Map<String, Object> expression = constant("1");
        for (int i = 0; i < 9; i++) expression = Map.of("op", "ROUND", "scale", 2, "args", List.of(expression));
        assertRejected(schema(calculated("total", expression)));
        Map<String, Object> summary = field("summary", "TEXT");
        summary.put("visibleWhen", Map.of("op", "IN", "field", "category", "values", java.util.Collections.nCopies(51, "A")));
        assertRejected(schema(field("category", "TEXT"), summary));
    }

    @Test
    void boundsRowsAndNumericAndTextConstraints() {
        Map<String, Object> items = group("items", List.of(field("amount", "NUMBER")));
        items.put("maxRows", 51);
        assertRejected(schema(items));
        items.put("maxRows", 2.5);
        assertRejected(schema(items));
        Map<String, Object> amount = field("amount", "NUMBER");
        amount.put("min", 2);
        amount.put("max", 1);
        assertRejected(schema(amount));
        Map<String, Object> text = field("summary", "TEXT");
        text.put("minLength", 3);
        text.put("maxLength", 2);
        assertRejected(schema(text));
        text.remove("minLength");
        text.remove("maxLength");
        text.put("min", 0);
        assertRejected(schema(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "1e9", "01", "+1", "0.000000001", "99999999999999999999999999999"})
    void rejectsInvalidDecimalConstants(String value) { assertRejected(schema(calculated("total", constant(value)))); }

    @Test
    void rejectsNonFiniteOrFractionalJsonNumbersButAcceptsPlainDecimalStrings() {
        assertRejected(schema(calculated("total", Map.of("op", "CONST", "value", Double.NaN))));
        assertRejected(schema(calculated("total", Map.of("op", "CONST", "value", Double.POSITIVE_INFINITY))));
        assertRejected(schema(calculated("total", Map.of("op", "CONST", "value", new BigDecimal("1E+999999999")))));
        assertRejected(schema(calculated("total", Map.of("op", "CONST", "value", 0.00000001))));
        assertRejected(schema(calculated("total", Map.of("op", "CONST", "value", 1.0d))));
        assertRejected(schema(calculated("total", Map.of("op", "CONST", "value", 1.0f))));
        assertRejected(schema(calculated("total", Map.of("op", "CONST", "value", 9007199254740992L))));
        assertThat(compiler.compile(schema(calculated("total", constant("0.00000001"))))).isNotNull();
    }

    private void assertRejected(Map<String, Object> schema) {
        assertThatThrownBy(() -> compiler.compile(schema)).isInstanceOf(BaseException.class);
    }

    @SafeVarargs
    static Map<String, Object> schema(Map<String, Object>... fields) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Map<String, Object> field : fields) values.add(field);
        return Map.of("schemaContract", ApprovalFormSchemaV2.CONTRACT, "schemaVersion", 2, "fields", values);
    }
    static Map<String, Object> field(String key, String type) {
        return new LinkedHashMap<>(Map.of("key", key, "type", type, "labelKo", "요약", "labelEn", "Summary", "required", false));
    }
    static Map<String, Object> calculated(String key, Map<String, Object> expression) {
        Map<String, Object> result = field(key, "CALCULATED_NUMBER");
        result.put("calculation", expression);
        return result;
    }
    static Map<String, Object> group(String key, List<Map<String, Object>> fields) {
        Map<String, Object> result = field(key, "REPEATING_GROUP");
        result.put("fields", fields);
        result.put("maxRows", 20);
        return result;
    }
    static Map<String, Object> constant(String value) { return Map.of("op", "CONST", "value", value); }
    static Map<String, Object> json(String value) throws Exception {
        return new ObjectMapper().readValue(value, new TypeReference<Map<String, Object>>() { });
    }
}
