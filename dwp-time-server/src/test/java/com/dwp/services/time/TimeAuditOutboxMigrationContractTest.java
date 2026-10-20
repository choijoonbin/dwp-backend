package com.dwp.services.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TimeAuditOutboxMigrationContractTest {

    private static final String RESOURCE =
            "db/migration/V13__tim_add_service_audit_outbox.sql";

    @Test
    void v13ProvidesTheCoreAuditSchemaWithOnlyRequiredRuntimeMutationColumns()
            throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("CREATE TABLE sys_audit_outbox")
                .contains("outbox_id UUID PRIMARY KEY")
                .contains("event_id UUID NOT NULL UNIQUE")
                .contains("payload JSONB NOT NULL")
                .contains("locked_until TIMESTAMPTZ")
                .contains("CREATE INDEX idx_tim_sys_audit_outbox_delivery")
                .contains("CREATE INDEX idx_tim_sys_audit_outbox_published")
                .contains("REVOKE ALL ON TABLE sys_audit_outbox FROM PUBLIC")
                .contains("GRANT SELECT, INSERT, DELETE ON TABLE sys_audit_outbox TO %I")
                .contains("GRANT UPDATE (status, attempt_count, available_at, locked_by, locked_until, '")
                .contains("published_at, last_error, updated_at)")
                .contains("runtime_role_name TEXT := '${timeRuntimeRole}'")
                .doesNotContain("GRANT ALL")
                .doesNotContain("GRANT UPDATE ON TABLE sys_audit_outbox")
                .doesNotContain("GRANT CREATE")
                .doesNotContain("GRANT TRUNCATE")
                .doesNotContain("GRANT REFERENCES")
                .doesNotContain("GRANT TRIGGER");
    }

    private static String migrationSql() throws IOException {
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing " + RESOURCE);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
