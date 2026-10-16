package com.dwp.services.time.home;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TimeHomeProjectionSchemaContractTest {

    @Test
    void projectionUsesOnlyStatesAdmittedByTheCanonicalTimecardSchema()
            throws IOException {
        String migrationResource = "db/migration/V11__tim_base_tfr_tim_017.sql";
        String migration;
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(migrationResource)) {
            if (stream == null) throw new IOException("Missing " + migrationResource);
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String repository = Files.readString(Path.of(
                "src/main/java/com/dwp/services/time/home/"
                        + "JdbcTimeHomeProjectionRepository.java"));

        assertThat(migration).contains(
                "'OPEN', 'SUBMITTED', 'APPROVED', 'REJECTED',",
                "'CORRECTION_REQUIRED', 'LOCKED', 'CLOSED', 'SUPERSEDED'");
        assertThat(repository)
                .contains("'OPEN', 'SUBMITTED', 'REJECTED',")
                .contains("'CORRECTION_REQUIRED')) AS open_count")
                .contains("WHERE state = 'SUBMITTED') AS pending_count")
                .doesNotContain("'IN_REVIEW'");
    }
}
