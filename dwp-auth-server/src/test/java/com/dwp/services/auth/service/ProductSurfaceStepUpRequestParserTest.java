package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSurfaceStepUpRequestParserTest {

    private final ProductSurfaceStepUpRequestParser parser =
            new ProductSurfaceStepUpRequestParser(new ObjectMapper().findAndRegisterModules());

    @Test
    void preservesOpaqueContextBindingsAndRejectsUnknownOrDuplicateFields() {
        String valid = """
                {
                  "commandMethod":"POST",
                  "commandPath":"/api/approvals/v1/admin/workflows/id/publish",
                  "contextKey":"opaque-context",
                  "contextScopeKey":"opaque-scope",
                  "targetType":"WORKFLOW",
                  "targetId":"id",
                  "expectedObjectVersion":7,
                  "idempotencyKey":"idem",
                  "payload":{"expectedVersion":7}
                }
                """;

        ProductSurfaceStepUpRequestParser.ParsedRequest parsed = parser.parse(valid);

        assertThat(parsed.request().contextKey()).isEqualTo("opaque-context");
        assertThat(parsed.request().contextScopeKey()).isEqualTo("opaque-scope");
        assertThat(new String(parsed.canonicalPayload(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"expectedVersion\":7}");

        assertThatThrownBy(() -> parser.parse(valid.replace(
                "\"targetId\":\"id\",",
                "\"targetId\":\"id\",\"targetId\":\"other\",")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> parser.parse(valid.replace(
                "\"payload\":", "\"crossUnion\":true,\"payload\":")))
                .isInstanceOf(BaseException.class);
    }
}
