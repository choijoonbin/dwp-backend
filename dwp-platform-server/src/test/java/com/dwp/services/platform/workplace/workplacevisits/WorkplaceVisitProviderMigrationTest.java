package com.dwp.services.platform.workplace.workplacevisits;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceVisitProviderMigrationTest {
    @Test
    void legacyInFlightRowsAreNotAttributedToTheCurrentActiveBinding() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V274__harden_workplace_visit_provider_delivery.sql"));

        assertThat(migration)
                .contains("Their NULL snapshot is intentional")
                .contains("ambiguous legacy operations")
                .doesNotContain("binding.active = TRUE")
                .doesNotContain("SET provider_kind = binding.provider_kind");
    }
}
