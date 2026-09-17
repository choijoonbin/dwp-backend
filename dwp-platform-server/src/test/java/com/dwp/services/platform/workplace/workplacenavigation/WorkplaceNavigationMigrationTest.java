package com.dwp.services.platform.workplace.workplacenavigation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

class WorkplaceNavigationMigrationTest {
    @Test
    void migrationEnforcesPublishedGraphDeviceCommandAndSecretBoundaries() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V263__govern_indoor_navigation_and_devices.sql"));
        String lower = sql.toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("CREATE TABLE wp_navigation_graph_revisions")
                .contains("CREATE UNIQUE INDEX uk_wp_navigation_published_graph")
                .contains("CREATE TABLE wp_navigation_nodes")
                .contains("CREATE TABLE wp_navigation_edges")
                .contains("required_permission")
                .contains("CREATE TABLE wp_navigation_pois")
                .contains("FOREIGN KEY (tenant_id, graph_revision_id, from_node_id)")
                .contains("FOREIGN KEY (tenant_id, floor_id, restricted_zone_id)")
                .contains("FOREIGN KEY (tenant_id, site_id, floor_id)")
                .contains("FOREIGN KEY (tenant_id, floor_id, resource_id)")
                .contains("CREATE TABLE wp_navigation_devices")
                .contains("device_identity_sha256")
                .contains("CREATE TABLE wp_navigation_command_previews")
                .contains("CREATE TABLE wp_navigation_device_commands")
                .contains("RESULT_UNKNOWN")
                .contains("CREATE TABLE wp_navigation_device_outbox")
                .contains("CREATE TABLE wp_navigation_audit_events")
                .contains("UNIQUE (tenant_id, actor_user_id, idempotency_key)")
                .contains("reusable credentials, tokens and PINs are prohibited");
        assertThat(lower)
                .doesNotContain("secret_value")
                .doesNotContain("plaintext_pin")
                .doesNotContain("access_token")
                .doesNotContain("refresh_token");
    }

    @Test
    void adminCommandMigrationPersistsExactReplayAndAuditLinkage() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V270__make_workplace_navigation_admin_commands_idempotent.sql"));

        assertThat(sql)
                .contains("CREATE TABLE wp_navigation_admin_commands")
                .contains("UNIQUE (tenant_id, actor_user_id, idempotency_key)")
                .contains("request_fingerprint CHAR(64) NOT NULL")
                .contains("result_snapshot JSONB NOT NULL")
                .contains("FOREIGN KEY (tenant_id, audit_event_id)")
                .contains("idempotency_key ~ '^[!-~]{1,160}$'")
                .contains("DEVICE_APPROVE", "DEVICE_BIND", "PROVIDER_CONFIGURE",
                        "DEVICE_COMMAND_PREVIEW");
    }

    @Test
    void providerDeliveryMigrationFreezesOnlyOpaqueProviderBindingMetadata() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V276__harden_workplace_device_provider_delivery.sql"));
        String lower = sql.toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("provider_code VARCHAR(80)")
                .contains("provider_configuration_version BIGINT")
                .contains("credential_reference VARCHAR(160)")
                .contains("secret-manager://")
                .contains("command_state='RESULT_UNKNOWN'")
                .contains("LEGACY_PROVIDER_BINDING_MISSING")
                .contains("command_state IN ('ACCEPTED','RUNNING','RESULT_UNKNOWN')")
                .contains("outbox.delivery_state IN "
                        + "('PENDING','PROCESSING','RETRY','RESULT_UNKNOWN')");
        assertThat(lower)
                .doesNotContain("secret_value")
                .doesNotContain("plaintext")
                .doesNotContain("access_token")
                .doesNotContain("refresh_token");
    }
}
