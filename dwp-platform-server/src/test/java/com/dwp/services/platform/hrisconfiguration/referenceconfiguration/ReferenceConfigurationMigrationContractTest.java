package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceConfigurationMigrationContractTest {

    @Test
    void v325OwnsOnlyLifecycleReferencesAndClosesDurabilityBoundaries() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V325__create_hris_reference_configuration_lifecycle.sql"));

        assertThat(sql)
                .contains("payload_reference")
                .contains("payload_digest")
                .doesNotContain("raw_payload", "configuration_payload")
                .contains("hris_reference_configuration_command_receipts")
                .contains("UNIQUE (tenant_id, idempotency_key)")
                .contains("dwp.hris.configuration.published.v1")
                .contains("EXCLUDE USING gist")
                .contains("lifecycle_state = 'PUBLISHED'")
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("current_setting('dwp.tenant_id', TRUE)")
                .contains("approved_by <> authored_by")
                .contains("immutable_content");
    }
}
