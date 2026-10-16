package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ControlledTransferMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V328__create_hris_controlled_file_transfers.sql");

    @Test
    void v328IsCreateOnlyAndStoresPinnedEvidenceWithoutPayloadsOrSecrets() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .containsOnlyOnce("CREATE TABLE sys_controlled_file_transfers")
                .contains("object_version VARCHAR(128) NOT NULL")
                .contains("access_authority_digest CHAR(64) NOT NULL")
                .contains("access_authority_fresh_until TIMESTAMPTZ NOT NULL")
                .contains("updated_at TIMESTAMPTZ NOT NULL")
                .contains("updated_at >= requested_at")
                .contains("population_kind VARCHAR(24) NOT NULL")
                .contains("authority_population_scope_digest CHAR(64) NOT NULL")
                .contains("population_kind IN ('CONNECTOR_SET', 'LEGAL_ENTITY')")
                .contains("metadata_snapshot_digest CHAR(64) NOT NULL")
                .contains("metadata_authority_digest CHAR(64) NOT NULL")
                .contains("metadata_authority_fresh_until TIMESTAMPTZ NOT NULL")
                .contains("scan_authority_digest CHAR(64) NOT NULL")
                .contains("scan_authority_fresh_until TIMESTAMPTZ NOT NULL")
                .contains("egress_authority_digest CHAR(64)")
                .contains("lease_token_digest CHAR(64)")
                .contains("Controlled-transfer active state requires a fresh lease")
                .contains("object_digest = scan_object_digest")
                .contains("byte_size = scan_byte_size")
                .contains("malware_state = 'CLEAN'")
                .contains("Controlled-transfer object and authority evidence is immutable")
                .contains("Controlled-transfer terminal evidence is immutable")
                .contains("Controlled-transfer evidence cannot be deleted")
                .contains("Controlled-transfer transition requires exact CAS version increment")
                .contains("Illegal controlled-transfer state transition")
                .contains("Controlled-transfer must start as REQUESTED version 1")
                .contains("OLD.updated_at + INTERVAL '1 microsecond'")
                .contains("BEFORE INSERT OR UPDATE OR DELETE")
                .doesNotContain("object_bytes", "signed_url", "credential_ref",
                        "filesystem_path", "ALTER TABLE sys_hris_command_receipts");
    }

    @Test
    void v328EnforcesDirectionIdempotencyAndTenantRls() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("transfer_kind = 'INGEST'")
                .contains("recipient_policy_ref IS NULL")
                .contains("egress_policy_revision IS NULL")
                .contains("transfer_kind IN ('EXPORT', 'DELIVERY', 'EXCHANGE')")
                .contains("recipient_policy_ref IS NOT NULL")
                .contains("UNIQUE (tenant_id, owner_module, idempotency_key)")
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("current_setting('dwp.tenant_id', TRUE)");
    }
}
