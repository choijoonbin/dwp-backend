package com.dwp.audit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSanitizerTest {

    @Test
    void redactsSecretsAndPreservesNullableMetadata() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("accessToken", "sensitive");
        source.put("optional", null);
        source.put("businessKey", "tenant-1");

        Map<String, Object> result = AuditSanitizer.sanitize(source);

        assertThat(result)
                .containsEntry("accessToken", "[REDACTED]")
                .containsEntry("businessKey", "tenant-1")
                .containsKey("optional");
        assertThat(result.get("optional")).isNull();
    }
}
