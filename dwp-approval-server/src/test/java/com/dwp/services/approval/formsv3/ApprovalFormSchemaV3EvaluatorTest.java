package com.dwp.services.approval.formsv3;

import static com.dwp.services.approval.formsv3.ApprovalFormV3TestFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.dwp.core.exception.BaseException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApprovalFormSchemaV3EvaluatorTest {
    private final ApprovalFormSchemaV3Compiler compiler = new ApprovalFormSchemaV3Compiler();
    private final ApprovalFormSchemaV3Evaluator evaluator = new ApprovalFormSchemaV3Evaluator();

    @Test
    void evaluatesCalculationsRulesValidationAndEmbeddedScenarioWithoutExternalState() {
        ApprovalFormSchemaV3 schema = compiler.compile(enterpriseSchema());
        Map<String, Object> payload = map("summary", "Purchase", "amount", "1000", "tax", "100",
                "country", "KR", "evidence", List.of(map("id", "a", "name", "a.pdf",
                        "mimeType", "application/pdf", "size", 1000)));

        ApprovalFormSchemaV3.Evaluation evaluation = evaluator.evaluate(schema, payload, Set.of("APPROVAL_OPERATOR"));

        assertThat((BigDecimal) evaluation.payload().get("total")).isEqualByComparingTo("1100");
        assertThat(evaluation.requiredFields()).contains("summary", "amount", "evidence");
        assertThat(evaluation.readOnlyFields()).contains("total");
        assertThat(evaluation.violations()).isEmpty();
        assertThat(evaluator.evaluateScenarios(schema)).singleElement().satisfies(result -> {
            assertThat(result.passed()).isTrue();
            assertThat(result.mismatches()).isEmpty();
        });
    }

    @Test
    void roleVisibilityNeverProjectsHiddenValuesAndInvalidEvidenceFailsDeterministically() {
        Map<String, Object> raw = enterpriseSchema();
        field(raw, "reviewer").put("viewRoles", List.of("SECURITY_REVIEWER"));
        field(raw, "reviewer").put("editRoles", List.of("SECURITY_REVIEWER"));
        ApprovalFormSchemaV3 schema = compiler.compile(raw);
        Map<String, Object> payload = map("summary", "Purchase", "amount", "1200", "tax", "0",
                "country", "KR", "reviewer", "person-1", "evidence", List.of(map("id", "a",
                        "name", "a.exe", "mimeType", "application/octet-stream", "size", 2_000_000)));

        ApprovalFormSchemaV3.Evaluation evaluation = evaluator.evaluate(schema, payload, Set.of("REQUESTER"));

        assertThat(evaluation.visibleFields()).doesNotContain("reviewer");
        assertThat(evaluation.payload()).doesNotContainKey("reviewer");
        assertThat(evaluation.violations()).extracting(ApprovalFormSchemaV3.Violation::code)
                .contains("MIME", "FILE_SIZE");
    }

    @Test
    void unknownPayloadFieldsAndFailedScenarioAreRejected() {
        ApprovalFormSchemaV3 schema = compiler.compile(enterpriseSchema());
        assertThatThrownBy(() -> evaluator.evaluate(schema, map("summary", "ok", "unknown", "leak"), Set.of()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> evaluator.evaluate(schema, map("summary", "x".repeat(262_145)), Set.of()))
                .isInstanceOf(BaseException.class);

        Map<String, Object> failed = enterpriseSchema();
        scenarios(failed).getFirst().put("expected", map("computed", map("total", "999")));
        ApprovalFormSchemaV3 compiled = compiler.compile(failed);
        assertThat(evaluator.evaluateScenarios(compiled)).singleElement()
                .satisfies(result -> assertThat(result.passed()).isFalse());
        assertThatThrownBy(() -> evaluator.requirePassingScenarios(compiled)).isInstanceOf(BaseException.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> field(Map<String, Object> schema, String key) {
        Map<String, Object> page = (Map<String, Object>) ((List<?>) schema.get("pages")).getFirst();
        Map<String, Object> section = (Map<String, Object>) ((List<?>) page.get("sections")).getFirst();
        return ((List<Map<String, Object>>) section.get("fields")).stream()
                .filter(field -> key.equals(field.get("key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scenarios(Map<String, Object> schema) {
        return (List<Map<String, Object>>) schema.get("scenarios");
    }
}
