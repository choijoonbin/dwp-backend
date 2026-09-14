package com.dwp.services.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApprovalFormSchemaV2ParityTest {

    @Test
    void executesTheSharedClientServerGoldenContract() throws Exception {
        Map<String, Object> fixture;
        try (InputStream input = getClass().getResourceAsStream("/approval/form-schema-v2-parity.json")) {
            assertThat(input).isNotNull();
            fixture = new ObjectMapper().readValue(input, new TypeReference<Map<String, Object>>() { });
        }
        ApprovalFormSchemaV2 schema = new ApprovalFormSchemaV2Compiler().compile(object(fixture.get("schema")));
        assertThat(schema.sha256()).isEqualTo(fixture.get("schemaSha256"));
        ApprovalFormSchemaV2Evaluator evaluator = new ApprovalFormSchemaV2Evaluator();
        for (Object raw : (List<?>) fixture.get("cases")) {
            Map<String, Object> test = object(raw);
            ApprovalFormSchemaV2.Evaluation result = evaluator.evaluate(schema, object(test.get("input")), (Boolean) test.get("submitting"));
            assertThat(result.payload()).as((String) test.get("name"))
                    .isEqualTo(ApprovalFormSchemaV2Canonical.freeze(object(test.get("expectedPayload"))));
            assertThat(result.visibleFields()).as((String) test.get("name")).containsExactlyInAnyOrderElementsOf(strings(test.get("expectedVisible")));
            assertThat(result.requiredFields()).as((String) test.get("name")).containsExactlyInAnyOrderElementsOf(strings(test.get("expectedRequired")));
            assertThat(result.schemaSha256()).isEqualTo(schema.sha256());
        }
        for (Object raw : (List<?>) fixture.get("invalidCases")) {
            Map<String, Object> test = object(raw);
            assertThatThrownBy(() -> evaluator.evaluate(schema, object(test.get("input")), (Boolean) test.get("submitting")))
                    .as((String) test.get("name")).isInstanceOf(BaseException.class);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private List<String> strings(Object value) { return (List<String>) value; }
}
