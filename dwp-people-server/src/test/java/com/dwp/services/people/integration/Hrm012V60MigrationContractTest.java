package com.dwp.services.people.integration;

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

class Hrm012V60MigrationContractTest {

    private static final String V60 =
            "db/migration/V60__hrm_base_tfr_hrm_012.sql";
    private static final String V59 =
            "db/migration/V59__hrm_base_tfr_hrm_011.sql";

    @Test
    void exactLeaseCreatesOnlyTwoTablesAndReusesTheSixSharedStores()
            throws Exception {
        String sql = resource(V60);

        assertThat(matches(sql, "(?im)^CREATE TABLE ")).isEqualTo(2);
        assertThat(sql)
                .containsOnlyOnce("CREATE TABLE ppl_workforce_ingestion_runs")
                .containsOnlyOnce("CREATE TABLE ppl_workforce_ingestion_items")
                .doesNotContainIgnoringCase("CREATE TABLE int_connector_instances")
                .doesNotContainIgnoringCase("CREATE TABLE int_mapping_profiles")
                .doesNotContainIgnoringCase("CREATE TABLE int_connector_cursors")
                .doesNotContainIgnoringCase("CREATE TABLE ppl_command_receipts")
                .doesNotContainIgnoringCase("CREATE TABLE sys_people_audit_events")
                .doesNotContainIgnoringCase("CREATE TABLE sys_people_outbox_events")
                .doesNotContainIgnoringCase("ALTER TABLE int_connector_instances")
                .doesNotContainIgnoringCase("ALTER TABLE int_mapping_profiles")
                .doesNotContainIgnoringCase("ALTER TABLE int_connector_cursors")
                .doesNotContainIgnoringCase("ALTER TABLE ppl_command_receipts")
                .doesNotContainIgnoringCase("ALTER TABLE sys_people_audit_events")
                .doesNotContainIgnoringCase("ALTER TABLE sys_people_outbox_events")
                .doesNotContainIgnoringCase("GRANT ")
                .doesNotContainIgnoringCase("INSERT INTO ")
                .doesNotContainIgnoringCase("UPDATE int_")
                .doesNotContainIgnoringCase("DELETE FROM ")
                .doesNotContainIgnoringCase("TRUNCATE ");
    }

    @Test
    void runAndItemColumnsKeysAndIndexesMatchTheSealedManifest()
            throws Exception {
        String sql = resource(V60);

        assertThat(sql)
                .contains("workforce_ingestion_run_id UUID PRIMARY KEY")
                .contains("run_public_id UUID NOT NULL")
                .contains("command_receipt_id UUID NOT NULL")
                .contains("linked_replay_receipt_id UUID")
                .contains("linked_replay_run_id UUID")
                .contains("connector_instance_id UUID NOT NULL")
                .contains("connector_public_id UUID NOT NULL")
                .contains("connector_version BIGINT NOT NULL")
                .contains("cursor_type VARCHAR(30) NOT NULL DEFAULT 'OPAQUE_TOKEN'")
                .contains("mapping_profile_id UUID NOT NULL")
                .contains("mapping_public_id UUID NOT NULL")
                .contains("mapping_version BIGINT NOT NULL")
                .contains("mapping_sha256 CHAR(64) NOT NULL")
                .contains("legal_entity_public_ids UUID[] NOT NULL")
                .contains("population_scope_digest CHAR(64) NOT NULL")
                .contains("object_ref VARCHAR(132) NOT NULL")
                .contains("object_digest CHAR(64) NOT NULL")
                .contains("schema_key VARCHAR(128) NOT NULL")
                .contains("schema_version BIGINT NOT NULL")
                .contains("source_count BIGINT NOT NULL DEFAULT 0")
                .contains("accepted_count BIGINT NOT NULL DEFAULT 0")
                .contains("rejected_count BIGINT NOT NULL DEFAULT 0")
                .contains("duplicate_count BIGINT NOT NULL DEFAULT 0")
                .contains("quarantined_count BIGINT NOT NULL DEFAULT 0")
                .contains("applied_count BIGINT NOT NULL DEFAULT 0")
                .doesNotContain("validated_count")
                .contains("workforce_ingestion_item_id UUID PRIMARY KEY")
                .contains("item_public_id UUID NOT NULL")
                .contains("source_record_key_digest CHAR(64) NOT NULL")
                .contains("source_version_digest CHAR(64) NOT NULL")
                .contains("input_digest CHAR(64) NOT NULL")
                .contains("outcome_digest CHAR(64) NOT NULL")
                .contains("quarantine_ciphertext BYTEA")
                .contains("quarantine_key_reference VARCHAR(255)")
                .contains("REFERENCES ppl_command_receipts(tenant_id, command_receipt_id)")
                .contains("REFERENCES int_connector_instances(tenant_id, connector_instance_id)")
                .contains("REFERENCES int_mapping_profiles(tenant_id, mapping_profile_id)")
                .contains("REFERENCES ppl_workforce_ingestion_runs(")
                .contains("FROM public.int_connector_cursors")
                .contains("FROM public.sys_people_audit_events")
                .contains("FROM public.sys_people_outbox_events")
                .contains("idx_ppl_workforce_ingestion_runs_work_queue")
                .contains("idx_ppl_workforce_ingestion_runs_stable")
                .contains("idx_ppl_workforce_ingestion_items_duplicate")
                .contains("idx_ppl_workforce_ingestion_items_stable")
                .contains("uk_sys_people_outbox_hrm012_reconciled");
        assertThat(matches(sql, "(?im)^CREATE (?:UNIQUE )?INDEX ")).isEqualTo(8);
    }

    @Test
    void validateOnlyAndApplyHaveDistinctClosedAccountingContracts()
            throws Exception {
        String sql = resource(V60);

        assertThat(sql)
                .contains("mode IN ('VALIDATE_ONLY','APPLY')")
                .contains("'VALIDATED','REJECTED','DUPLICATE',")
                .contains("'APPLIED','QUARANTINED_AFTER_ACCEPTANCE'")
                .contains("VALIDATE_ONLY item outcome is not allowed")
                .contains("APPLY item cannot use VALIDATED outcome")
                .contains("NEW.accepted_count <> 0 OR NEW.applied_count <> 0")
                .contains("validated_total + rejected_total + duplicate_total")
                .contains("NEW.accepted_count")
                .contains("NEW.applied_count + NEW.quarantined_count")
                .contains("run counts must equal immutable item evidence")
                .contains("VALIDATE_ONLY cannot emit reconciled event")
                .contains("APPLY success requires one exact reconciled event")
                .contains("APPLY success requires exact same-transaction cursor CAS")
                .contains("terminal ingestion requires same-transaction successful receipt")
                .contains("terminal ingestion requires one same-transaction exact audit event")
                .contains("APPLY success requires one same-transaction exact event")
                .contains("HRM-012 receipt requires frozen authority object")
                .contains("HRM-012 frozen receipt authority shape is not closed")
                .contains("HRM-012 frozen receipt authority is immutable")
                .contains("NEW.result_reference->'hrm012FrozenAuthority'")
                .contains("OLD.result_reference->'hrm012FrozenAuthority'")
                .contains("frozen_key_total <> 18")
                .contains("'tenantId','numericUserId','personPublicId','markerJti'")
                .contains("'idempotencyKeySha256','canonicalRequestHash'")
                .contains("HRM-012 frozen receipt authority does not match receipt identity")
                .contains("(frozen->>'tenantId')::NUMERIC <> NEW.tenant_id::NUMERIC")
                .contains("frozen->>'personPublicId' <> NEW.actor_public_id::TEXT")
                .contains("NEW.actor_public_id IS DISTINCT FROM NEW.subject_principal_public_id")
                .contains("<> NEW.population_scope_digest::TEXT")
                .contains("<> NEW.field_policy_revision::NUMERIC")
                .contains("frozen->>'purpose' <> NEW.purpose_code")
                .contains("<> NEW.authorization_revision::NUMERIC")
                .contains("frozen->>'correlationId' <> NEW.correlation_id::TEXT")
                .contains("frozen->>'canonicalRequestHash' <> NEW.request_hash::TEXT")
                .contains("authority_key_total <> 1")
                .contains("evidence_total NOT BETWEEN 1 AND 2")
                .contains("'jti','exp','markerSha256','state','boundAt',")
                .contains("'authDecisionRevision']::TEXT[]")
                .contains("'jtiSha256','exp','state','replacedAt']::TEXT[]")
                .contains("HRM-012 marker authority must be bound after receipt reservation")
                .contains("HRM-012 same-jti marker identity is immutable")
                .contains("HRM-012 fresh marker replacement is not safe")
                .contains("current_authority->>'state' = 'VERIFIED_ALLOWED'")
                .contains("current_authority->>'authDecisionRevision'")
                .contains("<> frozen->>'postReceiptDecisionRevision'")
                .contains("authority = old_authority")
                .contains("run.command_receipt_id = NEW.command_receipt_id")
                .doesNotContain("'markerDigest','expiresAt'")
                .contains("HRM-012 cursor mutation requires same-transaction successful terminal run")
                .contains("AND run.cursor_expected_version + 1 = NEW.version")
                .contains("AND run.lifecycle_state = 'SUCCEEDED'")
                .contains("AND run.xmin::TEXT = current_xid")
                .contains("related_run_total <> 1 OR exact_terminal_total <> 1")
                .contains("HRM-012 successful receipt requires same-transaction terminal run")
                .contains("AFTER INSERT OR UPDATE ON ppl_command_receipts")
                .contains("HRM-012 audit event requires same-transaction terminal run")
                .contains("HRM-012 reconciled event requires same-transaction terminal run")
                .contains("WorkforceIngestionReconciled.v1")
                .contains("cursor_committed_version = cursor_expected_version + 1")
                .contains("cursor_row.xmin::TEXT = current_xid")
                .contains("event.xmin::TEXT = current_xid")
                .doesNotContain("mode IN ('VALIDATE_ONLY','APPLY','REPLAY')");
    }

    @Test
    void lifecycleIdentityReplayAndEvidenceAreForwardOnlyAndImmutable()
            throws Exception {
        String sql = resource(V60);

        assertThat(sql)
                .contains("'RECEIVED','RUNNING','FAILED_RETRYABLE',")
                .contains("'SUCCEEDED','REJECTED','DEAD'")
                .contains("workforce ingestion run must begin RECEIVED at zero")
                .contains("linked replay lineage does not match original evidence")
                .contains("linked replay must preserve immutable source lineage")
                .contains("terminal workforce ingestion run is immutable")
                .contains("workforce ingestion identity and source snapshot are immutable")
                .contains("illegal workforce ingestion lifecycle transition")
                .contains("OLD.lease_until <= pg_catalog.clock_timestamp()")
                .contains("workforce ingestion version must advance exactly once")
                .contains("RUNNING transition must increment attempt exactly once")
                .contains("RUNNING transition requires a nonempty owner and future lease")
                .contains("expired RUNNING lease takeover requires a new owner")
                .contains("workforce ingestion item evidence is immutable")
                .contains("workforce ingestion items require a RUNNING run")
                .contains("workforce ingestion item connector must match run snapshot")
                .contains("workforce ingestion run requires exact same-tenant HRM-012 receipt")
                .contains("receipt_command_type <> 'WORKFORCE_INGESTION'")
                .contains("receipt_originating_action <> 'INITIATE_WORKFORCE_INGESTION'")
                .contains("receipt_purpose_code <> 'HRIS_INTEGRATION'")
                .contains("receipt_target_type <> 'WORKFORCE_INGESTION_OBJECT'")
                .contains("receipt_actor IS DISTINCT FROM receipt_subject")
                .contains("FOR SHARE")
                .contains("workforce ingestion reconciled event identity is immutable")
                .contains("reconciled event requires a RUNNING APPLY run");
    }

    @Test
    void bothTablesForceRlsAndAllSecurityDefinerGuardsAreLocked()
            throws Exception {
        String sql = resource(V60);

        assertThat(matches(sql,
                "(?m)^ALTER TABLE .* ENABLE ROW LEVEL SECURITY;$"))
                .isEqualTo(2);
        assertThat(matches(sql,
                "(?m)^ALTER TABLE .* FORCE ROW LEVEL SECURITY;$"))
                .isEqualTo(2);
        assertThat(matches(sql,
                "(?m)^CREATE POLICY .*_tenant_policy$")).isEqualTo(2);
        assertThat(matches(sql,
                "pg_catalog\\.current_setting\\('dwp\\.tenant_id', true\\)"))
                .isEqualTo(4);
        assertThat(matches(sql, "(?m)^SECURITY DEFINER$")).isEqualTo(6);
        assertThat(matches(sql,
                "(?m)^SET search_path = pg_catalog, public, pg_temp$"))
                .isEqualTo(6);
        assertThat(matches(sql,
                "(?m)^REVOKE ALL ON FUNCTION public\\..* FROM PUBLIC;$"))
                .isEqualTo(6);
    }

    @Test
    void leaseIsUniqueAndV1ThroughV59RemainByteExact() throws Exception {
        assertThat(Collections.list(getClass().getClassLoader().getResources(V60)))
                .hasSize(1);
        assertThat(sha256(resourceBytes(V59)))
                .isEqualTo("1c93c200ac45097ca86e4d83846d7d0c6e8c0a9e9ac0c54481ac750ffb0f7239");

        Resource[] discovered = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql");
        List<Resource> historical = Arrays.stream(discovered)
                .filter(resource -> migrationVersion(resource) <= 59)
                .sorted(Comparator.comparingInt(this::migrationVersion))
                .toList();
        assertThat(historical).hasSize(59);
        assertThat(historical.stream().map(this::migrationVersion).toList())
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(1, 59).boxed().toList());

        StringBuilder manifest = new StringBuilder();
        for (Resource resource : historical) {
            manifest.append(sha256(resource.getInputStream().readAllBytes()))
                    .append("  ")
                    .append(resource.getFilename())
                    .append('\n');
        }
        assertThat(sha256(manifest.toString().getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("06d376631d0e330fca61a1489f1e42d120c9e0dc6635a0b5b67c3a59acfe27d2");
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
