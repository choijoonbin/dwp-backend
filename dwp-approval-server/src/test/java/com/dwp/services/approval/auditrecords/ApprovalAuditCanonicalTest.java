package com.dwp.services.approval.auditrecords;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalAuditCanonicalTest {
    private static final String EXPECTED_JSON =
            "{\"alpha\":\"a\",\"values\":{\"a\":1,\"b\":2},\"zeta\":\"z\"}";
    private static final String EXPECTED_SHA256 =
            "913771c08123b403d86eece1a6c039cb541dc0522952d7b909800b287c7aa0c4";

    @Test
    void canonicalSerializationPreservesTheEstablishedDeterministicDigest() throws Exception {
        Map<String, Integer> reverseInsertionOrder = new LinkedHashMap<>();
        reverseInsertionOrder.put("b", 2);
        reverseInsertionOrder.put("a", 1);

        String json = ApprovalAuditService.canonicalMapper(
                new ObjectMapper().findAndRegisterModules())
                .writeValueAsString(new CanonicalProbe("z", reverseInsertionOrder, "a"));

        assertThat(json).isEqualTo(EXPECTED_JSON);
        assertThat(ApprovalAuditRedactor.sha256(json)).isEqualTo(EXPECTED_SHA256);
    }

    private record CanonicalProbe(String zeta, Map<String, Integer> values, String alpha) {
    }
}
