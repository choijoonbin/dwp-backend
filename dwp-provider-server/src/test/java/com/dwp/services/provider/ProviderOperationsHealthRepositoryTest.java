package com.dwp.services.provider;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderOperationsHealthRepositoryTest {

    @Test
    void commandCenterTimestampComesFromObservedServiceEvidence() {
        Instant older = Instant.parse("2026-09-17T00:00:00Z");
        Instant latest = Instant.parse("2026-09-17T00:02:00Z");

        assertThat(ProviderOperationsHealthRepository.latestServiceObservation(List.of(
                service("workspace", older), service("mail", null), service("calendar", latest))))
                .isEqualTo(latest);
    }

    @Test
    void missingServiceEvidenceDoesNotInventARequestTime() {
        assertThat(ProviderOperationsHealthRepository.latestServiceObservation(List.of(
                service("workspace", null), service("mail", null))))
                .isNull();
        assertThat(ProviderOperationsHealthRepository.latestServiceObservation(List.of())).isNull();
    }

    private ProviderDtos.ServicePosture service(String key, Instant observedAt) {
        return new ProviderDtos.ServicePosture(
                key, key, "HIGH", 1, 1, 0, 0, 0, 0, observedAt);
    }
}
