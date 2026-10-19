package com.dwp.services.people.hris.contracts.hrm011;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class Hrm011V59MigrationContractTest {

    private static final String V59 =
            "db/migration/V59__hrm_base_tfr_hrm_011.sql";
    private static final String V58 =
            "db/migration/V58__hrm_base_tfr_hrm_009_stage_a.sql";

    @Test
    void exactLeaseCreatesOnlyTheFourSealedTablesAndReusesSharedStores()
            throws Exception {
        String sql = resource(V59);

        assertThat(matches(sql, "(?im)^CREATE TABLE ")).isEqualTo(4);
        assertThat(sql)
                .containsOnlyOnce("CREATE TABLE ppl_compensation_basis")
                .containsOnlyOnce("CREATE TABLE ppl_employment_contracts")
                .containsOnlyOnce("CREATE TABLE ppl_worker_restricted_tokens")
                .containsOnlyOnce("CREATE TABLE ppl_dependents")
                .doesNotContainIgnoringCase("CREATE TABLE ppl_command_receipts")
                .doesNotContainIgnoringCase("CREATE TABLE sys_people_audit_events")
                .doesNotContainIgnoringCase("CREATE TABLE sys_people_outbox_events")
                .doesNotContainIgnoringCase("ALTER TABLE ppl_command_receipts")
                .doesNotContainIgnoringCase("ALTER TABLE sys_people_audit_events")
                .doesNotContainIgnoringCase("ALTER TABLE sys_people_outbox_events")
                .doesNotContainIgnoringCase("GRANT ")
                .doesNotContainIgnoringCase("TRUNCATE ");
        assertThat(sql.toLowerCase())
                .doesNotContain("account_number", "routing_number", "tax_identifier",
                        "raw_profile", "object_reference varchar");
    }

    @Test
    void everySealedColumnAndCrossTenantSafeKeyIsPresent() throws Exception {
        String sql = resource(V59);

        assertThat(sql)
                .contains("compensation_basis_id BIGSERIAL PRIMARY KEY")
                .contains("assignment_id BIGINT NOT NULL")
                .contains("amount NUMERIC(19,6)")
                .contains("source_event_public_id UUID")
                .contains("employment_contract_id BIGSERIAL PRIMARY KEY")
                .contains("relationship_version BIGINT NOT NULL")
                .contains("compensation_basis_version BIGINT NOT NULL")
                .contains("template_content_schema_version TEXT NOT NULL")
                .contains("template_content_object_public_id UUID NOT NULL")
                .contains("artifact_provider_receipt_id UUID")
                .contains("document_object_public_id UUID")
                .contains("retention_class VARCHAR(40) NOT NULL DEFAULT 'EMPLOYMENT_CONTRACT'")
                .contains("worker_restricted_token_id BIGSERIAL PRIMARY KEY")
                .contains("vault_token_ref VARCHAR(255) NOT NULL")
                .contains("dependent_id BIGSERIAL PRIMARY KEY")
                .contains("encrypted_profile BYTEA NOT NULL")
                .contains("profile_schema_version INTEGER NOT NULL")
                .contains("UNIQUE (tenant_id, compensation_basis_id)")
                .contains("UNIQUE (tenant_id, employment_contract_id)")
                .contains("UNIQUE (tenant_id, worker_restricted_token_id)")
                .contains("UNIQUE (tenant_id, dependent_id)")
                .contains("REFERENCES ppl_assignments(tenant_id, assignment_id)")
                .contains("REFERENCES ppl_work_relationships(tenant_id, work_relationship_id)")
                .contains("REFERENCES ppl_compensation_basis(tenant_id, compensation_basis_id)")
                .contains("REFERENCES ppl_workers(tenant_id, worker_id)")
                .contains("REFERENCES ppl_persons(tenant_id, person_id)");
    }

    @Test
    void correctedSchemaVersionStaysAnExactCaseSensitiveTypedString()
            throws Exception {
        String sql = resource(V59);

        assertThat(sql)
                .containsOnlyOnce("template_content_schema_version TEXT NOT NULL")
                .contains("~ '^[A-Za-z][A-Za-z0-9._-]{0,127}\\.v[1-9][0-9]*$'")
                .doesNotContain("template_content_schema_version INTEGER")
                .doesNotContain("lower(template_content_schema_version)")
                .doesNotContain("upper(template_content_schema_version)");
    }

    @Test
    void indexesExclusionsAndValueGuardsMatchTheManifest() throws Exception {
        String sql = resource(V59);

        assertThat(matches(sql, "(?im)^CREATE (?:UNIQUE )?INDEX ")).isEqualTo(11);
        assertThat(sql)
                .contains("ex_ppl_compensation_basis_period EXCLUDE USING gist")
                .contains("ex_ppl_worker_restricted_tokens_primary EXCLUDE USING gist")
                .contains("daterange(valid_from, valid_to, '[)') WITH &&")
                .contains("WHERE (primary_for_purpose)")
                .contains("amount IS NOT NULL AND amount >= 0")
                .contains("currency_code ~ '^[A-Z]{3}$'")
                .contains("token_kind IN ('TAX_IDENTITY','PAYMENT_DESTINATION')")
                .contains("token_revision > 0 AND version >= 0")
                .contains("profile_schema_version > 0")
                .contains("template_payload_digest ~ '^[0-9a-f]{64}$'")
                .contains("generation_snapshot_digest ~ '^[0-9a-f]{64}$'")
                .contains("valid_to IS NULL OR valid_to > valid_from");
    }

    @Test
    void allTablesForceRlsAndAllGuardsAreLockedFailClosed() throws Exception {
        String sql = resource(V59);

        assertThat(matches(sql, "(?m)^ALTER TABLE .* ENABLE ROW LEVEL SECURITY;$"))
                .isEqualTo(4);
        assertThat(matches(sql, "(?m)^ALTER TABLE .* FORCE ROW LEVEL SECURITY;$"))
                .isEqualTo(4);
        assertThat(matches(sql, "(?m)^CREATE POLICY .*_tenant_policy$")).isEqualTo(4);
        assertThat(matches(sql,
                "pg_catalog\\.current_setting\\('dwp\\.tenant_id', true\\)"))
                .isEqualTo(8);
        assertThat(matches(sql, "(?m)^SECURITY DEFINER$")).isEqualTo(4);
        assertThat(matches(sql,
                "(?m)^SET search_path = pg_catalog, public, pg_temp$")).isEqualTo(4);
        assertThat(matches(sql,
                "(?m)^REVOKE ALL ON FUNCTION public\\..* FROM PUBLIC;$"))
                .isEqualTo(4);
        assertThat(sql)
                .contains("compensation basis history cannot be deleted")
                .contains("employment contract history cannot be deleted")
                .contains("restricted worker token history cannot be deleted")
                .contains("dependent history cannot be deleted")
                .contains("employment contract snapshot, template, artifact and document evidence is immutable")
                .contains("illegal employment contract lifecycle transition")
                .contains("employment contract must begin DRAFT or ISSUED")
                .contains("employment contract version must advance exactly once")
                .contains("initial ISSUED employment contract requires a positive persisted version")
                .contains("NEW.lifecycle_state IN ('SIGNED','DECLINED')")
                .contains("NEW.lifecycle_state IN ('ACTIVE','REVOKED')")
                .contains("NEW.lifecycle_state IN ('SUPERSEDED','EXPIRED')");
    }

    @Test
    void migrationLeaseIsUniqueAndTheV58PredecessorIsByteExact()
            throws Exception {
        assertThat(Collections.list(getClass().getClassLoader().getResources(V59)))
                .hasSize(1);
        assertThat(sha256(resourceBytes(V58)))
                .isEqualTo("702b96820b38b7c0d5135792f7085db81e741206b134b83d488c1f7760bca9d2");
    }

    @Test
    void admittedBaseV1ThroughV58AggregateChecksumIsPinnedIndependently()
            throws Exception {
        Resource[] discovered = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql");
        List<Resource> historical = Arrays.stream(discovered)
                .filter(resource -> migrationVersion(resource) <= 58)
                .sorted(Comparator.comparingInt(this::migrationVersion))
                .toList();

        assertThat(historical).hasSize(58);
        assertThat(historical.stream().map(this::migrationVersion).toList())
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(1, 58).boxed().toList());

        StringBuilder manifest = new StringBuilder();
        for (Resource resource : historical) {
            manifest.append(sha256(resource.getInputStream().readAllBytes()))
                    .append("  ")
                    .append(resource.getFilename())
                    .append('\n');
        }
        assertThat(sha256(manifest.toString().getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("8df34c40571717a3d19ad034c1568c2f91d632767853a00442a3e1e26c249c33");
    }

    private int matches(String value, String regex) {
        int count = 0;
        var matcher = Pattern.compile(regex).matcher(value);
        while (matcher.find()) count++;
        return count;
    }

    private String resource(String path) throws Exception {
        return new String(resourceBytes(path), StandardCharsets.UTF_8);
    }

    private byte[] resourceBytes(String path) throws Exception {
        return new ClassPathResource(path).getInputStream().readAllBytes();
    }

    private int migrationVersion(Resource resource) {
        var matcher = Pattern.compile("^V([0-9]+)__.*\\.sql$")
                .matcher(resource.getFilename());
        if (!matcher.matches()) return Integer.MAX_VALUE;
        return Integer.parseInt(matcher.group(1));
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }
}
