package com.dwp.services.people.hr.performance.populationfreeze;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PopulationFreezeMigrationContractTest {

    @Test
    void exactLeasedMigrationOwnsImmutableTenantScopedFreezeEvidence() throws IOException {
        Path path = Path.of(
                "src/main/resources/db/performance-migration/"
                        + "V2__per_create_population_freeze.sql");
        String sql = Files.readString(path);

        assertThat(sql)
                .contains("G7-LEASE-PER-PERFORMANCE-V2-R1")
                .contains("CREATE TABLE hris_performance.prf_population_freezes")
                .contains("CREATE TABLE hris_performance.prf_population_freeze_members")
                .contains("CREATE TABLE hris_performance.prf_population_freeze_reviewers")
                .contains("PER_POPULATION_FREEZE_IMMUTABLE")
                .contains("BEFORE UPDATE OR DELETE")
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("current_setting('dwp.tenant_id', TRUE)")
                .doesNotContain("UPDATE hris_performance.prf_population_previews")
                .doesNotContain("DELETE FROM hris_performance.prf_population_previews");
    }
}
