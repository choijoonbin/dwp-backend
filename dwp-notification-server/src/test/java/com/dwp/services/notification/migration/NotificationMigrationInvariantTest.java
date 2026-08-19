package com.dwp.services.notification.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMigrationInvariantTest {

    @Test
    void forcesRlsThroughExplicitRuntimeRolesAndSessionContext() throws IOException {
        String migration = resource("db/migration/V2__enforce_notification_row_security.sql");

        assertThat(migration)
                .contains("CREATE ROLE dwp_notification_api")
                .contains("CREATE ROLE dwp_notification_worker")
                .contains("current_user = role_name")
                .contains("current_setting('dwp.tenant_id', TRUE)")
                .contains("current_setting('dwp.user_id', TRUE)")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("NOBYPASSRLS");
    }

    @Test
    void bindsEveryMaterializedTenantReferenceWithCompositeForeignKeys() throws IOException {
        String foundation = resource("db/migration/V1__create_notification_foundation.sql");
        String hardening = resource(
                "db/migration/V4__bind_tenant_owned_notification_foreign_keys.sql");

        assertThat(foundation)
                .contains("FOREIGN KEY (tenant_id, notification_id)")
                .contains("FOREIGN KEY (tenant_id, user_id, rule_id)");
        assertThat(hardening)
                .contains("fk_ntf_intent_scope_tenant_type_version")
                .contains("fk_ntf_notification_scope_tenant_type_version")
                .contains("fk_ntf_user_notification_scope_tenant_template")
                .contains("fk_ntf_delivery_scope_tenant_template")
                .contains("CHECK (type_scope_tenant_id = 0 OR type_scope_tenant_id = tenant_id)");
    }

    @Test
    void separatesTheRuntimeLoginFromTheMigrationOwner() throws IOException {
        String migration = resource(
                "db/migration/V5__separate_notification_runtime_identity.sql");

        assertThat(migration)
                .contains("${notificationRuntimeRole}")
                .contains("rolsuper")
                .contains("rolbypassrls")
                .contains("must not own application objects")
                .contains("GRANT dwp_notification_api, dwp_notification_worker")
                .contains("REVOKE ALL ON ALL TABLES");
    }

    @Test
    void keepsEventAndThreadIdentityStableAcrossContractVersions() throws IOException {
        String migration = resource(
                "db/migration/V6__stabilize_notification_event_identity.sql");

        assertThat(migration)
                .contains("UNIQUE (tenant_id, source_event_id, type_key)")
                .contains("ON ntf_notifications (tenant_id, type_key, thread_key)")
                .contains("contract identity")
                .contains("thread identity");
    }

    @Test
    void limitsApiOutboxConflictDetectionToTenantScopedKeyColumns() throws IOException {
        String migration = resource(
                "db/migration/V7__allow_tenant_scoped_outbox_conflict_detection.sql");

        assertThat(migration)
                .contains("SELECT (tenant_id, event_key)")
                .contains("TO dwp_notification_api")
                .contains("FOR SELECT")
                .contains("tenant_id = ntf_current_tenant_id()")
                .doesNotContain("GRANT SELECT ON ntf_outbox_events");
    }

    @Test
    void localSeedContainsVariedActualNotificationsForPilotUser() throws IOException {
        String seed = resource("db/local-seed/R__seed_local_notification_foundation.sql");

        assertThat(seed)
                .contains("INSERT INTO ntf_notifications")
                .contains("INSERT INTO ntf_user_notifications")
                .contains("900018")
                .contains("APPROVAL.REQUEST_SUBMITTED")
                .contains("HCM.LEAVE_APPROVED")
                .contains("SPACE.MENTION")
                .contains("'MENTION'")
                .contains("'DONE'");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing test resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
