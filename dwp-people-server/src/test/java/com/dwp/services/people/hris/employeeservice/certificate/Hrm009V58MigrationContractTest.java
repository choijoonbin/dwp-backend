package com.dwp.services.people.hris.employeeservice.certificate;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Hrm009V58MigrationContractTest {

    private static final String V58 =
            "db/migration/V58__hrm_base_tfr_hrm_009_stage_a.sql";
    private static final String V54 =
            "db/migration/V54__create_employee_change_request_lifecycle.sql";

    @Test
    void exactManifestCreatesOnlyTwoMetadataTablesAndTwoExplicitIndexes()
            throws Exception {
        String sql = resource(V58);

        assertThat(matches(sql, "(?im)^CREATE TABLE ")).isEqualTo(2);
        assertThat(matches(sql, "(?im)^CREATE (?:UNIQUE )?INDEX ")).isEqualTo(2);
        assertThat(sql)
                .containsOnlyOnce("CREATE TABLE ppl_certificate_requests")
                .containsOnlyOnce("CREATE TABLE ppl_issued_certificates")
                .containsOnlyOnce("CREATE INDEX idx_ppl_certificate_requests_work_queue")
                .containsOnlyOnce(
                        "CREATE UNIQUE INDEX uidx_ppl_command_receipts_tenant_command_idempotency")
                .contains("GROUP BY tenant_id, command_type, idempotency_key")
                .contains("HAVING count(*) > 1")
                .doesNotContainIgnoringCase("DELETE FROM ppl_command_receipts")
                .doesNotContainIgnoringCase("UPDATE ppl_command_receipts")
                .doesNotContainIgnoringCase("CREATE TABLE ppl_command_receipts")
                .doesNotContainIgnoringCase("CREATE TABLE sys_people_audit_events")
                .doesNotContainIgnoringCase("CREATE TABLE sys_people_outbox_events")
                .doesNotContainIgnoringCase("GRANT ")
                .doesNotContainIgnoringCase("TRUNCATE ");
        assertThat(sql.toLowerCase())
                .doesNotContain("document_bytes", "document_blob", "signature_table",
                        "seal_table", "verification_table", "svc_requests");
    }

    @Test
    void everyLeasedColumnConstraintAndQueuePredicateIsPresent() throws Exception {
        String sql = resource(V58);

        assertThat(sql)
                .contains("certificate_request_id BIGSERIAL PRIMARY KEY")
                .contains("public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid()")
                .contains("requester_person_id BIGINT NOT NULL")
                .contains("subject_person_id BIGINT NOT NULL")
                .contains("certificate_type VARCHAR(80) NOT NULL")
                .contains("locale VARCHAR(35) NOT NULL")
                .contains("purpose_code VARCHAR(80) NOT NULL")
                .contains("lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'REQUESTED'")
                .contains("template_public_id UUID NOT NULL")
                .contains("template_version INTEGER NOT NULL")
                .contains("correlation_id UUID NOT NULL")
                .contains("version BIGINT NOT NULL DEFAULT 0")
                .contains("requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()")
                .contains("issued_certificate_id BIGSERIAL PRIMARY KEY")
                .contains("object_public_id UUID NOT NULL")
                .contains("object_version INTEGER NOT NULL")
                .contains("content_sha256 CHAR(64) NOT NULL")
                .contains("verification_token_hash CHAR(64) NOT NULL")
                .contains("lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ISSUED'")
                .contains("retention_until DATE")
                .contains("legal_hold BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("UNIQUE (tenant_id, correlation_id)")
                .contains("UNIQUE (tenant_id, certificate_request_id)")
                .contains("UNIQUE (tenant_id, verification_token_hash)")
                .contains("WHERE lifecycle_state IN ('REQUESTED','VALIDATING')");
    }

    @Test
    void forceRlsUsesTheExactTenantExpressionOnBothTables() throws Exception {
        String sql = resource(V58);
        String expression =
                "tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT";

        assertThat(sql)
                .containsOnlyOnce("ALTER TABLE ppl_certificate_requests ENABLE ROW LEVEL SECURITY")
                .containsOnlyOnce("ALTER TABLE ppl_certificate_requests FORCE ROW LEVEL SECURITY")
                .containsOnlyOnce("ALTER TABLE ppl_issued_certificates ENABLE ROW LEVEL SECURITY")
                .containsOnlyOnce("ALTER TABLE ppl_issued_certificates FORCE ROW LEVEL SECURITY")
                .containsOnlyOnce("CREATE POLICY ppl_certificate_requests_tenant_policy")
                .containsOnlyOnce("CREATE POLICY ppl_issued_certificates_tenant_policy");
        assertThat(matches(sql, Pattern.quote(expression))).isEqualTo(4);
    }

    @Test
    void bothGuardsLockSearchPathRevokePublicAndCloseEveryTransitionBypass()
            throws Exception {
        String sql = resource(V58);

        assertThat(matches(sql, "(?m)^SECURITY DEFINER$")).isEqualTo(2);
        assertThat(matches(sql,
                "(?m)^SET search_path = pg_catalog, public, pg_temp$")).isEqualTo(2);
        assertThat(sql)
                .contains("REVOKE ALL ON FUNCTION public.ppl_guard_certificate_request_state() FROM PUBLIC")
                .contains("REVOKE ALL ON FUNCTION public.ppl_guard_issued_certificate_state() FROM PUBLIC")
                .contains("NEW.lifecycle_state <> 'REQUESTED' OR NEW.version <> 0")
                .contains("certificate request history cannot be deleted")
                .contains("OLD.lifecycle_state = 'REQUESTED'")
                .contains("NEW.lifecycle_state IN ('VALIDATING','CANCELLED')")
                .contains("OLD.lifecycle_state = 'VALIDATING'")
                .contains("NEW.lifecycle_state IN ('ISSUED','FAILED')")
                .contains("NEW.version <> OLD.version + 1")
                .contains("NEW.completed_at IS NULL")
                .contains("same-tenant VALIDATING request")
                .contains("issued certificate history cannot be deleted")
                .contains("NEW.lifecycle_state NOT IN ('REVOKED','EXPIRED')")
                .contains("NEW.revoked_at >= NEW.expires_at")
                .doesNotContain("NEW.revoked_at >= OLD.expires_at");
    }

    @Test
    void migrationLeaseIsUniqueAndHistoricalReceiptMigrationIsByteExact()
            throws Exception {
        assertThat(Collections.list(getClass().getClassLoader().getResources(V58)))
                .hasSize(1);
        assertThat(sha256(resourceBytes(V54)))
                .isEqualTo("f701bfaff260331c83dc60bcd5b7c7fb2f9c58b11e888705c4c999a48d0ecac9");
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

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }
}
