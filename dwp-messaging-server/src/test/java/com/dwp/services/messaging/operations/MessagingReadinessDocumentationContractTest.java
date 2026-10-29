package com.dwp.services.messaging.operations;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingReadinessDocumentationContractTest {

    @Test
    void runbookTracksCurrentMigrationsHealthGroupsAndProductionAdmission() throws IOException {
        String runbook = runbook();

        assertThat(runbook)
                .contains("migrations `V18` through `V25`")
                .contains("/actuator/health/presence")
                .contains("/actuator/health/retention")
                .contains("/actuator/health/scheduled-delivery")
                .contains("/actuator/health/messaging-operations")
                .contains("/actuator/health/messaging-providers")
                .contains("production` and `prod` profiles")
                .contains("Redis without TLS");
    }

    @Test
    void runbookDoesNotPromoteConfigurationOrTestsToExternalEvidence() throws IOException {
        String normalized = runbook().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("They are not probes")
                .contains("must never be enabled merely because unit or integration tests pass")
                .contains("TELEMETRY_EXPORT_UNVERIFIED")
                .contains("SQL_FALLBACK_ACTIVE")
                .contains("production search adapter")
                .contains("must not be simulated by configuration");
    }

    private String runbook() throws IOException {
        Path moduleRunbook = Path.of("RETENTION_AND_RUNTIME_READINESS.md");
        Path repositoryRunbook = Path.of(
                "dwp-messaging-server", "RETENTION_AND_RUNTIME_READINESS.md");
        Path path = Files.exists(moduleRunbook) ? moduleRunbook : repositoryRunbook;
        assertThat(path).exists();
        return Files.readString(path);
    }
}
