package com.dwp.services.time.absence.employment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class EmploymentAbsenceProjectionMigrationContractTest {

    private static final String RESOURCE =
            "db/migration/V14__tim_base_tfr_tim_003_employment_absence_projection.sql";

    @Test
    void v14IsTheExactOneTableForcedRlsLeastPrivilegeProjectionDelta()
            throws IOException {
        String sql = migrationSql();

        assertThat(matches(sql, "(?im)^CREATE TABLE\\s+")).isOne();
        assertThat(sql)
                .contains("CREATE TABLE public.abs_employment_relationship_projections")
                .contains("PRIMARY KEY (tenant_id, relationship_public_id)")
                .contains("CREATE FUNCTION public.tim003_assignment_snapshots_structurally_valid(")
                .contains("IMMUTABLE\nSTRICT\nSECURITY INVOKER")
                .contains("SET search_path = pg_catalog")
                .contains("CREATE TRIGGER trg_abs_emp_projection_seal_update")
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("pg_catalog.current_setting('dwp.tenant_id', TRUE)")
                .contains("GRANT SELECT ON TABLE public.abs_employment_relationship_projections")
                .contains("GRANT INSERT (")
                .contains("GRANT UPDATE (")
                .contains("public.tim003_assignment_snapshots_structurally_valid(JSONB, UUID)")
                .doesNotContain("GRANT ALL", "GRANT DELETE", "GRANT TRUNCATE")
                .doesNotContain("CREATE TABLE public.sys_domain_event_")
                .doesNotContain("CREATE TABLE public.ppl_")
                .doesNotContain("CREATE MATERIALIZED VIEW", "CREATE PROCEDURE")
                .doesNotContain("SECURITY DEFINER");

        String insertColumns = between(sql, "GRANT INSERT (", ")\nON public");
        assertThat(insertColumns)
                .contains("tenant_id", "relationship_public_id")
                .doesNotContain("projection_version", "created_at", "updated_at");

        String updateColumns = between(sql, "GRANT UPDATE (", ")\nON public");
        assertThat(updateColumns)
                .doesNotContain(
                        "tenant_id",
                        "relationship_public_id",
                        "projection_version",
                        "created_at",
                        "updated_at");
    }

    private static long matches(String value, String expression) {
        return Pattern.compile(expression).matcher(value).results().count();
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start);
        assertThat(from).as("start marker").isGreaterThanOrEqualTo(0);
        int to = value.indexOf(end, from + start.length());
        assertThat(to).as("end marker").isGreaterThan(from);
        return value.substring(from + start.length(), to);
    }

    private static String migrationSql() throws IOException {
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing " + RESOURCE);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
