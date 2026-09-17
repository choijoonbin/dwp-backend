package com.dwp.services.platform.workplace.workplaceassistant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceAssistantMigrationTest {
    @Test
    void migrationPinsConsentAuthorityRecoveryRetentionAndSecretBoundaries() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V266__govern_workplace_booking_assistant.sql"));
        String lower = sql.toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("CREATE TABLE wp_assistant_governance")
                .contains("tenant_opt_in")
                .contains("kill_switch")
                .contains("model_provider_reference")
                .contains("model_version")
                .contains("prompt_version")
                .contains("tool_version")
                .contains("request_processing_consent")
                .contains("CHECK (request_processing_consent)")
                .contains("CREATE TABLE wp_assistant_proposal_items")
                .contains("authoritative_intent_item_id")
                .contains("authoritative_intent_item_version")
                .contains("REFERENCES wp_booking_intents(tenant_id, intent_id)")
                .contains("REFERENCES wp_booking_batches(tenant_id, batch_id)")
                .contains("RESULT_UNKNOWN")
                .contains("requery_required")
                .contains("retention_expires_at")
                .contains("retention_deleted_at")
                .contains("execution_claim_token")
                .contains("execution_lease_until")
                .contains("CREATE UNIQUE INDEX uq_wp_assistant_active_confirmation")
                .contains("CREATE TABLE wp_assistant_audit_events")
                .contains("CREATE TABLE wp_assistant_outbox")
                .contains("UNIQUE (tenant_id, actor_user_id, command_type, idempotency_key)");
        assertThat(lower)
                .doesNotContain("raw_prompt varchar")
                .doesNotContain("secret_value")
                .doesNotContain("credential_value")
                .doesNotContain("access_token varchar")
                .doesNotContain("refresh_token varchar");
    }
}
