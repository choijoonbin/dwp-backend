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
        String hardeningSql = Files.readString(Path.of(
                "src/main/resources/db/performance-migration/"
                        + "V3__per_harden_review_operation_capture.sql"));

        assertThat(sql)
                .contains("G7-LEASE-PER-PERFORMANCE-V2-R1")
                .contains("CREATE TABLE hris_performance.prf_population_freezes")
                .contains("CREATE TABLE hris_performance.prf_population_freeze_members")
                .contains("CREATE TABLE hris_performance.prf_population_freeze_reviewers")
                .contains("CREATE TABLE hris_performance.prf_review_operation_history")
                .contains("prf_serialize_population_preview")
                .contains("ux_prf_population_previews_current_ready")
                .contains("PER_POPULATION_FREEZE_IMMUTABLE")
                .contains("BEFORE UPDATE OR DELETE")
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("set_config('dwp.tenant_id'")
                .contains("current_setting('dwp.tenant_id', TRUE)")
                .contains("SET preview_state = 'STALE'")
                .doesNotContain("DELETE FROM hris_performance.prf_population_previews");
        assertThat(sql)
                .doesNotContain("SECURITY DEFINER")
                .doesNotContain("SET search_path = pg_catalog;");
        assertThat(sql).doesNotContain("GRANT EXECUTE ON FUNCTION hris_performance.prf_");
        assertThat(hardeningSql)
                .contains("REVOKE ALL ON FUNCTION "
                        + "hris_performance.prf_capture_review_operation(BIGINT, UUID)")
                .contains("FROM PUBLIC;")
                .contains("ALTER FUNCTION hris_performance.prf_serialize_population_preview()")
                .contains("ALTER FUNCTION "
                        + "hris_performance.prf_capture_review_operation_trigger()")
                .contains("ALTER FUNCTION "
                        + "hris_performance.prf_reject_population_freeze_mutation()")
                .contains("CREATE FUNCTION "
                        + "hris_performance.prf_guard_review_operation_history()")
                .contains("trg_prf_review_operation_history_append_only")
                .contains("BEFORE INSERT OR UPDATE OR DELETE")
                .contains("pg_catalog.pg_trigger_depth() < 2")
                .contains("CURRENT_USER::TEXT IS DISTINCT FROM capture_owner")
                .contains("dwp.prf_review_operation_capture_path")
                .contains("SECURITY INVOKER")
                .contains("SECURITY DEFINER;")
                .contains("SET search_path TO pg_catalog;")
                .doesNotContain("GRANT EXECUTE ON FUNCTION");
        assertThat(hardeningSql.lines()
                .filter(line -> line.strip().equals("SECURITY DEFINER;")))
                .hasSize(3);
        assertThat(hardeningSql.lines()
                .filter(line -> line.strip().equals("SET search_path TO pg_catalog;")))
                .hasSize(3);
        assertThat(hardeningSql.lines()
                .filter(line -> line.startsWith("REVOKE ALL ON FUNCTION hris_performance.prf_")))
                .hasSize(5);
    }
}
