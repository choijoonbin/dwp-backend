package com.dwp.services.platform.workplace.workplaceservices;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

class WorkplaceServicesMigrationTest {
    @Test
    void migrationEnforcesTenantProviderCommandAndOutboxBoundariesWithoutPersistingPins()
            throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V258__orchestrate_workplace_reservation_services.sql"));
        String repository = Files.readString(Path.of(
                "src/main/java/com/dwp/services/platform/workplace/workplaceservices/"
                        + "WorkplaceServicesRepository.java"));
        String hardening = Files.readString(Path.of(
                "src/main/resources/db/migration/V259__harden_workplace_service_orders.sql"));
        String operations = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V262__complete_workplace_service_operations.sql"));
        String providerDelivery = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V277__harden_workplace_service_provider_delivery.sql"));
        String configuration = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(sql)
                .contains("CREATE TABLE wp_service_catalog_items")
                .contains("CREATE TABLE wp_service_orders")
                .contains("CREATE TABLE wp_service_fulfillment_tasks")
                .contains("FOREIGN KEY (tenant_id, provider_code)")
                .contains("UNIQUE (tenant_id, actor_user_id, command_scope, idempotency_key)")
                .contains("CREATE TABLE wp_service_order_outbox")
                .contains("reported_state IS NOT NULL AND evidence_reference IS NOT NULL")
                .contains("Cancelling a reservation never implicitly cancels a service order")
                .contains("READY is derived only from matching, fresh evidence");
        assertThat(repository)
                .contains("INSERT INTO wp_audit_events")
                .contains("INSERT INTO wp_service_order_outbox")
                .contains("'QUARANTINED'");
        assertThat(hardening)
                .contains("UNIQUE (tenant_id, service_order_id, service_order_line_id)")
                .contains("fk_wp_service_task_order_line")
                .contains("Workplace service fulfillment task order/line pairing is inconsistent")
                .contains("FOREIGN KEY (tenant_id, service_order_id, service_order_line_id)")
                .contains("UNIQUE (tenant_id, cancellation_preview_id, actor_user_id,")
                .contains("FOREIGN KEY (tenant_id, cancellation_preview_id, actor_user_id,")
                .contains("uq_wp_service_line_unresolved_adjustment")
                .contains("refundable_amount <= unit_price * cancel_quantity")
                .contains("unit_price <= 499999999999.99")
                .contains("maximum_quantity <= 1000")
                .contains("order_cutoff_minutes BETWEEN 0 AND 525600")
                .contains("currency ~ '^[A-Z]{3}$'")
                .contains("estimated_cost = unit_price * quantity")
                .contains("Workplace service catalog contains unsupported amount, currency, quantity, cutoff, or SLA values")
                .contains("Workplace service line snapshot contains unsupported amount, currency, quantity, cutoff, or SLA values")
                .contains("Workplace service line policy snapshot backfill is incomplete")
                .contains("adjustment_state = 'REFUNDED' AND refund_receipt_reference IS NOT NULL")
                .contains("scan_state IN ('NOT_CONFIGURED', 'QUARANTINED', 'CLEAN', 'INFECTED', 'ERROR')");
        assertThat(operations)
                .contains("CREATE TABLE wp_service_provider_profiles")
                .contains("CREATE TABLE wp_service_capacity_buckets")
                .contains("CREATE TABLE wp_service_capacity_holds")
                .contains("CREATE TABLE wp_service_assignee_directory_entries")
                .contains("CREATE TABLE wp_service_inspection_attempts")
                .contains("CREATE TABLE wp_service_ephemeral_access_grants")
                .contains("CREATE TABLE wp_service_operations_commands")
                .containsOnlyOnce("remediation_required BOOLEAN NOT NULL")
                .contains("Opaque vault or connector binding identifier")
                .contains("The one-time credential is never persisted");
        assertThat(configuration)
                .contains("max-file-size: ${DWP_PLATFORM_MULTIPART_MAX_FILE_SIZE:25MB}")
                .contains("max-request-size: ${DWP_PLATFORM_MULTIPART_MAX_REQUEST_SIZE:26MB}");
        assertThat(sql.toLowerCase(Locale.ROOT))
                .doesNotContain("pin_code")
                .doesNotContain("plaintext_pin")
                .doesNotContain("secret_value");
        assertThat(operations.toLowerCase(Locale.ROOT))
                .doesNotContain("pin_code")
                .doesNotContain("plaintext_pin")
                .doesNotContain("secret_value");
        assertThat(providerDelivery)
                .contains("provider_profile_id_snapshot IS NULL")
                .contains("provider_profile_id_snapshot IS NOT NULL")
                .contains("provider_code_snapshot IS NOT NULL")
                .contains("adapter_type_snapshot IS NOT NULL")
                .contains("provider_capabilities_snapshot IS NOT NULL")
                .contains("provider_profile_version IS NOT NULL")
                .contains("provider_credential_binding_reference")
                .contains("provider_configuration_version >= 0")
                .doesNotContain("UPDATE wp_service_ephemeral_access_grants grant_row")
                .contains("SET adapter_type_snapshot = NULL")
                .contains("provider_operation_kind = 'LEGACY_UNKNOWN'")
                .contains("provider_operation_kind IN ('ISSUE', 'REVOKE')")
                .contains("provider_next_attempt_at")
                .contains("provider_recovery_attempt_count")
                .contains("Recovery performs status lookup only");
    }
}
