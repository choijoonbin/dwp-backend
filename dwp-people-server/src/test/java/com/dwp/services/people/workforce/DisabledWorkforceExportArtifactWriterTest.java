package com.dwp.services.people.workforce;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisabledWorkforceExportArtifactWriterTest {

    private final DisabledWorkforceExportArtifactWriter writer =
            new DisabledWorkforceExportArtifactWriter();

    @Test
    void failsClosedWhenRetentionCleanupHasNoArtifactStore() {
        WorkforceExportDtos.ArtifactEvidence artifact = new WorkforceExportDtos.ArtifactEvidence(
                "object://workforce/export.csv", "c".repeat(64), 1024L,
                Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> writer.discard(artifact))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artifact store");
    }
}
