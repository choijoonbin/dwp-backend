package com.dwp.services.people.hris.performance.contracts.xcon;

import com.dwp.contracts.hris.xcon.v1.Xcon012PerformanceHomeContributionChangedV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Xcon012PerformanceHomeContributionChangedV1ProviderContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void providerBindingMatchesCanonicalXcon012Exactly() throws Exception {
        JsonNode schema = mapper.readTree(Files.readString(Path.of(
                "../contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json")))
                .path("$defs").path("XCON_012");
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(value -> required.add(value.textValue()));

        assertThat(schema.path("additionalProperties").booleanValue()).isFalse();
        assertThat(schema.path("x-profile").textValue())
                .isEqualTo("PerformanceHomeContribution.v1");
        assertThat(required).containsExactlyInAnyOrder(
                "ownerPopulationScope", "freshUntil", "generatedAt", "module",
                "payloadDigest", "payloadRef", "scopeRevision", "staleAfter",
                "state", "widgetKey");

        Xcon012PerformanceHomeContributionChangedV1 event = event();
        JsonNode json = mapper.valueToTree(event);
        assertThat(json.propertyStream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(required);
        assertThat(mapper.treeToValue(json,
                Xcon012PerformanceHomeContributionChangedV1.class)).isEqualTo(event);
    }

    @Test
    void providerCannotPublishAnotherModuleOrUnsealedWidgetKey() {
        assertThatThrownBy(() -> new Xcon012PerformanceHomeContributionChangedV1(
                "SELF", Instant.parse("2026-10-16T04:01:00Z"),
                Instant.parse("2026-10-16T04:00:00Z"), "HRM", "a".repeat(64),
                "href_0123456789abcdef", 4,
                Instant.parse("2026-10-16T04:05:00Z"), "FRESH", "people-summary"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Xcon012PerformanceHomeContributionChangedV1 event() {
        return new Xcon012PerformanceHomeContributionChangedV1(
                "SELF", Instant.parse("2026-10-16T04:01:00Z"),
                Instant.parse("2026-10-16T04:00:00Z"), "PER", "a".repeat(64),
                "href_0123456789abcdef", 4,
                Instant.parse("2026-10-16T04:05:00Z"), "FRESH",
                "performance-summary");
    }
}
