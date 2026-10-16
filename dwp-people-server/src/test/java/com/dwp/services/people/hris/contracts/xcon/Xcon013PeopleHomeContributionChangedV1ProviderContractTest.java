package com.dwp.services.people.hris.contracts.xcon;

import com.dwp.contracts.hris.xcon.v1.Xcon013PeopleHomeContributionChangedV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Xcon013PeopleHomeContributionChangedV1ProviderContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void providerEmitsTheExactClosedXcon013Shape() throws Exception {
        Instant generated = Instant.parse("2026-10-16T09:10:00Z");
        var event = new Xcon013PeopleHomeContributionChangedV1(
                "SELF", generated.plusSeconds(300), generated, "HRM",
                "a".repeat(64), "href_abcdefghijklmnop", 11,
                generated.plusSeconds(900), "FRESH", "people-profile-summary");

        var json = mapper.valueToTree(event);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(Set.of(
                "ownerPopulationScope", "freshUntil", "generatedAt", "module",
                "payloadDigest", "payloadRef", "scopeRevision", "staleAfter",
                "state", "widgetKey"));
        assertThat(mapper.readValue(mapper.writeValueAsBytes(event),
                Xcon013PeopleHomeContributionChangedV1.class)).isEqualTo(event);
    }

    @Test
    void providerContractRejectsUnknownOrNonHrmData() {
        assertThatThrownBy(() -> new Xcon013PeopleHomeContributionChangedV1(
                "SELF", Instant.parse("2026-10-16T09:15:00Z"),
                Instant.parse("2026-10-16T09:10:00Z"), "PER", "a".repeat(64),
                "href_abcdefghijklmnop", 11,
                Instant.parse("2026-10-16T09:25:00Z"), "FRESH",
                "people-profile-summary"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
