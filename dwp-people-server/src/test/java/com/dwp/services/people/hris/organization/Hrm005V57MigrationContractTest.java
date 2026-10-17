package com.dwp.services.people.hris.organization;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class Hrm005V57MigrationContractTest {

    private static final String V57 =
            "db/migration/V57__allow_organization_workforce_field_group.sql";
    private static final String V34 =
            "db/migration/V34__add_workforce_access_boundaries.sql";

    @Test
    void v57OnlyReplacesTheSingleFieldConstraintWithTheFiveValueClosedSet()
            throws Exception {
        String sql = resource(V57);

        assertThat(sql)
                .containsOnlyOnce("DROP CONSTRAINT ck_workforce_access_policy_fields")
                .containsOnlyOnce("ADD CONSTRAINT ck_workforce_access_policy_fields")
                .contains("'DIRECTORY'")
                .contains("'WORKER_IDENTIFIERS'")
                .contains("'EMPLOYMENT'")
                .contains("'JOB_GRADE'")
                .contains("'ORGANIZATION'")
                .contains("cardinality(field_groups) > 0")
                .doesNotContainIgnoringCase("INSERT INTO")
                .doesNotContainIgnoringCase("UPDATE ")
                .doesNotContainIgnoringCase("DELETE FROM")
                .doesNotContainIgnoringCase("TRUNCATE ")
                .doesNotContainIgnoringCase("CREATE TABLE")
                .doesNotContainIgnoringCase("GRANT ");
        assertThat(sql.lines()
                .filter(line -> line.startsWith("ALTER TABLE ")))
                .hasSize(2)
                .allMatch(line -> line.equals(
                        "ALTER TABLE public.ppl_workforce_access_policies"));
    }

    @Test
    void leaseIsUniqueAndHistoricalV34RemainsByteExact() throws Exception {
        assertThat(Collections.list(getClass().getClassLoader().getResources(V57)))
                .hasSize(1);
        assertThat(sha256(resourceBytes(V34)))
                .isEqualTo("561f4a50830205c0a5347bba5d78a2577284caf3b178740ec64a1664730639ea");
    }

    private String resource(String path) throws Exception {
        return new String(resourceBytes(path), StandardCharsets.UTF_8);
    }

    private byte[] resourceBytes(String path) throws Exception {
        return new ClassPathResource(path).getInputStream().readAllBytes();
    }

    private String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }
}
