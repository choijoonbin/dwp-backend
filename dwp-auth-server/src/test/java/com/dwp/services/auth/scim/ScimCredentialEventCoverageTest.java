package com.dwp.services.auth.scim;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ScimCredentialEventCoverageTest {

    @Test
    void marksTheFirstHundredEventsAsTruncatedWhenAProbeRowExists() {
        var observed = IntStream.range(0, 101)
                .mapToObj(index -> new ScimConnectorDtos.ProvisioningEvent(
                        UUID.randomUUID(), UUID.randomUUID(), "Connector", "PATCH", "USER",
                        String.valueOf(index), "SUCCESS", null, "redacted", Instant.EPOCH))
                .toList();

        var page = ScimCredentialService.eventPage(observed, 100);

        assertThat(page.items()).hasSize(100);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.coverageState()).isEqualTo("TRUNCATED_AT_LIMIT");
    }

    @Test
    void reportsCompleteCoverageWhenTheProbeRowIsAbsent() {
        var observed = IntStream.range(0, 100)
                .mapToObj(index -> new ScimConnectorDtos.ProvisioningEvent(
                        UUID.randomUUID(), UUID.randomUUID(), "Connector", "READ", "GROUP",
                        String.valueOf(index), "SUCCESS", null, "redacted", Instant.EPOCH))
                .toList();

        var page = ScimCredentialService.eventPage(observed, 100);

        assertThat(page.items()).hasSize(100);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.coverageState()).isEqualTo("COMPLETE_WITHIN_FILTER");
    }
}
