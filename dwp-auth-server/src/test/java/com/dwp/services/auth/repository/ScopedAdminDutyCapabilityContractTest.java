package com.dwp.services.auth.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedAdminDutyCapabilityContractTest {

    private static final Pattern DUTY_CAPABILITY = Pattern.compile(
            "\\('([A-Z][A-Z0-9_]*)',\\s*'(approvals\\.[a-z.]+)',"
                    + "\\s*'([A-Z][A-Z0-9._]+)',\\s*'([A-Z][A-Z0-9_]*)'\\)");

    @Test
    void v91DutyAuthoritiesMatchEveryCanonicalW1aV2SpecialistCapabilityExactly()
            throws Exception {
        String migration = text("db/migration/V91__scope_approval_specialist_duties.sql");
        Map<String, Set<String>> actual = new LinkedHashMap<>();
        int associationCount = 0;
        Matcher matcher = DUTY_CAPABILITY.matcher(migration);
        while (matcher.find()) {
            actual.computeIfAbsent(matcher.group(2), ignored -> new LinkedHashSet<>())
                    .add(matcher.group(3) + ':' + matcher.group(4));
            associationCount++;
        }

        JsonNode bundle = new ObjectMapper().readTree(text(
                "product-authorization/product-surfaces-v1.bundle-v2.generated.json"));
        Map<String, String> expected = new LinkedHashMap<>();
        bundle.path("capabilities").forEach(capability -> {
            if ("approvals.admin".equals(capability.path("surfaceKey").asText())
                    && !"LEGACY_OVERSIGHT".equals(
                            capability.path("responsibilityRequirement").asText())) {
                expected.put(
                        capability.path("contractKey").asText(),
                        capability.path("resolvedCapabilityCode").asText());
            }
        });

        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((contractKey, resolvedCapability) ->
                assertThat(actual.get(contractKey))
                        .as(contractKey)
                        .containsExactly(resolvedCapability));
        assertThat(actual).hasSize(11);
        assertThat(associationCount).isEqualTo(13);
        assertThat(migration)
                .contains("REFERENCES sys_tenant_resource_templates(resource_key)")
                .contains("REFERENCES com_permissions(code)")
                .contains("GENERATED ALWAYS AS (permission_resource_key || ':' || permission_code)");
    }

    private String text(String path) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
